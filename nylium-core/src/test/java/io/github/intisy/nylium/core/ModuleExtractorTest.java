package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.NyliumException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import static java.util.Collections.singletonList;
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

    private static ClassLoader outerJarContaining(Path dir, Map<String, byte[]> entries)
            throws Exception {
        Files.createDirectories(dir);
        Path outer = dir.resolve("outer.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outer))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return new URLClassLoader(new URL[]{outer.toUri().toURL()}, null);
    }

    private static Map<String, byte[]> indexedModuleJar() {
        byte[] alpha = "alpha".getBytes(StandardCharsets.UTF_8);
        byte[] beta = "beta".getBytes(StandardCharsets.UTF_8);
        String indexText = ModuleIndex.render(java.util.Arrays.asList(
                ModuleIndex.Entry.file("one.txt", Sha256.hex(alpha)),
                ModuleIndex.Entry.file("two.txt", Sha256.hex(beta))));
        Map<String, byte[]> outer = new HashMap<String, byte[]>();
        outer.put("modules/a.index", indexText.getBytes(StandardCharsets.UTF_8));
        outer.put("nylium/objects/" + Sha256.hex(alpha), alpha);
        outer.put("nylium/objects/" + Sha256.hex(beta), beta);
        return outer;
    }

    private static List<String> entryNames(Path jarFile) throws Exception {
        List<String> names = new ArrayList<String>();
        try (JarInputStream jar = new JarInputStream(Files.newInputStream(jarFile))) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    @Test
    void rebuildsAModuleDeclaredAsAnIndex(@TempDir Path dir) throws Exception {
        try (URLClassLoader loader = (URLClassLoader) outerJarContaining(dir, indexedModuleJar())) {
            ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));

            Path extracted = extractor.extract(loader, descriptor("modules/a.index"));

            assertTrue(Files.isRegularFile(extracted));
            assertTrue(extracted.getFileName().toString().startsWith("a-"),
                    extracted.getFileName().toString());
            assertTrue(extracted.getFileName().toString().endsWith(".jar"),
                    extracted.getFileName().toString());
            assertEquals(java.util.Arrays.asList("one.txt", "two.txt"), entryNames(extracted));
        }
    }

    @Test
    void reusesAnAlreadyRebuiltIndexedModule(@TempDir Path dir) throws Exception {
        try (URLClassLoader loader = (URLClassLoader) outerJarContaining(dir, indexedModuleJar())) {
            ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));
            ModuleDescriptor module = descriptor("modules/a.index");

            Path first = extractor.extract(loader, module);
            long stamp = Files.getLastModifiedTime(first).toMillis();
            Path second = extractor.extract(loader, module);

            assertEquals(first, second);
            assertEquals(stamp, Files.getLastModifiedTime(second).toMillis());
        }
    }

    @Test
    void anIndexNamingAnAbsentObjectFailsClearly(@TempDir Path dir) throws Exception {
        Map<String, byte[]> outer = indexedModuleJar();
        outer.remove("nylium/objects/"
                + Sha256.hex("alpha".getBytes(StandardCharsets.UTF_8)));
        try (URLClassLoader loader = (URLClassLoader) outerJarContaining(dir, outer)) {
            ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));

            NyliumException thrown = assertThrows(NyliumException.class,
                    () -> extractor.extract(loader, descriptor("modules/a.index")));

            assertTrue(thrown.getMessage().contains("one.txt"), thrown.getMessage());
        }
    }

    private static Path jarOnDisk(Path dir, Map<String, byte[]> entries) throws Exception {
        Files.createDirectories(dir);
        Path jar = dir.resolve("module.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return jar;
    }

    private static Map<String, byte[]> moduleBundling(String entry, byte[] payload) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("some/Class.class", new byte[]{1, 2, 3});
        entries.put(entry, payload);
        return entries;
    }

    @Test
    void listsAJarBundledUnderMetaInfJars(@TempDir Path dir) throws Exception {
        Path module = jarOnDisk(dir, moduleBundling("META-INF/jars/library-1.0.jar", tinyJar()));
        ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));

        assertEquals(singletonList("META-INF/jars/library-1.0.jar"),
                extractor.nestedJarEntries(module));
    }

    @Test
    void listsNothingWhenTheModuleBundlesNoJar(@TempDir Path dir) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("some/Class.class", new byte[]{1, 2, 3});
        entries.put("META-INF/jars/notes.txt", "ignored".getBytes(StandardCharsets.UTF_8));
        Path module = jarOnDisk(dir, entries);
        ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));

        assertTrue(extractor.nestedJarEntries(module).isEmpty());
    }

    @Test
    void extractsABundledJarWithItsOwnBytes(@TempDir Path dir) throws Exception {
        byte[] payload = tinyJar();
        Path module = jarOnDisk(dir, moduleBundling("META-INF/jars/library-1.0.jar", payload));
        ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));

        Path extracted = extractor.extractNested(module, "META-INF/jars/library-1.0.jar");

        assertArrayEquals(payload, Files.readAllBytes(extracted));
        assertTrue(extracted.getFileName().toString().startsWith("library-1.0-"),
                extracted.getFileName().toString());
    }

    @Test
    void reusesAnAlreadyExtractedBundledJar(@TempDir Path dir) throws Exception {
        Path module = jarOnDisk(dir, moduleBundling("META-INF/jars/library-1.0.jar", tinyJar()));
        ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));

        Path first = extractor.extractNested(module, "META-INF/jars/library-1.0.jar");
        Files.write(first, "clobbered".getBytes(StandardCharsets.UTF_8));
        Path second = extractor.extractNested(module, "META-INF/jars/library-1.0.jar");

        assertEquals(first, second);
        assertEquals("clobbered", new String(Files.readAllBytes(second), StandardCharsets.UTF_8));
    }

    @Test
    void aJarBundledBelowMetaInfJarsFailsClearly(@TempDir Path dir) throws Exception {
        Path module = jarOnDisk(dir, moduleBundling("META-INF/jars/deeper/library.jar", tinyJar()));
        ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));

        NyliumException thrown = assertThrows(NyliumException.class,
                () -> extractor.nestedJarEntries(module));

        assertTrue(thrown.getMessage().contains("deeper/library.jar"), thrown.getMessage());
    }

    @Test
    void aBundledJarWithNoUsableNameFailsClearly(@TempDir Path dir) throws Exception {
        Path module = jarOnDisk(dir, moduleBundling("META-INF/jars/...jar", tinyJar()));
        ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));

        NyliumException thrown = assertThrows(NyliumException.class,
                () -> extractor.extractNested(module, "META-INF/jars/...jar"));

        assertTrue(thrown.getMessage().contains("no usable filename"), thrown.getMessage());
    }
}
