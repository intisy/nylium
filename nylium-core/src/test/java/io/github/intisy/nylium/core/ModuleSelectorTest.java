package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.McVersion;
import io.github.intisy.nylium.api.NoCompatibleModuleException;
import io.github.intisy.nylium.api.PlatformId;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleSelectorTest {

    private static ModuleSelector selector(String manifest) {
        return new ModuleSelector(ModuleManifest.read(new StringReader(manifest)));
    }

    private static final String TWO_FABRIC_VERSIONS =
            "module.0.path=modules/a-1.21.11.jar\n"
                    + "module.0.platforms=FABRIC\n"
                    + "module.0.minecraft=1.21.11\n"
                    + "module.1.path=modules/a-1.21.10.jar\n"
                    + "module.1.platforms=FABRIC\n"
                    + "module.1.minecraft=1.21.10\n";

    @Test
    void picksTheModuleMatchingTheRunningVersion() {
        ModuleSelector selector = selector(TWO_FABRIC_VERSIONS);

        assertEquals("modules/a-1.21.11.jar",
                selector.select(PlatformId.FABRIC, McVersion.parse("1.21.11"), Environment.CLIENT).path());
        assertEquals("modules/a-1.21.10.jar",
                selector.select(PlatformId.FABRIC, McVersion.parse("1.21.10"), Environment.CLIENT).path());
    }

    @Test
    void prefersTheMoreSpecificModuleOverAWideRange() {
        ModuleSelector selector = selector(
                "module.0.path=modules/wide.jar\n"
                        + "module.0.platforms=FABRIC\n"
                        + "module.0.minecraft=[1.20,)\n"
                        + "module.1.path=modules/exact.jar\n"
                        + "module.1.platforms=FABRIC\n"
                        + "module.1.minecraft=1.21.11\n");

        assertEquals("modules/exact.jar",
                selector.select(PlatformId.FABRIC, McVersion.parse("1.21.11"), Environment.CLIENT).path());
        assertEquals("modules/wide.jar",
                selector.select(PlatformId.FABRIC, McVersion.parse("1.20.4"), Environment.CLIENT).path());
    }

    @Test
    void breaksSpecificityTiesByPriority() {
        ModuleSelector selector = selector(
                "module.0.path=modules/low.jar\n"
                        + "module.0.platforms=FABRIC\n"
                        + "module.0.minecraft=1.21.11\n"
                        + "module.0.priority=1\n"
                        + "module.1.path=modules/high.jar\n"
                        + "module.1.platforms=FABRIC\n"
                        + "module.1.minecraft=1.21.11\n"
                        + "module.1.priority=9\n");

        assertEquals("modules/high.jar",
                selector.select(PlatformId.FABRIC, McVersion.parse("1.21.11"), Environment.CLIENT).path());
    }

    @Test
    void honoursTheEnvironmentConstraint() {
        ModuleSelector selector = selector(
                "module.0.path=modules/client.jar\n"
                        + "module.0.platforms=FABRIC\n"
                        + "module.0.minecraft=1.21.11\n"
                        + "module.0.environment=CLIENT\n"
                        + "module.1.path=modules/server.jar\n"
                        + "module.1.platforms=FABRIC\n"
                        + "module.1.minecraft=1.21.11\n"
                        + "module.1.environment=SERVER\n");

        assertEquals("modules/server.jar",
                selector.select(PlatformId.FABRIC, McVersion.parse("1.21.11"), Environment.SERVER).path());
    }

    @Test
    void aModuleWithoutAnEnvironmentSuitsBoth() {
        ModuleSelector selector = selector(
                "module.0.path=modules/any.jar\n"
                        + "module.0.platforms=FABRIC\n"
                        + "module.0.minecraft=1.21.11\n");

        assertEquals("modules/any.jar",
                selector.select(PlatformId.FABRIC, McVersion.parse("1.21.11"), Environment.SERVER).path());
    }

    @Test
    void selectionIsStableAcrossRepeatedCalls() {
        ModuleSelector selector = selector(TWO_FABRIC_VERSIONS);
        McVersion version = McVersion.parse("1.21.11");

        String first = selector.select(PlatformId.FABRIC, version, Environment.CLIENT).path();
        for (int i = 0; i < 10; i++) {
            assertEquals(first, selector.select(PlatformId.FABRIC, version, Environment.CLIENT).path());
        }
    }

    @Test
    void aMissNamesTheEnvironmentAndEveryRejectedCandidate() {
        ModuleSelector selector = selector(TWO_FABRIC_VERSIONS);

        NoCompatibleModuleException thrown = assertThrows(NoCompatibleModuleException.class,
                () -> selector.select(PlatformId.MODLAUNCHER_9, McVersion.parse("1.19.2"), Environment.CLIENT));

        String message = thrown.getMessage();
        assertTrue(message.contains("MODLAUNCHER_9"), message);
        assertTrue(message.contains("1.19.2"), message);
        assertTrue(message.contains("modules/a-1.21.11.jar"), message);
        assertTrue(message.contains("modules/a-1.21.10.jar"), message);
        assertTrue(message.contains("platform"), message);
    }

    @Test
    void rejectionMessageNamesVersionRangeAndEnvironmentMismatches() {
        ModuleSelector selector = selector(
                "module.0.path=modules/wrong-version.jar\n"
                        + "module.0.platforms=FABRIC\n"
                        + "module.0.minecraft=[1.20,1.21)\n"
                        + "module.0.environment=CLIENT\n"
                        + "module.1.path=modules/wrong-env.jar\n"
                        + "module.1.platforms=FABRIC\n"
                        + "module.1.minecraft=1.21.11\n"
                        + "module.1.environment=SERVER\n");

        NoCompatibleModuleException thrown = assertThrows(NoCompatibleModuleException.class,
                () -> selector.select(PlatformId.FABRIC, McVersion.parse("1.21.11"), Environment.CLIENT));

        String message = thrown.getMessage();
        assertTrue(message.contains("[1.20,1.21)"), "message should name the declared version range");
        assertTrue(message.contains("1.21.11"), "message should name the requested version");
        assertTrue(message.contains("excludes"), "version message should use 'excludes'");
        assertTrue(message.contains("SERVER"), "message should name the declared environment");
        assertTrue(message.contains("wrong-version.jar"), "message should list version-rejected candidate");
        assertTrue(message.contains("wrong-env.jar"), "message should list environment-rejected candidate");
    }

    @Test
    void manifestOrderBreaksTiesBetweenIdenticalCandidates() {
        ModuleSelector selector = selector(
                "module.0.path=modules/first.jar\n"
                        + "module.0.platforms=FABRIC\n"
                        + "module.0.minecraft=1.21.11\n"
                        + "module.0.priority=10\n"
                        + "module.1.path=modules/second.jar\n"
                        + "module.1.platforms=FABRIC\n"
                        + "module.1.minecraft=1.21.11\n"
                        + "module.1.priority=10\n");

        assertEquals("modules/first.jar",
                selector.select(PlatformId.FABRIC, McVersion.parse("1.21.11"), Environment.CLIENT).path());
    }
}
