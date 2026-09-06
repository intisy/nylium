package io.github.intisy.rutter.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class RutterPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getExtensions().create("rutter", RutterExtension.class, project);
    }
}
