package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.core.ModuleIndex;
import io.github.intisy.nylium.core.Sha256;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class DedupeWriter {

    static final String OBJECTS = "objects";

    static final String INDEXES = "indexes";

    static final class Source {

        private final String indexFileName;
        private final File jar;

        Source(String indexFileName, File jar) {
            this.indexFileName = indexFileName;
            this.jar = jar;
        }
    }

    private DedupeWriter() {
    }

    /**
     * @implNote Every hash written to an index is added to {@code written} in the same pass that
     *     writes its object, so an index can never name a blob the store does not hold. That
     *     invariant is structural rather than checked, which is why the kernel does not verify
     *     hashes at boot.
     */
    static void write(List<Source> sources, Path outputDirectory) {
        Path objects = outputDirectory.resolve(OBJECTS);
        Path indexes = outputDirectory.resolve(INDEXES);
        try {
            Files.createDirectories(objects);
            Files.createDirectories(indexes);
            Set<String> written = new HashSet<String>();
            for (Source source : sources) {
                String rendered = ModuleIndex.render(entriesOf(source, objects, written));
                Files.write(indexes.resolve(source.indexFileName),
                        rendered.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not write the Nylium dedupe output to " + outputDirectory, e);
        }
    }

    private static List<ModuleIndex.Entry> entriesOf(Source source, Path objects, Set<String> written)
            throws IOException {
        List<ModuleIndex.Entry> entries = new ArrayList<ModuleIndex.Entry>();
        try (JarFile jar = new JarFile(source.jar)) {
            Enumeration<JarEntry> enumeration = jar.entries();
            while (enumeration.hasMoreElements()) {
                JarEntry entry = enumeration.nextElement();
                if (entry.isDirectory()) {
                    entries.add(ModuleIndex.Entry.directory(entry.getName()));
                    continue;
                }
                byte[] content = read(jar, entry);
                String hash = Sha256.hex(content);
                if (written.add(hash)) {
                    Files.write(objects.resolve(hash), content);
                }
                entries.add(ModuleIndex.Entry.file(entry.getName(), hash));
            }
        }
        return entries;
    }

    private static byte[] read(JarFile jar, JarEntry entry) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream stream = jar.getInputStream(entry)) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
        }
        return buffer.toByteArray();
    }
}
