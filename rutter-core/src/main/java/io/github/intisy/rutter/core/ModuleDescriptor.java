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

    /**
     * Ranks how narrowly this module constrains itself, highest first, which is the primary order
     * {@link ModuleSelector} considers candidates in.
     *
     * @implNote The terms are deliberately unequal: one point for every platform the module does
     *     <em>not</em> declare (so a single-platform module scores four of the five), two for an
     *     exact Minecraft version, one for a closed range, none for an open one, and one for a
     *     declared environment. Platform count is therefore the largest single term, though a narrow
     *     version range can still outweigh it: a two-platform module pinned to an exact version
     *     scores 3 + 2 and so beats a single-platform module with an open range, which scores
     *     4 + 0. Equal scores fall through to declared priority, highest first, and equal priorities
     *     keep manifest index order.
     */
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
