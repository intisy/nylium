package io.github.intisy.nylium.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @implNote Staleness is the defect class this plugin exists to fix, so both directions get a
 *     permanent test: a module whose bytes changed has to rebuild the jar, and a platform dropped
 *     between builds must not leave its generated service file behind.
 */
class UniversalJarStalenessFunctionalTest {

    private static final String LAUNCH_PLUGIN_SERVICE_FILE =
            "META-INF/services/cpw.mods.modlauncher.serviceapi.ILaunchPluginService";

    private static final String TRANSFORMATION_SERVICE_FILE =
            "META-INF/services/cpw.mods.modlauncher.api.ITransformationService";

    @TempDir
    Path projectDir;

    @Test
    void isUpToDateOnASecondRunAndRebuildsWhenAModuleJarChanges() throws IOException {
        fixture("FABRIC");
        assertEquals(TaskOutcome.SUCCESS, run().task(":nyliumUniversalJar").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, run().task(":nyliumUniversalJar").getOutcome());

        moduleJarContaining("module.jar", "demo/Added.class");
        BuildResult third = run();
        assertEquals(TaskOutcome.SUCCESS, third.task(":nyliumVerifyModules").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, third.task(":nyliumUniversalJar").getOutcome());
        assertTrue(jarEntries().contains("modules/demo-fabric.jar"));
    }

    @Test
    void droppingAPlatformDropsItsGeneratedServiceFile() throws IOException {
        fixture("FABRIC", "MODLAUNCHER_9");
        run();
        List<String> withModLauncher = jarEntries();
        assertTrue(withModLauncher.contains(LAUNCH_PLUGIN_SERVICE_FILE), withModLauncher.toString());
        assertTrue(withModLauncher.contains(TRANSFORMATION_SERVICE_FILE), withModLauncher.toString());

        fixture("FABRIC");
        run();
        List<String> fabricOnly = jarEntries();
        assertFalse(fabricOnly.contains(LAUNCH_PLUGIN_SERVICE_FILE), fabricOnly.toString());
        assertFalse(fabricOnly.contains(TRANSFORMATION_SERVICE_FILE), fabricOnly.toString());
        assertTrue(fabricOnly.contains("fabric.mod.json"), fabricOnly.toString());
    }

    private BuildResult run() {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("nyliumUniversalJar", "--offline")
                .build();
    }

    private void fixture(String... platforms) throws IOException {
        String version = pluginVersion();
        write("settings.gradle", "rootProject.name = 'fixture'\n");
        moduleJarContaining("module.jar");
        moduleJarContaining("libs/nylium-api-" + version + ".jar");
        moduleJarContaining("libs/nylium-core-" + version + ".jar");
        moduleJarContaining("libs/nylium-bootstrap-fabric-" + version + ".jar");
        moduleJarContaining("libs/nylium-bootstrap-modlauncher9-" + version + ".jar");
        StringBuilder modules = new StringBuilder();
        for (String platform : platforms) {
            modules.append("    module('").append(platform.toLowerCase()).append("') {\n")
                    .append("        jar = layout.projectDirectory.file('module.jar')\n")
                    .append("        platforms = ['").append(platform).append("']\n")
                    .append("        minecraft = '1.21.11'\n")
                    .append("    }\n");
        }
        write("build.gradle", ""
                + "plugins { id 'io.github.intisy.nylium' }\n"
                + "repositories { flatDir { dirs 'libs' } }\n"
                + "nylium {\n"
                + "    mod { id = 'demo'; version = '1.0.0' }\n"
                + modules
                + "}\n");
    }

    private void write(String relativePath, String content) throws IOException {
        Path target = projectDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    private void moduleJarContaining(String relativePath, String... entries) throws IOException {
        Path target = projectDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write("x".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    private List<String> jarEntries() throws IOException {
        List<String> names = new ArrayList<String>();
        try (JarFile file = new JarFile(
                projectDir.resolve("build/distributions/fixture-universal.jar").toFile())) {
            Enumeration<JarEntry> enumeration = file.entries();
            while (enumeration.hasMoreElements()) {
                names.add(enumeration.nextElement().getName());
            }
        }
        return names;
    }

    private String pluginVersion() throws IOException {
        InputStream stream = NyliumPlugin.class.getResourceAsStream(
                "/nylium-gradle-version.properties");
        Properties properties = new Properties();
        properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        stream.close();
        return properties.getProperty("version").trim();
    }
}
