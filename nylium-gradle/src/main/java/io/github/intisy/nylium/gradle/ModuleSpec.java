package io.github.intisy.nylium.gradle;

import org.gradle.api.Named;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

public abstract class ModuleSpec implements Named {

    private final String name;

    public ModuleSpec(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    public abstract RegularFileProperty getJar();

    public abstract ListProperty<String> getPlatforms();

    public abstract Property<String> getMinecraft();

    public abstract Property<String> getEnvironment();

    public abstract ListProperty<String> getMixins();

    public abstract Property<Integer> getPriority();

    public abstract Property<String> getEntrypoint();
}
