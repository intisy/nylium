package io.github.intisy.nylium.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UniversalJarConfigurationCacheFunctionalTest {

    @TempDir
    Path projectDir;

    private void write(String relativePath, String content) throws IOException {
        Path target = projectDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    private void emptyJar(String relativePath) throws IOException {
        Path target = projectDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target));
        zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
        zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.close();
    }

    private String pluginVersion() throws IOException {
        InputStream stream = NyliumPlugin.class.getResourceAsStream(
                "/nylium-gradle-version.properties");
        Properties properties = new Properties();
        properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        stream.close();
        return properties.getProperty("version").trim();
    }

    private void fixture() throws IOException {
        String version = pluginVersion();
        write("settings.gradle", "rootProject.name = 'fixture'\n");
        emptyJar("module.jar");
        emptyJar("libs/nylium-api-" + version + ".jar");
        emptyJar("libs/nylium-core-" + version + ".jar");
        emptyJar("libs/nylium-bootstrap-fabric-" + version + ".jar");
        write("build.gradle", ""
                + "plugins { id 'base'; id 'io.github.intisy.nylium' }\n"
                + "repositories { flatDir { dirs 'libs' } }\n"
                + "nylium {\n"
                + "    mod { id = 'demo'; name = 'Demo'; version = '1.0.0' }\n"
                + "    module('1.21.11') {\n"
                + "        jar = layout.projectDirectory.file('module.jar')\n"
                + "        platforms = ['FABRIC']\n"
                + "        minecraft = '1.21.11'\n"
                + "    }\n"
                + "}\n");
    }

    private BuildResult run() {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("nyliumUniversalJar", "--configuration-cache", "--offline")
                .build();
    }

    @Test
    void buildsTheUniversalJarUnderTheConfigurationCache() throws IOException {
        fixture();
        BuildResult first = run();
        assertTrue(first.getOutput().contains("Configuration cache entry stored"),
                first.getOutput());

        BuildResult second = run();
        assertTrue(second.getOutput().contains("Configuration cache entry reused"),
                second.getOutput());
    }
}
