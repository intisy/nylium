package io.github.intisy.rutter.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.util.List;

public abstract class ApiPurityTask extends DefaultTask {

    @InputFile
    public abstract RegularFileProperty getJar();

    @TaskAction
    public void check() throws IOException {
        List<String> findings = ApiPurityScanner.scan(getJar().get().getAsFile().toPath());
        if (!findings.isEmpty()) {
            throw new GradleException("rutter-api must not expose net.minecraft types:\n  "
                    + String.join("\n  ", findings));
        }
    }
}
