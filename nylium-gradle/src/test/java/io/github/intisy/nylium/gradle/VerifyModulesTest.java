package io.github.intisy.nylium.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyModulesTest {

    @TempDir
    Path directory;

    private Path jarContaining(String name, String... entries) throws IOException {
        Path jar = directory.resolve(name);
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(jar.toFile()));
        for (String entry : entries) {
            out.putNextEntry(new ZipEntry(entry));
            out.write("x".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        out.close();
        return jar;
    }

    /**
     * @implNote {@code ZipOutputStream} itself refuses a duplicate entry name with {@code
     *     ZipException: duplicate entry}, so a fixture exercising the case this guard exists for has
     *     to write the zip's local file headers, central directory and end-of-central-directory
     *     record by hand rather than through {@code ZipOutputStream}. Both entries are zero-length
     *     STORED entries: {@code ModuleJarInspector} only reads names from the central directory, so
     *     entry content is irrelevant here.
     */
    private Path jarContainingTheSameEntryNameTwice(String name) throws IOException {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int firstOffset = out.size();
        writeStoredLocalFileHeader(out, nameBytes);
        int secondOffset = out.size();
        writeStoredLocalFileHeader(out, nameBytes);
        int centralDirectoryStart = out.size();
        writeCentralDirectoryHeader(out, nameBytes, firstOffset);
        writeCentralDirectoryHeader(out, nameBytes, secondOffset);
        int centralDirectorySize = out.size() - centralDirectoryStart;
        writeEndOfCentralDirectory(out, 2, centralDirectorySize, centralDirectoryStart);

        Path jar = directory.resolve("dup.jar");
        Files.write(jar, out.toByteArray());
        return jar;
    }

    private static void writeStoredLocalFileHeader(ByteArrayOutputStream out, byte[] nameBytes) {
        writeInt(out, 0x04034b50);
        writeShort(out, 20);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0x21);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0);
        writeShort(out, nameBytes.length);
        writeShort(out, 0);
        out.write(nameBytes, 0, nameBytes.length);
    }

    private static void writeCentralDirectoryHeader(ByteArrayOutputStream out, byte[] nameBytes,
                                                     int localHeaderOffset) {
        writeInt(out, 0x02014b50);
        writeShort(out, 20);
        writeShort(out, 20);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0x21);
        writeInt(out, 0);
        writeInt(out, 0);
        writeInt(out, 0);
        writeShort(out, nameBytes.length);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, 0);
        writeInt(out, 0);
        writeInt(out, localHeaderOffset);
        out.write(nameBytes, 0, nameBytes.length);
    }

    private static void writeEndOfCentralDirectory(ByteArrayOutputStream out, int entryCount,
                                                    int centralDirectorySize, int centralDirectoryOffset) {
        writeInt(out, 0x06054b50);
        writeShort(out, 0);
        writeShort(out, 0);
        writeShort(out, entryCount);
        writeShort(out, entryCount);
        writeInt(out, centralDirectorySize);
        writeInt(out, centralDirectoryOffset);
        writeShort(out, 0);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    @Test
    void acceptsAModuleCarryingItsMixinConfig() throws IOException {
        Path jar = jarContaining("ok.jar", "mixins.demo.json", "demo/Entry.class");
        ModuleJarInspector.verify("1.21.11", jar.toFile(),
                Collections.singletonList("mixins.demo.json"));
    }

    @Test
    void rejectsADeclaredMixinConfigThatIsNotInTheJar() throws IOException {
        Path jar = jarContaining("missing.jar", "demo/Entry.class");
        List<String> mixins = Collections.singletonList("mixins.demo.json");
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> ModuleJarInspector.verify("1.21.11", jar.toFile(), mixins));
        assertTrue(thrown.getMessage().contains("mixins.demo.json"));
    }

    @Test
    void acceptsAModuleWhosePackageOnlyResemblesTheNyliumApi() throws IOException {
        Path jar = jarContaining("nearmiss.jar", "io/github/intisy/nylium/apiextra/Foo.class");
        ModuleJarInspector.verify("1.21.11", jar.toFile(), Collections.<String>emptyList());
    }

    @Test
    void rejectsAModuleThatShadowedTheNyliumApi() throws IOException {
        Path jar = jarContaining("shadowed.jar",
                "io/github/intisy/nylium/api/PlatformId.class");
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> ModuleJarInspector.verify("1.21.11", jar.toFile(),
                        Collections.<String>emptyList()));
        assertTrue(thrown.getMessage().contains("nylium"));
    }

    @Test
    void rejectsAModuleCarryingItsOwnManifest() throws IOException {
        Path jar = jarContaining("nested.jar", "nylium-modules.properties");
        assertThrows(RuntimeException.class, () -> ModuleJarInspector.verify("1.21.11",
                jar.toFile(), Collections.<String>emptyList()));
    }

    @Test
    void rejectsAModuleContainingTheSameEntryNameTwice() throws IOException {
        Path jar = jarContainingTheSameEntryNameTwice("demo/Entry.class");
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> ModuleJarInspector.verify("1.21.11", jar.toFile(),
                        Collections.<String>emptyList()));
        assertTrue(thrown.getMessage().contains("1.21.11"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("demo/Entry.class"), thrown.getMessage());
    }
}
