package io.github.intisy.rutter.testmod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class TestModEntry {

    private TestModEntry() {
    }

    public static void rutterInit() {
        String target = System.getProperty("rutter.smoke.marker");
        if (target == null) {
            return;
        }
        String report = "module=" + moduleId() + "\n";
        try {
            Path marker = Paths.get(target);
            Files.createDirectories(marker.getParent());
            Files.write(marker, report.getBytes(StandardCharsets.UTF_8));
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
