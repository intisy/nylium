package io.github.intisy.rutter.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @implNote The generated text is a declared {@code @Input} and the destination a declared
 *     {@code @OutputFile}, so a changed declaration always rebuilds the jar. The kernel's own
 *     packaging emitted these files through {@code resources.text.fromString}, whose backing temp
 *     path changes every configuration and is therefore not a tracked input at all, which let a
 *     stale module survive a rebuild.
 */
public abstract class RutterTextFileTask extends DefaultTask {

    @Input
    public abstract Property<String> getContent();

    @OutputFile
    public abstract RegularFileProperty getDestination();

    @TaskAction
    public void write() {
        Path target = getDestination().get().getAsFile().toPath();
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, getContent().get().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + target, e);
        }
    }
}
