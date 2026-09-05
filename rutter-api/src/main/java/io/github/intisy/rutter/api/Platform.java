package io.github.intisy.rutter.api;

import java.nio.file.Path;
import java.util.Optional;

public interface Platform {

    PlatformId id();

    Environment environment();

    void addToClasspath(Path jar);

    void registerMixinConfig(String name);

    Optional<String> nativeVersionProbe();

    default void whenModuleLoadable(Runnable activation) {
        activation.run();
    }

    default ClassLoader moduleClassLoader(ClassLoader source) {
        return source;
    }
}
