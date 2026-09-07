package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.NyliumException;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModuleIndex {

    public static final String HEADER = "# nylium-index 1";

    private static final int HASH_LENGTH = 64;

    public static final class Entry {

        private final String name;
        private final String hash;

        private Entry(String name, String hash) {
            this.name = name;
            this.hash = hash;
        }

        public static Entry file(String name, String hash) {
            return new Entry(name, hash);
        }

        public static Entry directory(String name) {
            return new Entry(name, null);
        }

        public String name() {
            return name;
        }

        public String hash() {
            return hash;
        }

        public boolean isDirectory() {
            return hash == null;
        }
    }

    private final List<Entry> entries;

    private ModuleIndex(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    /**
     * @implNote The name is the whole remainder of its line rather than a delimited field, so an
     *     entry name containing spaces needs no quoting and no escaping anywhere in the format.
     *     That is why {@link #render} rejects a name containing a line break: it is the one
     *     character the format cannot represent.
     */
    public static String render(List<Entry> entries) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Entry entry : entries) {
            requireSingleLine(entry.name());
            if (entry.isDirectory()) {
                out.append("d ").append(entry.name()).append('\n');
            } else {
                out.append("f ").append(entry.hash()).append(' ').append(entry.name()).append('\n');
            }
        }
        return out.toString();
    }

    public static ModuleIndex parse(byte[] bytes) {
        List<Entry> parsed = new ArrayList<Entry>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
        try {
            String header = reader.readLine();
            if (!HEADER.equals(header)) {
                throw new NyliumException(describeHeaderMismatch(header));
            }
            String line;
            int number = 1;
            while ((line = reader.readLine()) != null) {
                number++;
                if (!line.isEmpty()) {
                    parsed.add(entry(line, number));
                }
            }
        } catch (IOException e) {
            throw new NyliumException("Could not read a Nylium module index", e);
        }
        return new ModuleIndex(parsed);
    }

    /**
     * @implNote The plugin embeds {@code nylium-core} at exactly its own version, so the kernel
     *     reading a jar is always the kernel that shipped with the plugin that wrote it: a newer
     *     jar than the loading kernel cannot arise, and a truncated file yields a {@code null}
     *     header rather than a version at all. Reporting the version actually found, when parseable,
     *     is therefore more honest than asserting a cause that cannot happen.
     */
    private static String describeHeaderMismatch(String header) {
        Integer found = parseVersion(header);
        if (found != null) {
            return "A Nylium module index has to start with '" + HEADER + "' but found version "
                    + found + " instead.";
        }
        return "A Nylium module index has to start with '" + HEADER + "' but started with '"
                + header + "'.";
    }

    private static Integer parseVersion(String header) {
        if (header == null || !header.startsWith("# nylium-index ")) {
            return null;
        }
        try {
            return Integer.valueOf(header.substring("# nylium-index ".length()).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Entry entry(String line, int number) {
        if (line.startsWith("d ")) {
            return Entry.directory(requireName(line.substring(2), line, number));
        }
        if (line.startsWith("f ") && line.length() > HASH_LENGTH + 3
                && line.charAt(HASH_LENGTH + 2) == ' ') {
            String hash = line.substring(2, HASH_LENGTH + 2);
            if (isLowercaseHex(hash)) {
                return Entry.file(requireName(line.substring(HASH_LENGTH + 3), line, number), hash);
            }
        }
        throw new NyliumException("Line " + number + " of a Nylium module index is not a"
                + " recognised entry: '" + line + "'.");
    }

    private static String requireName(String name, String line, int number) {
        if (name.isEmpty()) {
            throw new NyliumException("Line " + number + " of a Nylium module index names no"
                    + " entry: '" + line + "'.");
        }
        return name;
    }

    private static boolean isLowercaseHex(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean digit = character >= '0' && character <= '9';
            boolean letter = character >= 'a' && character <= 'f';
            if (!digit && !letter) {
                return false;
            }
        }
        return true;
    }

    private static void requireSingleLine(String name) {
        if (name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
            throw new NyliumException("A jar entry name containing a line break cannot be written"
                    + " to a Nylium module index: '" + name + "'.");
        }
    }
}
