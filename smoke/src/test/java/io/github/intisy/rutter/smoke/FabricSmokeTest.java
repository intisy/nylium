package io.github.intisy.rutter.smoke;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FabricSmokeTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    private static String bootOn(String minecraftVersion) throws Exception {
        Path server = Paths.get(System.getProperty("rutter.smoke.servers"))
                .resolve("fabric-" + minecraftVersion);
        Path marker = server.resolve("rutter-marker.txt");
        return ServerSmokeHarness.run(server, Arrays.asList(
                javaExecutable(),
                "-Drutter.smoke.marker=" + marker.toAbsolutePath(),
                "-jar", "fabric-server-launch.jar",
                "nogui"), marker, TIMEOUT);
    }

    /**
     * @implNote The ambient PATH "java" is not guaranteed to satisfy Minecraft's Java floor
     * (1.21.x requires Java 21); this test JVM runs on the project's toolchain launcher, so its
     * own java.home is guaranteed to match, unlike a bare "java" command.
     */
    private static String javaExecutable() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return Paths.get(System.getProperty("java.home"), "bin", "java" + (windows ? ".exe" : "")).toString();
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
