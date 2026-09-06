package io.github.intisy.rutter.core;

import io.github.intisy.rutter.api.McVersion;
import io.github.intisy.rutter.api.Platform;
import io.github.intisy.rutter.core.probe.MarkerClassProbe;
import io.github.intisy.rutter.core.probe.ProbeChain;
import io.github.intisy.rutter.core.probe.VersionJsonProbe;
import io.github.intisy.rutter.core.probe.VersionProbe;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class RutterKernel {

    private RutterKernel() {
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
            }
        });
        return module;
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
