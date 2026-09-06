package io.github.intisy.rutter.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricMetadataRendererTest {

    private ModSpec spec() {
        Project project = ProjectBuilder.builder().build();
        ModSpec mod = project.getObjects().newInstance(ModSpec.class);
        mod.getId().set("rutter_testmod");
        mod.getName().set("Rutter Test Mod");
        mod.getVersion().set("0.1.0");
        mod.getEnvironment().set("*");
        mod.getFabricLoaderVersion().set(">=0.14.0");
        return mod;
    }

    private JsonObject render(ModSpec mod) {
        return JsonParser.parseString(FabricMetadataRenderer.render(mod)).getAsJsonObject();
    }

    @Test
    void declaresRuttersPreLaunchEntrypoint() {
        JsonArray preLaunch = render(spec()).getAsJsonObject("entrypoints").getAsJsonArray("preLaunch");
        assertEquals(1, preLaunch.size());
        assertEquals("io.github.intisy.rutter.bootstrap.fabric.RutterPreLaunch",
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
    void carriesTheIdentityFields() {
        JsonObject json = render(spec());
        assertEquals(1, json.get("schemaVersion").getAsInt());
        assertEquals("rutter_testmod", json.get("id").getAsString());
        assertEquals("0.1.0", json.get("version").getAsString());
        assertTrue(json.has("name"));
    }
}
