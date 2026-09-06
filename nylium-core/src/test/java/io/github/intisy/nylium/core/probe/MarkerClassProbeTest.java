package io.github.intisy.nylium.core.probe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MarkerClassProbeTest {

    private static ClassLoader loaderKnowing(final String... classNames) {
        final java.util.Set<String> known = new java.util.HashSet<>(java.util.Arrays.asList(classNames));
        return new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (known.contains(name)) {
                    return Object.class;
                }
                throw new ClassNotFoundException(name);
            }
        };
    }

    @Test
    void reportsTheNewestMarkerPresent() {
        ClassLoader loader = loaderKnowing(
                "net.minecraft.util.registry.Registry",
                "net.minecraft.init.Blocks");

        assertEquals("1.13", new MarkerClassProbe(loader).detect().orElseThrow(AssertionError::new));
    }

    @Test
    void reportsAnOlderMarkerWhenNewerOnesAreAbsent() {
        ClassLoader loader = loaderKnowing("net.minecraft.init.Blocks");

        assertEquals("1.7.10", new MarkerClassProbe(loader).detect().orElseThrow(AssertionError::new));
    }

    @Test
    void findsNothingWhenNoMarkerIsPresent() {
        assertFalse(new MarkerClassProbe(loaderKnowing()).detect().isPresent());
    }
}
