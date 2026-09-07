package io.github.intisy.nylium.conformance.identity;

/**
 * @implNote The values are returned from methods rather than exposed as {@code public static final
 *     String} constants. A {@code static final} field initialised from a string literal is a
 *     compile-time constant, so javac inlines its value at every reference site. Since
 *     {@code ConformanceEntry} lives in the shared {@code main} source set and compiles against the
 *     {@code identityStub}, constants would bake "stub" into the one shared
 *     {@code ConformanceEntry.class} that every module ships, and all eight modules would report
 *     {@code module=stub} at runtime even though the stub is never packaged. A method call is
 *     resolved against whichever class is actually on the classpath.
 */
public final class ModuleIdentity {

    private static final String ID_VALUE = "stub";

    public static String id() {
        return ID_VALUE;
    }

    public static String unique() {
        return "unique-stub";
    }

    private ModuleIdentity() {
    }
}
