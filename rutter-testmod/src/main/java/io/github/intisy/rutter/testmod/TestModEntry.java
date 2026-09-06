package io.github.intisy.rutter.testmod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public final class TestModEntry {

    private TestModEntry() {
    }

    public static void rutterInit() {
        String target = System.getProperty("rutter.smoke.marker");
        if (target == null) {
            return;
        }
        String report = "module=" + moduleId() + "\n";
        writeAtomically(Paths.get(target), report.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @implNote A plain {@code Files.write} is not atomic: a concurrent reader polling for the
     * marker's existence can observe it created but still empty or only partially written. Writing
     * to a sibling temporary file and landing it with an atomic move, the same pattern
     * {@code ModuleExtractor} uses for its cache entries, means the marker is only ever absent or
     * complete. Unlike that cache (content-addressed, first writer wins), a stale marker here
     * should simply be replaced, since there is exactly one writer and its latest result is always
     * the correct one.
     */
    private static void writeAtomically(Path marker, byte[] bytes) {
        try {
            Files.createDirectories(marker.getParent());
            Path temporary = Files.createTempFile(marker.getParent(), "rutter-marker-", ".part");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException | AccessDeniedException e) {
                    Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String moduleId() {
        try (InputStream stream = TestModEntry.class.getResourceAsStream("/rutter-testmod-id.txt")) {
            if (stream == null) {
                return "unknown";
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[256];
            int read;
            while ((read = stream.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "unreadable";
        }
    }
}
