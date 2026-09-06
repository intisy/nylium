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

import static org.junit.jupiter.api.Assertions.assertEquals;

class VerifyModulesProducerTaskFunctionalTest {

    @TempDir
    Path projectDir;

    private void write(String relativePath, String content) throws IOException {
        Path target = projectDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @implNote The consumer this plugin is built for sets {@code module.jar} from a task output
     *     (a remap task), not a static file. This proves {@code nyliumVerifyModules} waits for that
     *     producer through the implicit task dependency carried by its {@code RegularFileProperty},
     *     rather than merely tolerating a fixture where the jar already exists on disk.
     */
    private void fixture() throws IOException {
        write("settings.gradle", "rootProject.name = 'fixture'\n");
        write("resource.txt", "hello\n");
        write("build.gradle", ""
                + "plugins { id 'io.github.intisy.nylium' }\n"
                + "def produceModuleJar = tasks.register('produceModuleJar', Jar) {\n"
                + "    from('resource.txt')\n"
                + "    archiveFileName.set('produced.jar')\n"
                + "    destinationDirectory.set(layout.buildDirectory.dir('produced'))\n"
                + "}\n"
                + "nylium {\n"
                + "    mod { id = 'demo'; name = 'Demo'; version = '1.0.0' }\n"
                + "    module('1.21.11') {\n"
                + "        jar = produceModuleJar.flatMap { it.archiveFile }\n"
                + "        platforms = ['FABRIC']\n"
                + "        minecraft = '1.21.11'\n"
                + "    }\n"
                + "}\n");
    }

    @Test
    void waitsForTheModuleJarsProducerTaskWithoutNamingItOnTheCommandLine() throws IOException {
        fixture();
        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("nyliumVerifyModules", "--offline")
                .build();

        assertEquals(TaskOutcome.SUCCESS, result.task(":produceModuleJar").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":nyliumVerifyModules").getOutcome());
    }
}
