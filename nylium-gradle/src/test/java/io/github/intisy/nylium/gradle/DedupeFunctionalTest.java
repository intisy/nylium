package io.github.intisy.nylium.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Properties;
import java.util.Random;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DedupeFunctionalTest {

    @TempDir
    Path projectDir;

    private void moduleJar(String name, String unique) throws IOException {
        Path jar = projectDir.resolve(name);
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            write(out, "pkg/Shared.class", sharedPayload());
            write(out, "pkg/Unique.class", unique.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * @implNote 8 KB of fixed-seed random bytes, not a short literal. A stored blob's zip entry
     *     name is "nylium/objects/" plus a 64 character sha256 hex digest, 79 characters, and zip
     *     records that name twice, once in a 30 byte local file header and again in a 46 byte
     *     central directory record, roughly 234 bytes of fixed overhead per distinct blob. A short
     *     or compressible payload lets that overhead outweigh the one copy dedup avoids, and the
     *     deduped jar comes out larger, not smaller. The fixed seed keeps the test deterministic;
     *     deflate cannot shrink random bytes, so the payload's declared size is the size that
     *     actually lands in the jar.
     */
    private static byte[] sharedPayload() {
        byte[] payload = new byte[8192];
        new Random(20260906L).nextBytes(payload);
        return payload;
    }

    private static void write(ZipOutputStream out, String entry, byte[] content) throws IOException {
        out.putNextEntry(new ZipEntry(entry));
        out.write(content);
        out.closeEntry();
    }

    private void fixture(String dedupeLine) throws IOException {
        moduleJar("modules/a.jar", "alpha");
        moduleJar("modules/b.jar", "beta");
        Files.write(projectDir.resolve("settings.gradle"),
                "rootProject.name = 'fixture'\n".getBytes(StandardCharsets.UTF_8));
        String script = "plugins { id 'base'; id 'io.github.intisy.nylium' }\n"
                + "repositories { maven { url = '"
                + System.getProperty("nylium.test.repo").replace('\\', '/') + "' }; mavenCentral() }\n"
                + "nylium {\n"
                + dedupeLine
                + "    mod { id = 'demo'; version = '1.0.0'; modulePrefix = 'demo' }\n"
                + "    module('1.21.11') {\n"
                + "        jar = file('modules/a.jar')\n"
                + "        platforms = ['FABRIC']\n"
                + "        minecraft = '1.21.11'\n"
                + "    }\n"
                + "    module('1.21.10') {\n"
                + "        jar = file('modules/b.jar')\n"
                + "        platforms = ['FABRIC']\n"
                + "        minecraft = '1.21.10'\n"
                + "    }\n"
                + "}\n";
        Files.write(projectDir.resolve("build.gradle"), script.getBytes(StandardCharsets.UTF_8));
    }

    private BuildResult build() {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("nyliumUniversalJar", "--stacktrace")
                .build();
    }

    private Path producedJar() {
        return projectDir.resolve("build/distributions/fixture-universal.jar");
    }

    @Test
    void dedupesByDefaultWhenTwoModulesAreDeclared() throws Exception {
        fixture("");
        build();

        try (JarFile jar = new JarFile(producedJar().toFile())) {
            assertNotNull(jar.getJarEntry("modules/demo-1.21.11.index"), "index missing");
            assertNotNull(jar.getJarEntry("modules/demo-1.21.10.index"), "index missing");
            assertNull(jar.getJarEntry("modules/demo-1.21.11.jar"), "module jar still shipped");
            assertEquals(3, countObjects(jar),
                    "expected one shared blob plus two unique ones");
            Properties manifest = manifestOf(jar);
            assertEquals("modules/demo-1.21.11.index", manifest.getProperty("module.0.path"));
        }
    }

    @Test
    void producesTheUndedupedShapeWhenDedupeIsOff() throws Exception {
        fixture("    dedupe = false\n");
        build();

        try (JarFile jar = new JarFile(producedJar().toFile())) {
            assertNotNull(jar.getJarEntry("modules/demo-1.21.11.jar"), "module jar missing");
            assertEquals(0, countObjects(jar), "objects shipped with dedupe off");
            Properties manifest = manifestOf(jar);
            assertEquals("modules/demo-1.21.11.jar", manifest.getProperty("module.0.path"));
        }
    }

    @Test
    void leavesASingleModuleUndedupedByDefault() throws Exception {
        moduleJar("modules/a.jar", "alpha");
        Files.write(projectDir.resolve("settings.gradle"),
                "rootProject.name = 'fixture'\n".getBytes(StandardCharsets.UTF_8));
        String single = "plugins { id 'base'; id 'io.github.intisy.nylium' }\n"
                + "repositories { maven { url = '"
                + System.getProperty("nylium.test.repo").replace('\\', '/') + "' }; mavenCentral() }\n"
                + "nylium {\n"
                + "    mod { id = 'demo'; version = '1.0.0'; modulePrefix = 'demo' }\n"
                + "    module('1.21.11') {\n"
                + "        jar = file('modules/a.jar')\n"
                + "        platforms = ['FABRIC']\n"
                + "        minecraft = '1.21.11'\n"
                + "    }\n"
                + "}\n";
        Files.write(projectDir.resolve("build.gradle"), single.getBytes(StandardCharsets.UTF_8));

        build();

        try (JarFile jar = new JarFile(producedJar().toFile())) {
            assertNotNull(jar.getJarEntry("modules/demo-1.21.11.jar"), "module jar missing");
            assertNull(jar.getJarEntry("modules/demo-1.21.11.index"), "a lone module was deduped");
            assertEquals(0, countObjects(jar), "a lone module produced objects");
        }
    }

    @Test
    void theDedupedJarIsSmallerThanTheUndedupedOne() throws Exception {
        fixture("    dedupe = false\n");
        build();
        long undeduped = Files.size(producedJar());

        fixture("");
        build();
        long deduped = Files.size(producedJar());

        assertTrue(deduped < undeduped,
                "deduped " + deduped + " was not smaller than undeduped " + undeduped);
    }

    private static int countObjects(JarFile jar) {
        int count = 0;
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().startsWith("nylium/objects/")) {
                count++;
            }
        }
        return count;
    }

    private static Properties manifestOf(JarFile jar) throws IOException {
        Properties properties = new Properties();
        try (java.io.Reader reader = new java.io.InputStreamReader(
                jar.getInputStream(jar.getJarEntry("nylium-modules.properties")),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
