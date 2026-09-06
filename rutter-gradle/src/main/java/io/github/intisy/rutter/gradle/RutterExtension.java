package io.github.intisy.rutter.gradle;

import io.github.intisy.rutter.api.Environment;
import io.github.intisy.rutter.api.PlatformId;
import io.github.intisy.rutter.api.RutterException;
import io.github.intisy.rutter.core.VersionRange;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class RutterExtension {

    private final Project project;
    private final ModSpec mod;
    private final NamedDomainObjectContainer<ModuleSpec> modules;

    public RutterExtension(Project project) {
        this.project = project;
        this.mod = project.getObjects().newInstance(ModSpec.class);
        this.modules = project.getObjects().domainObjectContainer(ModuleSpec.class);
    }

    public void mod(Action<? super ModSpec> action) {
        action.execute(mod);
    }

    public ModSpec getMod() {
        return mod;
    }

    public void module(String name, Action<? super ModuleSpec> action) {
        action.execute(modules.maybeCreate(name));
    }

    public NamedDomainObjectContainer<ModuleSpec> getModules() {
        return modules;
    }

    Project project() {
        return project;
    }

    String modulePrefix() {
        return mod.getModulePrefix().getOrElse(requiredId());
    }

    private String requiredId() {
        String id = mod.getId().getOrNull();
        if (id == null || id.trim().isEmpty()) {
            throw new InvalidUserDataException(
                    "The Rutter mod id is not set. Set it in the rutter { mod { id = ... } } block.");
        }
        return id.trim();
    }

    List<ResolvedModule> resolve() {
        String prefix = modulePrefix();
        if (modules.isEmpty()) {
            throw new InvalidUserDataException(
                    "Rutter declares no modules. Add at least one rutter { module('...') { } } block.");
        }
        List<ResolvedModule> resolved = new ArrayList<ResolvedModule>();
        for (ModuleSpec spec : modules) {
            resolved.add(resolveOne(spec, prefix));
        }
        return resolved;
    }

    private ResolvedModule resolveOne(ModuleSpec spec, String prefix) {
        String name = spec.getName();
        if (!spec.getJar().isPresent()) {
            throw new InvalidUserDataException("Rutter module '" + name
                    + "' has no jar. Set module.jar to the built, already remapped module jar.");
        }
        Set<PlatformId> platforms = Platforms.parse(name, spec.getPlatforms().get());
        String minecraft = required(spec.getMinecraft().getOrNull(), name, "minecraft");
        validateRange(name, minecraft);
        return new ResolvedModule(name, "modules/" + prefix + "-" + name + ".jar", platforms,
                minecraft, environment(name, spec.getEnvironment().getOrNull()),
                spec.getMixins().get(), spec.getPriority().getOrElse(0),
                emptyToNull(spec.getEntrypoint().getOrNull()), spec.getJar());
    }

    /**
     * @implNote Parsed with the kernel's own parser rather than a copy, so a range the game would
     *     reject cannot pass the build; {@code VersionRange.parse} signals with a subclass of
     *     {@code RutterException}.
     */
    private void validateRange(String moduleName, String raw) {
        try {
            VersionRange.parse(raw);
        } catch (RutterException e) {
            throw new InvalidUserDataException("Rutter module '" + moduleName
                    + "' declares an unusable Minecraft range '" + raw + "': " + e.getMessage(), e);
        }
    }

    private Environment environment(String moduleName, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Environment.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidUserDataException("Rutter module '" + moduleName
                    + "' declares an unknown environment '" + raw + "'. Known environments are "
                    + Arrays.toString(Environment.values()) + ".", e);
        }
    }

    private String required(String value, String moduleName, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new InvalidUserDataException(
                    "Rutter module '" + moduleName + "' is missing '" + field + "'.");
        }
        return value.trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
