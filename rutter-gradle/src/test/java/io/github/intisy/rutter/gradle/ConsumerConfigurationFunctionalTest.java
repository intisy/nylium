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
 * @implNote {@code rutterUniversalJar} used to be registered from {@code afterEvaluate}, which runs
 *     after the consumer's script body, so reaching it with {@code tasks.named} threw
 *     {@code UnknownTaskException}. Registering it eagerly opened a second hazard: several ordinary
 *     idioms realize a task during script evaluation, so a jar configured from a snapshot of the
 *     declaration could be assembled empty, or refused, with a green build. Both halves are pinned
 *     here.
 */
class ConsumerConfigurationFunctionalTest {

    private static final String EMBEDDED_API_CLASS =
            "io/github/intisy/rutter/api/PlatformId.class";

    /**
     * @implNote {@code getByName} realizes on the spot. {@code withType(Jar) { }} measures lazy on
     *     the Gradle this repository tests against, but it is kept alongside because it is the
     *     common idiom in Minecraft build scripts and nothing promises it stays lazy.
     */
    private static final String EAGER_REALIZATION =
            "tasks.getByName('rutterUniversalJar')\ntasks.withType(Jar) { }\n";

    @TempDir
    Path projectDir;

    @Test
    void takesConfigurationFromTheScriptBodyAndBuildsFromAssemble() throws IOException {
        fixture("tasks.named('rutterUniversalJar') {\n"
                + "    archiveBaseName = 'renamed'\n"
                + "    from('LICENSE')\n"
                + "}\n", "");
        assemble();

        Path jar = projectDir.resolve("build/distributions/renamed-universal.jar");
        assertTrue(Files.exists(jar), "assemble did not produce " + jar);
        try (JarFile file = new JarFile(jar.toFile())) {
            assertNotNull(file.getJarEntry("LICENSE"));
            assertCompleteJar(file);
        }
    }

    @Test
    void buildsACompleteJarWhenTheConsumerRealizesTheTaskEagerly() throws IOException {
        fixture("", EAGER_REALIZATION);
        assemble();
        try (JarFile file = new JarFile(producedJar().toFile())) {
            assertCompleteJar(file);
        }
    }

    @Test
    void buildsACompleteJarWhenTheConsumerRealizesTheTaskBeforeDeclaringAnything()
            throws IOException {
        fixture(EAGER_REALIZATION, "");
        assemble();
        try (JarFile file = new JarFile(producedJar().toFile())) {
            assertCompleteJar(file);
        }
    }

    private void assertCompleteJar(JarFile jar) {
        assertNotNull(jar.getJarEntry("rutter-modules.properties"), "manifest missing");
        assertNotNull(jar.getJarEntry("fabric.mod.json"), "fabric.mod.json missing");
        assertNotNull(jar.getJarEntry(EMBEDDED_API_CLASS), "embedded rutter class missing");
        assertNotNull(jar.getJarEntry("modules/demo-1.21.11.jar"), "module jar missing");
    }

    private Path producedJar() {
        return projectDir.resolve("build/distributions/fixture-universal.jar");
    }

    private void assemble() {
        GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("assemble", "--offline")
                .build();
    }

    private void fixture(String beforeDeclaration, String afterDeclaration) throws IOException {
        String version = pluginVersion();
        write("settings.gradle", "rootProject.name = 'fixture'\n");
        write("LICENSE", "a license\n");
        jarContaining("module.jar");
        jarContaining("libs/rutter-api-" + version + ".jar", EMBEDDED_API_CLASS);
        jarContaining("libs/rutter-core-" + version + ".jar");
        jarContaining("libs/rutter-bootstrap-fabric-" + version + ".jar");
        write("build.gradle", ""
                + "plugins { id 'io.github.intisy.rutter' }\n"
                + "repositories { flatDir { dirs 'libs' } }\n"
                + beforeDeclaration
                + "rutter {\n"
                + "    mod { id = 'demo'; version = '1.0.0' }\n"
                + "    module('1.21.11') {\n"
                + "        jar = layout.projectDirectory.file('module.jar')\n"
                + "        platforms = ['FABRIC']\n"
                + "        minecraft = '1.21.11'\n"
                + "    }\n"
                + "}\n"
                + afterDeclaration);
    }

    private void write(String relativePath, String content) throws IOException {
        Path target = projectDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    private void jarContaining(String relativePath, String... entries) throws IOException {
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

    private String pluginVersion() throws IOException {
        InputStream stream = RutterPlugin.class.getResourceAsStream(
                "/rutter-gradle-version.properties");
        Properties properties = new Properties();
        properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        stream.close();
        return properties.getProperty("version").trim();
    }
}
