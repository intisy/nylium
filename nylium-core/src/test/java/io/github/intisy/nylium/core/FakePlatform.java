package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.Platform;
import io.github.intisy.nylium.api.PlatformId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

class FakePlatform implements Platform {

    private final PlatformId id;
    private final Environment environment;
    private final String nativeVersion;

    final List<Path> classpathAdditions = new ArrayList<>();
    final List<String> registeredConfigs = new ArrayList<>();
    final List<String> callOrder = new ArrayList<>();

    FakePlatform(PlatformId id, Environment environment, String nativeVersion) {
        this.id = id;
        this.environment = environment;
        this.nativeVersion = nativeVersion;
    }

    @Override
    public PlatformId id() {
        return id;
    }

    @Override
    public Environment environment() {
        return environment;
    }

    @Override
    public void addToClasspath(Path jar) {
        classpathAdditions.add(jar);
        callOrder.add("classpath");
    }

    @Override
    public void registerMixinConfig(String name) {
        registeredConfigs.add(name);
        callOrder.add("mixin:" + name);
    }

    @Override
    public Optional<String> nativeVersionProbe() {
        return Optional.ofNullable(nativeVersion);
    }
}
