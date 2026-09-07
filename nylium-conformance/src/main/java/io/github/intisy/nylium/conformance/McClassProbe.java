package io.github.intisy.nylium.conformance;

/**
 * @implNote Answers {@code unavailable} on ModLauncher 8 by design, not by accident: that
 *     backend's service is discovered by a sibling of the class loader hosting the game, which
 *     is Nylium's known limitation 1. Asserting the current answer per backend means the day
 *     the limitation is fixed, the suite says so instead of staying green either way.
 * @implNote Answers {@code unsafe-to-probe} on LaunchWrapper without ever calling
 *     {@code Class.forName}. The module entrypoint runs inside
 *     {@code NyliumBootTransformer.transform()} for the launch-target class, which on a server is
 *     exactly {@code net.minecraft.server.MinecraftServer}. Probing that class by name from here
 *     would reenter {@code LaunchClassLoader.findClass} for the class currently being defined,
 *     corrupting that transformer's own iteration over its transformer list. This mirrors
 *     {@code NyliumBootTransformer}'s and {@code LaunchWrapperPlatform}'s own {@code @implNote}s,
 *     which record the same reasoning for why the kernel itself never probes by name here.
 */
public final class McClassProbe {

    private McClassProbe() {
    }

    public static String state() {
        if ("launchwrapper".equals(LoaderProbe.detect())) {
            return "unsafe-to-probe";
        }
        boolean reachable = LoaderProbe.exists("net.minecraft.server.MinecraftServer")
                || LoaderProbe.exists("net.minecraft.server.dedicated.DedicatedServer");
        return reachable ? "reachable" : "unavailable";
    }
}
