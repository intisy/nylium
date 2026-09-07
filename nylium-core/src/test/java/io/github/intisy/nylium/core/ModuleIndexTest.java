package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.NyliumException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleIndexTest {

    private static final String HASH_A =
            "0000000000000000000000000000000000000000000000000000000000000001";
    private static final String HASH_B =
            "00000000000000000000000000000000000000000000000000000000000000ab";

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void rendersAndParsesBackToTheSameEntries() {
        List<ModuleIndex.Entry> entries = Arrays.asList(
                ModuleIndex.Entry.directory("pkg/"),
                ModuleIndex.Entry.file("pkg/A.class", HASH_A),
                ModuleIndex.Entry.file("a name with spaces.txt", HASH_B));

        List<ModuleIndex.Entry> parsed =
                ModuleIndex.parse(utf8(ModuleIndex.render(entries))).entries();

        assertEquals(3, parsed.size());
        assertTrue(parsed.get(0).isDirectory());
        assertEquals("pkg/", parsed.get(0).name());
        assertNull(parsed.get(0).hash());
        assertEquals("pkg/A.class", parsed.get(1).name());
        assertEquals(HASH_A, parsed.get(1).hash());
        assertEquals("a name with spaces.txt", parsed.get(2).name());
        assertEquals(HASH_B, parsed.get(2).hash());
    }

    @Test
    void rendersTheVersionHeaderFirst() {
        String rendered = ModuleIndex.render(new ArrayList<ModuleIndex.Entry>());

        assertEquals(ModuleIndex.HEADER + "\n", rendered);
    }

    @Test
    void refusesAnIndexWithoutTheHeader() {
        NyliumException thrown = assertThrows(NyliumException.class,
                () -> ModuleIndex.parse(utf8("f " + HASH_A + " pkg/A.class\n")));

        assertTrue(thrown.getMessage().contains(ModuleIndex.HEADER), thrown.getMessage());
    }

    @Test
    void refusesAnIndexFromAFutureFormat() {
        assertThrows(NyliumException.class,
                () -> ModuleIndex.parse(utf8("# nylium-index 2\n")));
    }

    @Test
    void refusesALineThatIsNeitherAFileNorADirectory() {
        NyliumException thrown = assertThrows(NyliumException.class, () -> ModuleIndex.parse(
                utf8(ModuleIndex.HEADER + "\nx " + HASH_A + " pkg/A.class\n")));

        assertTrue(thrown.getMessage().contains("2"), thrown.getMessage());
    }

    @Test
    void refusesAFileLineWhoseHashIsNotLowercaseHex() {
        assertThrows(NyliumException.class, () -> ModuleIndex.parse(utf8(ModuleIndex.HEADER
                + "\nf 00000000000000000000000000000000000000000000000000000000000000AB a.txt\n")));
    }

    @Test
    void refusesAnEntryWithNoName() {
        assertThrows(NyliumException.class,
                () -> ModuleIndex.parse(utf8(ModuleIndex.HEADER + "\nd \n")));
    }

    @Test
    void refusesToRenderAnEntryNameContainingALineBreak() {
        List<ModuleIndex.Entry> entries =
                Arrays.asList(ModuleIndex.Entry.file("bad\nname.txt", HASH_A));

        NyliumException thrown =
                assertThrows(NyliumException.class, () -> ModuleIndex.render(entries));

        assertTrue(thrown.getMessage().contains("line break"), thrown.getMessage());
    }

    @Test
    void hashesAreLowercaseHexOfTheRightLength() {
        String hash = Sha256.hex("hello".getBytes(StandardCharsets.UTF_8));

        assertEquals(64, hash.length());
        assertEquals(hash.toLowerCase(java.util.Locale.ROOT), hash);
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash);
    }
}
