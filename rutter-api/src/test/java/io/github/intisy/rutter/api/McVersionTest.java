package io.github.intisy.rutter.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McVersionTest {

    private static void assertOrdered(String lower, String higher) {
        assertTrue(McVersion.parse(lower).compareTo(McVersion.parse(higher)) < 0,
                lower + " should sort below " + higher);
        assertTrue(McVersion.parse(higher).compareTo(McVersion.parse(lower)) > 0,
                higher + " should sort above " + lower);
    }

    @Test
    void ordersLegacyVersions() {
        assertOrdered("1.7.10", "1.8");
        assertOrdered("1.16.5", "1.17");
        assertOrdered("1.21", "1.21.1");
    }

    @Test
    void ordersNumericallyNotLexically() {
        assertOrdered("1.21.9", "1.21.10");
        assertOrdered("1.21.10", "1.21.11");
        assertOrdered("1.9", "1.10");
    }

    @Test
    void treatsMissingComponentsAsZero() {
        assertEquals(0, McVersion.parse("1.21").compareTo(McVersion.parse("1.21.0")));
    }

    @Test
    void yearBasedIsAlwaysNewerThanLegacy() {
        assertOrdered("1.21.11", "26.1");
        assertOrdered("26.1", "26.2");
    }

    @Test
    void classifiesScheme() {
        assertTrue(McVersion.parse("26.2").isYearBased());
        assertFalse(McVersion.parse("1.21.11").isYearBased());
    }

    @Test
    void preservesRawText() {
        assertEquals("1.21.11", McVersion.parse("1.21.11").raw());
    }

    @Test
    void equalityFollowsComparison() {
        assertEquals(McVersion.parse("1.21"), McVersion.parse("1.21.0"));
        assertEquals(McVersion.parse("1.21").hashCode(), McVersion.parse("1.21.0").hashCode());
    }

    @Test
    void rejectsUnparseableVersions() {
        assertThrows(RutterException.class, () -> McVersion.parse("25w14a"));
        assertThrows(RutterException.class, () -> McVersion.parse("1.21.11-pre1"));
        assertThrows(RutterException.class, () -> McVersion.parse(""));
    }

    @Test
    void rejectsNoVersionAtAll() {
        assertThrows(RutterException.class, () -> McVersion.parse(null));
    }

    @Test
    void rejectsANegativeComponent() {
        assertThrows(RutterException.class, () -> McVersion.parse("1.-3"));
    }

    @Test
    void rejectsMoreComponentsThanAreCompared() {
        assertThrows(RutterException.class, () -> McVersion.parse("1.21.11.1.4"));
    }
}
