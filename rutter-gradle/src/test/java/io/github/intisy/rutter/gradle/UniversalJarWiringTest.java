package io.github.intisy.rutter.gradle;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.jvm.tasks.Jar;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Properties;
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

    private Set<String> embeddedCoordinates(Project project) {
        Set<String> coordinates = new LinkedHashSet<String>();
        for (Dependency dependency
                : project.getConfigurations().getByName("rutterEmbed").getDependencies()) {
            coordinates.add(dependency.getGroup() + ":" + dependency.getName() + ":"
                    + dependency.getVersion());
        }
        return coordinates;
    }

    private String pluginVersion() {
        InputStream stream = RutterPlugin.class.getResourceAsStream(
                "/rutter-gradle-version.properties");
        Properties properties = new Properties();
        try {
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            stream.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties.getProperty("version").trim();
    }

    @Test
    void embedsOnlyTheBootstrapForTheDeclaredPlatformAtThePluginVersion() {
        String version = pluginVersion();
        Set<String> embedded = embeddedCoordinates(projectWith("FABRIC"));
        assertTrue(embedded.contains("io.github.intisy.rutter:rutter-api:" + version));
        assertTrue(embedded.contains("io.github.intisy.rutter:rutter-core:" + version));
        assertTrue(embedded.contains("io.github.intisy.rutter:rutter-bootstrap-fabric:" + version));
        assertFalse(embedded.contains(
                "io.github.intisy.rutter:rutter-bootstrap-modlauncher8:" + version));
        assertFalse(embedded.contains(
                "io.github.intisy.rutter:rutter-bootstrap-modlauncher9:" + version));
        assertFalse(embedded.contains(
                "io.github.intisy.rutter:rutter-bootstrap-launchwrapper:" + version));
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
