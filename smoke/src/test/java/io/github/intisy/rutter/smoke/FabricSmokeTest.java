package io.github.intisy.rutter.smoke;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FabricSmokeTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(3);
    private static final int FABRIC_JAVA_VERSION = 21;

    private static String bootOn(String minecraftVersion) throws Exception {
        Path server = Paths.get(System.getProperty("rutter.smoke.servers"))
                .resolve("fabric-" + minecraftVersion);
        Path marker = server.resolve("rutter-marker.txt");
        return ServerSmokeHarness.run(server, Arrays.asList(
                javaExecutable(FABRIC_JAVA_VERSION),
                "-Drutter.smoke.marker=" + marker.toAbsolutePath(),
                "-jar", "fabric-server-launch.jar",
                "nogui"), marker, TIMEOUT);
    }

    /**
     * @implNote Minecraft's Java floor varies by version (1.21.x needs Java 21, older loaders need
     * Java 8), so smoke/build.gradle resolves a toolchain launcher per version and exposes its path
     * as system property "rutter.smoke.java.&lt;version&gt;", rather than this test assuming its own
     * java.home matches every server it spawns.
     */
    private static String javaExecutable(int javaVersion) {
        String path = System.getProperty("rutter.smoke.java." + javaVersion);
        if (path == null) {
            throw new IllegalStateException("System property rutter.smoke.java." + javaVersion
                    + " is not set; smoke/build.gradle must expose a Java " + javaVersion + " toolchain launcher.");
        }
        return path;
    }

    @Test
    void loadsThe12111ModuleOn12111() throws Exception {
        assertEquals("module=1.21.11", bootOn("1.21.11"));
    }

    @Test
    void loadsThe12110ModuleOn12110() throws Exception {
        assertEquals("module=1.21.10", bootOn("1.21.10"));
    }
}
