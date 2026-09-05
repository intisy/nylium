package io.github.intisy.rutter.core;

import io.github.intisy.rutter.api.Environment;
import io.github.intisy.rutter.api.PlatformId;
import io.github.intisy.rutter.api.RutterException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public final class ModuleManifest {

    public static final String RESOURCE = "rutter-modules.properties";

    // Plain TreeSet<String> order would put "10" before "2"; module selection depends on
    // this order when several candidates tie on specificity.
    private static final Comparator<String> INDEX_ORDER = (left, right) -> {
        Integer leftNumber = tryParseInt(left);
        Integer rightNumber = tryParseInt(right);
        if (leftNumber != null && rightNumber != null) {
            return Integer.compare(leftNumber, rightNumber);
        }
        return left.compareTo(right);
    };

    private final List<ModuleDescriptor> modules;

    private ModuleManifest(List<ModuleDescriptor> modules) {
        this.modules = Collections.unmodifiableList(modules);
    }

    public static ModuleManifest readFrom(ClassLoader loader, String resource) {
        InputStream stream = loader.getResourceAsStream(resource);
        if (stream == null) {
            throw new RutterException("No Rutter module manifest at '" + resource
                    + "'. The jar was built without one, or it was stripped by shading.");
        }
        // Properties.load(InputStream) is specified as ISO-8859-1, which would mangle any
        // non-ASCII module path; the Reader overload lets us pin UTF-8.
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return read(reader);
        } catch (IOException e) {
            throw new RutterException("Could not read the Rutter module manifest", e);
        }
    }

    public static ModuleManifest read(Reader reader) {
        Properties properties = new Properties();
        try {
            properties.load(reader);
        } catch (IOException e) {
            throw new RutterException("Could not parse the Rutter module manifest", e);
        }
        List<ModuleDescriptor> modules = new ArrayList<>();
        for (String index : indices(properties)) {
            modules.add(readModule(properties, index));
        }
        if (modules.isEmpty()) {
            throw new RutterException("The Rutter module manifest declares no modules");
        }
        return new ModuleManifest(modules);
    }

    private static List<String> indices(Properties properties) {
        Set<String> rawIndices = new LinkedHashSet<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("module.")) {
                continue;
            }
            int second = key.indexOf('.', "module.".length());
            if (second > 0) {
                rawIndices.add(key.substring("module.".length(), second));
            }
        }
        // Different raw index tokens (e.g. "2" and "02") can normalize to the same integer;
        // TreeSet with a numeric comparator would then silently drop the second module, so
        // collisions are detected explicitly before the tokens are handed to the caller.
        Map<Object, String> byNormalized = new HashMap<>();
        for (String raw : rawIndices) {
            Object normalized = normalizedIndex(raw);
            String collidingRaw = byNormalized.put(normalized, raw);
            if (collidingRaw != null) {
                throw new RutterException("The Rutter module manifest declares two different module indices, '"
                        + collidingRaw + "' and '" + raw + "', that both refer to the same module. "
                        + "Give each module a distinct index.");
            }
        }
        List<String> ordered = new ArrayList<>(rawIndices);
        Collections.sort(ordered, INDEX_ORDER);
        return ordered;
    }

    private static Object normalizedIndex(String raw) {
        Integer number = tryParseInt(raw);
        return number != null ? number : raw;
    }

    private static Integer tryParseInt(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ModuleDescriptor readModule(Properties properties, String index) {
        String prefix = "module." + index + ".";
        String path = required(properties, prefix + "path");
        VersionRange minecraft = VersionRange.parse(required(properties, prefix + "minecraft"));
        Set<PlatformId> platforms = platforms(required(properties, prefix + "platforms"));
        String environmentText = properties.getProperty(prefix + "environment");
        Environment environment = environmentText == null || environmentText.trim().isEmpty()
                ? null
                : environment(environmentText.trim());
        List<String> mixins = split(properties.getProperty(prefix + "mixins"));
        int priority = priority(properties.getProperty(prefix + "priority"), prefix);
        String entrypointText = properties.getProperty(prefix + "entrypoint");
        String entrypoint = entrypointText == null || entrypointText.trim().isEmpty()
                ? null
                : entrypointText.trim();
        return new ModuleDescriptor(path, platforms, minecraft, environment, mixins, priority, entrypoint);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new RutterException("The Rutter module manifest is missing '" + key + "'");
        }
        return value.trim();
    }

    private static Set<PlatformId> platforms(String value) {
        Set<PlatformId> platforms = new LinkedHashSet<>();
        for (String name : split(value)) {
            try {
                platforms.add(PlatformId.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new RutterException("Unknown platform '" + name + "' in the module manifest. "
                        + "Known platforms are " + Arrays.toString(PlatformId.values()) + ".", e);
            }
        }
        return platforms;
    }

    private static Environment environment(String value) {
        try {
            return Environment.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new RutterException("Unknown environment '" + value + "' in the module manifest. "
                    + "Known environments are " + Arrays.toString(Environment.values()) + ".", e);
        }
    }

    private static int priority(String value, String prefix) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new RutterException("'" + prefix + "priority' is not a whole number: " + value, e);
        }
    }

    private static List<String> split(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    public List<ModuleDescriptor> modules() {
        return modules;
    }
}
