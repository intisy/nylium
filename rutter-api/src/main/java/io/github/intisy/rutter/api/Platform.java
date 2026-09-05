package io.github.intisy.rutter.api;

import java.nio.file.Path;
import java.util.Optional;

public interface Platform {

    PlatformId id();

    Environment environment();

    void addToClasspath(Path jar);

    void registerMixinConfig(String name);

    Optional<String> nativeVersionProbe();

    /**
     * On ModLauncher 9, a module injected during the transformation-service phase is not loadable until
     * {@code ILaunchPluginService.initializeLaunch}, so that backend overrides to defer activation; all others act immediately.
     */
    default void whenModuleLoadable(Runnable activation) {
        activation.run();
    }

    /**
     * On ModLauncher 9, the game layer's classloader is not available during the transformation-service phase,
     * so that backend overrides to return it; all others inherit this identity passthrough.
     */
    default ClassLoader moduleClassLoader(ClassLoader source) {
        return source;
    }
}
