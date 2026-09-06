package io.github.intisy.nylium.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class FabricMetadataRenderer {

    static final String PRE_LAUNCH = "io.github.intisy.nylium.bootstrap.fabric.NyliumPreLaunch";

    private FabricMetadataRenderer() {
    }

    /**
     * @implNote No {@code mixins} key is emitted on purpose: a module's mixin configs live inside
     *     the module jar and are registered through the platform, so naming them here would point
     *     Fabric Loader at resources the outer jar does not contain.
     */
    static String render(ModSpec mod) {
        List<String> lines = new ArrayList<String>();
        lines.add("  \"schemaVersion\": 1");
        lines.add("  \"id\": " + Json.quote(mod.getId().get()));
        lines.add("  \"version\": " + Json.quote(mod.getVersion().getOrElse("0.0.0")));
        addOptional(lines, "name", mod.getName().getOrNull());
        addOptional(lines, "description", mod.getDescription().getOrNull());
        addAuthors(lines, mod.getAuthors().get());
        addContact(lines, mod.getContact().get());
        addOptional(lines, "license", mod.getLicense().getOrNull());
        addOptional(lines, "icon", mod.getIcon().getOrNull());
        lines.add("  \"environment\": "
                + Json.quote(FabricEnvironments.canonical(mod.getEnvironment().getOrNull())));
        lines.add("  \"entrypoints\": { \"preLaunch\": [" + Json.quote(PRE_LAUNCH) + "] }");
        lines.add("  \"depends\": " + depends(mod));
        return "{\n" + String.join(",\n", lines) + "\n}\n";
    }

    private static String depends(ModSpec mod) {
        List<String> entries = new ArrayList<String>();
        entries.add("\"fabricloader\": "
                + Json.quote(mod.getFabricLoaderVersion().getOrElse(">=0.14.0")));
        String minecraft = mod.getMinecraftDependency().getOrNull();
        if (minecraft != null && !minecraft.trim().isEmpty()) {
            entries.add("\"minecraft\": " + Json.quote(minecraft.trim()));
        }
        return "{ " + String.join(", ", entries) + " }";
    }

    private static void addOptional(List<String> lines, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            lines.add("  " + Json.quote(key) + ": " + Json.quote(value));
        }
    }

    private static void addAuthors(List<String> lines, List<String> authors) {
        if (authors.isEmpty()) {
            return;
        }
        List<String> quoted = new ArrayList<String>();
        for (String author : authors) {
            quoted.add(Json.quote(author));
        }
        lines.add("  \"authors\": [" + String.join(", ", quoted) + "]");
    }

    private static void addContact(List<String> lines, Map<String, String> contact) {
        if (contact.isEmpty()) {
            return;
        }
        List<String> entries = new ArrayList<String>();
        for (Map.Entry<String, String> entry : contact.entrySet()) {
            entries.add(Json.quote(entry.getKey()) + ": " + Json.quote(entry.getValue()));
        }
        lines.add("  \"contact\": { " + String.join(", ", entries) + " }");
    }
}
