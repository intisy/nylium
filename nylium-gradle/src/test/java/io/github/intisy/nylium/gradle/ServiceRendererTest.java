package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.api.PlatformId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServiceRendererTest {

    private static Set<PlatformId> set(PlatformId... platforms) {
        return new LinkedHashSet<PlatformId>(Arrays.asList(platforms));
    }

    @Test
    void listsBothModLauncherServicesInProvenOrder() {
        assertEquals("io.github.intisy.nylium.bootstrap.ml8.NyliumMl8Service\n"
                        + "io.github.intisy.nylium.bootstrap.ml9.NyliumMl9TransformationService\n",
                ServiceRenderer.transformationServices(
                        set(PlatformId.MODLAUNCHER_8, PlatformId.MODLAUNCHER_9)));
    }

    @Test
    void listsOnlyTheDeclaredModLauncherService() {
        assertEquals("io.github.intisy.nylium.bootstrap.ml9.NyliumMl9TransformationService\n",
                ServiceRenderer.transformationServices(set(PlatformId.MODLAUNCHER_9)));
    }

    @Test
    void emitsNoServiceFileWithoutModLauncher() {
        assertNull(ServiceRenderer.transformationServices(set(PlatformId.FABRIC)));
        assertNull(ServiceRenderer.launchPlugins(set(PlatformId.FABRIC)));
    }

    @Test
    void emitsTheLaunchPluginOnlyForModLauncher9() {
        assertEquals("io.github.intisy.nylium.bootstrap.ml9.NyliumMl9LaunchPlugin\n",
                ServiceRenderer.launchPlugins(set(PlatformId.MODLAUNCHER_9)));
        assertNull(ServiceRenderer.launchPlugins(set(PlatformId.MODLAUNCHER_8)));
    }

    @Test
    void emitsNothingForAnEmptyPlatformSet() {
        assertNull(ServiceRenderer.transformationServices(Collections.<PlatformId>emptySet()));
    }
}
