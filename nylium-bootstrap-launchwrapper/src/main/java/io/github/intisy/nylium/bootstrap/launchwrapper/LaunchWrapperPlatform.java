package io.github.intisy.nylium.bootstrap.launchwrapper;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.Platform;
import io.github.intisy.nylium.api.PlatformId;
import io.github.intisy.nylium.api.NyliumException;
import net.minecraft.launchwrapper.Launch;
import org.spongepowered.asm.mixin.Mixins;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.Optional;

final class LaunchWrapperPlatform implements Platform {

    private final Environment environment;

    /**
     * @implNote LaunchWrapper exposes no client/server side flag, so the side is taken from which
     * vanilla entry point {@link NyliumBootTransformer} fired on. Resolving it here by
     * {@code Class.forName} instead would reenter {@code LaunchClassLoader.findClass} for the very
     * class that transformer is being asked to transform, and both frames would then define it.
     */
    LaunchWrapperPlatform(Environment environment) {
        this.environment = environment;
    }

    @Override
    public PlatformId id() {
        return PlatformId.LAUNCHWRAPPER;
    }

    @Override
    public Environment environment() {
        return environment;
    }

    @Override
    public void addToClasspath(Path jar) {
        try {
            Launch.classLoader.addURL(jar.toUri().toURL());
        } catch (MalformedURLException e) {
            throw new NyliumException("Could not add " + jar + " to the LaunchWrapper classloader", e);
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
        Object version = Launch.blackboard == null ? null : Launch.blackboard.get("nylium.mcVersion");
        return Optional.ofNullable(version).map(Object::toString);
    }
}
