package io.github.intisy.rutter.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertTrue(!ApiPurityScanner.scan(Files.readAllBytes(out.resolve("LeakyGeneric.class"))).isEmpty());
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

        assertTrue(!ApiPurityScanner.scan(Files.readAllBytes(out.resolve("LeakyThrows.class"))).isEmpty());
    }
}
