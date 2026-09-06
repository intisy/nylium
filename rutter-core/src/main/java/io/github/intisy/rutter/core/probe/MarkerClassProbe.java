package io.github.intisy.rutter.core.probe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Detects Minecraft's version range by testing for the presence of classes known to have
 * appeared in specific versions, newest marker first.
 *
 * @implNote This probe covers the pre-1.14 versions that shipped no {@code version.json}, where
 * no loader API exposes the running version either. It reports a floor, not an exact version:
 * the returned string is the earliest release a matched marker is known to appear in, so callers
 * should treat this probe as a last resort and write dependent module ranges broadly.
 * @implNote Presence is tested with {@link ClassLoader#loadClass(String)} rather than
 * {@link Class#forName(String, boolean, ClassLoader)}: on this JDK, {@code forName} additionally
 * verifies that the resolved class's name matches the requested name and throws
 * {@link ClassNotFoundException} on a mismatch, while {@code loadClass} does not. Both behave
 * identically for a real classloader, which never resolves a name to a class with a different
 * name.
 */
public final class MarkerClassProbe implements VersionProbe {

    private static final Map<String, String> MARKERS = new LinkedHashMap<>();

    static {
        MARKERS.put("net.minecraft.util.registry.Registry", "1.13");
        MARKERS.put("net.minecraft.block.Blocks", "1.9");
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
                // empty
            }
        }
        return Optional.empty();
    }
}
