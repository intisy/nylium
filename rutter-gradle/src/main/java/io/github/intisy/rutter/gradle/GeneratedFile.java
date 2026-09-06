package io.github.intisy.rutter.gradle;

import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

/**
 * One generated metadata file, paired with the task that writes it.
 *
 * @implNote {@code rutterUniversalJar} takes each of these from its own writer's output instead of
 *     sweeping {@code build/rutter/}. Gradle does not delete the output of a task that no longer
 *     exists, so a consumer who drops a platform between builds keeps that platform's service file
 *     on disk, and a sweep would ship it: an entry naming a class the jar no longer embeds, which
 *     fails at game launch.
 */
final class GeneratedFile {

    private final String relativePath;
    private final TaskProvider<RutterTextFileTask> writer;

    GeneratedFile(String relativePath, TaskProvider<RutterTextFileTask> writer) {
        this.relativePath = relativePath;
        this.writer = writer;
    }

    TaskProvider<RutterTextFileTask> writer() {
        return writer;
    }

    Provider<RegularFile> destination() {
        return writer.flatMap(RutterTextFileTask::getDestination);
    }

    String parentDirectory() {
        int lastSlash = relativePath.lastIndexOf('/');
        return lastSlash < 0 ? "" : relativePath.substring(0, lastSlash);
    }
}
