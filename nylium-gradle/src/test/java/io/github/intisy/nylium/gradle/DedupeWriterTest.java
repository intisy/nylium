package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.core.ModuleAssembler;
import io.github.intisy.nylium.core.ModuleIndex;
import io.github.intisy.nylium.core.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DedupeWriterTest {

    private static Path jarOf(Path dir, String name, Map<String, byte[]> entries) throws Exception {
        return jarOf(dir, name, entries, null);
    }

    private static Path jarOf(Path dir, String name, Map<String, byte[]> entries, Manifest manifest)
            throws Exception {
        Files.createDirectories(dir);
        Path jar = dir.resolve(name);
        JarOutputStream out = manifest == null
                ? new JarOutputStream(Files.newOutputStream(jar))
                : new JarOutputStream(Files.newOutputStream(jar), manifest);
        try {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                if (entry.getValue() != null) {
                    out.write(entry.getValue());
                }
                out.closeEntry();
            }
        } finally {
            out.close();
        }
        return jar;
    }

    private static Manifest simpleManifest() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        return manifest;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @implNote {@code JarFile}, not {@code JarInputStream}: the latter silently consumes a leading
     *     {@code META-INF/MANIFEST.MF} and never returns it from {@code getNextJarEntry}, and reads
     *     in local-header order, whereas {@code DedupeWriter} builds an index from {@code
     *     JarFile.entries()}'s central-directory order. Reading both sides of the comparison the
     *     same way {@code DedupeWriter} itself reads a source jar is what lets this gate catch a
     *     divergence in either property.
     */
    private static Map<String, byte[]> readJar(Path jar) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> enumeration = file.entries();
            while (enumeration.hasMoreElements()) {
                JarEntry entry = enumeration.nextElement();
                entries.put(entry.getName(), readAll(file.getInputStream(entry)));
            }
        }
        return entries;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[512];
            int read;
            while ((read = in.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            in.close();
        }
    }

    private static Map<String, byte[]> moduleEntries(String unique) {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("pkg/Zebra.class", utf8(unique));
        entries.put("pkg/", null);
        entries.put("pkg/Apple.class", utf8("shared bytes"));
        return entries;
    }

    @Test
    void storesAnEntrySharedByTwoModulesOnlyOnce(@TempDir Path dir) throws Exception {
        Path first = jarOf(dir.resolve("in"), "one.jar", moduleEntries("one"));
        Path second = jarOf(dir.resolve("in"), "two.jar", moduleEntries("two"));
        Path output = dir.resolve("out");

        DedupeWriter.write(Arrays.asList(
                new DedupeWriter.Source("one.index", first.toFile()),
                new DedupeWriter.Source("two.index", second.toFile())), output);

        List<Path> objects = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream =
                     Files.list(output.resolve(DedupeWriter.OBJECTS))) {
            stream.forEach(objects::add);
        }
        assertEquals(3, objects.size(), "expected shared plus two unique blobs, got " + objects);
        assertTrue(Files.isRegularFile(output.resolve(DedupeWriter.OBJECTS)
                .resolve(Sha256.hex(utf8("shared bytes")))));
    }

    @Test
    void everyIndexRebuildsItsModuleEntryForEntry(@TempDir Path dir) throws Exception {
        Path first = jarOf(dir.resolve("in"), "one.jar", moduleEntries("one"), simpleManifest());
        Path second = jarOf(dir.resolve("in"), "two.jar", moduleEntries("two"), simpleManifest());
        Path output = dir.resolve("out");
        DedupeWriter.write(Arrays.asList(
                new DedupeWriter.Source("one.index", first.toFile()),
                new DedupeWriter.Source("two.index", second.toFile())), output);

        assertRebuilds(output, "one.index", first);
        assertRebuilds(output, "two.index", second);
    }

    @Test
    void writesNoObjectForADirectoryEntry(@TempDir Path dir) throws Exception {
        Map<String, byte[]> onlyDirectories = new LinkedHashMap<String, byte[]>();
        onlyDirectories.put("pkg/", null);
        Path jar = jarOf(dir.resolve("in"), "one.jar", onlyDirectories);
        Path output = dir.resolve("out");

        DedupeWriter.write(Arrays.asList(
                new DedupeWriter.Source("one.index", jar.toFile())), output);

        try (java.util.stream.Stream<Path> stream =
                     Files.list(output.resolve(DedupeWriter.OBJECTS))) {
            assertEquals(0, stream.count());
        }
    }

    @Test
    void succeedsForEmptyJar(@TempDir Path dir) throws Exception {
        Map<String, byte[]> empty = new LinkedHashMap<String, byte[]>();
        Path jar = jarOf(dir.resolve("in"), "empty.jar", empty);
        Path output = dir.resolve("out");

        DedupeWriter.write(Arrays.asList(
                new DedupeWriter.Source("empty.index", jar.toFile())), output);

        Path indexFile = output.resolve(DedupeWriter.INDEXES).resolve("empty.index");
        assertTrue(Files.isRegularFile(indexFile), "index file should exist");
        ModuleIndex index = ModuleIndex.parse(Files.readAllBytes(indexFile));
        assertEquals(0, index.entries().size(), "index should have zero entries");
    }

    private static void assertRebuilds(Path output, String indexName, Path original)
            throws Exception {
        final Path objects = output.resolve(DedupeWriter.OBJECTS);
        ModuleIndex index = ModuleIndex.parse(Files.readAllBytes(
                output.resolve(DedupeWriter.INDEXES).resolve(indexName)));
        Path rebuiltJar = output.resolve(indexName + ".rebuilt.jar");
        try (java.io.OutputStream rebuilt = Files.newOutputStream(rebuiltJar)) {
            ModuleAssembler.assemble(index, new ModuleAssembler.BlobSource() {
                @Override
                public InputStream open(String hash) throws java.io.IOException {
                    Path blob = objects.resolve(hash);
                    return Files.isRegularFile(blob)
                            ? Files.newInputStream(blob) : null;
                }
            }, rebuilt);
        }

        Map<String, byte[]> expected = readJar(original);
        Map<String, byte[]> actual = readJar(rebuiltJar);
        assertEquals(new ArrayList<String>(expected.keySet()), new ArrayList<String>(actual.keySet()),
                indexName + " rebuilt different entry names or a different order");
        for (String name : expected.keySet()) {
            assertArrayEquals(expected.get(name), actual.get(name),
                    indexName + " rebuilt " + name + " with different content");
        }
    }
}
