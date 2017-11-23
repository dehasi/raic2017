import java.util.Comparator;
import java.util.stream.Stream;

public class Runntner {
    public static void main(String[] args) {
        Stream.of(5,3,6,2).sorted(Comparator.reverseOrder()).forEach(System.out::println);
    }
}
