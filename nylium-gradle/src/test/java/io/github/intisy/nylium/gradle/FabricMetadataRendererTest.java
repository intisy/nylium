package io.github.intisy.nylium.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricMetadataRendererTest {

    private ModSpec spec() {
        Project project = ProjectBuilder.builder().build();
        ModSpec mod = project.getObjects().newInstance(ModSpec.class);
        mod.getId().set("nylium_testmod");
        mod.getName().set("Nylium Test Mod");
        mod.getVersion().set("0.1.0");
        mod.getEnvironment().set("*");
        mod.getFabricLoaderVersion().set(">=0.14.0");
        return mod;
    }

    private JsonObject render(ModSpec mod) {
        return JsonParser.parseString(FabricMetadataRenderer.render(mod)).getAsJsonObject();
    }

    @Test
    void declaresNyliumsPreLaunchEntrypoint() {
        JsonArray preLaunch = render(spec()).getAsJsonObject("entrypoints").getAsJsonArray("preLaunch");
        assertEquals(1, preLaunch.size());
        assertEquals("io.github.intisy.nylium.bootstrap.fabric.NyliumPreLaunch",
                preLaunch.get(0).getAsString());
    }

    @Test
    void declaresNoMixins() {
        assertFalse(render(spec()).has("mixins"));
    }

    @Test
    void omitsTheMinecraftDependencyByDefault() {
        assertFalse(render(spec()).getAsJsonObject("depends").has("minecraft"));
    }

    @Test
    void includesTheFabricLoaderDependencyByDefault() {
        Project project = ProjectBuilder.builder().build();
        ModSpec mod = project.getObjects().newInstance(ModSpec.class);
        mod.getId().set("nylium_testmod");
        mod.getVersion().set("0.1.0");
        assertEquals(">=0.14.0",
                render(mod).getAsJsonObject("depends").get("fabricloader").getAsString());
    }

    @Test
    void honoursAnExplicitMinecraftDependency() {
        ModSpec mod = spec();
        mod.getMinecraftDependency().set(">=1.16.5");
        assertEquals(">=1.16.5",
                render(mod).getAsJsonObject("depends").get("minecraft").getAsString());
    }

    @Test
    void escapesQuotesInText() {
        ModSpec mod = spec();
        mod.getDescription().set("A \"quoted\" mod");
        assertEquals("A \"quoted\" mod", render(mod).get("description").getAsString());
    }

    @Test
    void preservesBackslashesAndNewlinesThroughAGsonRoundTrip() {
        ModSpec mod = spec();
        String value = "back\\slash\nnewline";
        mod.getDescription().set(value);
        assertEquals(value, render(mod).get("description").getAsString());
    }

    @Test
    void lowerCasesAKnownEnvironmentName() {
        ModSpec mod = spec();
        mod.getEnvironment().set("CLIENT");
        assertEquals("client", render(mod).get("environment").getAsString());
    }

    @Test
    void rejectsAnEnvironmentFabricWouldRefuse() {
        ModSpec mod = spec();
        mod.getEnvironment().set("BOTH");
        InvalidUserDataException thrown =
                assertThrows(InvalidUserDataException.class, () -> render(mod));
        assertTrue(thrown.getMessage().contains("BOTH"));
        assertTrue(thrown.getMessage().contains("Known environments are [*, client, server]"));
    }

    @Test
    void defaultsTheEnvironmentToAnyWhenUnset() {
        Project project = ProjectBuilder.builder().build();
        ModSpec mod = project.getObjects().newInstance(ModSpec.class);
        mod.getId().set("nylium_testmod");
        assertEquals("*", render(mod).get("environment").getAsString());
    }

    @Test
    void carriesTheIdentityFields() {
        JsonObject json = render(spec());
        assertEquals(1, json.get("schemaVersion").getAsInt());
        assertEquals("nylium_testmod", json.get("id").getAsString());
        assertEquals("0.1.0", json.get("version").getAsString());
        assertTrue(json.has("name"));
    }
}
