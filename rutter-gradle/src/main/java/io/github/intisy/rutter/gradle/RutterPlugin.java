package io.github.intisy.rutter.gradle;

import io.github.intisy.rutter.api.PlatformId;
import io.github.intisy.rutter.core.ModuleManifest;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class RutterPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        final RutterExtension rutter =
                project.getExtensions().create("rutter", RutterExtension.class, project);

        final TaskProvider<Task> metadata = project.getTasks().register("rutterMetadata", task ->
                task.setDescription("Generates the Rutter manifest and loader metadata."));

        project.afterEvaluate(evaluated -> {
            List<ResolvedModule> modules = rutter.resolve();
            Set<PlatformId> platforms = platformUnion(modules);

            List<TaskProvider<RutterTextFileTask>> writers =
                    new java.util.ArrayList<TaskProvider<RutterTextFileTask>>();
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
        });
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
