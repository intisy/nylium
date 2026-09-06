package io.github.intisy.rutter.gradle;

import org.gradle.api.Project;

public class RutterExtension {

    private final Project project;

    public RutterExtension(Project project) {
        this.project = project;
    }

    Project project() {
        return project;
    }
}
