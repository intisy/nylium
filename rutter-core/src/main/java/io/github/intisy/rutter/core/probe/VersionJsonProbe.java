package io.github.intisy.rutter.core.probe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the {@code id} field out of Mojang's {@code version.json}, present at the root of the
 * Minecraft jar since 1.14.
 *
 * @implNote Matches the field with a regex rather than a JSON parser because {@code rutter-core}
 * carries zero runtime dependencies.
 */
public final class VersionJsonProbe implements VersionProbe {

    private static final String RESOURCE = "version.json";
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private final ClassLoader loader;

    public VersionJsonProbe(ClassLoader loader) {
        this.loader = loader;
    }

    @Override
    public String name() {
        return "version.json on the classpath";
    }

    @Override
    public Optional<String> detect() {
        try (InputStream stream = loader.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return Optional.empty();
            }
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    text.append(line).append('\n');
                }
            }
            Matcher matcher = ID.matcher(topLevelMembersOf(text.toString()));
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * @implNote An unscoped match would take an {@code id} out of any nested object, and this probe
     * is the only one that answers on ModLauncher 8, so a wrong match there silently selects a
     * wrong module. Dropping everything nested deeper than the outermost object keeps the field
     * match itself a regex, so {@code rutter-core} still needs no JSON dependency.
     */
    private static String topLevelMembersOf(String json) {
        StringBuilder members = new StringBuilder(json.length());
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < json.length(); index++) {
            char character = json.charAt(index);
            if (inString) {
                if (depth <= 1) {
                    members.append(character);
                }
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{' || character == '[') {
                depth++;
            } else if (character == '}' || character == ']') {
                if (depth <= 1) {
                    members.append(character);
                }
                depth--;
                continue;
            }
            if (depth <= 1) {
                members.append(character);
            }
        }
        return members.toString();
    }
}
