package io.github.intisy.nylium.conformance.library;

/**
 * Stands in for a dependency a consumer ships the way an ordinary Fabric mod does, with Loom's
 * {@code include}, so it is nested at {@code META-INF/jars} inside one module jar and is not
 * flattened into it.
 */
public final class BundledLibrary {

    private BundledLibrary() {
    }

    public static String token() {
        return "bundled";
    }
}
