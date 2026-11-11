package examples;

public class Demo {
    static int leaf(int value) { return value + 1; }
    static String leaf(String value) { return value.trim(); }

    public static int process(int value) {
        return leaf(value) + leaf(value + 1);
    }

    static void start() {
        process(1);
        process(2);
        java.util.function.IntUnaryOperator reference = Demo::process;
        Runnable callback = () -> process(3);
    }

    static int recursive(int value) {
        return value <= 0 ? 0 : recursive(value - 1);
    }

    int initialized = process(4);
}
