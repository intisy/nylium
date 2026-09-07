package io.github.intisy.nylium.conformance;

public final class McClassProbe {

    private McClassProbe() {
    }

    /**
     * @implNote Answers {@code unavailable} on ModLauncher 8 by design, not by accident: that
     *     backend's service is discovered by a sibling of the class loader hosting the game, which
     *     is Nylium's known limitation 1. Asserting the current answer per backend means the day
     *     the limitation is fixed, the suite says so instead of staying green either way.
     */
    public static String state() {
        boolean reachable = LoaderProbe.exists("net.minecraft.server.MinecraftServer")
                || LoaderProbe.exists("net.minecraft.server.dedicated.DedicatedServer");
        return reachable ? "reachable" : "unavailable";
    }
}
