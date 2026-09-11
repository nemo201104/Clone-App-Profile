package io.github.nemo.cap;

public final class Failure extends Exception {
    public final String code;
    public Failure(String code, String message) { super(message); this.code = code; }
    public static void require(boolean condition, String code, String message) throws Failure {
        if (!condition) throw new Failure(code, message);
    }
}
