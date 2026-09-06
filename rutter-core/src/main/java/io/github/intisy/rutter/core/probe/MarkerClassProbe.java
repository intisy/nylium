package io.github.intisy.rutter.core.probe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Detects Minecraft's version range by testing for the presence of classes known to have
 * appeared in specific versions, newest marker first.
 *
 * @implNote This probe covers the pre-1.14 versions that shipped no {@code version.json}, where
 * no loader API exposes the running version either.
 * @implNote Presence is tested with {@link ClassLoader#loadClass(String)} rather than
 * {@link Class#forName(String, boolean, ClassLoader)}: {@code forName}'s native implementation
 * additionally verifies that the resolved class's name matches the requested name and throws
 * {@link ClassNotFoundException} on a mismatch, while {@code loadClass} does not. This also keeps
 * a matched marker class unlinked and uninitialised, which matters because it may be a Minecraft
 * registry class whose static initialiser must not run this early in startup.
 * @implNote This probe identifies an era floor, not an exact version, so a caller that needs to
 * distinguish versions within an era must not depend on it; module ranges that rely on this probe
 * are deliberately written broadly.
 */
public final class MarkerClassProbe implements VersionProbe {

    private static final Map<String, String> MARKERS = new LinkedHashMap<>();

    static {
        MARKERS.put("net.minecraft.util.registry.Registry", "1.13");
        MARKERS.put("net.minecraft.init.Blocks", "1.7.10");
    }

    private final ClassLoader loader;

    public MarkerClassProbe(ClassLoader loader) {
        this.loader = loader;
    }

    @Override
    public String name() {
        return "marker class presence";
    }

    @Override
    public Optional<String> detect() {
        for (Map.Entry<String, String> marker : MARKERS.entrySet()) {
            try {
                loader.loadClass(marker.getKey());
                return Optional.of(marker.getValue());
            } catch (ClassNotFoundException | LinkageError e) {
            }
        }
        return Optional.empty();
    }
}
