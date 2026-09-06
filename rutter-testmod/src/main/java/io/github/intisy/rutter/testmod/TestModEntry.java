package io.github.intisy.rutter.testmod;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
        MarkerWriter.write(Paths.get(target), report.getBytes(StandardCharsets.UTF_8));
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
