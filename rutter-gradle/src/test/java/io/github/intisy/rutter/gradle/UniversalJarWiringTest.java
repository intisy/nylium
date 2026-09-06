package io.github.intisy.rutter.gradle;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.jvm.tasks.Jar;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniversalJarWiringTest {

    private Project projectWith(String platform) {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.rutter");
        RutterExtension rutter = (RutterExtension) project.getExtensions().getByName("rutter");
        rutter.mod(mod -> {
            mod.getId().set("demo");
            mod.getVersion().set("1.0.0");
        });
        rutter.module("1.21.11", module -> {
            module.getJar().set(new File(project.getProjectDir(), "module.jar"));
            module.getPlatforms().set(Collections.singletonList(platform));
            module.getMinecraft().set("1.21.11");
        });
        ((ProjectInternal) project).evaluate();
        return project;
    }

    private Set<String> embeddedArtifacts(Project project) {
        Set<String> names = new LinkedHashSet<String>();
        for (Dependency dependency
                : project.getConfigurations().getByName("rutterEmbed").getDependencies()) {
            names.add(dependency.getName());
        }
        return names;
    }

    @Test
    void embedsOnlyTheBootstrapForTheDeclaredPlatform() {
        Set<String> embedded = embeddedArtifacts(projectWith("FABRIC"));
        assertTrue(embedded.contains("rutter-api"));
        assertTrue(embedded.contains("rutter-core"));
        assertTrue(embedded.contains("rutter-bootstrap-fabric"));
        assertFalse(embedded.contains("rutter-bootstrap-modlauncher9"));
        assertFalse(embedded.contains("rutter-bootstrap-launchwrapper"));
    }

    @Test
    void addsTheTweakClassAttributeOnlyForLaunchWrapper() {
        Jar withLaunchWrapper = (Jar) projectWith("LAUNCHWRAPPER").getTasks()
                .getByName("rutterUniversalJar");
        assertEquals("io.github.intisy.rutter.bootstrap.launchwrapper.RutterTweaker",
                withLaunchWrapper.getManifest().getAttributes().get("TweakClass"));

        Jar fabricOnly = (Jar) projectWith("FABRIC").getTasks().getByName("rutterUniversalJar");
        assertFalse(fabricOnly.getManifest().getAttributes().containsKey("TweakClass"));
    }

    @Test
    void registersTheJarTask() {
        assertNotNull(projectWith("FABRIC").getTasks().findByName("rutterUniversalJar"));
    }
}
