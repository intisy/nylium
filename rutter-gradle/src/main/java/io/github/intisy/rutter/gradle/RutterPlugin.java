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

public class RutterPlugin implements Plugin<Project> {

    private static final String TASK_GROUP = "rutter";

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

        final RutterExtension rutter = project.getExtensions()
                .create("rutter", RutterExtension.class, project.getObjects());
        final Configuration embed = embedConfiguration(project);

        final TaskProvider<Task> metadata = project.getTasks().register("rutterMetadata", task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Generates the Rutter manifest and loader metadata.");
        });

        final TaskProvider<RutterVerifyModulesTask> verify = project.getTasks().register(
                "rutterVerifyModules", RutterVerifyModulesTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Rejects module jars that cannot work at runtime.");
            task.getStamp().set(project.getLayout().getBuildDirectory()
                    .file("rutter-internal/rutterVerifyModules.stamp"));
        });

        final TaskProvider<Jar> universalJar = project.getTasks().register(
                "rutterUniversalJar", Jar.class, jar -> {
            jar.setGroup(TASK_GROUP);
            jar.setDescription("Assembles the Rutter universal jar.");
            jar.getArchiveClassifier().set("universal");
            // FAIL surfaces a colliding entry instead of silently keeping one, per SP-1's lesson.
            jar.setDuplicatesStrategy(DuplicatesStrategy.FAIL);
            jar.dependsOn(verify);
        });

        project.afterEvaluate(evaluated -> {
            if (rutter.getModules().isEmpty()) {
                failWhenInvokedWithNoModules(metadata);
                failWhenInvokedWithNoModules(verify);
                failWhenInvokedWithNoModules(universalJar);
                return;
            }
            List<ResolvedModule> modules = rutter.resolve();
            Set<PlatformId> platforms = platformUnion(modules);
            addEmbedDependencies(evaluated, embed, platforms);
            List<GeneratedFile> generated = writers(evaluated, rutter, modules, platforms);
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
                    "io.github.intisy.rutter.bootstrap.launchwrapper.RutterTweaker");
        }
    }

    private static List<GeneratedFile> writers(Project project, RutterExtension rutter,
                                               List<ResolvedModule> modules,
                                               Set<PlatformId> platforms) {
        List<GeneratedFile> generated = new ArrayList<GeneratedFile>();
        generated.add(writer(project, "rutterManifest", ModuleManifest.RESOURCE,
                ManifestRenderer.render(modules)));
        if (ResolvedModule.declaresFabric(modules)) {
            generated.add(writer(project, "rutterFabricModJson", "fabric.mod.json",
                    FabricMetadataRenderer.render(rutter.getMod())));
        }
        String services = ServiceRenderer.transformationServices(platforms);
        if (services != null) {
            generated.add(writer(project, "rutterTransformationServices",
                    "META-INF/services/cpw.mods.modlauncher.api.ITransformationService", services));
        }
        String launchPlugins = ServiceRenderer.launchPlugins(platforms);
        if (launchPlugins != null) {
            generated.add(writer(project, "rutterLaunchPlugins",
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
        map.put(PlatformId.FABRIC, "rutter-bootstrap-fabric");
        map.put(PlatformId.LAUNCHWRAPPER, "rutter-bootstrap-launchwrapper");
        map.put(PlatformId.MODLAUNCHER_8, "rutter-bootstrap-modlauncher8");
        map.put(PlatformId.MODLAUNCHER_9, "rutter-bootstrap-modlauncher9");
        return map;
    }

    private static RutterVerifyModulesTask.ModuleToVerify moduleToVerify(Project project,
                                                                        ResolvedModule module) {
        RutterVerifyModulesTask.ModuleToVerify entry = project.getObjects()
                .newInstance(RutterVerifyModulesTask.ModuleToVerify.class);
        entry.getModuleName().set(module.name());
        entry.getMixins().set(module.mixins());
        entry.getJar().set(module.jar());
        return entry;
    }

    private static Configuration embedConfiguration(Project project) {
        Configuration embed = project.getConfigurations().maybeCreate("rutterEmbed");
        embed.setCanBeConsumed(false);
        embed.setCanBeResolved(true);
        return embed;
    }

    private static void addEmbedDependencies(Project project, Configuration embed,
                                             Set<PlatformId> platforms) {
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
     * @implNote Applying the plugin speculatively has to stay inert, so an unconfigured project
     *     keeps {@code tasks}, {@code help}, {@code clean} and {@code build} working; that is also
     *     why {@code assemble} is only wired to the jar once a declaration exists. Only invoking
     *     one of Rutter's own tasks fails, with an actionable message.
     */
    private static void failWhenInvokedWithNoModules(TaskProvider<? extends Task> task) {
        task.configure(configured -> configured.doFirst(ignored -> {
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

    private static GeneratedFile writer(Project project, String taskName, String relativePath,
                                        String content) {
        TaskProvider<RutterTextFileTask> writer = project.getTasks()
                .register(taskName, RutterTextFileTask.class, task -> {
                    task.setGroup(TASK_GROUP);
                    task.getContent().set(content);
                    task.getDestination().set(
                            project.getLayout().getBuildDirectory().file("rutter/" + relativePath));
                });
        return new GeneratedFile(relativePath, writer);
    }
}
