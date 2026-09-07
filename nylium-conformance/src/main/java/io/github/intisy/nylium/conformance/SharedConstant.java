package io.github.intisy.nylium.conformance;

public final class SharedConstant {

    private SharedConstant() {
    }

    /**
     * @implNote Returned from a method rather than exposed as a {@code public static final String}
     *     constant. This class and {@code ConformanceEntry} are both compiled once, into the single
     *     {@code ConformanceEntry.class} every module ships, so a constant here would be inlined at
     *     that one compile time and the class would never be loaded at runtime: the {@code
     *     sharedClass} report key would then read identically whether this class was present or
     *     deleted. A method call compiles to {@code invokestatic}, which is resolved, and therefore
     *     this class actually loaded, at call time.
     */
    public static String value() {
        return "shared-ok";
    }
}
