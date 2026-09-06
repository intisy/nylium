package io.github.intisy.nylium.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ClassFileVersionScannerTest {

    /**
     * @implNote Compiles at {@code --release 8} rather than trusting the runner's default, so the
     * positive case asserts the invariant itself instead of whichever JDK happens to run the
     * buildSrc tests.
     */
    private static byte[] compileAtRelease8(Path dir) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            fail("no system Java compiler available: a JDK (not just a JRE) is required to run this test");
        }
        Path source = dir.resolve("Eight.java");
        Files.write(source, "public class Eight {}".getBytes("UTF-8"));
        Path out = Files.createDirectories(dir.resolve("out"));
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        StringWriter errors = new StringWriter();
        boolean success;
        try {
            success = compiler.getTask(errors, fileManager, null,
                            Arrays.asList("-d", out.toString(), "--release", "8", "-Xlint:-options"), null,
                            fileManager.getJavaFileObjectsFromPaths(Collections.singletonList(source)))
                    .call();
        } finally {
            fileManager.close();
        }
        assertTrue(success, "the fixture must compile:\n" + errors);
        return Files.readAllBytes(out.resolve("Eight.class"));
    }

    private static byte[] withMajorVersion(byte[] classFile, int major) {
        byte[] retargeted = classFile.clone();
        retargeted[6] = (byte) (major >> 8);
        retargeted[7] = (byte) major;
        return retargeted;
    }

    private static Path jarOf(Path dir, String entryName, byte[] classFile) throws Exception {
        Path jarPath = dir.resolve("artifact.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            jar.write("Manifest-Version: 1.0\n".getBytes("UTF-8"));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry(entryName));
            jar.write(classFile);
            jar.closeEntry();
        }
        return jarPath;
    }

    @Test
    void readsTheMajorVersionOfARelease8Class(@TempDir Path dir) throws Exception {
        assertEquals(ClassFileVersionScanner.JAVA_8, ClassFileVersionScanner.majorVersion(compileAtRelease8(dir)));
    }

    @Test
    void acceptsAJarOfRelease8Classes(@TempDir Path dir) throws Exception {
        Path jar = jarOf(dir, "Eight.class", compileAtRelease8(dir));

        assertTrue(ClassFileVersionScanner.scan(jar).isEmpty());
    }

    @Test
    void rejectsAJarCarryingANewerClass(@TempDir Path dir) throws Exception {
        Path jar = jarOf(dir, "Seventeen.class", withMajorVersion(compileAtRelease8(dir), 61));

        List<String> findings = ClassFileVersionScanner.scan(jar);

        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("Seventeen.class"), findings.toString());
        assertTrue(findings.get(0).contains("61"), findings.toString());
    }

    @Test
    void rejectsSomethingThatIsNotAClassFile() {
        assertThrows(IllegalArgumentException.class,
                () -> ClassFileVersionScanner.majorVersion("not a class file".getBytes()));
    }
}
