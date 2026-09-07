package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.api.PlatformId;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.jvm.tasks.Jar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class UniversalJarFactory {

    private UniversalJarFactory() {
    }

    static void fill(Jar jar, Project project, Configuration embed,
                                  List<GeneratedFile> generated, List<ResolvedModule> modules,
                                  Set<PlatformId> platforms, boolean deduped,
                                  TaskProvider<NyliumDedupeTask> dedupe,
                                  ArchiveOperations archiveOperations) {
        embedInto(jar, project, embed, archiveOperations);
        for (GeneratedFile file : generated) {
            jar.from(file.destination(), copy -> copy.into(file.parentDirectory()));
        }
        if (deduped) {
            String path = modules.get(0).path();
            addDedupedModules(jar, dedupe, path.substring(0, path.lastIndexOf('/')));
        } else {
            for (ResolvedModule module : modules) {
                addModuleJar(jar, module);
            }
        }
        if (platforms.contains(PlatformId.LAUNCHWRAPPER)) {
            jar.getManifest().getAttributes().put("TweakClass",
                    "io.github.intisy.nylium.bootstrap.launchwrapper.NyliumTweaker");
        }
    }

    private static void addDedupedModules(Jar jar, TaskProvider<NyliumDedupeTask> dedupe,
                                          String indexDirectory) {
        jar.dependsOn(dedupe);
        jar.from(dedupe.flatMap(NyliumDedupeTask::getOutputDirectory)
                        .map(directory -> directory.dir(DedupeWriter.OBJECTS)),
                copy -> copy.into("nylium/objects"));
        jar.from(dedupe.flatMap(NyliumDedupeTask::getOutputDirectory)
                        .map(directory -> directory.dir(DedupeWriter.INDEXES)),
                copy -> copy.into(indexDirectory));
    }

    private static void addModuleJar(Jar jar, ResolvedModule module) {
        final String path = module.path();
        jar.from(module.jar(), copy -> {
            copy.into(path.substring(0, path.lastIndexOf('/')));
            copy.rename(".*", path.substring(path.lastIndexOf('/') + 1));
        });
    }

    private static void embedInto(Jar jar, Project project, Configuration embed,
                                  ArchiveOperations archiveOperations) {
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
}
