package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.McVersion;
import io.github.intisy.nylium.api.NyliumException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionRangeTest {

    private static boolean holds(String range, String version) {
        return VersionRange.parse(range).contains(McVersion.parse(version));
    }

    @Test
    void exactVersionMatchesOnlyItself() {
        assertTrue(holds("1.21.11", "1.21.11"));
        assertFalse(holds("1.21.11", "1.21.10"));
    }

    @Test
    void inclusiveRangeIncludesBothEndpoints() {
        assertTrue(holds("[1.16.5,1.21.11]", "1.16.5"));
        assertTrue(holds("[1.16.5,1.21.11]", "1.21.11"));
        assertTrue(holds("[1.16.5,1.21.11]", "1.19.2"));
        assertFalse(holds("[1.16.5,1.21.11]", "1.16.4"));
        assertFalse(holds("[1.16.5,1.21.11]", "26.1"));
    }

    @Test
    void exclusiveRangeExcludesBothEndpoints() {
        assertFalse(holds("(1.20,1.21)", "1.20"));
        assertFalse(holds("(1.20,1.21)", "1.21"));
        assertTrue(holds("(1.20,1.21)", "1.20.4"));
    }

    @Test
    void mixedBoundsAreHonoured() {
        assertTrue(holds("[1.20,1.21)", "1.20"));
        assertFalse(holds("[1.20,1.21)", "1.21"));
    }

    @Test
    void openUpperBoundReachesYearBasedVersions() {
        assertTrue(holds("[1.20,)", "26.2"));
        assertTrue(holds("[1.20,)", "1.21.11"));
        assertFalse(holds("[1.20,)", "1.19.4"));
    }

    @Test
    void openLowerBoundReachesTheOldestVersions() {
        assertTrue(holds("(,1.12.2]", "1.7.10"));
        assertFalse(holds("(,1.12.2]", "1.13"));
    }

    @Test
    void rejectsMalformedRanges() {
        assertThrows(NyliumException.class, () -> VersionRange.parse("[1.20"));
        assertThrows(NyliumException.class, () -> VersionRange.parse("[1.20,1.19]"));
        assertThrows(NyliumException.class, () -> VersionRange.parse(""));
        assertThrows(NyliumException.class, () -> VersionRange.parse("[,]"));
    }

    @Test
    void exactRangeIsExactAndClosed() {
        VersionRange exact = VersionRange.parse("1.21.11");
        assertTrue(exact.isExact());
        assertTrue(exact.isClosed());
    }

    @Test
    void closedInclusiveRangeIsClosedButNotExact() {
        VersionRange closed = VersionRange.parse("[1.16.5,1.21.11]");
        assertFalse(closed.isExact());
        assertTrue(closed.isClosed());
    }

    @Test
    void openEndedRangeIsNeitherExactNorClosed() {
        VersionRange openUpper = VersionRange.parse("[1.20,)");
        assertFalse(openUpper.isExact());
        assertFalse(openUpper.isClosed());

        VersionRange openLower = VersionRange.parse("(,1.21)");
        assertFalse(openLower.isExact());
        assertFalse(openLower.isClosed());
    }

    @Test
    void rangeWithEqualBoundsButExclusiveBracketIsNotExact() {
        VersionRange exclusiveEqual = VersionRange.parse("(1.20,1.20)");
        assertFalse(exclusiveEqual.isExact());
        assertTrue(exclusiveEqual.isClosed());
    }
}
