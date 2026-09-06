package io.github.intisy.nylium.smoke;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class ServerSmokeHarness {

    private ServerSmokeHarness() {
    }

    /**
     * @implNote Force-kills the process as soon as the marker appears (or the timeout elapses)
     * rather than waiting for shutdown; Nylium's dispatch completes well before world generation,
     * so there is never a reason to let the server finish booting.
     */
    public static String run(Path serverDirectory, List<String> command, Path marker, Duration timeout)
            throws IOException, InterruptedException {
        Files.deleteIfExists(marker);
        Path log = serverDirectory.resolve("smoke.log");
        Process process = new ProcessBuilder(command)
                .directory(serverDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(marker)) {
                    return new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
                }
                if (!process.isAlive()) {
                    throw new AssertionError("The server exited before writing " + marker
                            + ". Log:\n" + read(log));
                }
                Thread.sleep(250);
            }
            throw new AssertionError("Timed out after " + timeout + " waiting for " + marker
                    + ". Log:\n" + read(log));
        } finally {
            process.destroyForcibly();
            process.waitFor();
        }
    }

    /**
     * @implNote Exposed so a test asserting on a second marker can fail with the same captured log
     *     the timeout path reports, rather than with a bare {@code NoSuchFileException}.
     */
    public static String log(Path serverDirectory) throws IOException {
        return read(serverDirectory.resolve("smoke.log"));
    }

    private static String read(Path log) throws IOException {
        return Files.isRegularFile(log)
                ? new String(Files.readAllBytes(log), StandardCharsets.UTF_8)
                : "(no log)";
    }
}
