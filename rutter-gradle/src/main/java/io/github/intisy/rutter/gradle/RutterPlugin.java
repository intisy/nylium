package io.github.intisy.rutter.gradle;

import io.github.intisy.rutter.api.PlatformId;
import io.github.intisy.rutter.core.ModuleManifest;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class RutterPlugin implements Plugin<Project> {

    private final ArchiveOperations archiveOperations;

    /**
     * @implNote Injected because the embedded jars are unpacked lazily, at task execution time;
     *     calling {@code Project.zipTree} there would capture the {@link Project} itself, which
     *     breaks the configuration cache, whereas this service is safe to hold across it.
     */
    @Inject
    public RutterPlugin(ArchiveOperations archiveOperations) {
        this.archiveOperations = archiveOperations;
    }

    @Override
    public void apply(Project project) {
        final RutterExtension rutter =
                project.getExtensions().create("rutter", RutterExtension.class, project);

        final TaskProvider<Task> metadata = project.getTasks().register("rutterMetadata", task ->
                task.setDescription("Generates the Rutter manifest and loader metadata."));

        project.afterEvaluate(evaluated -> {
            if (rutter.getModules().isEmpty()) {
                failWhenInvokedWithNoModules(metadata);
                return;
            }

            List<ResolvedModule> modules = rutter.resolve();
            Set<PlatformId> platforms = platformUnion(modules);

            List<TaskProvider<RutterTextFileTask>> writers =
                    new ArrayList<TaskProvider<RutterTextFileTask>>();
            writers.add(writer(evaluated, "rutterManifest", ModuleManifest.RESOURCE,
                    ManifestRenderer.render(modules)));
            if (platforms.contains(PlatformId.FABRIC)) {
                writers.add(writer(evaluated, "rutterFabricModJson", "fabric.mod.json",
                        FabricMetadataRenderer.render(rutter.getMod())));
            }
            String services = ServiceRenderer.transformationServices(platforms);
            if (services != null) {
                writers.add(writer(evaluated, "rutterTransformationServices",
                        "META-INF/services/cpw.mods.modlauncher.api.ITransformationService",
                        services));
            }
            String launchPlugins = ServiceRenderer.launchPlugins(platforms);
            if (launchPlugins != null) {
                writers.add(writer(evaluated, "rutterLaunchPlugins",
                        "META-INF/services/cpw.mods.modlauncher.serviceapi.ILaunchPluginService",
                        launchPlugins));
            }
            metadata.configure(task -> task.dependsOn(writers));

            final Configuration embed = embedConfiguration(evaluated, platforms);
            final boolean launchWrapper = platforms.contains(PlatformId.LAUNCHWRAPPER);

            evaluated.getTasks().register("rutterUniversalJar", Jar.class, jar -> {
                jar.setDescription("Assembles the Rutter universal jar.");
                jar.getArchiveClassifier().set("universal");
                // FAIL surfaces a colliding entry instead of silently keeping one, per SP-1's lesson.
                jar.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
                jar.dependsOn(metadata);

                final ArchiveOperations archives = archiveOperations;
                jar.from(evaluated.provider(() -> {
                    List<Object> trees = new ArrayList<Object>();
                    for (File artifact : embed.getFiles()) {
                        trees.add(archives.zipTree(artifact));
                    }
                    return trees;
                }), copy -> copy.exclude("META-INF/MANIFEST.MF", "META-INF/*.SF",
                        "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/maven/**",
                        "module-info.class"));

                jar.from(evaluated.getLayout().getBuildDirectory().dir("rutter"));

                for (ResolvedModule module : modules) {
                    final String path = module.path();
                    jar.from(module.jar(), copy -> {
                        copy.into(path.substring(0, path.lastIndexOf('/')));
                        copy.rename(".*", path.substring(path.lastIndexOf('/') + 1));
                    });
                }

                if (launchWrapper) {
                    jar.getManifest().getAttributes().put("TweakClass",
                            "io.github.intisy.rutter.bootstrap.launchwrapper.RutterTweaker");
                }
            });
        });
    }

    private static final Map<PlatformId, String> BOOTSTRAPS = bootstraps();

    private static Map<PlatformId, String> bootstraps() {
        Map<PlatformId, String> map = new EnumMap<PlatformId, String>(PlatformId.class);
        map.put(PlatformId.FABRIC, "rutter-bootstrap-fabric");
        map.put(PlatformId.LAUNCHWRAPPER, "rutter-bootstrap-launchwrapper");
        map.put(PlatformId.MODLAUNCHER_8, "rutter-bootstrap-modlauncher8");
        map.put(PlatformId.MODLAUNCHER_9, "rutter-bootstrap-modlauncher9");
        return map;
    }

    private static Configuration embedConfiguration(Project project, Set<PlatformId> platforms) {
        Configuration embed = project.getConfigurations().maybeCreate("rutterEmbed");
        embed.setCanBeConsumed(false);
        embed.setCanBeResolved(true);
        String version = pluginVersion();
        addEmbed(project, embed, "rutter-api", version);
        addEmbed(project, embed, "rutter-core", version);
        for (PlatformId platform : platforms) {
            String artifact = BOOTSTRAPS.get(platform);
            if (artifact == null) {
                throw new InvalidUserDataException(
                        "No Rutter bootstrap exists for platform " + platform + ".");
            }
            addEmbed(project, embed, artifact, version);
        }
        return embed;
    }

    private static void addEmbed(Project project, Configuration embed, String artifact,
                                 String version) {
        embed.getDependencies().add(project.getDependencies()
                .create("io.github.intisy.rutter:" + artifact + ":" + version));
    }

    private static String pluginVersion() {
        InputStream stream = RutterPlugin.class.getResourceAsStream(
                "/rutter-gradle-version.properties");
        if (stream == null) {
            throw new IllegalStateException(
                    "rutter-gradle-version.properties is missing from the plugin jar");
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
            throw new IllegalStateException("rutter-gradle-version.properties declares no version");
        }
        return version.trim();
    }

    /**
     * @implNote {@code afterEvaluate} runs for every task invocation, including plain
     *     introspection such as {@code tasks} or {@code help}, so an unconfigured project (no
     *     {@code module(...)} declared yet) must not fail here; only invoking
     *     {@code rutterMetadata} itself should fail, with an actionable message.
     */
    private static void failWhenInvokedWithNoModules(TaskProvider<Task> metadata) {
        metadata.configure(task -> task.doFirst(ignored -> {
            throw new InvalidUserDataException("Rutter is applied but declares no modules. Add at"
                    + " least one rutter { module('...') { } } block.");
        }));
    }

    static Set<PlatformId> platformUnion(List<ResolvedModule> modules) {
        Set<PlatformId> platforms = new LinkedHashSet<PlatformId>();
        for (ResolvedModule module : modules) {
            platforms.addAll(module.platforms());
        }
        return platforms;
    }

    private static TaskProvider<RutterTextFileTask> writer(Project project, String taskName,
                                                           String relativePath, String content) {
        Provider<String> body = project.provider(() -> content);
        return project.getTasks().register(taskName, RutterTextFileTask.class, task -> {
            task.getContent().set(body);
            task.getDestination().set(
                    project.getLayout().getBuildDirectory().file("rutter/" + relativePath));
        });
    }
}
