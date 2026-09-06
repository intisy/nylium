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

    private void fixture(String minecraft) throws IOException {
        write("settings.gradle", "rootProject.name = 'fixture'\n");
        write("module.jar", "not a real jar for this test\n");
        write("build.gradle", ""
                + "plugins { id 'io.github.intisy.rutter' }\n"
                + "rutter {\n"
                + "    mod { id = 'demo'; name = 'Demo'; version = '1.0.0' }\n"
                + "    module('" + minecraft + "') {\n"
                + "        jar = layout.projectDirectory.file('module.jar')\n"
                + "        platforms = ['FABRIC']\n"
                + "        minecraft = '" + minecraft + "'\n"
                + "    }\n"
                + "}\n");
    }

    private BuildResult run() {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("rutterMetadata")
                .build();
    }

    @Test
    void isUpToDateOnASecondRunAndRebuildsAfterAChange() throws IOException {
        fixture("1.21.11");
        assertEquals(TaskOutcome.SUCCESS, run().task(":rutterManifest").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, run().task(":rutterManifest").getOutcome());

        File manifest = projectDir.resolve("build/rutter/rutter-modules.properties").toFile();
        assertTrue(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8)
                .contains("1.21.11"));

        fixture("1.21.10");
        assertEquals(TaskOutcome.SUCCESS, run().task(":rutterManifest").getOutcome());
        assertTrue(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8)
                .contains("1.21.10"));
    }

    @Test
    void emitsNoModLauncherServiceFileForAFabricOnlyMod() throws IOException {
        fixture("1.21.11");
        run();
        assertTrue(Files.exists(projectDir.resolve("build/rutter/fabric.mod.json")));
        assertTrue(!Files.exists(projectDir.resolve(
                "build/rutter/META-INF/services/cpw.mods.modlauncher.api.ITransformationService")));
    }
}
