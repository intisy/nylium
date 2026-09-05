package io.github.intisy.rutter.core;

import io.github.intisy.rutter.api.Environment;
import io.github.intisy.rutter.api.PlatformId;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ModuleDescriptor {

    private final String path;
    private final Set<PlatformId> platforms;
    private final VersionRange minecraft;
    private final Environment environment;
    private final List<String> mixinConfigs;
    private final int priority;
    private final String entrypoint;

    ModuleDescriptor(String path, Set<PlatformId> platforms, VersionRange minecraft,
                     Environment environment, List<String> mixinConfigs, int priority, String entrypoint) {
        this.path = path;
        this.platforms = Collections.unmodifiableSet(platforms);
        this.minecraft = minecraft;
        this.environment = environment;
        this.mixinConfigs = Collections.unmodifiableList(mixinConfigs);
        this.priority = priority;
        this.entrypoint = entrypoint;
    }

    public String path() {
        return path;
    }

    public Set<PlatformId> platforms() {
        return platforms;
    }

    public VersionRange minecraft() {
        return minecraft;
    }

    public Optional<Environment> environment() {
        return Optional.ofNullable(environment);
    }

    public List<String> mixinConfigs() {
        return mixinConfigs;
    }

    public int priority() {
        return priority;
    }

    public Optional<String> entrypoint() {
        return Optional.ofNullable(entrypoint);
    }

    public int specificity() {
        int score = 0;
        if (environment != null) {
            score++;
        }
        if (minecraft.isExact()) {
            score += 2;
        } else if (minecraft.isClosed()) {
            score++;
        }
        score += PlatformId.values().length - platforms.size();
        return score;
    }

    @Override
    public String toString() {
        return path + " (platforms=" + platforms + ", minecraft=" + minecraft
                + ", environment=" + (environment == null ? "any" : environment) + ")";
    }
}
