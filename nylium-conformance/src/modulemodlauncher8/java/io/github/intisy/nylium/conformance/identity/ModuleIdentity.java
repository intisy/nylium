package io.github.intisy.nylium.conformance.identity;

public final class ModuleIdentity {

    private static final String ID_VALUE = "modlauncher8";

    public static String id() {
        return ID_VALUE;
    }

    public static String unique() {
        return "unique-" + ID_VALUE;
    }

    private ModuleIdentity() {
    }
}
