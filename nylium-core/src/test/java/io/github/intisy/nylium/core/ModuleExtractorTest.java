package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.NyliumException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleExtractorTest {

    private static ModuleDescriptor descriptor(String path) {
        return ModuleManifest.read(new StringReader(
                        "module.0.path=" + path + "\n"
                                + "module.0.platforms=FABRIC\n"
                                + "module.0.minecraft=1.21.11\n"))
                .modules().get(0);
    }

    private static ClassLoader outerJarContaining(Path dir, String entry, byte[] payload) throws Exception {
        Files.createDirectories(dir);
        Path outer = dir.resolve("outer.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outer))) {
            jar.putNextEntry(new JarEntry(entry));
            jar.write(payload);
            jar.closeEntry();
        }
        return new URLClassLoader(new URL[]{outer.toUri().toURL()}, null);
    }

    private static byte[] tinyJar() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("payload.txt"));
            jar.write("hello".getBytes("UTF-8"));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    void extractsTheNestedJarToTheCache(@TempDir Path dir) throws Exception {
        byte[] payload = tinyJar();
        try (URLClassLoader loader = (URLClassLoader) outerJarContaining(dir, "modules/a.jar", payload)) {
            ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));

            Path extracted = extractor.extract(loader, descriptor("modules/a.jar"));

            assertTrue(Files.isRegularFile(extracted));
            assertArrayEquals(payload, Files.readAllBytes(extracted));
        }
    }

    @Test
    void reusesAnAlreadyExtractedModule(@TempDir Path dir) throws Exception {
        try (URLClassLoader loader = (URLClassLoader) outerJarContaining(dir, "modules/a.jar", tinyJar())) {
            ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));
            ModuleDescriptor module = descriptor("modules/a.jar");

            Path first = extractor.extract(loader, module);
            long stamp = Files.getLastModifiedTime(first).toMillis();
            Path second = extractor.extract(loader, module);

            assertEquals(first, second);
            assertEquals(stamp, Files.getLastModifiedTime(second).toMillis());
        }
    }

    @Test
    void differentContentLandsInDifferentCacheEntries(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache");
        try (URLClassLoader loader1 = (URLClassLoader) outerJarContaining(dir.resolve("a"), "m.jar", tinyJar())) {
            Path first = new ModuleExtractor(cache).extract(loader1, descriptor("m.jar"));

            ByteArrayOutputStream other = new ByteArrayOutputStream();
            try (JarOutputStream jar = new JarOutputStream(other)) {
                jar.putNextEntry(new JarEntry("payload.txt"));
                jar.write("goodbye".getBytes("UTF-8"));
                jar.closeEntry();
            }
            try (URLClassLoader loader2 = (URLClassLoader) outerJarContaining(dir.resolve("b"), "m.jar", other.toByteArray())) {
                Path second = new ModuleExtractor(cache).extract(loader2, descriptor("m.jar"));

                assertTrue(!first.equals(second));
            }
        }
    }

    @Test
    void concurrentExtractionYieldsOneCompleteFile(@TempDir Path dir) throws Exception {
        byte[] payload = tinyJar();
        try (URLClassLoader loader = (URLClassLoader) outerJarContaining(dir, "modules/a.jar", payload)) {
            Path cache = dir.resolve("cache");
            ModuleDescriptor module = descriptor("modules/a.jar");

            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
            java.util.List<java.util.concurrent.Future<Path>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> new ModuleExtractor(cache).extract(loader, module)));
            }
            for (java.util.concurrent.Future<Path> result : results) {
                assertArrayEquals(payload, Files.readAllBytes(result.get()));
            }
            pool.shutdown();
        }
    }

    @Test
    void aMissingNestedEntryFailsClearly(@TempDir Path dir) throws Exception {
        try (URLClassLoader loader = (URLClassLoader) outerJarContaining(dir, "modules/a.jar", tinyJar())) {
            ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));

            NyliumException thrown = assertThrows(NyliumException.class,
                    () -> extractor.extract(loader, descriptor("modules/absent.jar")));
            assertTrue(thrown.getMessage().contains("modules/absent.jar"), thrown.getMessage());
        }
    }

    @Test
    void aModulePathWithNoBasenameFailsClearly(@TempDir Path dir) throws Exception {
        byte[] payload = tinyJar();
        try (URLClassLoader loader = (URLClassLoader) outerJarContaining(dir, "modules/", payload)) {
            ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));

            NyliumException thrown = assertThrows(NyliumException.class,
                    () -> extractor.extract(loader, descriptor("modules/")));
            assertTrue(thrown.getMessage().contains("modules/"), thrown.getMessage());
        }
    }
}
