package io.github.intisy.rutter.bootstrap.launchwrapper;

import io.github.intisy.rutter.api.Environment;
import io.github.intisy.rutter.api.Platform;
import io.github.intisy.rutter.api.PlatformId;
import io.github.intisy.rutter.api.RutterException;
import net.minecraft.launchwrapper.Launch;
import org.spongepowered.asm.mixin.Mixins;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.Optional;

final class LaunchWrapperPlatform implements Platform {

    @Override
    public PlatformId id() {
        return PlatformId.LAUNCHWRAPPER;
    }

    /**
     * @implNote LaunchWrapper exposes no client/server side flag, so presence of the client entry
     * point is the only signal available this early in startup.
     */
    @Override
    public Environment environment() {
        return exists("net.minecraft.client.Minecraft") ? Environment.CLIENT : Environment.SERVER;
    }

    @Override
    public void addToClasspath(Path jar) {
        try {
            Launch.classLoader.addURL(jar.toUri().toURL());
        } catch (MalformedURLException e) {
            throw new RutterException("Could not add " + jar + " to the LaunchWrapper classloader", e);
        }
    }

    @Override
    public void registerMixinConfig(String name) {
        Mixins.addConfiguration(name);
    }

    /**
     * @implNote LaunchWrapper has no version accessor of its own; this reads a blackboard key that
     * nothing on this era actually populates, so the chain falls through to {@code MarkerClassProbe}
     * in practice, which is exactly why that probe exists.
     */
    @Override
    public Optional<String> nativeVersionProbe() {
        Object version = Launch.blackboard == null ? null : Launch.blackboard.get("rutter.mcVersion");
        return Optional.ofNullable(version).map(Object::toString);
    }

    private static boolean exists(String className) {
        try {
            Class.forName(className, false, Launch.classLoader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
