package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.NyliumException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleAssemblerTest {

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static ModuleAssembler.BlobSource sourceOf(final Map<String, byte[]> blobs) {
        return new ModuleAssembler.BlobSource() {
            @Override
            public InputStream open(String hash) {
                byte[] content = blobs.get(hash);
                return content == null ? null : new ByteArrayInputStream(content);
            }
        };
    }

    @Test
    void rebuildsEveryEntryInIndexOrderWithItsOwnContent() throws Exception {
        byte[] alpha = utf8("alpha");
        byte[] beta = utf8("beta");
        Map<String, byte[]> blobs = new HashMap<String, byte[]>();
        blobs.put(Sha256.hex(alpha), alpha);
        blobs.put(Sha256.hex(beta), beta);
        ModuleIndex index = ModuleIndex.parse(utf8(ModuleIndex.render(Arrays.asList(
                ModuleIndex.Entry.directory("pkg/"),
                ModuleIndex.Entry.file("pkg/one.txt", Sha256.hex(alpha)),
                ModuleIndex.Entry.file("pkg/two.txt", Sha256.hex(beta))))));
        ByteArrayOutputStream target = new ByteArrayOutputStream();

        ModuleAssembler.assemble(index, sourceOf(blobs), target);

        List<String> names = new ArrayList<String>();
        Map<String, byte[]> contents = new HashMap<String, byte[]>();
        try (JarInputStream jar = new JarInputStream(
                new ByteArrayInputStream(target.toByteArray()))) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                names.add(entry.getName());
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[64];
                int read;
                while ((read = jar.read(chunk)) >= 0) {
                    buffer.write(chunk, 0, read);
                }
                contents.put(entry.getName(), buffer.toByteArray());
            }
        }
        assertEquals(Arrays.asList("pkg/", "pkg/one.txt", "pkg/two.txt"), names);
        assertArrayEquals(alpha, contents.get("pkg/one.txt"));
        assertArrayEquals(beta, contents.get("pkg/two.txt"));
        assertEquals(0, contents.get("pkg/").length);
    }

    @Test
    void reusesOneBlobForTwoEntriesWithTheSameContent() throws Exception {
        byte[] shared = utf8("shared");
        Map<String, byte[]> blobs = new HashMap<String, byte[]>();
        blobs.put(Sha256.hex(shared), shared);
        ModuleIndex index = ModuleIndex.parse(utf8(ModuleIndex.render(Arrays.asList(
                ModuleIndex.Entry.file("a.txt", Sha256.hex(shared)),
                ModuleIndex.Entry.file("b.txt", Sha256.hex(shared))))));
        ByteArrayOutputStream target = new ByteArrayOutputStream();

        ModuleAssembler.assemble(index, sourceOf(blobs), target);

        try (JarInputStream jar = new JarInputStream(
                new ByteArrayInputStream(target.toByteArray()))) {
            assertEquals("a.txt", jar.getNextJarEntry().getName());
            assertEquals("b.txt", jar.getNextJarEntry().getName());
        }
    }

    @Test
    void aMissingBlobNamesBothTheEntryAndTheHash() {
        String hash = Sha256.hex(utf8("absent"));
        ModuleIndex index = ModuleIndex.parse(utf8(ModuleIndex.render(
                Arrays.asList(ModuleIndex.Entry.file("gone.txt", hash)))));

        NyliumException thrown = assertThrows(NyliumException.class, () -> ModuleAssembler.assemble(
                index, sourceOf(new HashMap<String, byte[]>()), new ByteArrayOutputStream()));

        assertTrue(thrown.getMessage().contains("gone.txt"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(hash), thrown.getMessage());
    }
}
