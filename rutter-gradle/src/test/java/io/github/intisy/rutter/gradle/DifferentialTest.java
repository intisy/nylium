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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * @implNote Compares the plugin's output against {@code rutter-testmod}'s hand-rolled universal
 *     jar, the one already proven booting on five real Minecraft servers, so a green run here is
 *     the acceptance gate for the whole SP-2 plugin rather than an ordinary unit test.
 */
class DifferentialTest {

    private static final String[] MODULE_IDS =
            {"1.21.11", "1.21.10", "1.7.10", "1.16.5", "1.21.11-ml9"};

    private static final String MANIFEST_ENTRY = "META-INF/MANIFEST.MF";

    private static final String TRANSFORMATION_SERVICE_FILE =
            "META-INF/services/cpw.mods.modlauncher.api.ITransformationService";

    private static final String LAUNCH_PLUGIN_SERVICE_FILE =
            "META-INF/services/cpw.mods.modlauncher.serviceapi.ILaunchPluginService";

    private static final Set<String> ENTRIES_COVERED_BY_OTHER_ASSERTIONS = new HashSet<String>(
            Arrays.asList(MANIFEST_ENTRY, "rutter-modules.properties", "fabric.mod.json",
                    TRANSFORMATION_SERVICE_FILE, LAUNCH_PLUGIN_SERVICE_FILE));

    /**
     * @implNote Entries observed to legitimately differ in content between the reference and the
     *     plugin's output, each with the reason recorded where the entry is added; empty unless a
     *     real difference is ever found. Never add an entry here to make a genuine mismatch
     *     disappear.
     */
    private static final Set<String> ALLOWLISTED_CONTENT_DIFFERENCES = Collections.emptySet();

    @TempDir
    static Path projectDir;

    @TempDir
    Path configCacheProjectDir;

    /**
     * @implNote {@code producedJar} lives under this class's {@code @TempDir}, gone once the test
     *     finishes, so a human wanting to point the smoke harness's {@code -PrutterSmokeJar} at a
     *     plugin-built jar needs a copy somewhere durable. This path is that copy; it is never read
     *     by an assertion in this class.
     */
    private static final Path PERSISTED_JAR =
            Paths.get("build/universal/plugin-built-universal.jar").toAbsolutePath();

    private static Path referenceJar;
    private static Path producedJar;

    @BeforeAll
    static void buildTheUniversalJar() throws IOException {
        writeFixture(projectDir);
        runner(projectDir, "rutterUniversalJar", "--offline").build();
        producedJar = onlyFileIn(projectDir.resolve("build/distributions"));
        referenceJar = Paths.get(System.getProperty("rutter.test.reference"));
        persistProducedJarForManualVerification();
    }

    /**
     * @implNote A copy failure here must never fail the differential gate: it serves manual
     *     verification only, not any assertion in this class, so any exception is swallowed.
     */
    private static void persistProducedJarForManualVerification() {
        try {
            Files.createDirectories(PERSISTED_JAR.getParent());
            Files.copy(producedJar, PERSISTED_JAR, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    @Test
    void entrySetsAreEqual() throws IOException {
        List<String> expected = sortedEntries(referenceJar);
        List<String> actual = sortedEntries(producedJar);
        if (!expected.equals(actual)) {
            Set<String> onlyInReference = new TreeSet<String>(expected);
            onlyInReference.removeAll(actual);
            Set<String> onlyInProduced = new TreeSet<String>(actual);
            onlyInProduced.removeAll(expected);
            fail("Entry lists differ. Only in reference: " + onlyInReference + ". Only in produced"
                    + " jar: " + onlyInProduced + ".");
        }
    }

    @Test
    void everyModuleJarIsByteIdentical() throws IOException {
        for (String id : MODULE_IDS) {
            String path = "modules/testmod-" + id + ".jar";
            assertArrayEquals(readEntry(referenceJar, path), readEntry(producedJar, path),
                    "Module jar '" + path + "' is not byte identical");
        }
    }

    /**
     * @implNote {@code entrySetsAreEqual} only compares entry names; this compares the bytes of
     *     every entry not already content-checked by a more specific assertion (the manifest, the
     *     module jars, the rendered metadata), which is most of the jar: every class file unpacked
     *     from the six embedded artifacts.
     */
    @Test
    void everyRemainingEntryIsByteIdentical() throws IOException {
        Set<String> moduleJarPaths = new HashSet<String>();
        for (String id : MODULE_IDS) {
            moduleJarPaths.add("modules/testmod-" + id + ".jar");
        }
        for (String name : sortedEntries(referenceJar)) {
            if (name.endsWith("/") || ENTRIES_COVERED_BY_OTHER_ASSERTIONS.contains(name)
                    || moduleJarPaths.contains(name)
                    || ALLOWLISTED_CONTENT_DIFFERENCES.contains(name)) {
                continue;
            }
            assertArrayEquals(readEntry(referenceJar, name), readEntry(producedJar, name),
                    "Entry '" + name + "' is not byte identical");
        }
    }

    @Test
    void launchWrapperTweakClassManifestAttributeIsEqual() throws IOException {
        assertEquals(manifestAttribute(referenceJar, "TweakClass"),
                manifestAttribute(producedJar, "TweakClass"));
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
     *     is the one path the earlier test could not cover. Runs in its own project directory,
     *     separate from the one the other assertions read {@code producedJar} from, so this test
     *     never shares mutable fixture state with them.
     */
    @Test
    void configurationCacheHoldsForRealEmbedResolution() throws IOException {
        writeFixture(configCacheProjectDir);
        BuildResult first = runner(configCacheProjectDir, "rutterUniversalJar",
                "--configuration-cache", "--offline").build();
        assertTrue(first.getOutput().contains("Configuration cache entry stored"), first.getOutput());

        BuildResult second = runner(configCacheProjectDir, "rutterUniversalJar",
                "--configuration-cache", "--offline").build();
        assertTrue(second.getOutput().contains("Configuration cache entry reused"), second.getOutput());
    }

    private static GradleRunner runner(Path dir, String... arguments) {
        return GradleRunner.create()
                .withProjectDir(dir.toFile())
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

    private static List<String> sortedEntries(Path jar) throws IOException {
        List<String> names = new ArrayList<String>();
        try (JarFile file = new JarFile(jar.toFile())) {
            Enumeration<JarEntry> enumeration = file.entries();
            while (enumeration.hasMoreElements()) {
                names.add(enumeration.nextElement().getName());
            }
        }
        Collections.sort(names);
        return names;
    }

    private static String manifestAttribute(Path jar, String attribute) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            return manifest == null ? null : manifest.getMainAttributes().getValue(attribute);
        }
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
