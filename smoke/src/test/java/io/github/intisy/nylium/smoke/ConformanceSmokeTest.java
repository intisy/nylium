package io.github.intisy.nylium.smoke;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("conformance")
class ConformanceSmokeTest {

    private static final String FORGE_1211_VERSION = "1.21.11-61.1.5";

    private static Path server(String name) {
        return Paths.get(System.getProperty("nylium.smoke.servers")).resolve(name);
    }

    private static String javaExecutable(int version) {
        String path = System.getProperty("nylium.smoke.java." + version);
        if (path == null) {
            throw new IllegalStateException("System property nylium.smoke.java." + version
                    + " is not set; smoke/build.gradle must expose a Java " + version
                    + " toolchain launcher.");
        }
        return path;
    }

    private static ConformanceReport bootAndRead(Path directory, List<String> command,
                                                 Duration timeout) throws Exception {
        Path report = directory.resolve("nylium-conformance-report.properties");
        return new ConformanceReport(ServerSmokeHarness.run(directory, command, report, timeout));
    }

    /**
     * @implNote {@code bundledJar} is asserted on every server, not only the one module that
     *     bundles a jar at {@code META-INF/jars}, because "absent" everywhere else is what makes
     *     the one "bundled" mean anything. Only fabric-1.21.10's module bundles one.
     */
    private static void assertCommonKeys(ConformanceReport report, String module, String loader,
                                         String mcClass, String bundledJar) {
        assertEquals(module, report.get("module"));
        assertEquals("invoked", report.get("entrypoint"));
        assertEquals("shared-ok", report.get("sharedClass"));
        assertEquals("unique-" + module, report.get("uniqueClass"));
        assertEquals(loader, report.get("loader"));
        assertEquals(mcClass, report.get("mcClass"));
        assertEquals(bundledJar, report.get("bundledJar"));
    }

    private ConformanceReport bootFabric(String minecraftVersion) throws Exception {
        Path directory = server("fabric-" + minecraftVersion);
        Path report = directory.resolve("nylium-conformance-report.properties");
        return bootAndRead(directory, Arrays.asList(
                javaExecutable(21),
                "-Dnylium.smoke.report=" + report.toAbsolutePath(),
                "-jar", "fabric-server-launch.jar",
                "nogui"), Duration.ofMinutes(3));
    }

    @Test
    void picksTheExactModuleOverTheBroadTheAlternateAndTheClientOne() throws Exception {
        assertCommonKeys(bootFabric("1.21.11"), "fabric-1.21.11", "fabric", "reachable", "absent");
    }

    @Test
    void picksTheOtherExactModuleOnTheAdjacentVersion() throws Exception {
        assertCommonKeys(bootFabric("1.21.10"), "fabric-1.21.10", "fabric", "reachable", "bundled");
    }

    /**
     * @implNote Asserts {@code mcClass=unsafe-to-probe}. {@code McClassProbe} refuses to probe
     *     game classes by name on LaunchWrapper because its own safety there cannot be shown, not
     *     because probing is known to cause the server's own launch failure recorded in
     *     {@code smoke/build/servers/forge-1.7.10/smoke.log} and this task's report: that failure
     *     reproduces with {@code nylium-testmod}, whose entrypoint loads and probes nothing, and
     *     traces instead to {@code MixinBootstrap.init()} registering a transformer into the same
     *     {@code ArrayList} that {@code LaunchClassLoader.runTransformers} is iterating inside
     *     {@code NyliumBootTransformer.transform()}. Whether a LaunchWrapper module can reach game
     *     classes at all is an open question for its own spike, not something this test tries to
     *     answer.
     */
    @Test
    void dispatchesOnLaunchWrapper() throws Exception {
        Path directory = server("forge-1.7.10");
        Path report = directory.resolve("nylium-conformance-report.properties");
        assertCommonKeys(bootAndRead(directory, Arrays.asList(
                javaExecutable(8),
                "-Dnylium.smoke.report=" + report.toAbsolutePath(),
                "-jar", "forge-server.jar",
                "nogui"), Duration.ofMinutes(3)), "launchwrapper", "launchwrapper", "unsafe-to-probe", "absent");
    }

    /**
     * @implNote Asserts {@code mcClass=unavailable}. That is Nylium's known limitation 1, not a
     *     defect in this mod: on ModLauncher 8 the transformation service is discovered by a
     *     sibling of the class loader hosting the game. When the limitation is fixed this test
     *     fails, which is the point of asserting the current answer rather than skipping it.
     */
    @Test
    void dispatchesOnModLauncher8WithoutReachingGameClasses() throws Exception {
        Path directory = server("forge-1.16.5");
        Path report = directory.resolve("nylium-conformance-report.properties");
        assertCommonKeys(bootAndRead(directory, Arrays.asList(
                javaExecutable(8),
                "-Dnylium.smoke.report=" + report.toAbsolutePath(),
                "-jar", "forge-server.jar",
                "nogui"), Duration.ofMinutes(4)), "modlauncher8", "modlauncher8", "unavailable", "absent");
    }

    @Test
    void dispatchesOnModLauncher9AndAppliesItsMixin() throws Exception {
        Path directory = server("forge-1.21.11");
        Path report = directory.resolve("nylium-conformance-report.properties");
        Path mixinReport = directory.resolve("nylium-conformance-mixin.properties");
        Files.deleteIfExists(report);

        String classpath = System.getProperty("nylium.smoke.jarName") + File.pathSeparator
                + "forge-" + FORGE_1211_VERSION + "-shim.jar";

        String mixinResult = ServerSmokeHarness.run(directory, Arrays.asList(
                javaExecutable(21),
                "-Dnylium.smoke.report=" + report.toAbsolutePath(),
                "-Dnylium.smoke.mixinReport=" + mixinReport.toAbsolutePath(),
                "-Djava.net.preferIPv6Addresses=system",
                "-cp", classpath,
                "net.minecraftforge.bootstrap.shim.Main",
                "nogui"), mixinReport, Duration.ofMinutes(4));

        assertEquals("mixin=applied", mixinResult.trim());
        if (!Files.isRegularFile(report)) {
            throw new AssertionError("The mixin report was written but " + report + " never was, so"
                    + " the module's entrypoint did not run. Log:\n"
                    + ServerSmokeHarness.log(directory));
        }
        assertCommonKeys(new ConformanceReport(new String(Files.readAllBytes(report),
                StandardCharsets.UTF_8)), "modlauncher9", "modlauncher9", "reachable", "absent");
    }

    /**
     * @implNote Reads the artifact rather than trusting -PnyliumConformanceDedupe's intent, because
     *     Phase 0 and Phase 3 assert identical report keys and never otherwise inspect the jar, so a
     *     mistyped property would silently turn the acceptance run back into the control with
     *     nothing here to notice. The expectation itself comes from
     *     {@code nylium.smoke.dedupe}, a system property smoke/build.gradle computes from the same
     *     -PnyliumConformanceDedupe value and from {@code nylium.smoke.mod}, since a single module
     *     is never deduped and testmod's hand-rolled build never uses the plugin's dedupe feature at
     *     all.
     */
    @Test
    void installedUniversalJarMatchesTheDedupeExpectation() throws Exception {
        boolean dedupeExpected = Boolean.parseBoolean(System.getProperty("nylium.smoke.dedupe"));
        Path jar = server("fabric-1.21.11").resolve("mods")
                .resolve(System.getProperty("nylium.smoke.jarName", "nylium-conformance-universal.jar"));

        List<String> modulePaths = new ArrayList<>();
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry manifestEntry = file.getJarEntry("nylium-modules.properties");
            Properties properties = new Properties();
            try (InputStream in = file.getInputStream(manifestEntry)) {
                properties.load(in);
            }
            for (String key : properties.stringPropertyNames()) {
                if (key.endsWith(".path")) {
                    modulePaths.add(properties.getProperty(key));
                }
            }
        }

        assertFalse(modulePaths.isEmpty(), "nylium-modules.properties named no module paths in " + jar);
        for (String path : modulePaths) {
            assertEquals(dedupeExpected, path.endsWith(".index"), "module path '" + path
                    + "' does not match the dedupe expectation (dedupeExpected=" + dedupeExpected + ")");
        }
    }
}
