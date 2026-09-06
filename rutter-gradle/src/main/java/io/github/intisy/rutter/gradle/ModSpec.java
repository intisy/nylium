package io.github.intisy.rutter.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

public abstract class ModSpec {

    public abstract Property<String> getId();

    public abstract Property<String> getName();

    public abstract Property<String> getVersion();

    public abstract Property<String> getDescription();

    public abstract Property<String> getLicense();

    public abstract Property<String> getIcon();

    public abstract Property<String> getEnvironment();

    public abstract Property<String> getFabricLoaderVersion();

    /**
     * A Fabric version predicate for Minecraft, left unset by default so the universal jar loads on
     * any version and Rutter, not the loader, reports an unsupported one.
     */
    public abstract Property<String> getMinecraftDependency();

    /** Prefix for the module file names inside the jar; defaults to {@link #getId()}. */
    public abstract Property<String> getModulePrefix();

    public abstract ListProperty<String> getAuthors();

    public abstract MapProperty<String, String> getContact();
}
