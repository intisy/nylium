package io.github.intisy.rutter.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformDefaultsTest {

    @Test
    void whenModuleLoadableRunsImmediately() {
        boolean[] activated = {false};
        Platform platform = new TestPlatform();
        platform.whenModuleLoadable(() -> activated[0] = true);
        assertTrue(activated[0]);
    }

    @Test
    void moduleClassLoaderReturnsSourceClassLoader() {
        Platform platform = new TestPlatform();
        ClassLoader source = Thread.currentThread().getContextClassLoader();
        ClassLoader result = platform.moduleClassLoader(source);
        assertSame(source, result);
    }

    private static class TestPlatform implements Platform {
        @Override
        public PlatformId id() {
            return PlatformId.FABRIC;
        }

        @Override
        public Environment environment() {
            return Environment.CLIENT;
        }

        @Override
        public void addToClasspath(Path jar) {
        }

        @Override
        public void registerMixinConfig(String name) {
        }

        @Override
        public Optional<String> nativeVersionProbe() {
            return Optional.empty();
        }
    }
}
