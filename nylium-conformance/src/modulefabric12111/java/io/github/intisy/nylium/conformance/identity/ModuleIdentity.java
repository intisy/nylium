package io.github.intisy.nylium.conformance.identity;

public final class ModuleIdentity {

    private static final String ID_VALUE = "fabric-1.21.11";

    public static String id() {
        return ID_VALUE;
    }

    public static String unique() {
        return "unique-fabric-1.21.11";
    }

    private ModuleIdentity() {
    }
}
