package io.github.intisy.nylium.smoke;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Properties;

final class ConformanceReport {

    private final Properties values = new Properties();

    ConformanceReport(String text) throws IOException {
        try (Reader reader = new StringReader(text)) {
            values.load(reader);
        }
    }

    String get(String key) {
        return values.getProperty(key);
    }
}
