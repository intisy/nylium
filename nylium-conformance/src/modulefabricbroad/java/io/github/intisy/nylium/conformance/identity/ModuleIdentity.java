package io.github.intisy.nylium.conformance.identity;

public final class ModuleIdentity {

    private static final String ID_VALUE = "fabric-broad";

    public static String id() {
        return ID_VALUE;
    }

    public static String unique() {
        return "unique-fabric-broad";
    }

    private ModuleIdentity() {
    }
}
