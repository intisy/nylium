package io.github.intisy.rutter.smoke;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModLauncher9SmokeTest {

    private static final String FORGE_VERSION = "1.21.11-61.1.5";

    /**
     * @implNote Forge 1.21.11 needs Java 21, unlike the older loaders' Java 8 floor, so the launcher
     * path comes from smoke/build.gradle's per-version toolchain resolution rather than this test's
     * own java.home.
     */
    private static String java21Executable() {
        String path = System.getProperty("rutter.smoke.java.21");
        if (path == null) {
            throw new IllegalStateException(
                    "System property rutter.smoke.java.21 is not set; smoke/build.gradle must expose "
                            + "a Java 21 toolchain launcher.");
        }
        return path;
    }

    /**
     * @implNote {@code ILaunchPluginService} is discovered only from ModLauncher's boot module
     * layer, which ForgeBootstrap builds from the literal JVM classpath, not from Forge's own
     * mods/ folder scanning. So unlike the other three loaders, the universal jar here is launched
     * with an explicit -cp plus the shim's real main class rather than -jar; see the task report.
     */
    @Test
    void loadsThe12111Ml9ModuleAndAppliesItsMixin() throws Exception {
        Path server = Paths.get(System.getProperty("rutter.smoke.servers")).resolve("forge-1.21.11");
        Path marker = server.resolve("rutter-marker.txt");
        Path mixinMarker = server.resolve("rutter-mixin-marker.txt");
        Files.deleteIfExists(marker);

        String classpath = "rutter-testmod-universal.jar" + File.pathSeparator
                + "forge-" + FORGE_VERSION + "-shim.jar";

        String mixinResult = ServerSmokeHarness.run(server, Arrays.asList(
                java21Executable(),
                "-Drutter.smoke.marker=" + marker.toAbsolutePath(),
                "-Drutter.smoke.mixinMarker=" + mixinMarker.toAbsolutePath(),
                "-Djava.net.preferIPv6Addresses=system",
                "-cp", classpath,
                "net.minecraftforge.bootstrap.shim.Main",
                "nogui"), mixinMarker, Duration.ofMinutes(4));

        assertEquals("mixin=applied", mixinResult);

        String moduleResult = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
        assertEquals("module=1.21.11-ml9", moduleResult);
    }
}
