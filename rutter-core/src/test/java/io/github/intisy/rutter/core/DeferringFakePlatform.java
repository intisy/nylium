package io.github.intisy.rutter.core;

import io.github.intisy.rutter.api.Environment;
import io.github.intisy.rutter.api.Platform;
import io.github.intisy.rutter.api.PlatformId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class DeferringFakePlatform implements Platform {

    private final PlatformId id;
    private final Environment environment;
    private final String nativeVersion;

    final List<Path> classpathAdditions = new ArrayList<>();
    final List<String> registeredConfigs = new ArrayList<>();
    Runnable capturedActivation;

    DeferringFakePlatform(PlatformId id, Environment environment, String nativeVersion) {
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
    }

    @Override
    public void registerMixinConfig(String name) {
        registeredConfigs.add(name);
    }

    @Override
    public Optional<String> nativeVersionProbe() {
        return Optional.ofNullable(nativeVersion);
    }

    @Override
    public void whenModuleLoadable(Runnable activation) {
        capturedActivation = activation;
    }
}
