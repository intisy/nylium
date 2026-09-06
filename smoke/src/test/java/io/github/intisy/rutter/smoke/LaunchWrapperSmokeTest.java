package io.github.intisy.rutter.smoke;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LaunchWrapperSmokeTest {

    /**
     * @implNote Forge 1.7.10's launchwrapper era needs Java 8, older than the Gradle daemon's own
     * JVM, so the launcher path comes from smoke/build.gradle's per-version toolchain resolution
     * rather than this test's own java.home.
     */
    private static String java8Executable() {
        String path = System.getProperty("rutter.smoke.java.8");
        if (path == null) {
            throw new IllegalStateException(
                    "System property rutter.smoke.java.8 is not set; smoke/build.gradle must expose "
                            + "a Java 8 toolchain launcher.");
        }
        return path;
    }

    @Test
    void loadsTheLegacyModuleOn1710() throws Exception {
        Path server = Paths.get(System.getProperty("rutter.smoke.servers")).resolve("forge-1.7.10");
        Path marker = server.resolve("rutter-marker.txt");

        String result = ServerSmokeHarness.run(server, Arrays.asList(
                java8Executable(),
                "-Drutter.smoke.marker=" + marker.toAbsolutePath(),
                "-jar", "forge-server.jar",
                "nogui"), marker, Duration.ofMinutes(3));

        assertEquals("module=1.7.10", result);
    }
}
