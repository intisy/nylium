package io.github.intisy.rutter.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.util.List;

/**
 * @implNote A single Java 9-or-newer class file in any published artifact throws
 * {@code UnsupportedClassVersionError} while ModLauncher 8's {@code ServiceLoader} instantiates
 * every listed service, which takes the game down; the Java 8 floor is therefore an invariant
 * rather than a preference, and this is its mechanical enforcement.
 */
public abstract class ClassFileVersionTask extends DefaultTask {

    @InputFile
    public abstract RegularFileProperty getJar();

    @TaskAction
    public void check() throws IOException {
        List<String> findings = ClassFileVersionScanner.scan(getJar().get().getAsFile().toPath());
        if (!findings.isEmpty()) {
            throw new GradleException("every Rutter artifact must emit Java "
                    + ClassFileVersionScanner.JAVA_8 + " bytecode:\n  "
                    + String.join("\n  ", findings));
        }
    }
}
