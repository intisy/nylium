package io.github.intisy.rutter.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class RutterPluginApplyTest {

    @Test
    void registersTheExtension() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.rutter");
        assertNotNull(project.getExtensions().findByName("rutter"));
    }
}
