package io.github.intisy.nylium.conformance;

/**
 * Reports whether a jar nested at {@code META-INF/jars} inside the dispatched module is reachable.
 *
 * @implNote Resolved reflectively so this class, which is compiled once into every module, needs no
 *     compile-time view of a library only one module bundles, and so a module that bundles nothing
 *     reports a value rather than failing to load. The returned token comes from the library's own
 *     code, so it cannot be produced by anything short of that class actually loading and running.
 */
public final class BundledJarProbe {

    private static final String LIBRARY = "io.github.intisy.nylium.conformance.library.BundledLibrary";

    private BundledJarProbe() {
    }

    public static String state() {
        try {
            return String.valueOf(Class.forName(LIBRARY).getMethod("token").invoke(null));
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return "absent";
        } catch (ReflectiveOperationException | LinkageError e) {
            return "unusable";
        }
    }
}
