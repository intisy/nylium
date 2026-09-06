package io.github.intisy.rutter.core.probe;

import io.github.intisy.rutter.api.McVersion;
import io.github.intisy.rutter.api.RutterException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbeChainTest {

    private static VersionProbe probe(String name, String result) {
        return new VersionProbe() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Optional<String> detect() {
                return Optional.ofNullable(result);
            }
        };
    }

    private static VersionProbe exploding(String name) {
        return new VersionProbe() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Optional<String> detect() {
                throw new IllegalStateException("probe blew up");
            }
        };
    }

    @Test
    void returnsTheFirstProbeThatAnswers() {
        ProbeChain chain = new ProbeChain(Arrays.asList(
                probe("first", null),
                probe("second", "1.21.11"),
                probe("third", "1.16.5")));

        assertEquals(McVersion.parse("1.21.11"), chain.detect());
    }

    @Test
    void skipsAProbeThatThrows() {
        ProbeChain chain = new ProbeChain(Arrays.asList(
                exploding("broken"),
                probe("good", "1.21.11")));

        assertEquals(McVersion.parse("1.21.11"), chain.detect());
    }

    @Test
    void skipsAProbeThatAnswersUnparseably() {
        ProbeChain chain = new ProbeChain(Arrays.asList(
                probe("snapshot", "25w14a"),
                probe("release", "1.21.11")));

        assertEquals(McVersion.parse("1.21.11"), chain.detect());
    }

    @Test
    void failingChainNamesEveryProbeItTried() {
        ProbeChain chain = new ProbeChain(Arrays.asList(
                probe("alpha", null),
                probe("beta", "25w14a")));

        RutterException thrown = assertThrows(RutterException.class, chain::detect);
        assertTrue(thrown.getMessage().contains("alpha"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("beta"), thrown.getMessage());
    }

    @Test
    void rejectsAnEmptyChain() {
        assertThrows(RutterException.class, () -> new ProbeChain(Collections.emptyList()));
    }
}
