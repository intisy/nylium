package io.github.intisy.rutter.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public abstract class RutterVerifyModulesTask extends DefaultTask {

    public abstract static class ModuleToVerify {

        @Input
        public abstract Property<String> getModuleName();

        @Input
        public abstract ListProperty<String> getMixins();

        @InputFile
        @PathSensitive(PathSensitivity.NAME_ONLY)
        public abstract RegularFileProperty getJar();
    }

    @Nested
    public abstract ListProperty<ModuleToVerify> getModules();

    /**
     * @implNote Wired to a directory outside {@code build/rutter/}, since that one is swept
     *     wholesale into the universal jar and this stamp is not metadata to embed.
     */
    @OutputFile
    public abstract RegularFileProperty getStamp();

    @TaskAction
    public void verify() {
        for (ModuleToVerify module : getModules().get()) {
            ModuleJarInspector.verify(module.getModuleName().get(),
                    module.getJar().get().getAsFile(), module.getMixins().get());
        }
        Path stamp = getStamp().get().getAsFile().toPath();
        try {
            Files.createDirectories(stamp.getParent());
            Files.write(stamp, new byte[0]);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + stamp, e);
        }
    }
}
