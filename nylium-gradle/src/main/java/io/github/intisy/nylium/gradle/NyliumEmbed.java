package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.api.PlatformId;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

final class NyliumEmbed {

    private NyliumEmbed() {
    }

    static Configuration configuration(Project project) {
        Configuration embed = project.getConfigurations().maybeCreate("nyliumEmbed");
        embed.setCanBeConsumed(false);
        embed.setCanBeResolved(true);
        return embed;
    }

    static void addDependencies(Project project, Configuration embed,
                                             Set<PlatformId> platforms) {
        String version = pluginVersion();
        addEmbed(project, embed, "nylium-api", version);
        addEmbed(project, embed, "nylium-core", version);
        for (PlatformId platform : platforms) {
            String artifact = BOOTSTRAPS.get(platform);
            if (artifact == null) {
                throw new InvalidUserDataException(
                        "No Nylium bootstrap exists for platform " + platform + ".");
            }
            addEmbed(project, embed, artifact, version);
        }
    }

    private static void addEmbed(Project project, Configuration embed, String artifact,
                                 String version) {
        embed.getDependencies().add(project.getDependencies()
                .create("io.github.intisy.nylium:" + artifact + ":" + version));
    }

    private static final Map<PlatformId, String> BOOTSTRAPS = bootstraps();

    private static Map<PlatformId, String> bootstraps() {
        Map<PlatformId, String> map = new EnumMap<PlatformId, String>(PlatformId.class);
        map.put(PlatformId.FABRIC, "nylium-bootstrap-fabric");
        map.put(PlatformId.LAUNCHWRAPPER, "nylium-bootstrap-launchwrapper");
        map.put(PlatformId.MODLAUNCHER_8, "nylium-bootstrap-modlauncher8");
        map.put(PlatformId.MODLAUNCHER_9, "nylium-bootstrap-modlauncher9");
        return map;
    }

    private static String pluginVersion() {
        InputStream stream = NyliumPlugin.class.getResourceAsStream(
                "/nylium-gradle-version.properties");
        if (stream == null) {
            throw new IllegalStateException(
                    "nylium-gradle-version.properties is missing from the plugin jar");
        }
        Properties properties = new Properties();
        try {
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            stream.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String version = properties.getProperty("version");
        if (version == null || version.trim().isEmpty()) {
            throw new IllegalStateException("nylium-gradle-version.properties declares no version");
        }
        return version.trim();
    }
}
