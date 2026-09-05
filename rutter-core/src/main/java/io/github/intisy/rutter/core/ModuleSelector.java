package io.github.intisy.rutter.core;

import io.github.intisy.rutter.api.Environment;
import io.github.intisy.rutter.api.McVersion;
import io.github.intisy.rutter.api.NoCompatibleModuleException;
import io.github.intisy.rutter.api.PlatformId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ModuleSelector {

    private final List<ModuleDescriptor> candidates;

    public ModuleSelector(ModuleManifest manifest) {
        List<ModuleDescriptor> ordered = new ArrayList<>(manifest.modules());
        ordered.sort(Comparator
                .comparingInt(ModuleDescriptor::specificity).reversed()
                .thenComparing(Comparator.comparingInt(ModuleDescriptor::priority).reversed()));
        this.candidates = ordered;
    }

    public ModuleDescriptor select(PlatformId platform, McVersion version, Environment environment) {
        StringBuilder rejections = new StringBuilder();
        for (ModuleDescriptor candidate : candidates) {
            String rejection = reject(candidate, platform, version, environment);
            if (rejection == null) {
                return candidate;
            }
            rejections.append("\n  - ").append(candidate.path()).append(": ").append(rejection);
        }
        throw new NoCompatibleModuleException(
                "No Rutter module suits platform " + platform + ", Minecraft " + version.raw()
                        + ", environment " + environment + ". Candidates considered:" + rejections);
    }

    private static String reject(ModuleDescriptor candidate, PlatformId platform,
                                 McVersion version, Environment environment) {
        if (!candidate.platforms().contains(platform)) {
            return "declares platform " + candidate.platforms() + ", not " + platform;
        }
        if (!candidate.minecraft().contains(version)) {
            return "declares Minecraft " + candidate.minecraft().raw() + ", which excludes " + version.raw();
        }
        if (candidate.environment().isPresent() && candidate.environment().get() != environment) {
            return "declares environment " + candidate.environment().get() + ", not " + environment;
        }
        return null;
    }
}
