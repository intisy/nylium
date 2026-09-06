package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.McVersion;
import io.github.intisy.nylium.api.NyliumException;

public final class VersionRange {

    private final String raw;
    private final McVersion lower;
    private final boolean lowerInclusive;
    private final McVersion upper;
    private final boolean upperInclusive;

    private VersionRange(String raw, McVersion lower, boolean lowerInclusive,
                         McVersion upper, boolean upperInclusive) {
        this.raw = raw;
        this.lower = lower;
        this.lowerInclusive = lowerInclusive;
        this.upper = upper;
        this.upperInclusive = upperInclusive;
    }

    public static VersionRange parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new NyliumException("Version range is empty");
        }
        String text = raw.trim();
        char first = text.charAt(0);
        if (first != '[' && first != '(') {
            McVersion exact = McVersion.parse(text);
            return new VersionRange(text, exact, true, exact, true);
        }
        char last = text.charAt(text.length() - 1);
        if (last != ']' && last != ')') {
            throw new VersionRangeException(raw, "it opens with a bracket but does not close with one");
        }
        String body = text.substring(1, text.length() - 1);
        int comma = body.indexOf(',');
        if (comma < 0) {
            throw new VersionRangeException(raw, "a bracketed range needs a comma");
        }
        String lowerText = body.substring(0, comma).trim();
        String upperText = body.substring(comma + 1).trim();
        if (lowerText.isEmpty() && upperText.isEmpty()) {
            throw new VersionRangeException(raw, "it bounds nothing");
        }
        McVersion lower = lowerText.isEmpty() ? null : McVersion.parse(lowerText);
        McVersion upper = upperText.isEmpty() ? null : McVersion.parse(upperText);
        if (lower != null && upper != null && lower.compareTo(upper) > 0) {
            throw new VersionRangeException(raw, "its lower bound is above its upper bound");
        }
        return new VersionRange(text, lower, first == '[', upper, last == ']');
    }

    public boolean contains(McVersion version) {
        if (lower != null) {
            int diff = version.compareTo(lower);
            if (diff < 0 || (diff == 0 && !lowerInclusive)) {
                return false;
            }
        }
        if (upper != null) {
            int diff = version.compareTo(upper);
            if (diff > 0 || (diff == 0 && !upperInclusive)) {
                return false;
            }
        }
        return true;
    }

    public boolean isExact() {
        return lower != null && upper != null && lowerInclusive && upperInclusive
                && lower.compareTo(upper) == 0;
    }

    public boolean isClosed() {
        return lower != null && upper != null;
    }

    public String raw() {
        return raw;
    }

    @Override
    public String toString() {
        return raw;
    }

    private static final class VersionRangeException extends NyliumException {
        VersionRangeException(String raw, String problem) {
            super("Cannot read version range '" + raw + "': " + problem
                    + ". Use an exact version (1.21.11) or bracket notation ([1.20,1.21) or [1.20,)).");
        }
    }
}
