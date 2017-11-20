import model.ActionType;
import model.Game;
import model.Move;
import model.Player;
import model.TerrainType;
import model.Vehicle;
import model.VehicleType;
import model.VehicleUpdate;
import model.WeatherType;
import model.World;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;

@SuppressWarnings({"UnsecureRandomNumberGeneration", "FieldCanBeLocal", "unused", "OverlyLongMethod"})
public final class ScaleStrategy implements Strategy {
    /**
     * Список целей для каждого типа техники, упорядоченных по убыванию урона по ним.
     */
    private static final Map<VehicleType, VehicleType[]> preferredTargetTypesByVehicleType;
    private static final int FRONT = 1;
    private static final int BACK = 2;

    static {
        preferredTargetTypesByVehicleType = new EnumMap<>(VehicleType.class);

        preferredTargetTypesByVehicleType.put(VehicleType.FIGHTER, new VehicleType[]{
                VehicleType.HELICOPTER, VehicleType.FIGHTER
        });

        preferredTargetTypesByVehicleType.put(VehicleType.HELICOPTER, new VehicleType[]{
                VehicleType.TANK, VehicleType.ARRV, VehicleType.HELICOPTER, VehicleType.IFV, VehicleType.FIGHTER
        });

        preferredTargetTypesByVehicleType.put(VehicleType.IFV, new VehicleType[]{
                VehicleType.HELICOPTER, VehicleType.ARRV, VehicleType.IFV, VehicleType.FIGHTER, VehicleType.TANK
        });

        preferredTargetTypesByVehicleType.put(VehicleType.TANK, new VehicleType[]{
                VehicleType.IFV, VehicleType.ARRV, VehicleType.TANK, VehicleType.FIGHTER, VehicleType.HELICOPTER
        });
    }

    private Random random;

    private TerrainType[][] terrainTypeByCellXY;
    private WeatherType[][] weatherTypeByCellXY;

    private Player me;
    private World world;
    private Game game;
    private Move move;

    private double centerX;
    private double centerY;

    private double oneThirdX;
    private double oneThirdY;


    private Rectangle fightersRectangle;
    private Rectangle helicoptersRectangle;
    private Rectangle ifvRectangle;
    private Rectangle tanksRectangle;
    private Rectangle arrvsRectangle;

    private boolean attactIsStarted = false;

    private final Map<Long, Vehicle> vehicleById = new HashMap<>();
    private final Map<Long, Integer> updateTickByVehicleId = new HashMap<>();
    private final Queue<PriorityMove> delayedMoves = new PriorityQueue<>(PriorityMove::compareTo);
    private final Map<Vehicle, Rectangle> initialSquares = new HashMap<>();

    private final MoveHelper moveHelper = new MoveHelper();
    private final RectangleHelper rectangleHelper = new RectangleHelper();

    /**
     * Основной метод стратегии, осуществляющий управление армией. Вызывается каждый тик.
     *
     * @param me    Информация о вашем игроке.
     * @param world Текущее состояние мира.
     * @param game  Различные игровые константы.
     * @param move  Результатом работы метода является изменение полей данного объекта.
     */
    @Override
    public void move(Player me, World world, Game game, Move move) {
        initializeStrategy(world, game);
        initializeTick(me, world, game, move);
        findUnitsPosition(world);

        if (world.getTickIndex() == 0) {
            move();
            return;
        }

        if (me.getRemainingActionCooldownTicks() > 0) {
            return;
        }

        if (executeDelayedMove()) {
            return;
        }

        executeDelayedMove();
    }

    private void startAttack() {
//front
        delayedMoves.add(new PriorityMove(m -> moveHelper.clearAndSelectRectangle(m, fightersRectangle)));
        delayedMoves.add(new PriorityMove(m -> moveHelper.selectRectangle(m, rectangleHelper.verticalSplitLeft(arrvsRectangle))));
        delayedMoves.add(new PriorityMove(m -> moveHelper.selectRectangle(m, rectangleHelper.verticalSplitLeft(ifvRectangle))));
        delayedMoves.add(new PriorityMove(m -> moveHelper.creteGroup(m, FRONT)));

        delayedMoves.add(new PriorityMove(m -> moveHelper.selectGroup(m, FRONT)));
        delayedMoves.add(new PriorityMove(m -> moveHelper.shiftVehicle(m, oneThirdX, oneThirdY)));

//back
        delayedMoves.add(new PriorityMove(m -> moveHelper.clearAndSelectRectangle(m, tanksRectangle)));
        delayedMoves.add(new PriorityMove(m -> moveHelper.selectRectangle(m, helicoptersRectangle)));
        delayedMoves.add(new PriorityMove(m -> moveHelper.selectRectangle(m, rectangleHelper.verticalSplitRight(arrvsRectangle))));
        delayedMoves.add(new PriorityMove(m -> moveHelper.selectRectangle(m, rectangleHelper.verticalSplitRight(ifvRectangle))));
        delayedMoves.add(new PriorityMove(m -> moveHelper.creteGroup(m, BACK)));

        delayedMoves.add(new PriorityMove(m -> moveHelper.selectGroup(m, BACK)));
        delayedMoves.add(new PriorityMove(m -> moveHelper.rotateVehicle(m, 0, 1.10D * centerY, Math.PI / 2.0D)));


    }

    private void findUnitsPosition(World world) {
        if (fightersRectangle != null) return;
        fightersRectangle = getUnitsSquare(streamVehicles(Ownership.ALLY, VehicleType.FIGHTER));
        helicoptersRectangle = getUnitsSquare(streamVehicles(Ownership.ALLY, VehicleType.HELICOPTER));
        ifvRectangle = getUnitsSquare(streamVehicles(Ownership.ALLY, VehicleType.IFV));
        tanksRectangle = getUnitsSquare(streamVehicles(Ownership.ALLY, VehicleType.TANK));
        arrvsRectangle = getUnitsSquare(streamVehicles(Ownership.ALLY, VehicleType.ARRV));
        startAttack();

    }

    private Rectangle getUnitsSquare(Stream<Vehicle> vehicles) {
        Rectangle rectangle = new Rectangle(Double.MAX_VALUE, Double.MAX_VALUE, Double.MIN_VALUE, Double.MIN_VALUE);
        List<Point> points = vehicles.peek(v -> rectangle.type = v.getType())
                .map(v -> new Point(v.getX(), v.getY()))
                .collect(toList());
        for (Point p : points) {
            if (p.x < rectangle.left) {
                rectangle.left = p.x;
            }
            if (p.y < rectangle.top) {
                rectangle.top = p.y;
            }

            if (p.x > rectangle.right) {
                rectangle.right = p.x;
            }
            if (p.y > rectangle.bottom) {
                rectangle.bottom = p.y;
            }
        }
        return rectangle;
    }

    /**
     * THE MAIN MOVEMENT LOGIC
     */
    private void move() {
//        delayedMoves.add(move -> selectAll(move, VehicleType.FIGHTER));
//        delayedMoves.add(move -> scaleVehicle(move, 0, 0, 4));
//        delayedMoves.add(move -> selectAll(move, VehicleType.HELICOPTER));
//        delayedMoves.add(move -> rotateVehicle(move, 0, centerY));


//        delayedMoves.add(move -> {            selectAll(move, VehicleType.IFV);        });
//        delayedMoves.add(move -> {            shiftVehicle(move, world.getWidth() / 2.0D, .0D);        });
//        delayedMoves.add(move -> {        });
    }


    private boolean executeDelayedMove() {
        if (delayedMoves.isEmpty()) return false;
        Consumer<Move> action = delayedMoves.poll().consumer;
        if (action == null) return false;

        action.accept(move);
        return true;
    }


    /**
     * Инциализируем стратегию.
     * <p>
     * Для этих целей обычно можно использовать конструктор, однако в данном случае мы хотим инициализировать генератор
     * случайных чисел значением, полученным от симулятора игры.
     */
    private void initializeStrategy(World world, Game game) {
        if (random == null) {
            random = new Random(game.getRandomSeed());

            terrainTypeByCellXY = world.getTerrainByCellXY();
            weatherTypeByCellXY = world.getWeatherByCellXY();
        }
        this.centerX = world.getWidth() / 2.0d;
        this.centerY = world.getHeight() / 2.0d;
        this.oneThirdX = world.getWidth() / 3.0d;
        this.oneThirdY = world.getHeight() / 3.0d;
    }

    /**
     * Сохраняем все входные данные в полях класса для упрощения доступа к ним, а также актуализируем сведения о каждой
     * технике и времени последнего изменения её состояния.
     */
    private void initializeTick(Player me, World world, Game game, Move move) {
        this.me = me;
        this.world = world;
        this.game = game;
        this.move = move;

        for (Vehicle vehicle : world.getNewVehicles()) {
            vehicleById.put(vehicle.getId(), vehicle);
            updateTickByVehicleId.put(vehicle.getId(), world.getTickIndex());
        }

        for (VehicleUpdate vehicleUpdate : world.getVehicleUpdates()) {
            long vehicleId = vehicleUpdate.getId();

            if (vehicleUpdate.getDurability() == 0) {
                vehicleById.remove(vehicleId);
                updateTickByVehicleId.remove(vehicleId);
            } else {
                vehicleById.put(vehicleId, new Vehicle(vehicleById.get(vehicleId), vehicleUpdate));
                updateTickByVehicleId.put(vehicleId, world.getTickIndex());
            }
        }

    }

    private Point findEnemyVehicleFormation(VehicleType[] targetTypes) {
        // ... получаем центр формации противника или центр мира ...
        double targetX = Arrays.stream(targetTypes)
                .flatMap(targetType -> streamVehicles(Ownership.ENEMY, targetType))
                .mapToDouble(Vehicle::getX).average().orElse(centerX);

        double targetY = Arrays.stream(targetTypes)
                .flatMap(targetType -> streamVehicles(Ownership.ENEMY, targetType))
                .mapToDouble(Vehicle::getY).average().orElse(Double.NaN);

        return new Point(targetX, targetY);
    }

    private Stream<Vehicle> streamVehicles(Ownership ownership, VehicleType vehicleType) {
        Stream<Vehicle> stream = vehicleById.values().stream();

        switch (ownership) {
            case ALLY:
                stream = stream.filter(vehicle -> vehicle.getPlayerId() == me.getId());
                break;
            case ENEMY:
                stream = stream.filter(vehicle -> vehicle.getPlayerId() != me.getId());
                break;
            default:
        }

        if (vehicleType != null) {
            stream = stream.filter(vehicle -> vehicle.getType() == vehicleType);
        }

        return stream;
    }

    private Stream<Vehicle> streamVehicles(Ownership ownership) {
        return streamVehicles(ownership, null);
    }

    private Stream<Vehicle> streamVehicles() {
        return streamVehicles(Ownership.ANY);
    }

    private enum Ownership {
        ANY,

        ALLY,

        ENEMY
    }

}

/***********  my domain classes ********************/
class Rectangle implements Comparable<Rectangle> {
    VehicleType type;
    double left, top, right, bottom;

    public Rectangle(double left, double top, double right, double bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    @Override
    public String toString() {
        return "Rectangle{" +
                "type=" + type +
                ", left=" + left +
                ", top=" + top +
                ", right=" + right +
                ", bottom=" + bottom +
                '}';
    }

    @Override
    public int compareTo(Rectangle that) {
        if (right != that.right) return Double.compare(right, that.right);
        return Double.compare(bottom, that.bottom);
    }
}

class Point {
    double x, y;

    public Point(double x, double y) {
        this.x = x;
        this.y = y;
    }
}

class PriorityMove implements Comparable<PriorityMove> {
    int priority = 0;
    Consumer<Move> consumer;

    public PriorityMove(int priority, Consumer<Move> consumer) {
        this.consumer = consumer;
        this.priority = priority;
    }

    public PriorityMove(Consumer<Move> consumer) {
        this.consumer = consumer;
    }

    @Override
    public int compareTo(PriorityMove o) {
        return Integer.compare(priority, o.priority);
    }
}

final class RectangleHelper {
    Rectangle verticalSplitLeft(Rectangle r) {
        Rectangle rectangle = new Rectangle(r.left, r.top, r.left + (r.right - r.left) / 2.0d, r.bottom);
        rectangle.type = r.type;
        return rectangle;
    }

    Rectangle verticalSplitRight(Rectangle r) {
        Rectangle rectangle = new Rectangle(r.left + (r.right - r.left) / 2.0d, r.top, r.right, r.bottom);
        rectangle.type = r.type;
        return rectangle;
    }

}

final class MoveHelper {
    void rotateVehicle(Move move, double x, double y, double angle) {
        move.setAction(ActionType.ROTATE);
        move.setX(x);
        move.setY(y);
        move.setAngle(angle);
    }

    void scaleVehicle(Move move, double x, double y, double factor) {
        move.setAction(ActionType.SCALE);
        move.setX(x);
        move.setY(y);
        move.setFactor(factor);
    }

    void shiftVehicle(Move move, double x, double y) {
        move.setAction(ActionType.MOVE);
        move.setX(x);
        move.setY(y);
    }


    void selectAll(World world, Move move, VehicleType vehicleType) {
        move.setAction(ActionType.CLEAR_AND_SELECT);
        move.setVehicleType(vehicleType);
        move.setRight(world.getWidth());
        move.setBottom(world.getHeight());
    }

    void clearAndSelectRectangle(Move move, Rectangle rectangle) {
        move.setAction(ActionType.CLEAR_AND_SELECT);
        move.setLeft(rectangle.left);
        move.setTop(rectangle.top);
        move.setRight(rectangle.right);
        move.setBottom(rectangle.bottom);
    }

    void selectRectangle(Move move, Rectangle rectangle) {
        move.setAction(ActionType.ADD_TO_SELECTION);
        move.setLeft(rectangle.left);
        move.setTop(rectangle.top);
        move.setRight(rectangle.right);
        move.setBottom(rectangle.bottom);
    }

    void creteGroup(Move move, int group) {
        move.setAction(ActionType.ASSIGN);
        move.setGroup(group);
    }

    void selectGroup(Move move, int group) {
        move.setAction(ActionType.CLEAR_AND_SELECT);
        move.setGroup(group);
    }

    void callNuclearStrike(Move move, long vehicleId, double x, double y) {
        move.setAction(ActionType.TACTICAL_NUCLEAR_STRIKE);
        move.setVehicleId(vehicleId);
        move.setX(x);
        move.setY(y);
    }

}