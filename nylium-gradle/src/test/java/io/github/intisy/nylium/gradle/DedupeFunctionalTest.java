package io.github.intisy.nylium.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
     * @implNote A 79 character blob entry name costs roughly 234 bytes of fixed zip metadata, so
     *     the payload has to be large enough for the one copy dedup avoids to beat that overhead,
     *     and it has to resist deflate or compression erases the savings the assertion measures.
     *     The fixed seed keeps the test deterministic.
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

    @Test
    void dedupesASingleModuleWhenForced() throws Exception {
        moduleJar("modules/a.jar", "alpha");
        Files.write(projectDir.resolve("settings.gradle"),
                "rootProject.name = 'fixture'\n".getBytes(StandardCharsets.UTF_8));
        String single = "plugins { id 'base'; id 'io.github.intisy.nylium' }\n"
                + "repositories { maven { url = '"
                + System.getProperty("nylium.test.repo").replace('\\', '/') + "' }; mavenCentral() }\n"
                + "nylium {\n"
                + "    dedupe = true\n"
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
            assertNotNull(jar.getJarEntry("modules/demo-1.21.11.index"), "index missing");
            assertNull(jar.getJarEntry("modules/demo-1.21.11.jar"), "module jar still shipped");
            assertEquals(2, countObjects(jar),
                    "expected the shared entry plus the one unique entry");
            Properties manifest = manifestOf(jar);
            assertEquals("modules/demo-1.21.11.index", manifest.getProperty("module.0.path"));
        }
    }

    private BuildResult buildWith(String... extraArguments) {
        java.util.List<String> arguments = new java.util.ArrayList<String>(
                Arrays.asList("nyliumUniversalJar", "--stacktrace"));
        arguments.addAll(Arrays.asList(extraArguments));
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .build();
    }

    @Test
    void isUpToDateOnASecondBuildWithNoChanges() throws Exception {
        fixture("");
        build();

        BuildResult second = build();

        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":nyliumDedupe").getOutcome());
    }

    @Test
    void rerunsWhenAModuleJarChanges() throws Exception {
        fixture("");
        build();

        moduleJar("modules/b.jar", "beta changed");
        BuildResult second = build();

        assertEquals(TaskOutcome.SUCCESS, second.task(":nyliumDedupe").getOutcome());
    }

    /**
     * @implNote A dropped module's object would otherwise stay on disk and be copied into the jar,
     *     naming content no index references. The task empties its output directory to prevent it.
     */
    @Test
    void dropsTheObjectsOfAModuleThatWasRemoved() throws Exception {
        fixture("");
        build();

        String single = "plugins { id 'base'; id 'io.github.intisy.nylium' }\n"
                + "repositories { maven { url = '"
                + System.getProperty("nylium.test.repo").replace('\\', '/') + "' }; mavenCentral() }\n"
                + "nylium {\n"
                + "    dedupe = true\n"
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
            assertEquals(2, countObjects(jar), "the removed module's unique object still shipped");
            assertNull(jar.getJarEntry("modules/demo-1.21.10.index"), "stale index still shipped");
        }
    }

    @Test
    void worksUnderTheConfigurationCache() throws Exception {
        fixture("");
        buildWith("--configuration-cache");

        BuildResult second = buildWith("--configuration-cache");

        assertTrue(second.getOutput().contains("Reusing configuration cache"), second.getOutput());
    }
}
