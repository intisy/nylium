package io.github.intisy.rutter.bootstrap.launchwrapper;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.File;
import java.util.List;

public final class RutterTweaker implements ITweaker {

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
     * fail verification. Both are deferred to {@link RutterBootTransformer}, which runs on the
     * first class LaunchClassLoader transforms, after every tweaker has finished injecting.
     */
    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        RutterBootTransformer.gameDirectory = gameDirectory;
        classLoader.registerTransformer(RutterBootTransformer.class.getName());
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
