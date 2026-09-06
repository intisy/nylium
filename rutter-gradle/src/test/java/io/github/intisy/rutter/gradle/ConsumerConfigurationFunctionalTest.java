package io.github.intisy.rutter.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @implNote {@code rutterUniversalJar} used to be registered from {@code afterEvaluate}, which
 *     runs after the consumer's script body, so reaching it with {@code tasks.named} threw
 *     {@code UnknownTaskException}. This is the test that the surface a consumer actually uses,
 *     renaming the archive, adding a file to the jar root, and getting it from {@code assemble},
 *     keeps working.
 */
class ConsumerConfigurationFunctionalTest {

    @TempDir
    Path projectDir;

    @Test
    void takesConfigurationFromTheScriptBodyAndBuildsFromAssemble() throws IOException {
        String version = pluginVersion();
        write("settings.gradle", "rootProject.name = 'fixture'\n");
        write("LICENSE", "a license\n");
        emptyJar("module.jar");
        emptyJar("libs/rutter-api-" + version + ".jar");
        emptyJar("libs/rutter-core-" + version + ".jar");
        emptyJar("libs/rutter-bootstrap-fabric-" + version + ".jar");
        write("build.gradle", ""
                + "plugins { id 'io.github.intisy.rutter' }\n"
                + "repositories { flatDir { dirs 'libs' } }\n"
                + "tasks.named('rutterUniversalJar') {\n"
                + "    archiveBaseName = 'renamed'\n"
                + "    from('LICENSE')\n"
                + "}\n"
                + "rutter {\n"
                + "    mod { id = 'demo'; version = '1.0.0' }\n"
                + "    module('1.21.11') {\n"
                + "        jar = layout.projectDirectory.file('module.jar')\n"
                + "        platforms = ['FABRIC']\n"
                + "        minecraft = '1.21.11'\n"
                + "    }\n"
                + "}\n");

        GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("assemble", "--offline")
                .build();

        Path jar = projectDir.resolve("build/distributions/renamed-universal.jar");
        assertTrue(Files.exists(jar), "assemble did not produce " + jar);
        try (JarFile file = new JarFile(jar.toFile())) {
            assertNotNull(file.getJarEntry("LICENSE"));
            assertNotNull(file.getJarEntry("rutter-modules.properties"));
            assertNotNull(file.getJarEntry("modules/demo-1.21.11.jar"));
        }
    }

    private void write(String relativePath, String content) throws IOException {
        Path target = projectDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    private void emptyJar(String relativePath) throws IOException {
        Path target = projectDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private String pluginVersion() throws IOException {
        InputStream stream = RutterPlugin.class.getResourceAsStream(
                "/rutter-gradle-version.properties");
        Properties properties = new Properties();
        properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        stream.close();
        return properties.getProperty("version").trim();
    }
}
