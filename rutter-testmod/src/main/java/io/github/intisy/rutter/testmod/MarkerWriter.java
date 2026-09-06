package io.github.intisy.rutter.testmod;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * @implNote A plain {@code Files.write} is not atomic: a concurrent reader polling for the
 * marker's existence can observe it created but still empty or only partially written. Writing to
 * a sibling temporary file and landing it with an atomic move, the same pattern
 * {@code ModuleExtractor} uses for its cache entries, means the marker is only ever absent or
 * complete. Unlike that cache (content-addressed, first writer wins), a stale marker here should
 * simply be replaced, since there is exactly one writer per marker path and its latest result is
 * always the correct one.
 */
public final class MarkerWriter {

    private MarkerWriter() {
    }

    public static void write(Path marker, byte[] bytes) {
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
}
