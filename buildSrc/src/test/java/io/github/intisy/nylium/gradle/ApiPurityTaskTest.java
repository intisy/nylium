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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class ApiPurityTaskTest {

    /**
     * @implNote Uses the in-process compiler rather than shelling out to {@code javac}, because
     * this build resolves its JDK through a Gradle toolchain and {@code PATH} may not carry a
     * compiler at all. A JRE-only test runner fails loudly here instead of silently skipping the
     * negative case this task exists to enforce.
     */
    private static Path compile(Path dir, Path... sourceFiles) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            fail("no system Java compiler available: a JDK (not just a JRE) is required to run this test");
        }
        Path out = Files.createDirectories(dir.resolve("out"));
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        StringWriter errors = new StringWriter();
        boolean success;
        try {
            success = compiler.getTask(errors, fileManager, null,
                            Arrays.asList("-d", out.toString()), null,
                            fileManager.getJavaFileObjectsFromPaths(Arrays.asList(sourceFiles)))
                    .call();
        } finally {
            fileManager.close();
        }
        assertTrue(success, "the fixture must compile:\n" + errors);
        return out;
    }

    private static Path writeSource(Path dir, String relativePath, String source) throws Exception {
        Path file = dir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, source.getBytes("UTF-8"));
        return file;
    }

    private static Path buildJar(Path dir, Map<String, byte[]> entries) throws Exception {
        Path jarPath = dir.resolve("test.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return jarPath;
    }

    @Test
    void acceptsAClassWithNoMinecraftReference(@TempDir Path dir) throws Exception {
        Path source = writeSource(dir, "Clean.java",
                "public class Clean { public String hello() { return \"hi\"; } }");
        Path out = compile(dir, source);

        assertTrue(ApiPurityScanner.scan(Files.readAllBytes(out.resolve("Clean.class"))).isEmpty());
    }

    @Test
    void rejectsAMinecraftReturnType(@TempDir Path dir) throws Exception {
        Path level = writeSource(dir, "net/minecraft/Level.java",
                "package net.minecraft; public class Level {}");
        Path leaky = writeSource(dir, "Leaky.java",
                "public class Leaky { public net.minecraft.Level level() { return null; } }");
        Path out = compile(dir, level, leaky);

        List<String> findings = ApiPurityScanner.scan(Files.readAllBytes(out.resolve("Leaky.class")));

        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("level"), findings.toString());
    }

    @Test
    void rejectsAMinecraftFieldType(@TempDir Path dir) throws Exception {
        Path level = writeSource(dir, "net/minecraft/Level.java",
                "package net.minecraft; public class Level {}");
        Path leakyField = writeSource(dir, "LeakyField.java",
                "public class LeakyField { public net.minecraft.Level level; }");
        Path out = compile(dir, level, leakyField);

        assertEquals(1, ApiPurityScanner.scan(Files.readAllBytes(out.resolve("LeakyField.class"))).size());
    }

    @Test
    void rejectsAMinecraftGenericArgument(@TempDir Path dir) throws Exception {
        Path level = writeSource(dir, "net/minecraft/Level.java",
                "package net.minecraft; public class Level {}");
        Path leakyGeneric = writeSource(dir, "LeakyGeneric.java",
                "import java.util.List;\n"
                        + "public class LeakyGeneric { public List<net.minecraft.Level> levels() { return null; } }");
        Path out = compile(dir, level, leakyGeneric);

        assertFalse(ApiPurityScanner.scan(Files.readAllBytes(out.resolve("LeakyGeneric.class"))).isEmpty());
    }

    @Test
    void ignoresPrivateInternals(@TempDir Path dir) throws Exception {
        Path level = writeSource(dir, "net/minecraft/Level.java",
                "package net.minecraft; public class Level {}");
        Path privateUse = writeSource(dir, "PrivateUse.java",
                "public class PrivateUse { private net.minecraft.Level hidden; }");
        Path out = compile(dir, level, privateUse);

        assertTrue(ApiPurityScanner.scan(Files.readAllBytes(out.resolve("PrivateUse.class"))).isEmpty());
    }

    @Test
    void rejectsAMinecraftThrowsClause(@TempDir Path dir) throws Exception {
        Path level = writeSource(dir, "net/minecraft/Level.java",
                "package net.minecraft; public class Level extends RuntimeException {}");
        Path leakyThrows = writeSource(dir, "LeakyThrows.java",
                "public class LeakyThrows { public void doThing() throws net.minecraft.Level {} }");
        Path out = compile(dir, level, leakyThrows);

        assertFalse(ApiPurityScanner.scan(Files.readAllBytes(out.resolve("LeakyThrows.class"))).isEmpty());
    }

    @Test
    void rejectsAMinecraftAnnotationOnAPublicMethod(@TempDir Path dir) throws Exception {
        Path annotation = writeSource(dir, "net/minecraft/MinecraftAnnotation.java",
                "package net.minecraft; public @interface MinecraftAnnotation {}");
        Path annotatedMethod = writeSource(dir, "AnnotatedMethod.java",
                "public class AnnotatedMethod { @net.minecraft.MinecraftAnnotation public void doThing() {} }");
        Path out = compile(dir, annotation, annotatedMethod);

        assertFalse(ApiPurityScanner.scan(Files.readAllBytes(out.resolve("AnnotatedMethod.class"))).isEmpty());
    }

    @Test
    void rejectsAMinecraftAnnotationOnAPublicField(@TempDir Path dir) throws Exception {
        Path annotation = writeSource(dir, "net/minecraft/MinecraftAnnotation.java",
                "package net.minecraft; public @interface MinecraftAnnotation {}");
        Path annotatedField = writeSource(dir, "AnnotatedField.java",
                "public class AnnotatedField { @net.minecraft.MinecraftAnnotation public int value; }");
        Path out = compile(dir, annotation, annotatedField);

        assertFalse(ApiPurityScanner.scan(Files.readAllBytes(out.resolve("AnnotatedField.class"))).isEmpty());
    }

    @Test
    void rejectsAMinecraftAnnotationOnAParameterOfAPublicMethod(@TempDir Path dir) throws Exception {
        Path annotation = writeSource(dir, "net/minecraft/MinecraftAnnotation.java",
                "package net.minecraft; public @interface MinecraftAnnotation {}");
        Path annotatedParameter = writeSource(dir, "AnnotatedParameter.java",
                "public class AnnotatedParameter { public void doThing(@net.minecraft.MinecraftAnnotation String value) {} }");
        Path out = compile(dir, annotation, annotatedParameter);

        assertFalse(ApiPurityScanner.scan(Files.readAllBytes(out.resolve("AnnotatedParameter.class"))).isEmpty());
    }

    @Test
    void rejectsAPublicClassExtendingAMinecraftType(@TempDir Path dir) throws Exception {
        Path level = writeSource(dir, "net/minecraft/Level.java",
                "package net.minecraft; public class Level {}");
        Path leakySupertype = writeSource(dir, "LeakySupertype.java",
                "public class LeakySupertype extends net.minecraft.Level {}");
        Path out = compile(dir, level, leakySupertype);

        List<String> findings = ApiPurityScanner.scan(Files.readAllBytes(out.resolve("LeakySupertype.class")));

        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("supertype"), findings.toString());
    }

    @Test
    void rejectsAPublicClassImplementingAMinecraftInterface(@TempDir Path dir) throws Exception {
        Path tickable = writeSource(dir, "net/minecraft/Tickable.java",
                "package net.minecraft; public interface Tickable {}");
        Path leakyInterface = writeSource(dir, "LeakyInterface.java",
                "public class LeakyInterface implements net.minecraft.Tickable {}");
        Path out = compile(dir, tickable, leakyInterface);

        List<String> findings = ApiPurityScanner.scan(Files.readAllBytes(out.resolve("LeakyInterface.class")));

        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("interface"), findings.toString());
    }

    @Test
    void ignoresNonPublicClassSupertype(@TempDir Path dir) throws Exception {
        Path level = writeSource(dir, "net/minecraft/Level.java",
                "package net.minecraft; public class Level {}");
        Path packagePrivate = writeSource(dir, "PackagePrivateLeaky.java",
                "class PackagePrivateLeaky extends net.minecraft.Level {}");
        Path out = compile(dir, level, packagePrivate);

        assertTrue(ApiPurityScanner.scan(Files.readAllBytes(out.resolve("PackagePrivateLeaky.class"))).isEmpty());
    }

    @Test
    void jarScanFindsNoIssuesInACleanJar(@TempDir Path dir) throws Exception {
        Path source = writeSource(dir, "Clean.java",
                "public class Clean { public String hello() { return \"hi\"; } }");
        Path out = compile(dir, source);
        Path jar = buildJar(dir, Collections.singletonMap("Clean.class",
                Files.readAllBytes(out.resolve("Clean.class"))));

        assertTrue(ApiPurityScanner.scan(jar).isEmpty());
    }

    @Test
    void jarScanFindsTheLeakInsideAJar(@TempDir Path dir) throws Exception {
        Path level = writeSource(dir, "net/minecraft/Level.java",
                "package net.minecraft; public class Level {}");
        Path leaky = writeSource(dir, "Leaky.java",
                "public class Leaky { public net.minecraft.Level level() { return null; } }");
        Path out = compile(dir, level, leaky);
        Path jar = buildJar(dir, Collections.singletonMap("Leaky.class",
                Files.readAllBytes(out.resolve("Leaky.class"))));

        List<String> findings = ApiPurityScanner.scan(jar);

        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("level"), findings.toString());
    }

    @Test
    void jarScanIgnoresNonClassEntries(@TempDir Path dir) throws Exception {
        Path source = writeSource(dir, "Clean.java",
                "public class Clean { public String hello() { return \"hi\"; } }");
        Path out = compile(dir, source);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes("UTF-8"));
        entries.put("Clean.class", Files.readAllBytes(out.resolve("Clean.class")));
        entries.put("some/resource.txt", "not a class file".getBytes("UTF-8"));
        Path jar = buildJar(dir, entries);

        assertTrue(ApiPurityScanner.scan(jar).isEmpty());
    }

    @Test
    void jarScanOnAnEmptyJarFindsNothing(@TempDir Path dir) throws Exception {
        Path jar = buildJar(dir, Collections.emptyMap());

        assertTrue(ApiPurityScanner.scan(jar).isEmpty());
    }

    @Test
    void jarScanOnAClassWithNoMembersFindsNothing(@TempDir Path dir) throws Exception {
        Path source = writeSource(dir, "Empty.java", "public class Empty {}");
        Path out = compile(dir, source);
        Path jar = buildJar(dir, Collections.singletonMap("Empty.class",
                Files.readAllBytes(out.resolve("Empty.class"))));

        assertTrue(ApiPurityScanner.scan(jar).isEmpty());
    }
}
