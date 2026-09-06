package io.github.intisy.rutter.core.probe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VersionJsonProbeTest {

    /**
     * @implNote Returns {@link URLClassLoader} rather than {@link ClassLoader} so callers can
     * close it; left open, the jar's file handle survives the test and {@code @TempDir} cleanup
     * fails on Windows, which locks open files.
     */
    private static URLClassLoader withVersionJson(Path dir, String json) throws Exception {
        Path jar = dir.resolve("mc.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            if (json != null) {
                out.putNextEntry(new JarEntry("version.json"));
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            } else {
                out.putNextEntry(new JarEntry("unrelated.txt"));
                out.closeEntry();
            }
        }
        return new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
    }

    @Test
    void readsTheIdField(@TempDir Path dir) throws Exception {
        try (URLClassLoader loader = withVersionJson(dir,
                "{\"id\": \"1.21.11\", \"name\": \"1.21.11\", \"world_version\": 4189}")) {
            assertEquals("1.21.11", new VersionJsonProbe(loader).detect().orElseThrow(AssertionError::new));
        }
    }

    @Test
    void toleratesWhitespaceAndFieldOrder(@TempDir Path dir) throws Exception {
        try (URLClassLoader loader = withVersionJson(dir, "{\n  \"world_version\" : 1 ,\n  \"id\"\n:\n\"26.2\"\n}")) {
            assertEquals("26.2", new VersionJsonProbe(loader).detect().orElseThrow(AssertionError::new));
        }
    }

    @Test
    void findsNothingWhenTheResourceIsAbsent(@TempDir Path dir) throws Exception {
        try (URLClassLoader loader = withVersionJson(dir, null)) {
            assertFalse(new VersionJsonProbe(loader).detect().isPresent());
        }
    }

    @Test
    void findsNothingWhenIdIsAbsent(@TempDir Path dir) throws Exception {
        try (URLClassLoader loader = withVersionJson(dir, "{\"name\": \"1.21.11\"}")) {
            assertFalse(new VersionJsonProbe(loader).detect().isPresent());
        }
    }
}
