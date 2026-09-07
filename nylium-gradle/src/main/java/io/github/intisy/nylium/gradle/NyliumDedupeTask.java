package io.github.intisy.nylium.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public abstract class NyliumDedupeTask extends DefaultTask {

    public abstract static class ModuleToDedupe {

        @Input
        public abstract Property<String> getIndexFileName();

        @InputFile
        @PathSensitive(PathSensitivity.NAME_ONLY)
        public abstract RegularFileProperty getJar();
    }

    @Nested
    public abstract ListProperty<ModuleToDedupe> getModules();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /**
     * @implNote The directory is emptied first because Gradle never removes the output belonging to
     *     a module a consumer dropped between builds, and a stale object or index would then be
     *     copied straight into the universal jar. This is the same hazard {@code GeneratedFile}
     *     records for the metadata writers.
     */
    @TaskAction
    public void dedupe() {
        Path output = getOutputDirectory().get().getAsFile().toPath();
        empty(output);
        List<DedupeWriter.Source> sources = new ArrayList<DedupeWriter.Source>();
        for (ModuleToDedupe module : getModules().get()) {
            sources.add(new DedupeWriter.Source(module.getIndexFileName().get(),
                    module.getJar().get().getAsFile()));
        }
        DedupeWriter.write(sources, output);
    }

    private static void empty(Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path visited, IOException failure)
                        throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    if (!visited.equals(directory)) {
                        Files.delete(visited);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not empty " + directory, e);
        }
    }
}
