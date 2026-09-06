package io.github.intisy.rutter.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpToDateFunctionalTest {

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

    private void fixture(String minecraft, String modVersion) throws IOException {
        write("settings.gradle", "rootProject.name = 'fixture'\n");
        emptyJar("module.jar");
        write("build.gradle", ""
                + "plugins { id 'io.github.intisy.rutter' }\n"
                + "rutter {\n"
                + "    mod { id = 'demo'; name = 'Demo'; version = '" + modVersion + "' }\n"
                + "    module('" + minecraft + "') {\n"
                + "        jar = layout.projectDirectory.file('module.jar')\n"
                + "        platforms = ['FABRIC']\n"
                + "        minecraft = '" + minecraft + "'\n"
                + "    }\n"
                + "}\n");
    }

    private void unconfiguredFixture() throws IOException {
        write("settings.gradle", "rootProject.name = 'fixture'\n");
        write("build.gradle", "plugins { id 'io.github.intisy.rutter' }\n");
    }

    private GradleRunner runner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments);
    }

    private BuildResult run() {
        return runner("rutterMetadata").build();
    }

    @Test
    void isUpToDateOnASecondRunAndRebuildsAfterAChange() throws IOException {
        fixture("1.21.11", "1.0.0");
        BuildResult first = run();
        assertEquals(TaskOutcome.SUCCESS, first.task(":rutterManifest").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, first.task(":rutterFabricModJson").getOutcome());

        BuildResult second = run();
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":rutterManifest").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":rutterFabricModJson").getOutcome());

        File manifest = projectDir.resolve("build/rutter/rutter-modules.properties").toFile();
        assertTrue(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8)
                .contains("1.21.11"));
        File fabricModJson = projectDir.resolve("build/rutter/fabric.mod.json").toFile();
        assertTrue(new String(Files.readAllBytes(fabricModJson.toPath()), StandardCharsets.UTF_8)
                .contains("1.0.0"));

        fixture("1.21.10", "2.0.0");
        BuildResult third = run();
        assertEquals(TaskOutcome.SUCCESS, third.task(":rutterManifest").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, third.task(":rutterFabricModJson").getOutcome());
        assertTrue(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8)
                .contains("1.21.10"));
        assertTrue(new String(Files.readAllBytes(fabricModJson.toPath()), StandardCharsets.UTF_8)
                .contains("2.0.0"));
    }

    @Test
    void emitsNoModLauncherServiceFileForAFabricOnlyMod() throws IOException {
        fixture("1.21.11", "1.0.0");
        run();
        assertTrue(Files.exists(projectDir.resolve("build/rutter/fabric.mod.json")));
        assertTrue(!Files.exists(projectDir.resolve(
                "build/rutter/META-INF/services/cpw.mods.modlauncher.api.ITransformationService")));
        assertTrue(!Files.exists(projectDir.resolve(
                "build/rutter/META-INF/services/cpw.mods.modlauncher.serviceapi.ILaunchPluginService")));
    }

    @Test
    void worksWithoutARutterBlockForPlainIntrospection() throws IOException {
        unconfiguredFixture();
        BuildResult result = runner("tasks").build();
        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"));
    }

    @Test
    void failsInvokingRutterMetadataWithoutModules() throws IOException {
        unconfiguredFixture();
        BuildResult result = runner("rutterMetadata").buildAndFail();
        assertTrue(result.getOutput().contains("declares no modules"));
    }
}
