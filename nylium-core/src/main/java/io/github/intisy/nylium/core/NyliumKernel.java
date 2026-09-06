package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.McVersion;
import io.github.intisy.nylium.api.Platform;
import io.github.intisy.nylium.api.NyliumException;
import io.github.intisy.nylium.core.probe.MarkerClassProbe;
import io.github.intisy.nylium.core.probe.ProbeChain;
import io.github.intisy.nylium.core.probe.VersionJsonProbe;
import io.github.intisy.nylium.core.probe.VersionProbe;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class NyliumKernel {

    private NyliumKernel() {
    }

    /**
     * @implNote Mixin reads a config's JSON when the config is registered, and one platform cannot
     * load the module until after the service phase, so registration is wrapped rather than called directly.
     */
    public static ModuleDescriptor boot(Platform platform, ClassLoader source, Path cacheDirectory) {
        ModuleManifest manifest = ModuleManifest.readFrom(source, ModuleManifest.RESOURCE);
        McVersion version = new ProbeChain(probes(platform, source)).detect();
        final ModuleDescriptor module =
                new ModuleSelector(manifest).select(platform.id(), version, platform.environment());

        Path extracted = new ModuleExtractor(cacheDirectory).extract(source, module);
        platform.addToClasspath(extracted);
        platform.whenModuleLoadable(new Runnable() {
            @Override
            public void run() {
                for (String config : module.mixinConfigs()) {
                    platform.registerMixinConfig(config);
                }
                module.entrypoint().ifPresent(className ->
                        invoke(className, module, platform.moduleClassLoader(source)));
            }
        });
        return module;
    }

    private static void invoke(String className, ModuleDescriptor module, ClassLoader loader) {
        try {
            Class.forName(className, true, loader).getMethod("nyliumInit").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new NyliumException("Module '" + module.path() + "' names entrypoint '" + className
                    + "', which could not be invoked. It needs a public static void nyliumInit().", e);
        }
    }

    private static List<VersionProbe> probes(Platform platform, ClassLoader source) {
        List<VersionProbe> probes = new ArrayList<>();
        probes.add(new VersionProbe() {
            @Override
            public String name() {
                return platform.id() + " native probe";
            }

            @Override
            public Optional<String> detect() {
                return platform.nativeVersionProbe();
            }
        });
        probes.add(new VersionJsonProbe(source));
        probes.add(new MarkerClassProbe(source));
        return probes;
    }
}
