package nz.sodium;

public class Tuple<A,B> {
    private final A _a;
    private final B _b;

    private Tuple(A a, B b) {
        this._a = a;
        this._b = b;
    }

    public static <A,B> Tuple<A,B> of(A a, B b) {
        return new Tuple<>(a, b);
    }

    public A _1() {
        return _a;
    }

    public B _2() {
        return _b;
    }
}
