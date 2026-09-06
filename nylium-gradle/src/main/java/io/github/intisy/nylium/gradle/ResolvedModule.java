package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.PlatformId;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;

import java.util.Collections;
import java.util.List;
import java.util.Set;

final class ResolvedModule {

    private final String name;
    private final String path;
    private final Set<PlatformId> platforms;
    private final String minecraft;
    private final Environment environment;
    private final List<String> mixins;
    private final int priority;
    private final String entrypoint;
    private final Provider<RegularFile> jar;

    ResolvedModule(String name, String path, Set<PlatformId> platforms, String minecraft,
                   Environment environment, List<String> mixins, int priority, String entrypoint,
                   Provider<RegularFile> jar) {
        this.name = name;
        this.path = path;
        this.platforms = Collections.unmodifiableSet(platforms);
        this.minecraft = minecraft;
        this.environment = environment;
        this.mixins = Collections.unmodifiableList(mixins);
        this.priority = priority;
        this.entrypoint = entrypoint;
        this.jar = jar;
    }

    String name() {
        return name;
    }

    String path() {
        return path;
    }

    Set<PlatformId> platforms() {
        return platforms;
    }

    String minecraft() {
        return minecraft;
    }

    Environment environment() {
        return environment;
    }

    List<String> mixins() {
        return mixins;
    }

    int priority() {
        return priority;
    }

    String entrypoint() {
        return entrypoint;
    }

    Provider<RegularFile> jar() {
        return jar;
    }

    static boolean declaresFabric(List<ResolvedModule> modules) {
        for (ResolvedModule module : modules) {
            if (module.platforms().contains(PlatformId.FABRIC)) {
                return true;
            }
        }
        return false;
    }
}
