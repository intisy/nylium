package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.PlatformId;
import io.github.intisy.nylium.core.ModuleManifest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManifestRendererTest {

    private static ResolvedModule module(String name, PlatformId platform, String minecraft,
                                         List<String> mixins) {
        Set<PlatformId> platforms = new LinkedHashSet<PlatformId>();
        platforms.add(platform);
        return new ResolvedModule(name, "modules/testmod-" + name + ".jar", platforms, minecraft,
                null, mixins, 0, "io.github.intisy.nylium.testmod.TestModEntry", null);
    }

    @Test
    void reproducesTheProvenTestModManifest() throws IOException {
        List<ResolvedModule> modules = new ArrayList<ResolvedModule>();
        modules.add(module("1.21.11", PlatformId.FABRIC, "1.21.11", Collections.<String>emptyList()));
        modules.add(module("1.21.10", PlatformId.FABRIC, "1.21.10", Collections.<String>emptyList()));
        modules.add(module("1.7.10", PlatformId.LAUNCHWRAPPER, "[1.7,1.12.2]", Collections.<String>emptyList()));
        modules.add(module("1.16.5", PlatformId.MODLAUNCHER_8, "[1.13,1.16.5]", Collections.<String>emptyList()));
        modules.add(module("1.21.11-ml9", PlatformId.MODLAUNCHER_9, "1.21.11",
                Collections.singletonList("mixins.nylium-testmod-ml9.json")));

        assertEquals(normalize(expected()), normalize(ManifestRenderer.render(modules)));
    }

    @Test
    void ordersMultiplePlatformsAndOmitsDefaults() {
        Set<PlatformId> platforms = new LinkedHashSet<PlatformId>(
                Arrays.asList(PlatformId.FABRIC, PlatformId.LAUNCHWRAPPER));
        ResolvedModule module = new ResolvedModule("wide", "modules/demo-wide.jar", platforms,
                "[1.7,)", null, Collections.<String>emptyList(), 0, null, null);
        assertEquals("module.0.path=modules/demo-wide.jar\n"
                + "module.0.platforms=FABRIC,LAUNCHWRAPPER\n"
                + "module.0.minecraft=[1.7,)\n", ManifestRenderer.render(
                        Collections.singletonList(module)));
    }

    @Test
    void rendersWhatTheKernelCanRead() {
        List<ResolvedModule> modules = new ArrayList<ResolvedModule>();
        modules.add(module("1.21.11", PlatformId.FABRIC, "1.21.11", Collections.<String>emptyList()));
        ModuleManifest manifest = ModuleManifest.read(
                new StringReader(ManifestRenderer.render(modules)));
        assertEquals(1, manifest.modules().size());
        assertEquals("modules/testmod-1.21.11.jar", manifest.modules().get(0).path());
    }

    @Test
    void rendersEnvironmentTheKernelCanRead() {
        Set<PlatformId> platforms = new LinkedHashSet<PlatformId>();
        platforms.add(PlatformId.FABRIC);
        ResolvedModule module = new ResolvedModule("server-only", "modules/demo-server.jar",
                platforms, "1.21.11", Environment.SERVER, Collections.<String>emptyList(), 0, null, null);
        ModuleManifest manifest = ModuleManifest.read(
                new StringReader(ManifestRenderer.render(Collections.singletonList(module))));
        assertEquals(Optional.of(Environment.SERVER), manifest.modules().get(0).environment());
    }

    @Test
    void rendersPriorityTheKernelCanRead() {
        Set<PlatformId> platforms = new LinkedHashSet<PlatformId>();
        platforms.add(PlatformId.FABRIC);
        ResolvedModule module = new ResolvedModule("prioritized", "modules/demo-priority.jar",
                platforms, "1.21.11", null, Collections.<String>emptyList(), 7, null, null);
        ModuleManifest manifest = ModuleManifest.read(
                new StringReader(ManifestRenderer.render(Collections.singletonList(module))));
        assertEquals(7, manifest.modules().get(0).priority());
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").trim();
    }

    private static String expected() throws IOException {
        InputStream stream = ManifestRendererTest.class.getResourceAsStream(
                "/expected-testmod-manifest.properties");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        stream.close();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
