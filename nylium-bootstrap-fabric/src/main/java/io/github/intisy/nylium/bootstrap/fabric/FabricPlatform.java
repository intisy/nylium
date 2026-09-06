package io.github.intisy.nylium.bootstrap.fabric;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.Platform;
import io.github.intisy.nylium.api.PlatformId;
import io.github.intisy.nylium.api.NyliumException;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.spongepowered.asm.mixin.Mixins;

import java.nio.file.Path;
import java.util.Optional;

final class FabricPlatform implements Platform {

    @Override
    public PlatformId id() {
        return PlatformId.FABRIC;
    }

    @Override
    public Environment environment() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT
                ? Environment.CLIENT
                : Environment.SERVER;
    }

    /**
     * @implNote {@code FabricLauncherBase.getLauncher().addToClassPath} is Fabric Loader internals
     * rather than published API; it is the call every multi-version Fabric mod uses, and there is
     * no public equivalent. The guard turns a future removal into a clear message instead of a
     * bare stack trace.
     */
    @Override
    public void addToClasspath(Path jar) {
        try {
            FabricLauncherBase.getLauncher().addToClassPath(jar);
        } catch (LinkageError e) {
            throw new NyliumException("This Fabric Loader does not expose addToClassPath; "
                    + "Nylium needs Fabric Loader 0.14 or newer.", e);
        }
    }

    @Override
    public void registerMixinConfig(String name) {
        Mixins.addConfiguration(name);
    }

    @Override
    public Optional<String> nativeVersionProbe() {
        return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString());
    }
}
