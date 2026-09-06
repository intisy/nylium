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
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class RutterExtension {

    private static final Pattern MOD_ID = Pattern.compile("^[a-z][a-z0-9-_]{1,63}$");

    private final Project project;
    private final ModSpec mod;
    private final NamedDomainObjectContainer<ModuleSpec> modules;

    /**
     * @implNote {@code NamedDomainObjectContainer} iterates sorted by name, not insertion order,
     *     but the manifest's module index has to be declaration order, so that order is tracked
     *     separately here. Populated by {@code whenObjectAdded}, not by {@link #module}, so every
     *     entry point that creates a module (the DSL method, {@code modules.create(...)}, the
     *     Groovy container form) is covered, not only the one this class exposes itself.
     */
    private final List<String> moduleOrder = new ArrayList<String>();

    public RutterExtension(Project project) {
        this.project = project;
        this.mod = project.getObjects().newInstance(ModSpec.class);
        this.modules = project.getObjects().domainObjectContainer(ModuleSpec.class);
        this.modules.whenObjectAdded(spec -> moduleOrder.add(spec.getName()));
    }

    public void mod(Action<? super ModSpec> action) {
        action.execute(mod);
    }

    public ModSpec getMod() {
        return mod;
    }

    public void module(String name, Action<? super ModuleSpec> action) {
        action.execute(modules.create(name));
    }

    public NamedDomainObjectContainer<ModuleSpec> getModules() {
        return modules;
    }

    Project project() {
        return project;
    }

    String modulePrefix() {
        String id = requiredId();
        String prefix = mod.getModulePrefix().getOrNull();
        if (prefix == null) {
            return id;
        }
        if (prefix.trim().isEmpty()) {
            throw new InvalidUserDataException("Rutter mod '" + id
                    + "' declares a blank modulePrefix. Leave it unset to default to the mod id, or"
                    + " set it to a non-blank value.");
        }
        return prefix.trim();
    }

    /**
     * @implNote The pattern is Fabric Loader's own, and an id it refuses is a launch failure with
     *     an otherwise green build; the id also becomes the default module file name prefix.
     */
    private String requiredId() {
        String id = mod.getId().getOrNull();
        if (id == null || id.trim().isEmpty()) {
            throw new InvalidUserDataException(
                    "The Rutter mod id is not set. Set it in the rutter { mod { id = ... } } block.");
        }
        String trimmed = id.trim();
        if (!MOD_ID.matcher(trimmed).matches()) {
            throw new InvalidUserDataException("The Rutter mod id '" + trimmed + "' is not a legal"
                    + " mod id. It has to match " + MOD_ID.pattern() + ".");
        }
        return trimmed;
    }

    List<ResolvedModule> resolve() {
        String prefix = modulePrefix();
        FabricEnvironments.requireKnown(mod.getEnvironment().getOrNull());
        if (modules.isEmpty()) {
            throw new InvalidUserDataException(
                    "Rutter declares no modules. Add at least one rutter { module('...') { } } block.");
        }
        if (moduleOrder.size() != modules.size()) {
            throw new InvalidUserDataException("Rutter tracked " + moduleOrder.size()
                    + " declared modules but the container holds " + modules.size() + ".");
        }
        List<ResolvedModule> resolved = new ArrayList<ResolvedModule>();
        for (String name : moduleOrder) {
            resolved.add(resolveOne(modules.getByName(name), prefix));
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
            return Environment.valueOf(raw.trim().toUpperCase(Locale.ROOT));
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
