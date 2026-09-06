package io.github.intisy.rutter.gradle;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationTest {

    private RutterExtension extensionWith(String platform, String minecraft) {
        return extensionWith(platform, minecraft, "demo");
    }

    private RutterExtension extensionWith(String platform, String minecraft, String modId) {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.rutter");
        RutterExtension rutter = (RutterExtension) project.getExtensions().getByName("rutter");
        rutter.mod(mod -> {
            mod.getId().set(modId);
            mod.getName().set("Demo");
            mod.getVersion().set("1.0.0");
        });
        rutter.module("1.21.11", module -> {
            module.getJar().set(new File(project.getProjectDir(), "module.jar"));
            module.getPlatforms().set(Collections.singletonList(platform));
            module.getMinecraft().set(minecraft);
        });
        return rutter;
    }

    @Test
    void acceptsAValidModule() {
        List<ResolvedModule> modules = extensionWith("FABRIC", "1.21.11").resolve();
        assertEquals(1, modules.size());
        assertEquals("modules/demo-1.21.11.jar", modules.get(0).path());
    }

    @Test
    void acceptsLowerCasePlatformNames() {
        List<ResolvedModule> modules = extensionWith("fabric", "1.21.11").resolve();
        assertTrue(modules.get(0).platforms().toString().contains("FABRIC"));
    }

    @Test
    void rejectsAMalformedVersionRange() {
        InvalidUserDataException thrown = assertThrows(InvalidUserDataException.class,
                () -> extensionWith("FABRIC", "[1.21.11").resolve());
        assertTrue(thrown.getMessage().contains("1.21.11"));
    }

    @Test
    void rejectsAnUnknownPlatform() {
        InvalidUserDataException thrown = assertThrows(InvalidUserDataException.class,
                () -> extensionWith("QUILT", "1.21.11").resolve());
        assertTrue(thrown.getMessage().contains("QUILT"));
    }

    @Test
    void rejectsNeoforgeAndNamesTheBlockingSubProject() {
        InvalidUserDataException thrown = assertThrows(InvalidUserDataException.class,
                () -> extensionWith("NEOFORGE", "1.21.11").resolve());
        assertTrue(thrown.getMessage().contains("SP-1b"));
    }

    @Test
    void rejectsAModuleWithNoPlatforms() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.rutter");
        RutterExtension rutter = (RutterExtension) project.getExtensions().getByName("rutter");
        rutter.mod(mod -> mod.getId().set("demo"));
        rutter.module("1.21.11", module -> {
            module.getJar().set(new File(project.getProjectDir(), "module.jar"));
            module.getPlatforms().set(Arrays.<String>asList());
            module.getMinecraft().set("1.21.11");
        });
        assertThrows(InvalidUserDataException.class, rutter::resolve);
    }

    @Test
    void rejectsAnEmptyModuleSet() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.rutter");
        RutterExtension rutter = (RutterExtension) project.getExtensions().getByName("rutter");
        rutter.mod(mod -> mod.getId().set("demo"));
        assertThrows(InvalidUserDataException.class, rutter::resolve);
    }

    @Test
    void rejectsAMissingModId() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.rutter");
        RutterExtension rutter = (RutterExtension) project.getExtensions().getByName("rutter");
        rutter.module("1.21.11", module -> {
            module.getJar().set(new File(project.getProjectDir(), "module.jar"));
            module.getPlatforms().set(Collections.singletonList("FABRIC"));
            module.getMinecraft().set("1.21.11");
        });
        assertThrows(InvalidUserDataException.class, rutter::resolve);
    }

    @Test
    void rejectsAnUnknownEnvironment() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.rutter");
        RutterExtension rutter = (RutterExtension) project.getExtensions().getByName("rutter");
        rutter.mod(mod -> mod.getId().set("demo"));
        rutter.module("1.21.11", module -> {
            module.getJar().set(new File(project.getProjectDir(), "module.jar"));
            module.getPlatforms().set(Collections.singletonList("FABRIC"));
            module.getMinecraft().set("1.21.11");
            module.getEnvironment().set("BOTH");
        });
        assertThrows(InvalidUserDataException.class, rutter::resolve);
    }

    @Test
    void rejectsADuplicateModuleName() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.rutter");
        RutterExtension rutter = (RutterExtension) project.getExtensions().getByName("rutter");
        rutter.module("1.21.11", module -> { });
        InvalidUserDataException thrown = assertThrows(InvalidUserDataException.class,
                () -> rutter.module("1.21.11", module -> { }));
        assertTrue(thrown.getMessage().contains("1.21.11"));
    }

    @Test
    void acceptsAModuleCreatedThroughTheContainerDirectly() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.rutter");
        RutterExtension rutter = (RutterExtension) project.getExtensions().getByName("rutter");
        rutter.mod(mod -> mod.getId().set("demo"));
        ModuleSpec spec = rutter.getModules().create("1.21.11");
        spec.getJar().set(new File(project.getProjectDir(), "module.jar"));
        spec.getPlatforms().set(Collections.singletonList("FABRIC"));
        spec.getMinecraft().set("1.21.11");
        List<ResolvedModule> modules = rutter.resolve();
        assertEquals(1, modules.size());
        assertEquals("modules/demo-1.21.11.jar", modules.get(0).path());
    }

    @Test
    void rejectsAnUnknownModEnvironment() {
        RutterExtension rutter = extensionWith("FABRIC", "1.21.11");
        rutter.getMod().getEnvironment().set("BOTH");
        InvalidUserDataException thrown = assertThrows(InvalidUserDataException.class,
                rutter::resolve);
        assertTrue(thrown.getMessage().contains("BOTH"));
        assertTrue(thrown.getMessage().contains("Known environments are [*, client, server]"));
    }

    @Test
    void acceptsAModEnvironmentInAnyCase() {
        RutterExtension rutter = extensionWith("FABRIC", "1.21.11");
        rutter.getMod().getEnvironment().set("Server");
        assertEquals(1, rutter.resolve().size());
    }

    @Test
    void rejectsAFabricIllegalModIdWhenFabricIsDeclared() {
        RutterExtension rutter = extensionWith("FABRIC", "1.21.11", "My.Mod");
        InvalidUserDataException thrown = assertThrows(InvalidUserDataException.class,
                rutter::resolve);
        assertTrue(thrown.getMessage().contains("My.Mod"));
        assertTrue(thrown.getMessage().contains("^[a-z][a-z0-9-_]{1,63}$"));
    }

    @Test
    void acceptsAFabricIllegalModIdWhenNoModuleDeclaresFabric() {
        List<ResolvedModule> modules =
                extensionWith("LAUNCHWRAPPER", "[1.7,1.12.2]", "My.Mod").resolve();
        assertEquals(1, modules.size());
        assertTrue(modules.get(0).path().startsWith("modules/My.Mod-"));
    }

    @Test
    void rejectsAModuleWithoutAJar() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.rutter");
        RutterExtension rutter = (RutterExtension) project.getExtensions().getByName("rutter");
        rutter.mod(mod -> mod.getId().set("demo"));
        rutter.module("1.21.11", module -> {
            module.getPlatforms().set(Collections.singletonList("FABRIC"));
            module.getMinecraft().set("1.21.11");
        });
        assertThrows(InvalidUserDataException.class, rutter::resolve);
    }
}
