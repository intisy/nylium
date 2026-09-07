package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.PlatformId;
import io.github.intisy.nylium.api.NyliumException;
import io.github.intisy.nylium.core.VersionRange;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public class NyliumExtension {

    private static final Pattern MOD_ID = Pattern.compile("^[a-z][a-z0-9-_]{1,63}$");

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

    private final Property<Boolean> dedupe;

    public NyliumExtension(ObjectFactory objects) {
        this.mod = objects.newInstance(ModSpec.class);
        this.modules = objects.domainObjectContainer(ModuleSpec.class);
        this.modules.whenObjectAdded(spec -> moduleOrder.add(spec.getName()));
        this.dedupe = objects.property(Boolean.class);
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

    /**
     * Whether byte-identical entries shared by the module jars are stored once and each module
     * shipped as an index. Defaults to on once a second module exists, and off for a single module,
     * where an index is pure indirection.
     */
    public Property<Boolean> getDedupe() {
        return dedupe;
    }

    boolean dedupeEnabled() {
        return dedupe.getOrElse(modules.size() >= 2);
    }

    String modulePrefix() {
        String id = requiredId();
        String prefix = mod.getModulePrefix().getOrNull();
        if (prefix == null) {
            return id;
        }
        if (prefix.trim().isEmpty()) {
            throw new InvalidUserDataException("Nylium mod '" + id
                    + "' declares a blank modulePrefix. Leave it unset to default to the mod id, or"
                    + " set it to a non-blank value.");
        }
        return prefix.trim();
    }

    private String requiredId() {
        String id = mod.getId().getOrNull();
        if (id == null || id.trim().isEmpty()) {
            throw new InvalidUserDataException(
                    "The Nylium mod id is not set. Set it in the nylium { mod { id = ... } } block.");
        }
        return id.trim();
    }

    /**
     * @implNote The pattern is Fabric Loader's own, and an id it refuses is a launch failure with
     *     an otherwise green build. Checked only when a module declares FABRIC, which is the same
     *     condition that generates the {@code fabric.mod.json} the id lands in: to a mod targeting
     *     only LaunchWrapper or ModLauncher the id is just a module file name prefix, and Fabric's
     *     rule is none of its business.
     */
    private void requireFabricLegalId() {
        String id = requiredId();
        if (!MOD_ID.matcher(id).matches()) {
            throw new InvalidUserDataException("The Nylium mod id '" + id + "' is not a legal"
                    + " fabric mod id. It has to match " + MOD_ID.pattern() + ".");
        }
    }

    List<ResolvedModule> resolve() {
        String prefix = modulePrefix();
        FabricEnvironments.requireKnown(mod.getEnvironment().getOrNull());
        if (modules.isEmpty()) {
            throw new InvalidUserDataException(
                    "Nylium declares no modules. Add at least one nylium { module('...') { } } block.");
        }
        if (moduleOrder.size() != modules.size()) {
            throw new InvalidUserDataException("Nylium tracked " + moduleOrder.size()
                    + " declared modules but the container holds " + modules.size() + ".");
        }
        boolean deduped = dedupeEnabled();
        List<ResolvedModule> resolved = new ArrayList<ResolvedModule>();
        for (String name : moduleOrder) {
            resolved.add(resolveOne(modules.getByName(name), prefix, deduped));
        }
        if (ResolvedModule.declaresFabric(resolved)) {
            requireFabricLegalId();
        }
        return resolved;
    }

    private ResolvedModule resolveOne(ModuleSpec spec, String prefix, boolean deduped) {
        String name = spec.getName();
        if (!spec.getJar().isPresent()) {
            throw new InvalidUserDataException("Nylium module '" + name
                    + "' has no jar. Set module.jar to the built, already remapped module jar.");
        }
        Set<PlatformId> platforms = Platforms.parse(name, spec.getPlatforms().get());
        String minecraft = required(spec.getMinecraft().getOrNull(), name, "minecraft");
        validateRange(name, minecraft);
        String suffix = deduped ? ".index" : ".jar";
        return new ResolvedModule(name, "modules/" + prefix + "-" + name + suffix, platforms,
                minecraft, environment(name, spec.getEnvironment().getOrNull()),
                spec.getMixins().get(), spec.getPriority().getOrElse(0),
                emptyToNull(spec.getEntrypoint().getOrNull()), spec.getJar());
    }

    /**
     * @implNote Parsed with the kernel's own parser rather than a copy, so a range the game would
     *     reject cannot pass the build; {@code VersionRange.parse} signals with a subclass of
     *     {@code NyliumException}.
     */
    private void validateRange(String moduleName, String raw) {
        try {
            VersionRange.parse(raw);
        } catch (NyliumException e) {
            throw new InvalidUserDataException("Nylium module '" + moduleName
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
            throw new InvalidUserDataException("Nylium module '" + moduleName
                    + "' declares an unknown environment '" + raw + "'. Known environments are "
                    + Arrays.toString(Environment.values()) + ".", e);
        }
    }

    private String required(String value, String moduleName, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new InvalidUserDataException(
                    "Nylium module '" + moduleName + "' is missing '" + field + "'.");
        }
        return value.trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
