package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.core.ModuleAssembler;
import io.github.intisy.nylium.core.ModuleIndex;
import io.github.intisy.nylium.core.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DedupeWriterTest {

    private static Path jarOf(Path dir, String name, Map<String, byte[]> entries) throws Exception {
        Files.createDirectories(dir);
        Path jar = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                if (entry.getValue() != null) {
                    out.write(entry.getValue());
                }
                out.closeEntry();
            }
        }
        return jar;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> readJar(Path jar) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        try (JarInputStream in = new JarInputStream(Files.newInputStream(jar))) {
            JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[512];
                int read;
                while ((read = in.read(chunk)) >= 0) {
                    buffer.write(chunk, 0, read);
                }
                entries.put(entry.getName(), buffer.toByteArray());
            }
        }
        return entries;
    }

    private static Map<String, byte[]> moduleEntries(String unique) {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("pkg/", null);
        entries.put("pkg/Shared.class", utf8("shared bytes"));
        entries.put("pkg/Unique.class", utf8(unique));
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
        Path first = jarOf(dir.resolve("in"), "one.jar", moduleEntries("one"));
        Path second = jarOf(dir.resolve("in"), "two.jar", moduleEntries("two"));
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

    private static void assertRebuilds(Path output, String indexName, Path original)
            throws Exception {
        final Path objects = output.resolve(DedupeWriter.OBJECTS);
        ModuleIndex index = ModuleIndex.parse(Files.readAllBytes(
                output.resolve(DedupeWriter.INDEXES).resolve(indexName)));
        ByteArrayOutputStream rebuilt = new ByteArrayOutputStream();
        ModuleAssembler.assemble(index, new ModuleAssembler.BlobSource() {
            @Override
            public InputStream open(String hash) throws java.io.IOException {
                Path blob = objects.resolve(hash);
                return Files.isRegularFile(blob) ? new ByteArrayInputStream(
                        Files.readAllBytes(blob)) : null;
            }
        }, rebuilt);

        Map<String, byte[]> expected = readJar(original);
        Map<String, byte[]> actual = new LinkedHashMap<String, byte[]>();
        try (JarInputStream in = new JarInputStream(
                new ByteArrayInputStream(rebuilt.toByteArray()))) {
            JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[512];
                int read;
                while ((read = in.read(chunk)) >= 0) {
                    buffer.write(chunk, 0, read);
                }
                actual.put(entry.getName(), buffer.toByteArray());
            }
        }
        assertEquals(new ArrayList<String>(expected.keySet()), new ArrayList<String>(actual.keySet()),
                indexName + " rebuilt different entry names or a different order");
        for (String name : expected.keySet()) {
            assertArrayEquals(expected.get(name), actual.get(name),
                    indexName + " rebuilt " + name + " with different content");
        }
    }
}
