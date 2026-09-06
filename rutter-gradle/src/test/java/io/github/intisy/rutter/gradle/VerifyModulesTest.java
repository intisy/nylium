package io.github.intisy.rutter.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyModulesTest {

    @TempDir
    Path directory;

    private Path jarContaining(String name, String... entries) throws IOException {
        Path jar = directory.resolve(name);
        ZipOutputStream out = new ZipOutputStream(new FileOutputStream(jar.toFile()));
        for (String entry : entries) {
            out.putNextEntry(new ZipEntry(entry));
            out.write("x".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        out.close();
        return jar;
    }

    @Test
    void acceptsAModuleCarryingItsMixinConfig() throws IOException {
        Path jar = jarContaining("ok.jar", "mixins.demo.json", "demo/Entry.class");
        ModuleJarInspector.verify("1.21.11", jar.toFile(),
                Collections.singletonList("mixins.demo.json"));
    }

    @Test
    void rejectsADeclaredMixinConfigThatIsNotInTheJar() throws IOException {
        Path jar = jarContaining("missing.jar", "demo/Entry.class");
        List<String> mixins = Collections.singletonList("mixins.demo.json");
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> ModuleJarInspector.verify("1.21.11", jar.toFile(), mixins));
        assertTrue(thrown.getMessage().contains("mixins.demo.json"));
    }

    @Test
    void acceptsAModuleWhosePackageOnlyResemblesTheRutterApi() throws IOException {
        Path jar = jarContaining("nearmiss.jar", "io/github/intisy/rutter/apiextra/Foo.class");
        ModuleJarInspector.verify("1.21.11", jar.toFile(), Collections.<String>emptyList());
    }

    @Test
    void rejectsAModuleThatShadowedTheRutterApi() throws IOException {
        Path jar = jarContaining("shadowed.jar",
                "io/github/intisy/rutter/api/PlatformId.class");
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> ModuleJarInspector.verify("1.21.11", jar.toFile(),
                        Collections.<String>emptyList()));
        assertTrue(thrown.getMessage().contains("rutter"));
    }

    @Test
    void rejectsAModuleCarryingItsOwnManifest() throws IOException {
        Path jar = jarContaining("nested.jar", "rutter-modules.properties");
        assertThrows(RuntimeException.class, () -> ModuleJarInspector.verify("1.21.11",
                jar.toFile(), Collections.<String>emptyList()));
    }
}
