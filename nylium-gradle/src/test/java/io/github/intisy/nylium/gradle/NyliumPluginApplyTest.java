package io.github.intisy.nylium.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class NyliumPluginApplyTest {

    @Test
    void registersTheExtension() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.nylium");
        assertNotNull(project.getExtensions().findByName("nylium"));
    }
}
