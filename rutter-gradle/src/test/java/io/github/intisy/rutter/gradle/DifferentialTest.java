package io.github.intisy.rutter.gradle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @implNote Compares the plugin's output against {@code rutter-testmod}'s hand-rolled universal
 *     jar, the one already proven booting on five real Minecraft servers, so a green run here is
 *     the acceptance gate for the whole SP-2 plugin rather than an ordinary unit test.
 */
class DifferentialTest {

    private static final String[] MODULE_IDS =
            {"1.21.11", "1.21.10", "1.7.10", "1.16.5", "1.21.11-ml9"};

    private static final String TRANSFORMATION_SERVICE_FILE =
            "META-INF/services/cpw.mods.modlauncher.api.ITransformationService";

    private static final String LAUNCH_PLUGIN_SERVICE_FILE =
            "META-INF/services/cpw.mods.modlauncher.serviceapi.ILaunchPluginService";

    @TempDir
    static Path projectDir;

    private static Path referenceJar;
    private static Path producedJar;

    @BeforeAll
    static void buildTheUniversalJar() throws IOException {
        writeFixture(projectDir);
        runner("rutterUniversalJar", "--offline").build();
        producedJar = onlyFileIn(projectDir.resolve("build/distributions"));
        referenceJar = Paths.get(System.getProperty("rutter.test.reference"));
    }

    @Test
    void entrySetsAreEqual() throws IOException {
        Set<String> expected = entries(referenceJar);
        Set<String> actual = entries(producedJar);
        Set<String> onlyInReference = new TreeSet<String>(expected);
        onlyInReference.removeAll(actual);
        Set<String> onlyInProduced = new TreeSet<String>(actual);
        onlyInProduced.removeAll(expected);
        assertTrue(onlyInReference.isEmpty() && onlyInProduced.isEmpty(), "Entry sets differ. Only"
                + " in reference: " + onlyInReference + ". Only in produced jar: " + onlyInProduced
                + ".");
    }

    @Test
    void everyModuleJarIsByteIdentical() throws IOException {
        for (String id : MODULE_IDS) {
            String path = "modules/testmod-" + id + ".jar";
            assertArrayEquals(readEntry(referenceJar, path), readEntry(producedJar, path),
                    "Module jar '" + path + "' is not byte identical");
        }
    }

    @Test
    void rutterModulesPropertiesIsEqual() throws IOException {
        assertEquals(normalizeLineEndings(readEntryText(referenceJar, "rutter-modules.properties")),
                normalizeLineEndings(readEntryText(producedJar, "rutter-modules.properties")));
    }

    @Test
    void fabricModJsonIsSemanticallyEqual() throws IOException {
        JsonObject expected =
                JsonParser.parseString(readEntryText(referenceJar, "fabric.mod.json")).getAsJsonObject();
        JsonObject actual =
                JsonParser.parseString(readEntryText(producedJar, "fabric.mod.json")).getAsJsonObject();
        assertEquals(expected, actual);
    }

    @Test
    void transformationServiceFileIsEqual() throws IOException {
        assertEquals(normalizeLineEndings(readEntryText(referenceJar, TRANSFORMATION_SERVICE_FILE)),
                normalizeLineEndings(readEntryText(producedJar, TRANSFORMATION_SERVICE_FILE)));
    }

    @Test
    void launchPluginServiceFileIsEqual() throws IOException {
        assertEquals(normalizeLineEndings(readEntryText(referenceJar, LAUNCH_PLUGIN_SERVICE_FILE)),
                normalizeLineEndings(readEntryText(producedJar, LAUNCH_PLUGIN_SERVICE_FILE)));
    }

    /**
     * @implNote The existing configuration cache test only ever resolved a test-only
     *     {@code flatDir} repository; this reuses the same fixture that resolves the six embedded
     *     artifacts through {@code rutterEmbed} against a real published maven repository, which
     *     is the one path the earlier test could not cover.
     */
    @Test
    void configurationCacheHoldsForRealEmbedResolution() {
        BuildResult first = runner("rutterUniversalJar", "--configuration-cache", "--offline").build();
        assertTrue(first.getOutput().contains("Configuration cache entry stored"), first.getOutput());

        BuildResult second = runner("rutterUniversalJar", "--configuration-cache", "--offline").build();
        assertTrue(second.getOutput().contains("Configuration cache entry reused"), second.getOutput());
    }

    private static GradleRunner runner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }

    private static void writeFixture(Path dir) throws IOException {
        Path modules = Paths.get(System.getProperty("rutter.test.modules"));
        Path repo = Paths.get(System.getProperty("rutter.test.repo"));
        write(dir, "settings.gradle", "rootProject.name = 'differential'\n");
        write(dir, "build.gradle", buildScript(modules, repo));
    }

    private static String buildScript(Path modules, Path repo) {
        return ""
                + "plugins { id 'base'; id 'io.github.intisy.rutter' }\n"
                + "repositories { maven { url = file('" + slashes(repo) + "') } }\n"
                + "rutter {\n"
                + "    mod {\n"
                + "        id = 'rutter_testmod'\n"
                + "        name = 'Rutter Test Mod'\n"
                + "        version = '0.1.0'\n"
                + "        environment = '*'\n"
                + "        fabricLoaderVersion = '>=0.14.0'\n"
                + "        modulePrefix = 'testmod'\n"
                + "    }\n"
                + module(modules, "1.21.11", "FABRIC", "1.21.11", null)
                + module(modules, "1.21.10", "FABRIC", "1.21.10", null)
                + module(modules, "1.7.10", "LAUNCHWRAPPER", "[1.7,1.12.2]", null)
                + module(modules, "1.16.5", "MODLAUNCHER_8", "[1.13,1.16.5]", null)
                + module(modules, "1.21.11-ml9", "MODLAUNCHER_9", "1.21.11",
                        "mixins.rutter-testmod-ml9.json")
                + "}\n";
    }

    private static String module(Path modules, String id, String platform, String minecraft,
                                 String mixin) {
        String jarPath = slashes(modules.resolve("testmod-" + id + ".jar"));
        StringBuilder out = new StringBuilder();
        out.append("    module('").append(id).append("') {\n");
        out.append("        jar = file('").append(jarPath).append("')\n");
        out.append("        platforms = ['").append(platform).append("']\n");
        out.append("        minecraft = '").append(minecraft).append("'\n");
        if (mixin != null) {
            out.append("        mixins = ['").append(mixin).append("']\n");
        }
        out.append("        entrypoint = 'io.github.intisy.rutter.testmod.TestModEntry'\n");
        out.append("    }\n");
        return out.toString();
    }

    private static String slashes(Path path) {
        return path.toAbsolutePath().toString().replace('\\', '/');
    }

    private static void write(Path dir, String relativePath, String content) throws IOException {
        Path target = dir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Path onlyFileIn(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> all = files.collect(Collectors.toList());
            assertEquals(1, all.size(), "Expected exactly one archive in " + dir + " but found " + all);
            return all.get(0);
        }
    }

    private static Set<String> entries(Path jar) throws IOException {
        Set<String> names = new TreeSet<String>();
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> enumeration = file.entries();
            while (enumeration.hasMoreElements()) {
                names.add(enumeration.nextElement().getName());
            }
        }
        return names;
    }

    private static byte[] readEntry(Path jar, String entryName) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(entryName);
            assertNotNull(entry, entryName + " missing from " + jar);
            try (InputStream in = file.getInputStream(entry)) {
                return readAll(in);
            }
        }
    }

    private static String readEntryText(Path jar, String entryName) throws IOException {
        return new String(readEntry(jar, entryName), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String normalizeLineEndings(String text) {
        String unified = text.replace("\r\n", "\n").replace("\r", "\n");
        return unified.endsWith("\n") ? unified.substring(0, unified.length() - 1) : unified;
    }
}
