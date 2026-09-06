package io.github.intisy.rutter.core;

import io.github.intisy.rutter.api.Environment;
import io.github.intisy.rutter.api.NoCompatibleModuleException;
import io.github.intisy.rutter.api.PlatformId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RutterKernelTest {

    private static final String MANIFEST =
            "module.0.path=modules/mod-1.21.11.jar\n"
                    + "module.0.platforms=FABRIC,MODLAUNCHER_9\n"
                    + "module.0.minecraft=1.21.11\n"
                    + "module.0.mixins=mixins.mod.json,mixins.mod.extra.json\n"
                    + "module.1.path=modules/mod-1.21.10.jar\n"
                    + "module.1.platforms=FABRIC\n"
                    + "module.1.minecraft=1.21.10\n"
                    + "module.1.mixins=mixins.mod.json\n";

    private static byte[] tinyJar(String marker) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("marker.txt"));
            jar.write(marker.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    /**
     * @implNote Returns URLClassLoader, not ClassLoader, so callers can close it; an unclosed
     * loader keeps the jar file handle open and Windows then refuses to delete the JUnit temp dir.
     */
    private static URLClassLoader outerJar(Path dir) throws Exception {
        Path outer = dir.resolve("outer.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outer))) {
            jar.putNextEntry(new JarEntry(ModuleManifest.RESOURCE));
            jar.write(MANIFEST.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("modules/mod-1.21.11.jar"));
            jar.write(tinyJar("newer"));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("modules/mod-1.21.10.jar"));
            jar.write(tinyJar("older"));
            jar.closeEntry();
        }
        return new URLClassLoader(new URL[]{outer.toUri().toURL()}, null);
    }

    @Test
    void loadsTheModuleMatchingTheNativeProbe(@TempDir Path dir) throws Exception {
        FakePlatform platform = new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, "1.21.11");

        try (URLClassLoader source = outerJar(dir)) {
            ModuleDescriptor loaded = RutterKernel.boot(platform, source, dir.resolve("cache"));

            assertEquals("modules/mod-1.21.11.jar", loaded.path());
            assertEquals(1, platform.classpathAdditions.size());
            assertTrue(Files.isRegularFile(platform.classpathAdditions.get(0)));
        }
    }

    @Test
    void discriminatesBetweenTwoVersionsInOneJar(@TempDir Path dir) throws Exception {
        try (URLClassLoader source = outerJar(dir)) {
            assertEquals("modules/mod-1.21.10.jar",
                    RutterKernel.boot(new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, "1.21.10"),
                            source, dir.resolve("cache")).path());
            assertEquals("modules/mod-1.21.11.jar",
                    RutterKernel.boot(new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, "1.21.11"),
                            source, dir.resolve("cache")).path());
        }
    }

    @Test
    void registersEveryMixinConfigOfTheChosenModule(@TempDir Path dir) throws Exception {
        FakePlatform platform = new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, "1.21.11");

        try (URLClassLoader source = outerJar(dir)) {
            RutterKernel.boot(platform, source, dir.resolve("cache"));

            assertEquals(java.util.Arrays.asList("mixins.mod.json", "mixins.mod.extra.json"),
                    platform.registeredConfigs);
        }
    }

    @Test
    void classpathsTheModuleBeforeRegisteringItsMixins(@TempDir Path dir) throws Exception {
        FakePlatform platform = new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, "1.21.11");

        try (URLClassLoader source = outerJar(dir)) {
            RutterKernel.boot(platform, source, dir.resolve("cache"));

            assertEquals("classpath", platform.callOrder.get(0));
        }
    }

    @Test
    void selectsPerPlatformFromTheSameManifest(@TempDir Path dir) throws Exception {
        try (URLClassLoader source = outerJar(dir)) {
            assertEquals("modules/mod-1.21.11.jar",
                    RutterKernel.boot(new FakePlatform(PlatformId.MODLAUNCHER_9, Environment.SERVER, "1.21.11"),
                            source, dir.resolve("cache")).path());

            assertThrows(NoCompatibleModuleException.class,
                    () -> RutterKernel.boot(new FakePlatform(PlatformId.MODLAUNCHER_9, Environment.SERVER, "1.21.10"),
                            source, dir.resolve("cache")));
        }
    }

    @Test
    void fallsBackToTheClasspathProbesWhenThePlatformCannotTell(@TempDir Path dir) throws Exception {
        Path outerDir = Files.createDirectories(dir.resolve("outer"));
        Path outer = outerDir.resolve("outer.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outer))) {
            jar.putNextEntry(new JarEntry(ModuleManifest.RESOURCE));
            jar.write(MANIFEST.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("modules/mod-1.21.11.jar"));
            jar.write(tinyJar("newer"));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("version.json"));
            jar.write("{\"id\":\"1.21.11\"}".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        FakePlatform platform = new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, null);

        try (URLClassLoader source = new URLClassLoader(new URL[]{outer.toUri().toURL()}, null)) {
            assertEquals("modules/mod-1.21.11.jar",
                    RutterKernel.boot(platform, source, dir.resolve("cache")).path());
        }
    }

    @Test
    void defersMixinRegistrationUntilThePlatformSaysTheModuleIsLoadable(@TempDir Path dir) throws Exception {
        DeferringFakePlatform platform = new DeferringFakePlatform(PlatformId.MODLAUNCHER_9, Environment.CLIENT, "1.21.11");

        try (URLClassLoader source = outerJar(dir)) {
            RutterKernel.boot(platform, source, dir.resolve("cache"));

            assertTrue(platform.registeredConfigs.isEmpty());
            assertEquals(1, platform.classpathAdditions.size());

            platform.capturedActivation.run();

            assertEquals(java.util.Arrays.asList("mixins.mod.json", "mixins.mod.extra.json"),
                    platform.registeredConfigs);
        }
    }

    public static class RecordingEntrypoint {
        static int invocations = 0;
        static java.util.List<String> callOrder;

        public static void rutterInit() {
            invocations++;
            if (callOrder != null) {
                callOrder.add("entrypoint");
            }
        }
    }

    @Test
    void invokesTheModuleEntrypointAfterMixinRegistration(@TempDir Path dir) throws Exception {
        RecordingEntrypoint.invocations = 0;
        String manifest = "module.0.path=modules/mod-1.21.11.jar\n"
                + "module.0.platforms=FABRIC\n"
                + "module.0.minecraft=1.21.11\n"
                + "module.0.mixins=mixins.mod.json\n"
                + "module.0.entrypoint=" + RecordingEntrypoint.class.getName() + "\n";
        Path outer = dir.resolve("outer.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outer))) {
            jar.putNextEntry(new JarEntry(ModuleManifest.RESOURCE));
            jar.write(manifest.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("modules/mod-1.21.11.jar"));
            jar.write(tinyJar("newer"));
            jar.closeEntry();
        }
        FakePlatform platform = new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, "1.21.11");
        RecordingEntrypoint.callOrder = platform.callOrder;

        try (URLClassLoader source = new URLClassLoader(new URL[]{outer.toUri().toURL()},
                getClass().getClassLoader())) {
            RutterKernel.boot(platform, source, dir.resolve("cache"));

            assertEquals(1, RecordingEntrypoint.invocations);
            assertEquals(java.util.Arrays.asList("classpath", "mixin:mixins.mod.json", "entrypoint"),
                    platform.callOrder);
        }
    }

    @Test
    void aModuleWithoutAnEntrypointStillBoots(@TempDir Path dir) throws Exception {
        FakePlatform platform = new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, "1.21.11");

        try (URLClassLoader source = outerJar(dir)) {
            assertEquals("modules/mod-1.21.11.jar",
                    RutterKernel.boot(platform, source, dir.resolve("cache")).path());
        }
    }

    @Test
    void aBrokenEntrypointNamesTheClassAndTheModule(@TempDir Path dir) throws Exception {
        String manifest = "module.0.path=modules/mod-1.21.11.jar\n"
                + "module.0.platforms=FABRIC\n"
                + "module.0.minecraft=1.21.11\n"
                + "module.0.entrypoint=com.example.Absent\n";
        Path outer = dir.resolve("outer.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outer))) {
            jar.putNextEntry(new JarEntry(ModuleManifest.RESOURCE));
            jar.write(manifest.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("modules/mod-1.21.11.jar"));
            jar.write(tinyJar("newer"));
            jar.closeEntry();
        }
        FakePlatform platform = new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, "1.21.11");

        try (URLClassLoader source = new URLClassLoader(new URL[]{outer.toUri().toURL()}, null)) {
            io.github.intisy.rutter.api.RutterException thrown =
                    assertThrows(io.github.intisy.rutter.api.RutterException.class,
                            () -> RutterKernel.boot(platform, source, dir.resolve("cache")));
            assertTrue(thrown.getMessage().contains("com.example.Absent"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("modules/mod-1.21.11.jar"), thrown.getMessage());
        }
    }

    @Test
    void defersEntrypointInvocationUntilThePlatformSaysTheModuleIsLoadable(@TempDir Path dir) throws Exception {
        RecordingEntrypoint.invocations = 0;
        RecordingEntrypoint.callOrder = null;
        String manifest = "module.0.path=modules/mod-1.21.11.jar\n"
                + "module.0.platforms=MODLAUNCHER_9\n"
                + "module.0.minecraft=1.21.11\n"
                + "module.0.mixins=mixins.mod.json\n"
                + "module.0.entrypoint=" + RecordingEntrypoint.class.getName() + "\n";
        Path outer = dir.resolve("outer.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outer))) {
            jar.putNextEntry(new JarEntry(ModuleManifest.RESOURCE));
            jar.write(manifest.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("modules/mod-1.21.11.jar"));
            jar.write(tinyJar("newer"));
            jar.closeEntry();
        }
        DeferringFakePlatform platform =
                new DeferringFakePlatform(PlatformId.MODLAUNCHER_9, Environment.CLIENT, "1.21.11");

        try (URLClassLoader source = new URLClassLoader(new URL[]{outer.toUri().toURL()},
                getClass().getClassLoader())) {
            RutterKernel.boot(platform, source, dir.resolve("cache"));

            assertEquals(0, RecordingEntrypoint.invocations);

            platform.capturedActivation.run();

            assertEquals(1, RecordingEntrypoint.invocations);
        }
    }
}
