package io.github.intisy.rutter.core;

import io.github.intisy.rutter.api.Environment;
import io.github.intisy.rutter.api.PlatformId;
import io.github.intisy.rutter.api.RutterException;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleManifestTest {

    private static ModuleManifest read(String text) {
        return ModuleManifest.read(new StringReader(text));
    }

    @Test
    void readsASingleModule() {
        ModuleManifest manifest = read(
                "module.0.path=modules/a.jar\n"
                        + "module.0.platforms=FABRIC\n"
                        + "module.0.minecraft=[1.21.11,1.21.11]\n"
                        + "module.0.environment=CLIENT\n"
                        + "module.0.mixins=mixins.a.json\n"
                        + "module.0.priority=3\n");

        List<ModuleDescriptor> modules = manifest.modules();
        assertEquals(1, modules.size());
        ModuleDescriptor module = modules.get(0);
        assertEquals("modules/a.jar", module.path());
        assertEquals(java.util.Collections.singleton(PlatformId.FABRIC), module.platforms());
        assertEquals(Environment.CLIENT, module.environment().orElseThrow(AssertionError::new));
        assertEquals(java.util.Collections.singletonList("mixins.a.json"), module.mixinConfigs());
        assertEquals(3, module.priority());
    }

    @Test
    void readsSeveralModulesInIndexOrder() {
        ModuleManifest manifest = read(
                "module.1.path=modules/b.jar\n"
                        + "module.1.platforms=FABRIC\n"
                        + "module.1.minecraft=1.21.10\n"
                        + "module.0.path=modules/a.jar\n"
                        + "module.0.platforms=FABRIC\n"
                        + "module.0.minecraft=1.21.11\n");

        assertEquals(2, manifest.modules().size());
        assertEquals("modules/a.jar", manifest.modules().get(0).path());
        assertEquals("modules/b.jar", manifest.modules().get(1).path());
    }

    @Test
    void splitsMultiValuedFields() {
        ModuleManifest manifest = read(
                "module.0.path=modules/a.jar\n"
                        + "module.0.platforms=MODLAUNCHER_9, FABRIC\n"
                        + "module.0.minecraft=[1.20,)\n"
                        + "module.0.mixins=one.json, two.json\n");

        ModuleDescriptor module = manifest.modules().get(0);
        assertTrue(module.platforms().contains(PlatformId.FABRIC));
        assertTrue(module.platforms().contains(PlatformId.MODLAUNCHER_9));
        assertEquals(2, module.mixinConfigs().size());
    }

    @Test
    void optionalFieldsDefaultSensibly() {
        ModuleManifest manifest = read(
                "module.0.path=modules/a.jar\n"
                        + "module.0.platforms=FABRIC\n"
                        + "module.0.minecraft=1.21.11\n");

        ModuleDescriptor module = manifest.modules().get(0);
        assertFalse(module.environment().isPresent());
        assertEquals(0, module.priority());
        assertTrue(module.mixinConfigs().isEmpty());
    }

    @Test
    void specificityRewardsNarrowerConstraints() {
        ModuleDescriptor broad = read(
                "module.0.path=a.jar\nmodule.0.platforms=FABRIC\nmodule.0.minecraft=[1.20,)\n")
                .modules().get(0);
        ModuleDescriptor narrow = read(
                "module.0.path=a.jar\nmodule.0.platforms=FABRIC\nmodule.0.minecraft=1.21.11\n"
                        + "module.0.environment=CLIENT\n")
                .modules().get(0);

        assertTrue(narrow.specificity() > broad.specificity());
    }

    @Test
    void rejectsAnEmptyManifest() {
        assertThrows(RutterException.class, () -> read("\n"));
    }

    @Test
    void rejectsAModuleMissingRequiredFields() {
        assertThrows(RutterException.class, () -> read("module.0.platforms=FABRIC\n"));
        assertThrows(RutterException.class, () -> read("module.0.path=a.jar\n"));
    }

    @Test
    void rejectsAnUnknownPlatformName() {
        RutterException thrown = assertThrows(RutterException.class, () -> read(
                "module.0.path=a.jar\nmodule.0.platforms=QUILT\nmodule.0.minecraft=1.21.11\n"));
        assertTrue(thrown.getMessage().contains("QUILT"));
    }

    @Test
    void ordersIndicesNumericallyNotLexicographically() {
        ModuleManifest manifest = read(
                "module.10.path=modules/ten.jar\n"
                        + "module.10.platforms=FABRIC\n"
                        + "module.10.minecraft=1.21.11\n"
                        + "module.2.path=modules/two.jar\n"
                        + "module.2.platforms=FABRIC\n"
                        + "module.2.minecraft=1.21.10\n");

        List<ModuleDescriptor> modules = manifest.modules();
        assertEquals(2, modules.size());
        assertEquals("modules/two.jar", modules.get(0).path());
        assertEquals("modules/ten.jar", modules.get(1).path());
    }

    @Test
    void readsAnEntrypointWhenDeclared() {
        ModuleManifest manifest = read(
                "module.0.path=modules/a.jar\n"
                        + "module.0.platforms=FABRIC\n"
                        + "module.0.minecraft=1.21.11\n"
                        + "module.0.entrypoint=io.github.intisy.rutter.example.Entry\n");

        ModuleDescriptor module = manifest.modules().get(0);
        assertEquals("io.github.intisy.rutter.example.Entry", module.entrypoint().orElseThrow(AssertionError::new));
    }

    @Test
    void entrypointIsAbsentWhenOmitted() {
        ModuleManifest manifest = read(
                "module.0.path=modules/a.jar\n"
                        + "module.0.platforms=FABRIC\n"
                        + "module.0.minecraft=1.21.11\n");

        ModuleDescriptor module = manifest.modules().get(0);
        assertFalse(module.entrypoint().isPresent());
    }

    @Test
    void rejectsIndicesThatCollideAfterNumericNormalization() {
        RutterException thrown = assertThrows(RutterException.class, () -> read(
                "module.2.path=modules/a.jar\n"
                        + "module.2.platforms=FABRIC\n"
                        + "module.2.minecraft=1.21.11\n"
                        + "module.02.path=modules/b.jar\n"
                        + "module.02.platforms=FABRIC\n"
                        + "module.02.minecraft=1.21.10\n"));

        assertTrue(thrown.getMessage().contains("'2'"));
        assertTrue(thrown.getMessage().contains("'02'"));
    }

    @Test
    void rejectsAnUnknownEnvironmentName() {
        RutterException thrown = assertThrows(RutterException.class, () -> read(
                "module.0.path=a.jar\nmodule.0.platforms=FABRIC\nmodule.0.minecraft=1.21.11\n"
                        + "module.0.environment=INTEGRATED\n"));
        assertTrue(thrown.getMessage().contains("INTEGRATED"));
    }

    @Test
    void rejectsAnUnrecognisedField() {
        RutterException thrown = assertThrows(RutterException.class, () -> read(
                "module.0.path=a.jar\nmodule.0.platforms=FABRIC\nmodule.0.minecraft=1.21.11\n"
                        + "module.0.enviroment=CLIENT\n"));

        assertTrue(thrown.getMessage().contains("module.0.enviroment"));
        assertTrue(thrown.getMessage().contains("path"));
        assertTrue(thrown.getMessage().contains("environment"));
    }

    @Test
    void parsesAFullyPopulatedManifestWithAllKnownFields() {
        ModuleManifest manifest = read(
                "module.0.path=modules/a.jar\n"
                        + "module.0.platforms=FABRIC\n"
                        + "module.0.minecraft=1.21.11\n"
                        + "module.0.environment=CLIENT\n"
                        + "module.0.mixins=mixins.a.json\n"
                        + "module.0.priority=5\n"
                        + "module.0.entrypoint=io.github.intisy.rutter.example.Entry\n");

        ModuleDescriptor module = manifest.modules().get(0);
        assertEquals("modules/a.jar", module.path());
        assertEquals(5, module.priority());
        assertEquals("io.github.intisy.rutter.example.Entry",
                module.entrypoint().orElseThrow(AssertionError::new));
    }

    @Test
    void ignoresPropertiesOutsideTheModuleNamespace() {
        ModuleManifest manifest = read(
                "manifest.version=1\n"
                        + "module.0.path=modules/a.jar\n"
                        + "module.0.platforms=FABRIC\n"
                        + "module.0.minecraft=1.21.11\n");

        assertEquals(1, manifest.modules().size());
    }

    @Test
    void ignoresAModuleIndexKeyWithNoFieldPart() {
        ModuleManifest manifest = read(
                "module.0=irrelevant\n"
                        + "module.0.path=modules/a.jar\n"
                        + "module.0.platforms=FABRIC\n"
                        + "module.0.minecraft=1.21.11\n");

        assertEquals(1, manifest.modules().size());
    }

    @Test
    void ignoresATrailingDotModuleKeyWithNoFieldPart() {
        ModuleManifest manifest = read(
                "module.0.path=modules/a.jar\n"
                        + "module.0.platforms=FABRIC\n"
                        + "module.0.minecraft=1.21.11\n"
                        + "module.0.=irrelevant\n");

        assertEquals(1, manifest.modules().size());
    }
}
