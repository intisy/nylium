package io.github.intisy.nylium.conformance;

/**
 * @implNote Answers {@code unavailable} on ModLauncher 8 by design, not by accident: that
 *     backend's service is discovered by a sibling of the class loader hosting the game, which
 *     is Nylium's known limitation 1. Asserting the current answer for that backend means the day
 *     the limitation is fixed, the suite says so instead of staying green either way. That
 *     reasoning does not extend to the LaunchWrapper branch below: {@code unsafe-to-probe} is a
 *     function of the loader alone, not a measurement, so it can never newly fail or newly pass.
 * @implNote Answers {@code unsafe-to-probe} on LaunchWrapper without ever calling
 *     {@code Class.forName}, because this probe's own safety on that backend cannot be shown, not
 *     because it is known to cause a crash. {@code forge-1.7.10}'s server does fail to launch
 *     after Nylium boots (see {@code smoke/build/servers/forge-1.7.10/smoke.log} and this task's
 *     report), but the cause is independent of this probe: {@code NyliumBootTransformer.transform()}
 *     calls {@code MixinBootstrap.init()}, which registers a new transformer into the very
 *     {@code ArrayList} that {@code LaunchClassLoader.runTransformers} is iterating, and the next
 *     iteration step throws {@code ConcurrentModificationException}. That reproduces with
 *     {@code nylium-testmod}, whose entrypoint only writes a marker and never loads or probes any
 *     class, which is the proof this probe is not the cause. Whether reading a game class by name
 *     from here is itself safe remains unknown and is deferred to its own spike.
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
