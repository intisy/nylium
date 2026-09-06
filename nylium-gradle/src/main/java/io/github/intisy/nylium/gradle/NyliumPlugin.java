package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.api.PlatformId;
import io.github.intisy.nylium.core.ModuleManifest;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.plugins.BasePlugin;
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

public class NyliumPlugin implements Plugin<Project> {

    private static final String TASK_GROUP = "nylium";

    private final ArchiveOperations archiveOperations;

    /**
     * @implNote Injected because the embedded jars are unpacked lazily, at task execution time;
     *     calling {@code Project.zipTree} there would capture the {@link Project} itself, which
     *     breaks the configuration cache, whereas this service is safe to hold across it.
     */
    @Inject
    public NyliumPlugin(ArchiveOperations archiveOperations) {
        this.archiveOperations = archiveOperations;
    }

    /**
     * @implNote Every task is registered here, so a consumer can reach one with
     *     {@code tasks.named(...)} from its own script body, but everything that depends on the
     *     declaration is applied from {@code afterEvaluate} through
     *     {@code TaskProvider.configure}, which runs immediately when the consumer has already
     *     realized the task and at realization otherwise. Realization itself is no guarantee of
     *     ordering: {@code tasks.getByName} and friends realize during script evaluation, so a
     *     registration action that read the declaration could see it half written.
     */
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(BasePlugin.class);

        final NyliumExtension nylium = project.getExtensions()
                .create("nylium", NyliumExtension.class, project.getObjects());
        final Configuration embed = embedConfiguration(project);

        final TaskProvider<Task> metadata = project.getTasks().register("nyliumMetadata", task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Generates the Nylium manifest and loader metadata.");
        });

        final TaskProvider<NyliumVerifyModulesTask> verify = project.getTasks().register(
                "nyliumVerifyModules", NyliumVerifyModulesTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Rejects module jars that cannot work at runtime.");
            task.getStamp().set(project.getLayout().getBuildDirectory()
                    .file("nylium-internal/nyliumVerifyModules.stamp"));
        });

        final TaskProvider<Jar> universalJar = project.getTasks().register(
                "nyliumUniversalJar", Jar.class, jar -> {
            jar.setGroup(TASK_GROUP);
            jar.setDescription("Assembles the Nylium universal jar.");
            jar.getArchiveClassifier().set("universal");
            // FAIL surfaces a colliding entry instead of silently keeping one, per SP-1's lesson.
            jar.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
            jar.dependsOn(verify);
        });

        project.afterEvaluate(evaluated -> {
            if (nylium.getModules().isEmpty()) {
                failWhenInvokedWithNoModules(metadata);
                failWhenInvokedWithNoModules(verify);
                failWhenInvokedWithNoModules(universalJar);
                return;
            }
            List<ResolvedModule> modules = nylium.resolve();
            Set<PlatformId> platforms = platformUnion(modules);
            addEmbedDependencies(evaluated, embed, platforms);
            List<GeneratedFile> generated = writers(evaluated, nylium, modules, platforms);
            metadata.configure(task -> {
                for (GeneratedFile file : generated) {
                    task.dependsOn(file.writer());
                }
            });
            verify.configure(task -> {
                for (ResolvedModule module : modules) {
                    task.getModules().add(moduleToVerify(evaluated, module));
                }
            });
            universalJar.configure(jar -> fillUniversalJar(jar, evaluated, embed, generated,
                    modules, platforms));
            evaluated.getTasks().named(BasePlugin.ASSEMBLE_TASK_NAME)
                    .configure(assemble -> assemble.dependsOn(universalJar));
        });
    }

    private void fillUniversalJar(Jar jar, Project project, Configuration embed,
                                  List<GeneratedFile> generated, List<ResolvedModule> modules,
                                  Set<PlatformId> platforms) {
        embedInto(jar, project, embed);
        for (GeneratedFile file : generated) {
            jar.from(file.destination(), copy -> copy.into(file.parentDirectory()));
        }
        for (ResolvedModule module : modules) {
            addModuleJar(jar, module);
        }
        if (platforms.contains(PlatformId.LAUNCHWRAPPER)) {
            jar.getManifest().getAttributes().put("TweakClass",
                    "io.github.intisy.nylium.bootstrap.launchwrapper.NyliumTweaker");
        }
    }

    private static List<GeneratedFile> writers(Project project, NyliumExtension nylium,
                                               List<ResolvedModule> modules,
                                               Set<PlatformId> platforms) {
        List<GeneratedFile> generated = new ArrayList<GeneratedFile>();
        generated.add(writer(project, "nyliumManifest", ModuleManifest.RESOURCE,
                ManifestRenderer.render(modules)));
        if (ResolvedModule.declaresFabric(modules)) {
            generated.add(writer(project, "nyliumFabricModJson", "fabric.mod.json",
                    FabricMetadataRenderer.render(nylium.getMod())));
        }
        String services = ServiceRenderer.transformationServices(platforms);
        if (services != null) {
            generated.add(writer(project, "nyliumTransformationServices",
                    "META-INF/services/cpw.mods.modlauncher.api.ITransformationService", services));
        }
        String launchPlugins = ServiceRenderer.launchPlugins(platforms);
        if (launchPlugins != null) {
            generated.add(writer(project, "nyliumLaunchPlugins",
                    "META-INF/services/cpw.mods.modlauncher.serviceapi.ILaunchPluginService",
                    launchPlugins));
        }
        return generated;
    }

    private static void addModuleJar(Jar jar, ResolvedModule module) {
        final String path = module.path();
        jar.from(module.jar(), copy -> {
            copy.into(path.substring(0, path.lastIndexOf('/')));
            copy.rename(".*", path.substring(path.lastIndexOf('/') + 1));
        });
    }

    private void embedInto(Jar jar, Project project, Configuration embed) {
        final ArchiveOperations archives = archiveOperations;
        jar.from(project.provider(() -> {
            List<Object> trees = new ArrayList<Object>();
            for (File artifact : embed.getFiles()) {
                trees.add(archives.zipTree(artifact));
            }
            return trees;
        }), copy -> copy.exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA",
                "META-INF/*.RSA", "META-INF/maven/**", "module-info.class"));
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

    private static NyliumVerifyModulesTask.ModuleToVerify moduleToVerify(Project project,
                                                                        ResolvedModule module) {
        NyliumVerifyModulesTask.ModuleToVerify entry = project.getObjects()
                .newInstance(NyliumVerifyModulesTask.ModuleToVerify.class);
        entry.getModuleName().set(module.name());
        entry.getMixins().set(module.mixins());
        entry.getJar().set(module.jar());
        return entry;
    }

    private static Configuration embedConfiguration(Project project) {
        Configuration embed = project.getConfigurations().maybeCreate("nyliumEmbed");
        embed.setCanBeConsumed(false);
        embed.setCanBeResolved(true);
        return embed;
    }

    private static void addEmbedDependencies(Project project, Configuration embed,
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

    /**
     * @implNote Applying the plugin speculatively has to stay inert, so an unconfigured project
     *     keeps {@code tasks}, {@code help}, {@code clean} and {@code build} working; that is also
     *     why {@code assemble} is only wired to the jar once a declaration exists. Only invoking
     *     one of Nylium's own tasks fails, with an actionable message.
     */
    private static void failWhenInvokedWithNoModules(TaskProvider<? extends Task> task) {
        task.configure(configured -> configured.doFirst(ignored -> {
            throw new InvalidUserDataException("Nylium is applied but declares no modules. Add at"
                    + " least one nylium { module('...') { } } block.");
        }));
    }

    static Set<PlatformId> platformUnion(List<ResolvedModule> modules) {
        Set<PlatformId> platforms = new LinkedHashSet<PlatformId>();
        for (ResolvedModule module : modules) {
            platforms.addAll(module.platforms());
        }
        return platforms;
    }

    private static GeneratedFile writer(Project project, String taskName, String relativePath,
                                        String content) {
        TaskProvider<NyliumTextFileTask> writer = project.getTasks()
                .register(taskName, NyliumTextFileTask.class, task -> {
                    task.setGroup(TASK_GROUP);
                    task.getContent().set(content);
                    task.getDestination().set(
                            project.getLayout().getBuildDirectory().file("nylium/" + relativePath));
                });
        return new GeneratedFile(relativePath, writer);
    }
}
