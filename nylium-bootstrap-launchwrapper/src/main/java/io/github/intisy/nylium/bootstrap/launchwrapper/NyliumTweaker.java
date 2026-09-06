package io.github.intisy.nylium.bootstrap.launchwrapper;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.File;
import java.util.List;

public final class NyliumTweaker implements ITweaker {

    private File gameDirectory = new File(".");

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        if (gameDir != null) {
            gameDirectory = gameDir;
        }
    }

    /**
     * @implNote Forge's own deobfuscation tweaker is queued to run after this one, so a marker
     * class like {@code net.minecraft.init.Blocks} cannot yet be resolved by name here, and
     * bootstrapping Mixin here was observed to make that later tweaker's own class registration
     * fail verification. Both are deferred to {@link NyliumBootTransformer}, which runs on the
     * first class LaunchClassLoader transforms, after every tweaker has finished injecting.
     * @implNote The transformer only fires if its watched class actually loads, unlike this
     * method, which LaunchWrapper always calls; the shutdown hook here is what makes a variant
     * that never loads either class fail loudly instead of leaving the kernel silently unbooted.
     */
    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        NyliumBootTransformer.gameDirectory = gameDirectory;
        classLoader.registerTransformer(NyliumBootTransformer.class.getName());
        Runtime.getRuntime().addShutdownHook(new Thread("nylium-boot-watchdog") {
            @Override
            public void run() {
                if (!NyliumBootTransformer.wasBooted()) {
                    System.err.println("[Nylium] WARNING: shutting down without ever booting the "
                            + "kernel; neither net.minecraft.client.Minecraft nor "
                            + "net.minecraft.server.MinecraftServer was ever loaded through this "
                            + "LaunchClassLoader.");
                }
            }
        });
    }

    @Override
    public String getLaunchTarget() {
        return null;
    }

    @Override
    public String[] getLaunchArguments() {
        return new String[0];
    }
}
