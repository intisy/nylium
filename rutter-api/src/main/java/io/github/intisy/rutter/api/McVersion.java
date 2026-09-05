package io.github.intisy.rutter.api;

import java.util.Arrays;

public final class McVersion implements Comparable<McVersion> {

    private static final int COMPARED_COMPONENTS = 4;

    private final String raw;
    private final int[] components;
    private final boolean yearBased;

    private McVersion(String raw, int[] components, boolean yearBased) {
        this.raw = raw;
        this.components = components;
        this.yearBased = yearBased;
    }

    public static McVersion parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            throw new RutterException("Minecraft version is empty");
        }
        String[] parts = raw.split("\\.", -1);
        int[] components = new int[COMPARED_COMPONENTS];
        for (int i = 0; i < parts.length; i++) {
            if (i >= COMPARED_COMPONENTS) {
                throw new RutterException("Minecraft version has too many components: " + raw);
            }
            try {
                components[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                throw new RutterException(
                        "Unrecognised Minecraft version '" + raw + "'. Rutter understands release "
                                + "versions only (for example 1.21.11 or 26.2), not snapshots or "
                                + "pre-releases.", e);
            }
            if (components[i] < 0) {
                throw new RutterException("Minecraft version component is negative: " + raw);
            }
        }
        return new McVersion(raw, components, components[0] != 1);
    }

    public String raw() {
        return raw;
    }

    public boolean isYearBased() {
        return yearBased;
    }

    @Override
    public int compareTo(McVersion other) {
        if (yearBased != other.yearBased) {
            return yearBased ? 1 : -1;
        }
        for (int i = 0; i < COMPARED_COMPONENTS; i++) {
            int diff = Integer.compare(components[i], other.components[i]);
            if (diff != 0) {
                return diff;
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof McVersion && compareTo((McVersion) o) == 0;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(components) * 31 + (yearBased ? 1 : 0);
    }

    @Override
    public String toString() {
        return raw;
    }
}
