import model.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

@SuppressWarnings({"UnsecureRandomNumberGeneration", "FieldCanBeLocal", "unused", "OverlyLongMethod"})
public final class FirstStrategy implements Strategy {
    /**
     * Список целей для каждого типа техники, упорядоченных по убыванию урона по ним.
     */
    private static final Map<VehicleType, VehicleType[]> preferredTargetTypesByVehicleType;

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

    private final Map<Long, Vehicle> vehicleById = new HashMap<>();
    private final Map<Long, Integer> updateTickByVehicleId = new HashMap<>();
    private final Queue<Consumer<Move>> delayedMoves = new ArrayDeque<>();

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

        if (world.getTickIndex() == 0){
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

    private void move() {
        delayedMoves.add(move -> {           selectAll(move, VehicleType.HELICOPTER);        });
        delayedMoves.add(move -> {            shiftVehicle(move, world.getWidth() / 2.0D, world.getHeight() / 2.0D);        });
        delayedMoves.add(move -> {           selectAll(move, VehicleType.FIGHTER);        });
        delayedMoves.add(move -> {            shiftVehicle(move, world.getWidth() / 2.0D, world.getHeight() / 2.0D);        });
        delayedMoves.add(move -> {            selectAll(move, VehicleType.TANK);        });
        delayedMoves.add(move -> {            shiftVehicle(move, 0.0d, world.getHeight() / 2.0D);        });
        delayedMoves.add(move -> {            selectAll(move, VehicleType.ARRV);        });
        delayedMoves.add(move -> {            shiftVehicle(move, 0.0d, world.getHeight() / 2.0D);        });
        delayedMoves.add(move -> {            selectAll(move, VehicleType.IFV);        });
        delayedMoves.add(move -> {            shiftVehicle(move, world.getWidth() / 2.0D, .0D);        });
        delayedMoves.add(move -> {        });
    }

    private void shiftVehicle(Move move, double x, double y){
        move.setAction(ActionType.MOVE);
        move.setX(x);
        move.setY(y);
    }
    private void selectAll(Move move, VehicleType vehicleType) {
        move.setAction(ActionType.CLEAR_AND_SELECT);
        move.setVehicleType(vehicleType);
        move.setRight(world.getWidth());
        move.setBottom(world.getHeight());
    }
    private boolean executeDelayedMove() {
        Consumer<Move> action = delayedMoves.poll();
        if (action == null) return false;

        action.accept(move);
        return true;
    }

    private void callNuclearStrike(long vehicleId, double x, double y, Move move) {
        move.setAction(ActionType.TACTICAL_NUCLEAR_STRIKE);
        move.setVehicleId(vehicleId);
        move.setX(x);
        move.setY(y);
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