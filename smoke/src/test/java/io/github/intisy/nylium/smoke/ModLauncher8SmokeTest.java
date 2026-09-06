package io.github.intisy.nylium.smoke;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModLauncher8SmokeTest {

    /**
     * @implNote Forge 1.16.5's ModLauncher 8 era needs Java 8, older than the Gradle daemon's own
     * JVM, so the launcher path comes from smoke/build.gradle's per-version toolchain resolution
     * rather than this test's own java.home.
     */
    private static String java8Executable() {
        String path = System.getProperty("nylium.smoke.java.8");
        if (path == null) {
            throw new IllegalStateException(
                    "System property nylium.smoke.java.8 is not set; smoke/build.gradle must expose "
                            + "a Java 8 toolchain launcher.");
        }
        return path;
    }

    @Test
    void loadsThe1165ModuleOn1165() throws Exception {
        Path server = Paths.get(System.getProperty("nylium.smoke.servers")).resolve("forge-1.16.5");
        Path marker = server.resolve("nylium-marker.txt");

        String result = ServerSmokeHarness.run(server, Arrays.asList(
                java8Executable(),
                "-Dnylium.smoke.marker=" + marker.toAbsolutePath(),
                "-jar", "forge-server.jar",
                "nogui"), marker, Duration.ofMinutes(4));

        assertEquals("module=1.16.5", result);
    }
}
