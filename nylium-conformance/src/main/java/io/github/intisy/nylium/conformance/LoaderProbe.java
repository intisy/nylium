package io.github.intisy.nylium.conformance;

public final class LoaderProbe {

    private LoaderProbe() {
    }

    /**
     * @implNote A module has no way to ask the kernel which platform selected it: {@code Platform}
     *     is handed to the bootstrap, never to the module. Until SP-4's unified loader API exists,
     *     the loader is inferred from what is visible, which is also an independent check on the
     *     bootstrap rather than an echo of it.
     */
    public static String detect() {
        if (exists("net.fabricmc.loader.api.FabricLoader")) {
            return "fabric";
        }
        if (exists("cpw.mods.jarhandling.SecureJar")) {
            return "modlauncher9";
        }
        if (exists("cpw.mods.modlauncher.api.ITransformationService")) {
            return "modlauncher8";
        }
        if (exists("net.minecraft.launchwrapper.Launch")) {
            return "launchwrapper";
        }
        return "unknown";
    }

    static boolean exists(String className) {
        try {
            Class.forName(className, false, LoaderProbe.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
