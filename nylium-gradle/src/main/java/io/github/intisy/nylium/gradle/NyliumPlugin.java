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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
        final Configuration embed = NyliumEmbed.configuration(project);

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

        final TaskProvider<NyliumDedupeTask> dedupe = project.getTasks().register(
                "nyliumDedupe", NyliumDedupeTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Collapses byte-identical entries shared by the module jars.");
            task.getOutputDirectory().set(
                    project.getLayout().getBuildDirectory().dir("nylium/dedupe"));
        });

        project.afterEvaluate(evaluated -> {
            if (nylium.getModules().isEmpty()) {
                failWhenInvokedWithNoModules(metadata);
                failWhenInvokedWithNoModules(verify);
                failWhenInvokedWithNoModules(universalJar);
                failWhenInvokedWithNoModules(dedupe);
                return;
            }
            List<ResolvedModule> modules = nylium.resolve();
            Set<PlatformId> platforms = platformUnion(modules);
            NyliumEmbed.addDependencies(evaluated, embed, platforms);
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
            boolean deduped = modules.get(0).path().endsWith(".index");
            if (deduped) {
                dedupe.configure(task -> {
                    for (ResolvedModule module : modules) {
                        task.getModules().add(moduleToDedupe(evaluated, module));
                    }
                });
            }
            universalJar.configure(jar -> UniversalJarFactory.fill(jar, evaluated, embed, generated,
                    modules, platforms, deduped, dedupe, archiveOperations));
            evaluated.getTasks().named(BasePlugin.ASSEMBLE_TASK_NAME)
                    .configure(assemble -> assemble.dependsOn(universalJar));
        });
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

    private static NyliumVerifyModulesTask.ModuleToVerify moduleToVerify(Project project,
                                                                        ResolvedModule module) {
        NyliumVerifyModulesTask.ModuleToVerify entry = project.getObjects()
                .newInstance(NyliumVerifyModulesTask.ModuleToVerify.class);
        entry.getModuleName().set(module.name());
        entry.getMixins().set(module.mixins());
        entry.getJar().set(module.jar());
        return entry;
    }

    private static NyliumDedupeTask.ModuleToDedupe moduleToDedupe(Project project,
                                                                 ResolvedModule module) {
        NyliumDedupeTask.ModuleToDedupe entry = project.getObjects()
                .newInstance(NyliumDedupeTask.ModuleToDedupe.class);
        String path = module.path();
        entry.getIndexFileName().set(path.substring(path.lastIndexOf('/') + 1));
        entry.getJar().set(module.jar());
        return entry;
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
