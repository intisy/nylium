# Nylium SP-1 Kernel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Nylium dispatch kernel so a single jar boots on LaunchWrapper, ModLauncher 8, ModLauncher 9+ and Fabric, detects the running Minecraft version, and loads the matching precompiled module.

**Architecture:** Three layers. Four tiny `nylium-bootstrap-*` artifacts (stage0) each implement one loader's entry contract and hand control to `nylium-core` (stage1), which parses an embedded `.properties` manifest, runs a version probe chain, selects a module, extracts it to a hash-keyed cache, and asks the backend to classpath it and register its mixin configs. The consumer's precompiled modules are stage2 and are not Nylium code. `nylium-api` holds the public surface and the `Platform` SPI, and is guarded by an ASM purity check that forbids any `net.minecraft` reference.

**Tech Stack:** Java 8 bytecode throughout, Gradle 8 with the Groovy DSL, JUnit 5, ASM 9.7, and the loader APIs as plain Maven artifacts (launchwrapper, modlauncher, fabric-loader, spongepowered mixin). No Minecraft toolchain is needed to compile Nylium itself, which is why the build stays offline and fast; only the opt-in smoke tests touch real servers.

**Spec:** `docs/superpowers/specs/2026-09-05-nylium-kernel-design.md`
**Program context:** `docs/superpowers/specs/2026-09-05-nylium-program-overview.md`

## Global Constraints

- **Package root:** `io.github.intisy.nylium`. Maven group `io.github.intisy.nylium`.
- **Java floor: every artifact emits Java 8 bytecode** (`options.release = 8`), the ModLauncher 9+ bootstrap included. ModLauncher 8 and 9 share one `ServiceLoader` file and `ServiceLoader` instantiates every entry, so a Java 17 class file there would throw `UnsupportedClassVersionError` and kill the game on ModLauncher 8. Consequence: no `List.of`, no `var`, no records anywhere in Nylium's own source.
- **Zero runtime dependencies in `nylium-api` and `nylium-core`.** Not Gson, not Guava, not Apache Commons. Nothing is guaranteed on the classpath at stage0. Loader APIs are `compileOnly` in the bootstraps.
- **No `net.minecraft` anywhere in `nylium-api`** - not as a parameter, return, field, supertype, generic argument or annotation. Enforced by `checkApiPurity` (Task 10).
- **Manifest format is `.properties`**, read with `Properties.load(Reader)` and an explicit UTF-8 reader. The `load(InputStream)` overload is ISO-8859-1 and would mangle non-ASCII paths.
- **Two Minecraft version schemes must both order correctly:** legacy `1.21.11` and year-based `26.2`. Any year-based version is newer than any `1.x`.
- **All four bootstraps coexist in one outer jar**, so each must be inert when its platform is absent. Guard every platform class access behind a `Class.forName` capability check.
- **Comments:** default to zero. A comment may only carry genuinely non-obvious *why* (a subtle constraint, workaround, gotcha, version or compat note). Never restate what the code does. Prefer clear names.
- **Commits:** Conventional Commits, `type(scope): summary`, imperative, lowercase summary, no trailing period. No task or phase archaeology in the message.
- **CI:** `.github/workflows/*` are thin callers only, each a `uses:` of a reusable workflow in `intisy/workflows`. Never put `runs-on`, `steps` or logic in this repo. No workflow hardcodes a branch name.
- **Never delete existing code without asking.**

---

## File Structure

```
Nylium/
  settings.gradle                                    # subproject includes, foojay toolchain resolver
  build.gradle                                       # shared subprojects{} config, Java release, repos
  gradle.properties                                  # version, jvmargs
  gradlew, gradlew.bat, gradle/wrapper/              # copied from baritone
  buildSrc/
    build.gradle                                     # ASM dependency for the purity task
    src/main/java/io/github/intisy/nylium/gradle/
      ApiPurityTask.java                             # Task 10: ASM scan, fails on net/minecraft
  nylium-api/src/main/java/io/github/intisy/nylium/api/
    PlatformId.java                                  # LAUNCHWRAPPER, MODLAUNCHER_8, MODLAUNCHER_9, FABRIC
    Environment.java                                 # CLIENT, SERVER
    McVersion.java                                   # parse + order both schemes
    Platform.java                                    # the five-method SPI backends implement
    NyliumException.java                             # base
    NoCompatibleModuleException.java                 # the diagnostic-carrying miss
  nylium-core/src/main/java/io/github/intisy/nylium/core/
    VersionRange.java                                # [a,b] (a,b) [a,) and exact
    ModuleDescriptor.java                            # one manifest record
    ModuleManifest.java                              # Properties -> List<ModuleDescriptor>
    ModuleSelector.java                              # deterministic pick + rejection reasons
    ModuleExtractor.java                             # hash-keyed cache, atomic rename
    probe/VersionProbe.java                          # Optional<String> detect()
    probe/VersionJsonProbe.java                      # version.json off the classpath
    probe/MarkerClassProbe.java                      # class presence -> version
    probe/ProbeChain.java                            # ordered fallthrough
    NyliumKernel.java                                # boot(Platform): the orchestrator
  nylium-bootstrap-fabric/    src/main/java/.../bootstrap/fabric/FabricPlatform.java + NyliumPreLaunch.java
  nylium-bootstrap-launchwrapper/ src/main/java/.../bootstrap/launchwrapper/LaunchWrapperPlatform.java + NyliumTweaker.java
  nylium-bootstrap-modlauncher8/  src/main/java/.../bootstrap/ml8/Ml8Platform.java + NyliumMl8Service.java
  nylium-bootstrap-modlauncher9/  src/main/java/.../bootstrap/ml9/Ml9Platform.java + NyliumMl9Service.java
  nylium-testmod/                                    # verification consumer + universal jar assembly
  smoke/src/test/java/io/github/intisy/nylium/smoke/  # server-launch harness, one test per backend
```

**Responsibility split rationale:** `nylium-core` is written once; each backend is written four times. So every piece of logic that is not literally a loader API call lives in core. The `Platform` SPI staying at five methods is the mechanism that enforces this, and it is why the probe chain's generic probes are in core while only the native probe is per-backend.

---

## Task 1: Gradle skeleton and toolchains

**Files:**
- Create: `settings.gradle`, `build.gradle`, `gradle.properties`
- Create: `nylium-api/build.gradle`, `nylium-core/build.gradle`
- Copy: `gradlew`, `gradlew.bat`, `gradle/wrapper/` from `../../../vendor/baritone`
- Test: `nylium-api/src/test/java/io/github/intisy/nylium/api/BuildSanityTest.java`

**Interfaces:**
- Produces: a Gradle build where `./gradlew build` compiles every subproject at its declared Java release and runs JUnit 5 tests. Later tasks add subprojects to `settings.gradle` as they are created.

- [ ] **Step 1: Copy the Gradle wrapper from baritone**

Generating a wrapper needs a local Gradle install; copying a known-good one does not.

```bash
cd "F:/Documents/GitHub/intisy/minecraft/mods/Nylium"
cp -r ../../../vendor/baritone/gradle .
cp ../../../vendor/baritone/gradlew ../../../vendor/baritone/gradlew.bat .
ls gradle/wrapper/
```
Expected: `gradle-wrapper.jar` and `gradle-wrapper.properties` present.

- [ ] **Step 2: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2G
version=0.1.0-SNAPSHOT
```

- [ ] **Step 3: Write `settings.gradle`**

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id 'org.gradle.toolchains.foojay-resolver-convention' version '0.8.0'
}

rootProject.name = 'nylium'

include 'nylium-api'
include 'nylium-core'
```

- [ ] **Step 4: Write the root `build.gradle`**

```groovy
subprojects {
    apply plugin: 'java-library'
    apply plugin: 'maven-publish'

    group = 'io.github.intisy.nylium'
    version = rootProject.version

    repositories {
        mavenCentral()
        maven { name = 'fabric';     url = 'https://maven.fabricmc.net/' }
        maven { name = 'forge';      url = 'https://maven.minecraftforge.net/' }
        maven { name = 'neoforged';  url = 'https://maven.neoforged.net/releases/' }
        maven { name = 'sponge';     url = 'https://repo.spongepowered.org/repository/maven-public/' }
        maven {
            name = 'multimc'
            url = 'https://files.multimc.org/maven/'
            metadataSources { artifact() }
        }
    }

    java {
        toolchain { languageVersion = JavaLanguageVersion.of(21) }
    }

    tasks.withType(JavaCompile).configureEach {
        options.encoding = 'UTF-8'
        options.release = (project.findProperty('nyliumJavaRelease') ?: '8') as Integer
    }

    dependencies {
        testImplementation platform('org.junit:junit-bom:5.10.2')
        testImplementation 'org.junit.jupiter:junit-jupiter'
        testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
    }

    tasks.withType(Test).configureEach {
        useJUnitPlatform()
        testLogging { exceptionFormat = 'full' }
    }
}
```

The toolchain is 21 while `release` is 8: the build runs on a modern JDK but emits Java 8 bytecode, which is the requirement. `nyliumJavaRelease` lets the ModLauncher 9+ backend opt up to 17 from its own `gradle.properties` in Task 14.

- [ ] **Step 5: Write the two subproject build files**

`nylium-api/build.gradle`:
```groovy
// intentionally empty: nylium-api must have zero dependencies
```

`nylium-core/build.gradle`:
```groovy
dependencies {
    api project(':nylium-api')
}
```

- [ ] **Step 6: Write the build sanity test**

```java
package io.github.intisy.nylium.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildSanityTest {

    @Test
    void compilesToJava8Bytecode() {
        assertEquals("1.8", System.getProperty("java.class.version").startsWith("52") ? "1.8" : "1.8");
    }

    @Test
    void junit5Runs() {
        assertEquals(4, 2 + 2);
    }
}
```

Replace the first test with the real assertion once there is a class to inspect; for now the second test is what proves the harness runs. Delete `compilesToJava8Bytecode` in Step 8 and rely on Task 10's purity task for bytecode assertions instead.

- [ ] **Step 7: Run the build**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`, with `junit5Runs` passing.

- [ ] **Step 8: Remove the placeholder assertion**

Delete the `compilesToJava8Bytecode` method (it asserts nothing real). Keep `junit5Runs`.

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "build: gradle skeleton with java 8 release target and junit 5"
```

---

## Task 2: ModLauncher 9+ module layer spike

**Files:**
- Create: `spike/ml9/` (throwaway, deleted in Step 6)
- Create: `docs/superpowers/plans/SPIKE-modlauncher9.md` (kept)

This is the spec's highest-risk item and it runs before any kernel code, because its outcome can narrow the ModLauncher 9+ backend's declared version span. The open question: **which ModLauncher 9+ API actually gets a jar into a module layer such that its classes are loadable and its mixins apply, and does that API differ between Forge and NeoForge?**

- [ ] **Step 1: Create the spike subproject**

```bash
cd "F:/Documents/GitHub/intisy/minecraft/mods/Nylium"
mkdir -p spike/ml9/src/main/java/spike
mkdir -p spike/ml9/src/main/resources/META-INF/services
```

Add to `settings.gradle`: `include 'spike:ml9'`

`spike/ml9/build.gradle`:
```groovy
nyliumJavaRelease = 17

dependencies {
    compileOnly 'cpw.mods:modlauncher:10.0.9'
    compileOnly 'cpw.mods:securejarhandler:2.1.10'
    compileOnly 'org.spongepowered:mixin:0.8.7'
}
```

If `nyliumJavaRelease = 17` as a script assignment does not resolve (it is a project property, not an extension), put `nyliumJavaRelease=17` in `spike/ml9/gradle.properties` instead. Confirm which works and record it, because Task 14 needs the same mechanism.

- [ ] **Step 2: Write the candidate transformation service**

```java
package spike;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

public class SpikeService implements ITransformationService {

    @Override
    public String name() {
        return "nylium-spike";
    }

    @Override
    public void initialize(IEnvironment environment) {
        System.out.println("SPIKE initialize, layers=" + environment.findModuleLayerManager().isPresent());
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) {
    }

    @Override
    public List<Resource> beginScanning(IEnvironment environment) {
        Path jar = Paths.get(System.getProperty("nylium.spike.jar"));
        SecureJar secure = SecureJar.from(jar);
        System.out.println("SPIKE beginScanning offering " + secure.name());
        return List.of(new Resource(IModuleLayerManager.Layer.GAME, List.of(secure)));
    }

    @Override
    public List<? extends ITransformer<?>> transformers() {
        return List.of();
    }
}
```

`META-INF/services/cpw.mods.modlauncher.api.ITransformationService`:
```
spike.SpikeService
```

- [ ] **Step 3: Build a trivial payload jar to inject**

```bash
mkdir -p /tmp/nylium-spike/payload
cat > /tmp/nylium-spike/Payload.java <<'JAVA'
package payload;
public class Payload {
    public static void announce() {
        System.out.println("SPIKE payload loaded from module layer");
    }
}
JAVA
javac -d /tmp/nylium-spike/payload /tmp/nylium-spike/Payload.java
jar cf /tmp/nylium-spike/payload.jar -C /tmp/nylium-spike/payload .
```

- [ ] **Step 4: Run against a real NeoForge server and a real Forge server**

Install a NeoForge 1.21.11 server and a Forge 1.21.11 server into separate directories. Put the spike jar and `payload.jar` on the launch classpath, and set `-Dnylium.spike.jar=/tmp/nylium-spike/payload.jar`.

Record for each of the two:
- Does `beginScanning` get called, and is the `Resource`/`Layer` API shape as written above?
- Does `Class.forName("payload.Payload")` succeed from a later lifecycle point?
- Is `IModuleLayerManager.Layer.GAME` the right layer, or is `PLUGIN` required?
- Does `SecureJar.from(Path)` exist with that signature on this version?

- [ ] **Step 5: Write up the findings**

Create `docs/superpowers/plans/SPIKE-modlauncher9.md` recording, for Forge and NeoForge separately: the exact working API calls, the correct layer, the lifecycle point at which injected classes become loadable, and the version range each finding was verified against. Task 14 implements directly from this document.

If neither layer works on some Forge range, record that range as **unsupported** with the error, and note it for the spec's backend coverage table. Do not invent a workaround inside the spike.

- [ ] **Step 6: Delete the spike, keep the findings**

```bash
cd "F:/Documents/GitHub/intisy/minecraft/mods/Nylium"
rm -rf spike
# remove the include 'spike:ml9' line from settings.gradle
rm -rf /tmp/nylium-spike
git add -A
git commit -m "docs: record modlauncher 9 module layer injection findings"
```

---

## Task 3: nylium-api foundations and McVersion ordering

**Files:**
- Create: `nylium-api/src/main/java/io/github/intisy/nylium/api/PlatformId.java`
- Create: `nylium-api/src/main/java/io/github/intisy/nylium/api/Environment.java`
- Create: `nylium-api/src/main/java/io/github/intisy/nylium/api/NyliumException.java`
- Create: `nylium-api/src/main/java/io/github/intisy/nylium/api/McVersion.java`
- Create: `nylium-api/src/main/java/io/github/intisy/nylium/api/Platform.java`
- Test: `nylium-api/src/test/java/io/github/intisy/nylium/api/McVersionTest.java`

**Interfaces:**
- Produces:
  - `enum PlatformId { LAUNCHWRAPPER, MODLAUNCHER_8, MODLAUNCHER_9, FABRIC }`
  - `enum Environment { CLIENT, SERVER }`
  - `class NyliumException extends RuntimeException` with `(String)` and `(String, Throwable)` constructors
  - `final class McVersion implements Comparable<McVersion>` with `static McVersion parse(String)`, `String raw()`, `boolean isYearBased()`
  - `interface Platform` with `PlatformId id()`, `Environment environment()`, `void addToClasspath(Path)`, `void registerMixinConfig(String)`, `Optional<String> nativeVersionProbe()`

- [ ] **Step 1: Write the failing McVersion test**

```java
package io.github.intisy.nylium.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(!McVersion.parse("1.21.11").isYearBased());
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
        assertThrows(NyliumException.class, () -> McVersion.parse("25w14a"));
        assertThrows(NyliumException.class, () -> McVersion.parse("1.21.11-pre1"));
        assertThrows(NyliumException.class, () -> McVersion.parse(""));
    }
}
```

Snapshots and pre-releases throw rather than guess. A silently mis-ordered snapshot would select the wrong module and crash deep inside mixin application, which is the worst possible failure mode to debug.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :nylium-api:test --console=plain`
Expected: FAIL, cannot find symbol `McVersion`.

- [ ] **Step 3: Write the enums and exception**

```java
package io.github.intisy.nylium.api;

public enum PlatformId {
    LAUNCHWRAPPER,
    MODLAUNCHER_8,
    MODLAUNCHER_9,
    FABRIC
}
```

```java
package io.github.intisy.nylium.api;

public enum Environment {
    CLIENT,
    SERVER
}
```

```java
package io.github.intisy.nylium.api;

public class NyliumException extends RuntimeException {

    public NyliumException(String message) {
        super(message);
    }

    public NyliumException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: Write McVersion**

```java
package io.github.intisy.nylium.api;

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
            throw new NyliumException("Minecraft version is empty");
        }
        String[] parts = raw.split("\\.", -1);
        int[] components = new int[COMPARED_COMPONENTS];
        for (int i = 0; i < parts.length; i++) {
            if (i >= COMPARED_COMPONENTS) {
                throw new NyliumException("Minecraft version has too many components: " + raw);
            }
            try {
                components[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                throw new NyliumException(
                        "Unrecognised Minecraft version '" + raw + "'. Nylium understands release "
                                + "versions only (for example 1.21.11 or 26.2), not snapshots or "
                                + "pre-releases.", e);
            }
            if (components[i] < 0) {
                throw new NyliumException("Minecraft version component is negative: " + raw);
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
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :nylium-api:test --console=plain`
Expected: PASS, all nine tests.

- [ ] **Step 6: Write the Platform SPI**

```java
package io.github.intisy.nylium.api;

import java.nio.file.Path;
import java.util.Optional;

public interface Platform {

    PlatformId id();

    Environment environment();

    void addToClasspath(Path jar);

    void registerMixinConfig(String name);

    Optional<String> nativeVersionProbe();
}
```

- [ ] **Step 7: Run the full build**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(api): platform spi and minecraft version ordering across both schemes"
```

---

## Task 4: VersionRange

**Files:**
- Create: `nylium-core/src/main/java/io/github/intisy/nylium/core/VersionRange.java`
- Test: `nylium-core/src/test/java/io/github/intisy/nylium/core/VersionRangeTest.java`

**Interfaces:**
- Consumes: `McVersion.parse`, `McVersion.compareTo`, `NyliumException` from Task 3.
- Produces: `final class VersionRange` with `static VersionRange parse(String)`, `boolean contains(McVersion)`, `String raw()`. Ranges use Maven and Forge `mods.toml` bracket notation, which is the notation this audience already knows.

- [ ] **Step 1: Write the failing test**

```java
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
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :nylium-core:test --console=plain`
Expected: FAIL, cannot find symbol `VersionRange`.

- [ ] **Step 3: Write VersionRange**

```java
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :nylium-core:test --console=plain`
Expected: PASS, all seven tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(core): version range parsing in bracket notation"
```

---
## Task 5: ModuleDescriptor and manifest parsing

**Files:**
- Create: `nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleDescriptor.java`
- Create: `nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleManifest.java`
- Test: `nylium-core/src/test/java/io/github/intisy/nylium/core/ModuleManifestTest.java`

**Interfaces:**
- Consumes: `VersionRange.parse` (Task 4), `PlatformId`, `Environment`, `NyliumException` (Task 3).
- Produces:
  - `final class ModuleDescriptor` with `String path()`, `Set<PlatformId> platforms()`, `VersionRange minecraft()`, `Optional<Environment> environment()`, `List<String> mixinConfigs()`, `int priority()`, `int specificity()`
  - `final class ModuleManifest` with `static ModuleManifest read(Reader)`, `static ModuleManifest readFrom(ClassLoader, String resource)`, `List<ModuleDescriptor> modules()`

The manifest resource path is fixed at `nylium-modules.properties`.

Key shape, one indexed block per module:
```properties
module.0.path=modules/baritone-1.21.11-fabric.jar
module.0.platforms=FABRIC
module.0.minecraft=[1.21.11,1.21.11]
module.0.environment=CLIENT
module.0.mixins=mixins.baritone.json
module.0.priority=0
```
`platforms` and `mixins` are comma-separated. `environment` and `priority` are optional; omitting `environment` means the module suits both.

`specificity()` exists so the selector can order candidates deterministically without a hand-written comparator per field. It counts declared constraints: an environment constraint and a closed version range each narrow the module.

- [ ] **Step 1: Write the failing test**

```java
package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.PlatformId;
import io.github.intisy.nylium.api.NyliumException;
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
        assertThrows(NyliumException.class, () -> read("\n"));
    }

    @Test
    void rejectsAModuleMissingRequiredFields() {
        assertThrows(NyliumException.class, () -> read("module.0.platforms=FABRIC\n"));
        assertThrows(NyliumException.class, () -> read("module.0.path=a.jar\n"));
    }

    @Test
    void rejectsAnUnknownPlatformName() {
        NyliumException thrown = assertThrows(NyliumException.class, () -> read(
                "module.0.path=a.jar\nmodule.0.platforms=QUILT\nmodule.0.minecraft=1.21.11\n"));
        assertTrue(thrown.getMessage().contains("QUILT"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :nylium-core:test --console=plain`
Expected: FAIL, cannot find symbol `ModuleManifest`.

- [ ] **Step 3: Write ModuleDescriptor**

```java
package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.PlatformId;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class ModuleDescriptor {

    private final String path;
    private final Set<PlatformId> platforms;
    private final VersionRange minecraft;
    private final Environment environment;
    private final List<String> mixinConfigs;
    private final int priority;

    ModuleDescriptor(String path, Set<PlatformId> platforms, VersionRange minecraft,
                     Environment environment, List<String> mixinConfigs, int priority) {
        this.path = path;
        this.platforms = Collections.unmodifiableSet(platforms);
        this.minecraft = minecraft;
        this.environment = environment;
        this.mixinConfigs = Collections.unmodifiableList(mixinConfigs);
        this.priority = priority;
    }

    public String path() {
        return path;
    }

    public Set<PlatformId> platforms() {
        return platforms;
    }

    public VersionRange minecraft() {
        return minecraft;
    }

    public Optional<Environment> environment() {
        return Optional.ofNullable(environment);
    }

    public List<String> mixinConfigs() {
        return mixinConfigs;
    }

    public int priority() {
        return priority;
    }

    public int specificity() {
        int score = 0;
        if (environment != null) {
            score++;
        }
        if (minecraft.isExact()) {
            score += 2;
        } else if (minecraft.isClosed()) {
            score++;
        }
        score += PlatformId.values().length - platforms.size();
        return score;
    }

    @Override
    public String toString() {
        return path + " (platforms=" + platforms + ", minecraft=" + minecraft
                + ", environment=" + (environment == null ? "any" : environment) + ")";
    }
}
```

- [ ] **Step 4: Add `isExact` and `isClosed` to VersionRange**

Append to `VersionRange` (Task 4), alongside `contains`:

```java
    public boolean isExact() {
        return lower != null && upper != null && lowerInclusive && upperInclusive
                && lower.compareTo(upper) == 0;
    }

    public boolean isClosed() {
        return lower != null && upper != null;
    }
```

- [ ] **Step 5: Write ModuleManifest**

```java
package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.PlatformId;
import io.github.intisy.nylium.api.NyliumException;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

public final class ModuleManifest {

    public static final String RESOURCE = "nylium-modules.properties";

    private final List<ModuleDescriptor> modules;

    private ModuleManifest(List<ModuleDescriptor> modules) {
        this.modules = Collections.unmodifiableList(modules);
    }

    public static ModuleManifest readFrom(ClassLoader loader, String resource) {
        InputStream stream = loader.getResourceAsStream(resource);
        if (stream == null) {
            throw new NyliumException("No Nylium module manifest at '" + resource
                    + "'. The jar was built without one, or it was stripped by shading.");
        }
        // Properties.load(InputStream) is specified as ISO-8859-1, which would mangle any
        // non-ASCII module path; the Reader overload lets us pin UTF-8.
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return read(reader);
        } catch (IOException e) {
            throw new NyliumException("Could not read the Nylium module manifest", e);
        }
    }

    public static ModuleManifest read(Reader reader) {
        Properties properties = new Properties();
        try {
            properties.load(reader);
        } catch (IOException e) {
            throw new NyliumException("Could not parse the Nylium module manifest", e);
        }
        List<ModuleDescriptor> modules = new ArrayList<>();
        for (String index : indices(properties)) {
            modules.add(readModule(properties, index));
        }
        if (modules.isEmpty()) {
            throw new NyliumException("The Nylium module manifest declares no modules");
        }
        return new ModuleManifest(modules);
    }

    private static Iterable<String> indices(Properties properties) {
        Set<String> found = new TreeSet<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("module.")) {
                continue;
            }
            int second = key.indexOf('.', "module.".length());
            if (second > 0) {
                found.add(key.substring("module.".length(), second));
            }
        }
        return found;
    }

    private static ModuleDescriptor readModule(Properties properties, String index) {
        String prefix = "module." + index + ".";
        String path = required(properties, prefix + "path");
        VersionRange minecraft = VersionRange.parse(required(properties, prefix + "minecraft"));
        Set<PlatformId> platforms = platforms(required(properties, prefix + "platforms"));
        String environmentText = properties.getProperty(prefix + "environment");
        Environment environment = environmentText == null || environmentText.trim().isEmpty()
                ? null
                : environment(environmentText.trim());
        List<String> mixins = split(properties.getProperty(prefix + "mixins"));
        int priority = priority(properties.getProperty(prefix + "priority"), prefix);
        return new ModuleDescriptor(path, platforms, minecraft, environment, mixins, priority);
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new NyliumException("The Nylium module manifest is missing '" + key + "'");
        }
        return value.trim();
    }

    private static Set<PlatformId> platforms(String value) {
        Set<PlatformId> platforms = new LinkedHashSet<>();
        for (String name : split(value)) {
            try {
                platforms.add(PlatformId.valueOf(name));
            } catch (IllegalArgumentException e) {
                throw new NyliumException("Unknown platform '" + name + "' in the module manifest. "
                        + "Known platforms are " + Arrays.toString(PlatformId.values()) + ".", e);
            }
        }
        return platforms;
    }

    private static Environment environment(String value) {
        try {
            return Environment.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new NyliumException("Unknown environment '" + value + "' in the module manifest. "
                    + "Known environments are " + Arrays.toString(Environment.values()) + ".", e);
        }
    }

    private static int priority(String value, String prefix) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new NyliumException("'" + prefix + "priority' is not a whole number: " + value, e);
        }
    }

    private static List<String> split(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> parts = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    public List<ModuleDescriptor> modules() {
        return modules;
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :nylium-core:test --console=plain`
Expected: PASS, all eight manifest tests plus the seven range tests.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(core): module descriptors and properties manifest parsing"
```

---

## Task 6: ModuleSelector and its rejection diagnostics

**Files:**
- Create: `nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleSelector.java`
- Create: `nylium-api/src/main/java/io/github/intisy/nylium/api/NoCompatibleModuleException.java`
- Test: `nylium-core/src/test/java/io/github/intisy/nylium/core/ModuleSelectorTest.java`

**Interfaces:**
- Consumes: `ModuleManifest`, `ModuleDescriptor` (Task 5), `McVersion`, `PlatformId`, `Environment` (Task 3).
- Produces:
  - `class NoCompatibleModuleException extends NyliumException` with `(String message)`
  - `final class ModuleSelector` with `ModuleSelector(ModuleManifest)` and `ModuleDescriptor select(PlatformId, McVersion, Environment)`

Ordering is specificity descending, then priority descending, then manifest order. A miss throws with every candidate and its rejection reason, because that message is the whole support story for a library shipped to third parties.

- [ ] **Step 1: Write the failing test**

```java
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
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :nylium-core:test --console=plain`
Expected: FAIL, cannot find symbol `ModuleSelector`.

- [ ] **Step 3: Write NoCompatibleModuleException**

```java
package io.github.intisy.nylium.api;

public class NoCompatibleModuleException extends NyliumException {

    public NoCompatibleModuleException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Write ModuleSelector**

```java
package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.McVersion;
import io.github.intisy.nylium.api.NoCompatibleModuleException;
import io.github.intisy.nylium.api.PlatformId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ModuleSelector {

    private final List<ModuleDescriptor> candidates;

    public ModuleSelector(ModuleManifest manifest) {
        List<ModuleDescriptor> ordered = new ArrayList<>(manifest.modules());
        ordered.sort(Comparator
                .comparingInt(ModuleDescriptor::specificity).reversed()
                .thenComparing(Comparator.comparingInt(ModuleDescriptor::priority).reversed()));
        this.candidates = ordered;
    }

    public ModuleDescriptor select(PlatformId platform, McVersion version, Environment environment) {
        StringBuilder rejections = new StringBuilder();
        for (ModuleDescriptor candidate : candidates) {
            String rejection = reject(candidate, platform, version, environment);
            if (rejection == null) {
                return candidate;
            }
            rejections.append("\n  - ").append(candidate.path()).append(": ").append(rejection);
        }
        throw new NoCompatibleModuleException(
                "No Nylium module suits platform " + platform + ", Minecraft " + version.raw()
                        + ", environment " + environment + ". Candidates considered:" + rejections);
    }

    private static String reject(ModuleDescriptor candidate, PlatformId platform,
                                 McVersion version, Environment environment) {
        if (!candidate.platforms().contains(platform)) {
            return "declares platform " + candidate.platforms() + ", not " + platform;
        }
        if (!candidate.minecraft().contains(version)) {
            return "declares Minecraft " + candidate.minecraft().raw() + ", which excludes " + version.raw();
        }
        if (candidate.environment().isPresent() && candidate.environment().get() != environment) {
            return "declares environment " + candidate.environment().get() + ", not " + environment;
        }
        return null;
    }
}
```

Sorting once in the constructor rather than per call is what makes `selectionIsStableAcrossRepeatedCalls` hold by construction. `Comparator` ordering combined with `List.sort` being stable means manifest order remains the final tie-break without needing an explicit index field.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :nylium-core:test --console=plain`
Expected: PASS, all seven selector tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(core): deterministic module selection with rejection diagnostics"
```

---

## Task 7: ModuleExtractor

**Files:**
- Create: `nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleExtractor.java`
- Test: `nylium-core/src/test/java/io/github/intisy/nylium/core/ModuleExtractorTest.java`

**Interfaces:**
- Consumes: `ModuleDescriptor` (Task 5), `NyliumException` (Task 3).
- Produces: `final class ModuleExtractor` with `ModuleExtractor(Path cacheDirectory)` and `Path extract(ClassLoader source, ModuleDescriptor module)`.

Nested jars must exist as real files, because Fabric extracts them and ModLauncher needs a `Path`. The cache is keyed by SHA-256 of the nested entry's bytes, so a rebuilt module invalidates automatically and an unchanged one is never rewritten. Two game instances launching at once must not see a half-written file, so writes go to a temporary name and land via an atomic rename.

- [ ] **Step 1: Write the failing test**

```java
package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.NyliumException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleExtractorTest {

    private static ModuleDescriptor descriptor(String path) {
        return ModuleManifest.read(new StringReader(
                        "module.0.path=" + path + "\n"
                                + "module.0.platforms=FABRIC\n"
                                + "module.0.minecraft=1.21.11\n"))
                .modules().get(0);
    }

    private static ClassLoader outerJarContaining(Path dir, String entry, byte[] payload) throws Exception {
        Path outer = dir.resolve("outer.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outer))) {
            jar.putNextEntry(new JarEntry(entry));
            jar.write(payload);
            jar.closeEntry();
        }
        return new URLClassLoader(new URL[]{outer.toUri().toURL()}, null);
    }

    private static byte[] tinyJar() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("payload.txt"));
            jar.write("hello".getBytes("UTF-8"));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test
    void extractsTheNestedJarToTheCache(@TempDir Path dir) throws Exception {
        byte[] payload = tinyJar();
        ClassLoader loader = outerJarContaining(dir, "modules/a.jar", payload);
        ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));

        Path extracted = extractor.extract(loader, descriptor("modules/a.jar"));

        assertTrue(Files.isRegularFile(extracted));
        assertArrayEquals(payload, Files.readAllBytes(extracted));
    }

    @Test
    void reusesAnAlreadyExtractedModule(@TempDir Path dir) throws Exception {
        ClassLoader loader = outerJarContaining(dir, "modules/a.jar", tinyJar());
        ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));
        ModuleDescriptor module = descriptor("modules/a.jar");

        Path first = extractor.extract(loader, module);
        long stamp = Files.getLastModifiedTime(first).toMillis();
        Path second = extractor.extract(loader, module);

        assertEquals(first, second);
        assertEquals(stamp, Files.getLastModifiedTime(second).toMillis());
    }

    @Test
    void differentContentLandsInDifferentCacheEntries(@TempDir Path dir) throws Exception {
        Path cache = dir.resolve("cache");
        Path first = new ModuleExtractor(cache)
                .extract(outerJarContaining(dir.resolve("a"), "m.jar", tinyJar()), descriptor("m.jar"));

        ByteArrayOutputStream other = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(other)) {
            jar.putNextEntry(new JarEntry("payload.txt"));
            jar.write("goodbye".getBytes("UTF-8"));
            jar.closeEntry();
        }
        Path second = new ModuleExtractor(cache)
                .extract(outerJarContaining(dir.resolve("b"), "m.jar", other.toByteArray()), descriptor("m.jar"));

        assertTrue(!first.equals(second));
    }

    @Test
    void concurrentExtractionYieldsOneCompleteFile(@TempDir Path dir) throws Exception {
        byte[] payload = tinyJar();
        ClassLoader loader = outerJarContaining(dir, "modules/a.jar", payload);
        Path cache = dir.resolve("cache");
        ModuleDescriptor module = descriptor("modules/a.jar");

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        java.util.List<java.util.concurrent.Future<Path>> results = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> new ModuleExtractor(cache).extract(loader, module)));
        }
        for (java.util.concurrent.Future<Path> result : results) {
            assertArrayEquals(payload, Files.readAllBytes(result.get()));
        }
        pool.shutdown();
    }

    @Test
    void aMissingNestedEntryFailsClearly(@TempDir Path dir) throws Exception {
        ClassLoader loader = outerJarContaining(dir, "modules/a.jar", tinyJar());
        ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));

        NyliumException thrown = assertThrows(NyliumException.class,
                () -> extractor.extract(loader, descriptor("modules/absent.jar")));
        assertTrue(thrown.getMessage().contains("modules/absent.jar"), thrown.getMessage());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :nylium-core:test --console=plain`
Expected: FAIL, cannot find symbol `ModuleExtractor`.

- [ ] **Step 3: Write ModuleExtractor**

```java
package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.NyliumException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ModuleExtractor {

    private final Path cacheDirectory;

    public ModuleExtractor(Path cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    public Path extract(ClassLoader source, ModuleDescriptor module) {
        byte[] bytes = read(source, module.path());
        Path target = cacheDirectory.resolve(fileName(module, bytes));
        if (Files.isRegularFile(target)) {
            return target;
        }
        write(bytes, target);
        return target;
    }

    private static byte[] read(ClassLoader source, String path) {
        try (InputStream stream = source.getResourceAsStream(path)) {
            if (stream == null) {
                throw new NyliumException("The Nylium manifest names module '" + path
                        + "' but no such entry exists in the jar.");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new NyliumException("Could not read module '" + path + "' from the jar", e);
        }
    }

    private static String fileName(ModuleDescriptor module, byte[] bytes) {
        String base = module.path();
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        if (base.endsWith(".jar")) {
            base = base.substring(0, base.length() - ".jar".length());
        }
        return base + "-" + sha256(bytes).substring(0, 16) + ".jar";
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new NyliumException("SHA-256 is unavailable on this JVM", e);
        }
    }

    private void write(byte[] bytes, Path target) {
        try {
            Files.createDirectories(cacheDirectory);
            // A second game instance may be extracting the same module: write under a unique
            // name and land it atomically, so nobody ever opens a half-written jar.
            Path temporary = Files.createTempFile(cacheDirectory, "nylium-", ".jar.part");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException | java.nio.file.FileAlreadyExistsException e) {
                if (Files.isRegularFile(target)) {
                    Files.deleteIfExists(temporary);
                } else {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException e) {
            throw new NyliumException("Could not write module to the Nylium cache at " + target, e);
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :nylium-core:test --console=plain`
Expected: PASS, all five extractor tests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(core): hash-keyed module extraction with atomic landing"
```

---
## Task 8: Version probe chain

**Files:**
- Create: `nylium-core/src/main/java/io/github/intisy/nylium/core/probe/VersionProbe.java`
- Create: `nylium-core/src/main/java/io/github/intisy/nylium/core/probe/VersionJsonProbe.java`
- Create: `nylium-core/src/main/java/io/github/intisy/nylium/core/probe/MarkerClassProbe.java`
- Create: `nylium-core/src/main/java/io/github/intisy/nylium/core/probe/ProbeChain.java`
- Test: `nylium-core/src/test/java/io/github/intisy/nylium/core/probe/ProbeChainTest.java`
- Test: `nylium-core/src/test/java/io/github/intisy/nylium/core/probe/VersionJsonProbeTest.java`
- Test: `nylium-core/src/test/java/io/github/intisy/nylium/core/probe/MarkerClassProbeTest.java`

**Interfaces:**
- Consumes: `McVersion`, `NyliumException` (Task 3).
- Produces:
  - `interface VersionProbe` with `String name()` and `Optional<String> detect()`
  - `final class VersionJsonProbe implements VersionProbe` with `VersionJsonProbe(ClassLoader)`
  - `final class MarkerClassProbe implements VersionProbe` with `MarkerClassProbe(ClassLoader)`
  - `final class ProbeChain` with `ProbeChain(List<VersionProbe>)` and `McVersion detect()`

No single strategy is reliable across twenty versions; Fabric Loader itself falls through eight. The chain returns the first probe that yields a parseable version, and on total failure reports every probe it tried so the user can say which one should have worked.

`VersionJsonProbe` reads Mojang's `version.json`, present at the root of the Minecraft jar since 1.14. It extracts the `"id"` field with a targeted scan rather than a JSON parser, because `nylium-core` carries zero dependencies.

`MarkerClassProbe` covers the pre-1.14 versions that have no `version.json`, mapping the presence of a class that appeared in a known version to a floor.

- [ ] **Step 1: Write the failing chain test**

```java
package io.github.intisy.nylium.core.probe;

import io.github.intisy.nylium.api.McVersion;
import io.github.intisy.nylium.api.NyliumException;
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

        NyliumException thrown = assertThrows(NyliumException.class, chain::detect);
        assertTrue(thrown.getMessage().contains("alpha"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("beta"), thrown.getMessage());
    }

    @Test
    void rejectsAnEmptyChain() {
        assertThrows(NyliumException.class, () -> new ProbeChain(Collections.emptyList()));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :nylium-core:test --console=plain`
Expected: FAIL, cannot find symbol `ProbeChain`.

- [ ] **Step 3: Write VersionProbe and ProbeChain**

```java
package io.github.intisy.nylium.core.probe;

import java.util.Optional;

public interface VersionProbe {

    String name();

    Optional<String> detect();
}
```

```java
package io.github.intisy.nylium.core.probe;

import io.github.intisy.nylium.api.McVersion;
import io.github.intisy.nylium.api.NyliumException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class ProbeChain {

    private final List<VersionProbe> probes;

    public ProbeChain(List<VersionProbe> probes) {
        if (probes == null || probes.isEmpty()) {
            throw new NyliumException("A version probe chain needs at least one probe");
        }
        this.probes = Collections.unmodifiableList(new ArrayList<>(probes));
    }

    public McVersion detect() {
        StringBuilder attempts = new StringBuilder();
        for (VersionProbe probe : probes) {
            String outcome;
            try {
                Optional<String> detected = probe.detect();
                if (detected.isPresent()) {
                    try {
                        return McVersion.parse(detected.get());
                    } catch (NyliumException e) {
                        outcome = "found '" + detected.get() + "', which Nylium cannot read";
                    }
                } else {
                    outcome = "found nothing";
                }
            } catch (RuntimeException e) {
                outcome = "failed with " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            attempts.append("\n  - ").append(probe.name()).append(": ").append(outcome);
        }
        throw new NyliumException(
                "Nylium could not determine the running Minecraft version. Probes tried:" + attempts);
    }
}
```

A probe that throws is skipped rather than fatal: probes reach into loader internals that move between versions, so one being broken on a given version must not prevent a later probe from succeeding.

- [ ] **Step 4: Run the chain test to verify it passes**

Run: `./gradlew :nylium-core:test --tests '*ProbeChainTest' --console=plain`
Expected: PASS, all five tests.

- [ ] **Step 5: Write the failing VersionJsonProbe test**

```java
package io.github.intisy.nylium.core.probe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VersionJsonProbeTest {

    private static ClassLoader withVersionJson(Path dir, String json) throws Exception {
        Path jar = dir.resolve("mc.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            if (json != null) {
                out.putNextEntry(new JarEntry("version.json"));
                out.write(json.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            } else {
                out.putNextEntry(new JarEntry("unrelated.txt"));
                out.closeEntry();
            }
        }
        return new URLClassLoader(new URL[]{jar.toUri().toURL()}, null);
    }

    @Test
    void readsTheIdField(@TempDir Path dir) throws Exception {
        ClassLoader loader = withVersionJson(dir,
                "{\"id\": \"1.21.11\", \"name\": \"1.21.11\", \"world_version\": 4189}");

        assertEquals("1.21.11", new VersionJsonProbe(loader).detect().orElseThrow(AssertionError::new));
    }

    @Test
    void toleratesWhitespaceAndFieldOrder(@TempDir Path dir) throws Exception {
        ClassLoader loader = withVersionJson(dir, "{\n  \"world_version\" : 1 ,\n  \"id\"\n:\n\"26.2\"\n}");

        assertEquals("26.2", new VersionJsonProbe(loader).detect().orElseThrow(AssertionError::new));
    }

    @Test
    void findsNothingWhenTheResourceIsAbsent(@TempDir Path dir) throws Exception {
        assertFalse(new VersionJsonProbe(withVersionJson(dir, null)).detect().isPresent());
    }

    @Test
    void findsNothingWhenIdIsAbsent(@TempDir Path dir) throws Exception {
        ClassLoader loader = withVersionJson(dir, "{\"name\": \"1.21.11\"}");

        assertFalse(new VersionJsonProbe(loader).detect().isPresent());
    }
}
```

- [ ] **Step 6: Write VersionJsonProbe**

```java
package io.github.intisy.nylium.core.probe;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VersionJsonProbe implements VersionProbe {

    private static final String RESOURCE = "version.json";

    // nylium-core carries zero dependencies, so the one field we need is matched directly
    // rather than by parsing the document.
    private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");

    private final ClassLoader loader;

    public VersionJsonProbe(ClassLoader loader) {
        this.loader = loader;
    }

    @Override
    public String name() {
        return "version.json on the classpath";
    }

    @Override
    public Optional<String> detect() {
        try (InputStream stream = loader.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return Optional.empty();
            }
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    text.append(line).append('\n');
                }
            }
            Matcher matcher = ID.matcher(text);
            return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 7: Write the failing MarkerClassProbe test**

```java
package io.github.intisy.nylium.core.probe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MarkerClassProbeTest {

    private static ClassLoader loaderKnowing(final String... classNames) {
        final java.util.Set<String> known = new java.util.HashSet<>(java.util.Arrays.asList(classNames));
        return new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (known.contains(name)) {
                    return Object.class;
                }
                throw new ClassNotFoundException(name);
            }
        };
    }

    @Test
    void reportsTheNewestMarkerPresent() {
        ClassLoader loader = loaderKnowing(
                "net.minecraft.util.registry.Registry",
                "net.minecraft.block.Blocks");

        assertEquals("1.13", new MarkerClassProbe(loader).detect().orElseThrow(AssertionError::new));
    }

    @Test
    void reportsAnOlderMarkerWhenNewerOnesAreAbsent() {
        ClassLoader loader = loaderKnowing("net.minecraft.block.Blocks");

        assertEquals("1.9", new MarkerClassProbe(loader).detect().orElseThrow(AssertionError::new));
    }

    @Test
    void findsNothingWhenNoMarkerIsPresent() {
        assertFalse(new MarkerClassProbe(loaderKnowing()).detect().isPresent());
    }
}
```

- [ ] **Step 8: Write MarkerClassProbe**

```java
package io.github.intisy.nylium.core.probe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class MarkerClassProbe implements VersionProbe {

    // Ordered newest first: the newest marker present wins. This probe only has to cover the
    // pre-1.14 versions, where Mojang shipped no version.json, so it reports a floor rather
    // than an exact version and callers should treat it as a last resort.
    private static final Map<String, String> MARKERS = new LinkedHashMap<>();

    static {
        MARKERS.put("net.minecraft.util.registry.Registry", "1.13");
        MARKERS.put("net.minecraft.block.Blocks", "1.9");
        MARKERS.put("net.minecraft.init.Blocks", "1.7.10");
    }

    private final ClassLoader loader;

    public MarkerClassProbe(ClassLoader loader) {
        this.loader = loader;
    }

    @Override
    public String name() {
        return "marker class presence";
    }

    @Override
    public Optional<String> detect() {
        for (Map.Entry<String, String> marker : MARKERS.entrySet()) {
            try {
                Class.forName(marker.getKey(), false, loader);
                return Optional.of(marker.getValue());
            } catch (ClassNotFoundException | LinkageError e) {
                // not this one; try the next marker
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 9: Run every probe test**

Run: `./gradlew :nylium-core:test --console=plain`
Expected: PASS, twelve probe tests plus everything from Tasks 4 to 7.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat(core): version probe chain with version.json and marker class fallbacks"
```

---

## Task 9: NyliumKernel and the fake-platform integration test

**Files:**
- Create: `nylium-core/src/main/java/io/github/intisy/nylium/core/NyliumKernel.java`
- Test: `nylium-core/src/test/java/io/github/intisy/nylium/core/NyliumKernelTest.java`
- Test: `nylium-core/src/test/java/io/github/intisy/nylium/core/FakePlatform.java`

**Interfaces:**
- Consumes: `Platform` (Task 3), `ModuleManifest`, `ModuleSelector`, `ModuleExtractor`, `ProbeChain`, `VersionJsonProbe`, `MarkerClassProbe`.
- Produces: `final class NyliumKernel` with `static ModuleDescriptor boot(Platform platform, ClassLoader source, Path cacheDirectory)`.

This is the whole flow in one place, and it is the last piece testable without a game. Every backend's job reduces to constructing a `Platform` and calling `boot`.

Order matters and is load-bearing: the module must be on the classpath **before** its mixin configs are registered, since Mixin resolves config resources at registration time.

- [ ] **Step 1: Write the fake platform**

```java
package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.Platform;
import io.github.intisy.nylium.api.PlatformId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class FakePlatform implements Platform {

    private final PlatformId id;
    private final Environment environment;
    private final String nativeVersion;

    final List<Path> classpathAdditions = new ArrayList<>();
    final List<String> registeredConfigs = new ArrayList<>();
    final List<String> callOrder = new ArrayList<>();

    FakePlatform(PlatformId id, Environment environment, String nativeVersion) {
        this.id = id;
        this.environment = environment;
        this.nativeVersion = nativeVersion;
    }

    @Override
    public PlatformId id() {
        return id;
    }

    @Override
    public Environment environment() {
        return environment;
    }

    @Override
    public void addToClasspath(Path jar) {
        classpathAdditions.add(jar);
        callOrder.add("classpath");
    }

    @Override
    public void registerMixinConfig(String name) {
        registeredConfigs.add(name);
        callOrder.add("mixin:" + name);
    }

    @Override
    public Optional<String> nativeVersionProbe() {
        return Optional.ofNullable(nativeVersion);
    }
}
```

- [ ] **Step 2: Write the failing kernel test**

```java
package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.NoCompatibleModuleException;
import io.github.intisy.nylium.api.PlatformId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NyliumKernelTest {

    private static final String MANIFEST =
            "module.0.path=modules/mod-1.21.11.jar\n"
                    + "module.0.platforms=FABRIC,MODLAUNCHER_9\n"
                    + "module.0.minecraft=1.21.11\n"
                    + "module.0.mixins=mixins.mod.json,mixins.mod.extra.json\n"
                    + "module.1.path=modules/mod-1.21.10.jar\n"
                    + "module.1.platforms=FABRIC\n"
                    + "module.1.minecraft=1.21.10\n"
                    + "module.1.mixins=mixins.mod.json\n";

    private static byte[] tinyJar(String marker) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("marker.txt"));
            jar.write(marker.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static ClassLoader outerJar(Path dir) throws Exception {
        Path outer = dir.resolve("outer.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outer))) {
            jar.putNextEntry(new JarEntry(ModuleManifest.RESOURCE));
            jar.write(MANIFEST.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("modules/mod-1.21.11.jar"));
            jar.write(tinyJar("newer"));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("modules/mod-1.21.10.jar"));
            jar.write(tinyJar("older"));
            jar.closeEntry();
        }
        return new URLClassLoader(new URL[]{outer.toUri().toURL()}, null);
    }

    @Test
    void loadsTheModuleMatchingTheNativeProbe(@TempDir Path dir) throws Exception {
        FakePlatform platform = new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, "1.21.11");

        ModuleDescriptor loaded = NyliumKernel.boot(platform, outerJar(dir), dir.resolve("cache"));

        assertEquals("modules/mod-1.21.11.jar", loaded.path());
        assertEquals(1, platform.classpathAdditions.size());
        assertTrue(Files.isRegularFile(platform.classpathAdditions.get(0)));
    }

    @Test
    void discriminatesBetweenTwoVersionsInOneJar(@TempDir Path dir) throws Exception {
        ClassLoader source = outerJar(dir);

        assertEquals("modules/mod-1.21.10.jar",
                NyliumKernel.boot(new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, "1.21.10"),
                        source, dir.resolve("cache")).path());
        assertEquals("modules/mod-1.21.11.jar",
                NyliumKernel.boot(new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, "1.21.11"),
                        source, dir.resolve("cache")).path());
    }

    @Test
    void registersEveryMixinConfigOfTheChosenModule(@TempDir Path dir) throws Exception {
        FakePlatform platform = new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, "1.21.11");

        NyliumKernel.boot(platform, outerJar(dir), dir.resolve("cache"));

        assertEquals(java.util.Arrays.asList("mixins.mod.json", "mixins.mod.extra.json"),
                platform.registeredConfigs);
    }

    @Test
    void classpathsTheModuleBeforeRegisteringItsMixins(@TempDir Path dir) throws Exception {
        FakePlatform platform = new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, "1.21.11");

        NyliumKernel.boot(platform, outerJar(dir), dir.resolve("cache"));

        assertEquals("classpath", platform.callOrder.get(0));
    }

    @Test
    void selectsPerPlatformFromTheSameManifest(@TempDir Path dir) throws Exception {
        ClassLoader source = outerJar(dir);

        assertEquals("modules/mod-1.21.11.jar",
                NyliumKernel.boot(new FakePlatform(PlatformId.MODLAUNCHER_9, Environment.SERVER, "1.21.11"),
                        source, dir.resolve("cache")).path());

        assertThrows(NoCompatibleModuleException.class,
                () -> NyliumKernel.boot(new FakePlatform(PlatformId.MODLAUNCHER_9, Environment.SERVER, "1.21.10"),
                        source, dir.resolve("cache")));
    }

    @Test
    void fallsBackToTheClasspathProbesWhenThePlatformCannotTell(@TempDir Path dir) throws Exception {
        Path outerDir = Files.createDirectories(dir.resolve("outer"));
        Path outer = outerDir.resolve("outer.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outer))) {
            jar.putNextEntry(new JarEntry(ModuleManifest.RESOURCE));
            jar.write(MANIFEST.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("modules/mod-1.21.11.jar"));
            jar.write(tinyJar("newer"));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("version.json"));
            jar.write("{\"id\":\"1.21.11\"}".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        ClassLoader source = new URLClassLoader(new URL[]{outer.toUri().toURL()}, null);
        FakePlatform platform = new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, null);

        assertEquals("modules/mod-1.21.11.jar",
                NyliumKernel.boot(platform, source, dir.resolve("cache")).path());
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :nylium-core:test --console=plain`
Expected: FAIL, cannot find symbol `NyliumKernel`.

- [ ] **Step 4: Write NyliumKernel**

```java
package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.McVersion;
import io.github.intisy.nylium.api.Platform;
import io.github.intisy.nylium.core.probe.MarkerClassProbe;
import io.github.intisy.nylium.core.probe.ProbeChain;
import io.github.intisy.nylium.core.probe.VersionJsonProbe;
import io.github.intisy.nylium.core.probe.VersionProbe;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class NyliumKernel {

    private NyliumKernel() {
    }

    public static ModuleDescriptor boot(Platform platform, ClassLoader source, Path cacheDirectory) {
        ModuleManifest manifest = ModuleManifest.readFrom(source, ModuleManifest.RESOURCE);
        McVersion version = new ProbeChain(probes(platform, source)).detect();
        ModuleDescriptor module =
                new ModuleSelector(manifest).select(platform.id(), version, platform.environment());

        Path extracted = new ModuleExtractor(cacheDirectory).extract(source, module);
        // Mixin resolves a config's resources when the config is registered, so the module has
        // to be reachable on the classpath first.
        platform.addToClasspath(extracted);
        for (String config : module.mixinConfigs()) {
            platform.registerMixinConfig(config);
        }
        return module;
    }

    private static List<VersionProbe> probes(Platform platform, ClassLoader source) {
        List<VersionProbe> probes = new ArrayList<>();
        probes.add(new VersionProbe() {
            @Override
            public String name() {
                return platform.id() + " native probe";
            }

            @Override
            public Optional<String> detect() {
                return platform.nativeVersionProbe();
            }
        });
        probes.add(new VersionJsonProbe(source));
        probes.add(new MarkerClassProbe(source));
        return probes;
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :nylium-core:test --console=plain`
Expected: PASS, all six kernel tests and every earlier core test.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(core): kernel orchestration from probe through mixin registration"
```

---

## Task 10: checkApiPurity

**Files:**
- Create: `buildSrc/build.gradle`
- Create: `buildSrc/src/main/java/io/github/intisy/nylium/gradle/ApiPurityTask.java`
- Modify: `nylium-api/build.gradle`
- Test: `buildSrc/src/test/java/io/github/intisy/nylium/gradle/ApiPurityTaskTest.java`

**Interfaces:**
- Produces: a Gradle task type `ApiPurityTask` with an input `jar` property, registered in `nylium-api` as `checkApiPurity` and wired into `check`.

This mechanically enforces the program's founding compatibility contract. It runs from the first commit, before there is any API worth protecting, because retrofitting purity onto an already-leaked API is not practical.

The scan must cover more than method descriptors. A `net.minecraft` type can reach the API surface through a supertype, an interface, a field type, a generic signature, an annotation, or a thrown exception, and each of those would equally break the contract.

- [ ] **Step 1: Write `buildSrc/build.gradle`**

```groovy
plugins {
    id 'groovy-gradle-plugin'
    id 'java'
}

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.ow2.asm:asm:9.7'
    testImplementation platform('org.junit:junit-bom:5.10.2')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}

tasks.withType(Test).configureEach {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Write the failing purity test**

```java
package io.github.intisy.nylium.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiPurityTaskTest {

    private static Path classFileFor(Path dir, String source, String className) throws Exception {
        Path java = dir.resolve(className + ".java");
        Files.write(java, source.getBytes("UTF-8"));
        Path out = Files.createDirectories(dir.resolve("out"));
        int status = new ProcessBuilder("javac", "-d", out.toString(), java.toString())
                .inheritIO().start().waitFor();
        assertEquals(0, status, "the fixture must compile");
        return out.resolve(className + ".class");
    }

    @Test
    void acceptsAClassWithNoMinecraftReference(@TempDir Path dir) throws Exception {
        Path clazz = classFileFor(dir,
                "public class Clean { public String hello() { return \"hi\"; } }", "Clean");

        assertTrue(ApiPurityScanner.scan(Files.readAllBytes(clazz)).isEmpty());
    }

    @Test
    void rejectsAMinecraftReturnType(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("net/minecraft"));
        Files.write(dir.resolve("net/minecraft/Level.java"),
                "package net.minecraft; public class Level {}".getBytes("UTF-8"));
        Path java = dir.resolve("Leaky.java");
        Files.write(java, "public class Leaky { public net.minecraft.Level level() { return null; } }"
                .getBytes("UTF-8"));
        Path out = Files.createDirectories(dir.resolve("out"));
        assertEquals(0, new ProcessBuilder("javac", "-d", out.toString(),
                dir.resolve("net/minecraft/Level.java").toString(), java.toString())
                .inheritIO().start().waitFor());

        List<String> findings = ApiPurityScanner.scan(Files.readAllBytes(out.resolve("Leaky.class")));

        assertEquals(1, findings.size(), findings.toString());
        assertTrue(findings.get(0).contains("level"), findings.toString());
    }

    @Test
    void rejectsAMinecraftFieldType(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("net/minecraft"));
        Files.write(dir.resolve("net/minecraft/Level.java"),
                "package net.minecraft; public class Level {}".getBytes("UTF-8"));
        Path java = dir.resolve("LeakyField.java");
        Files.write(java, "public class LeakyField { public net.minecraft.Level level; }".getBytes("UTF-8"));
        Path out = Files.createDirectories(dir.resolve("out"));
        assertEquals(0, new ProcessBuilder("javac", "-d", out.toString(),
                dir.resolve("net/minecraft/Level.java").toString(), java.toString())
                .inheritIO().start().waitFor());

        assertEquals(1, ApiPurityScanner.scan(Files.readAllBytes(out.resolve("LeakyField.class"))).size());
    }

    @Test
    void rejectsAMinecraftGenericArgument(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("net/minecraft"));
        Files.write(dir.resolve("net/minecraft/Level.java"),
                "package net.minecraft; public class Level {}".getBytes("UTF-8"));
        Path java = dir.resolve("LeakyGeneric.java");
        Files.write(java, ("import java.util.List;\n"
                + "public class LeakyGeneric { public List<net.minecraft.Level> levels() { return null; } }")
                .getBytes("UTF-8"));
        Path out = Files.createDirectories(dir.resolve("out"));
        assertEquals(0, new ProcessBuilder("javac", "-d", out.toString(),
                dir.resolve("net/minecraft/Level.java").toString(), java.toString())
                .inheritIO().start().waitFor());

        assertTrue(!ApiPurityScanner.scan(Files.readAllBytes(out.resolve("LeakyGeneric.class"))).isEmpty());
    }

    @Test
    void ignoresPrivateInternals(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("net/minecraft"));
        Files.write(dir.resolve("net/minecraft/Level.java"),
                "package net.minecraft; public class Level {}".getBytes("UTF-8"));
        Path java = dir.resolve("PrivateUse.java");
        Files.write(java, "public class PrivateUse { private net.minecraft.Level hidden; }".getBytes("UTF-8"));
        Path out = Files.createDirectories(dir.resolve("out"));
        assertEquals(0, new ProcessBuilder("javac", "-d", out.toString(),
                dir.resolve("net/minecraft/Level.java").toString(), java.toString())
                .inheritIO().start().waitFor());

        assertTrue(ApiPurityScanner.scan(Files.readAllBytes(out.resolve("PrivateUse.class"))).isEmpty());
    }
}
```

The last test encodes a deliberate decision: the contract is about the **public** surface. A private field cannot be depended on by a consumer, so it cannot break their compatibility. Restricting the scan to public and protected members keeps `nylium-api` able to hold an internal helper if it ever needs one, without weakening the promise.

- [ ] **Step 3: Write the scanner and the task**

```java
package io.github.intisy.nylium.gradle;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

public final class ApiPurityScanner {

    private static final String FORBIDDEN = "net/minecraft/";

    private ApiPurityScanner() {
    }

    public static List<String> scan(byte[] classFile) {
        final List<String> findings = new ArrayList<>();
        new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {

            private String owner;

            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                owner = name;
                check("class " + name + " supertype", superName);
                check("class " + name + " signature", signature);
                if (interfaces != null) {
                    for (String each : interfaces) {
                        check("class " + name + " interface", each);
                    }
                }
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if (isPublicSurface(access)) {
                    check("field " + owner + "." + name, descriptor);
                    check("field " + owner + "." + name + " signature", signature);
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (isPublicSurface(access)) {
                    check("method " + owner + "." + name, descriptor);
                    check("method " + owner + "." + name + " signature", signature);
                    if (exceptions != null) {
                        for (String each : exceptions) {
                            check("method " + owner + "." + name + " throws", each);
                        }
                    }
                }
                return null;
            }

            private boolean isPublicSurface(int access) {
                return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0;
            }

            private void check(String where, String text) {
                if (text != null && text.contains(FORBIDDEN)) {
                    findings.add(where + " references " + FORBIDDEN);
                }
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return findings;
    }
}
```

```java
package io.github.intisy.nylium.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public abstract class ApiPurityTask extends DefaultTask {

    @InputFile
    public abstract RegularFileProperty getJar();

    @TaskAction
    public void check() throws IOException {
        List<String> findings = new ArrayList<>();
        try (ZipFile zip = new ZipFile(getJar().get().getAsFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream stream = zip.getInputStream(entry)) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int read;
                    while ((read = stream.read(chunk)) >= 0) {
                        buffer.write(chunk, 0, read);
                    }
                    findings.addAll(ApiPurityScanner.scan(buffer.toByteArray()));
                }
            }
        }
        if (!findings.isEmpty()) {
            throw new GradleException("nylium-api must not expose net.minecraft types:\n  "
                    + String.join("\n  ", findings));
        }
    }
}
```

- [ ] **Step 4: Run the scanner tests**

Run: `./gradlew :buildSrc:test --console=plain`
Expected: PASS, all five tests.

- [ ] **Step 5: Wire the task into nylium-api**

Replace `nylium-api/build.gradle` with:

```groovy
import io.github.intisy.nylium.gradle.ApiPurityTask

// intentionally no dependencies: nylium-api must stay standalone

tasks.register('checkApiPurity', ApiPurityTask) {
    jar = tasks.jar.archiveFile
    dependsOn tasks.jar
}

tasks.named('check') {
    dependsOn tasks.named('checkApiPurity')
}
```

- [ ] **Step 6: Prove the check runs and passes**

Run: `./gradlew :nylium-api:check --console=plain`
Expected: `BUILD SUCCESSFUL` with `checkApiPurity` executed.

- [ ] **Step 7: Prove the check actually fails on a violation**

Temporarily add to `nylium-api/src/main/java/io/github/intisy/nylium/api/Leak.java`:
```java
package io.github.intisy.nylium.api;

public class Leak {
    public net.minecraft.Level level() {
        return null;
    }
}
```
This will not compile (no Minecraft on the classpath), which proves a different point. Instead, verify the negative case by running the `buildSrc` test suite, which compiles real fixtures against a stub `net.minecraft.Level`. Then delete the file if created:
```bash
rm -f nylium-api/src/main/java/io/github/intisy/nylium/api/Leak.java
```
Record in the commit message that the negative case is covered by `ApiPurityTaskTest`, not by a live violation, because `nylium-api` cannot compile against Minecraft by construction. That inability is itself a second layer of the same guarantee.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "build: fail the build when nylium-api exposes a minecraft type"
```

---
## Task 11: Module entrypoints, the test mod, and universal jar assembly

**Files:**
- Modify: `nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleDescriptor.java`
- Modify: `nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleManifest.java`
- Modify: `nylium-core/src/main/java/io/github/intisy/nylium/core/NyliumKernel.java`
- Modify: `nylium-core/src/test/java/io/github/intisy/nylium/core/NyliumKernelTest.java`
- Create: `nylium-testmod/build.gradle`
- Create: `nylium-testmod/src/main/java/io/github/intisy/nylium/testmod/TestModEntry.java`
- Create: `smoke/build.gradle`
- Create: `smoke/src/test/java/io/github/intisy/nylium/smoke/ServerSmokeHarness.java`
- Modify: `settings.gradle`

**Interfaces:**
- Produces:
  - `Optional<String> ModuleDescriptor.entrypoint()`, read from `module.N.entrypoint`
  - `NyliumKernel.boot` invoking `public static void nyliumInit()` on the entrypoint class after mixin registration
  - `ServerSmokeHarness.run(Path serverDirectory, List<String> command, Path markerFile, Duration timeout)` returning the marker's contents

**Why the entrypoint is needed:** by the time Nylium dispatches, the loader has already finished discovering mods. Nothing in the freshly classpathed module would ever run on its own, so the kernel has to call into it. This is the piece that makes a loaded module actually a mod.

**Why the smoke tests run servers:** a dedicated server is headless by nature, scriptable, and needs no display. Better still, Nylium's dispatch completes during preLaunch or the transformation-service phase, long before world generation, so the harness can poll for the marker and kill the process without ever letting the game finish booting. No EULA, no world, no display, and a few seconds per case.

- [ ] **Step 1: Write the failing entrypoint test**

Add to `NyliumKernelTest`:

```java
    public static class RecordingEntrypoint {
        static int invocations = 0;

        public static void nyliumInit() {
            invocations++;
        }
    }

    @Test
    void invokesTheModuleEntrypointAfterMixinRegistration(@TempDir Path dir) throws Exception {
        RecordingEntrypoint.invocations = 0;
        String manifest = "module.0.path=modules/mod-1.21.11.jar\n"
                + "module.0.platforms=FABRIC\n"
                + "module.0.minecraft=1.21.11\n"
                + "module.0.mixins=mixins.mod.json\n"
                + "module.0.entrypoint=" + RecordingEntrypoint.class.getName() + "\n";
        Path outer = dir.resolve("outer.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outer))) {
            jar.putNextEntry(new JarEntry(ModuleManifest.RESOURCE));
            jar.write(manifest.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("modules/mod-1.21.11.jar"));
            jar.write(tinyJar("newer"));
            jar.closeEntry();
        }
        ClassLoader source = new URLClassLoader(new URL[]{outer.toUri().toURL()},
                getClass().getClassLoader());
        FakePlatform platform = new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, "1.21.11");

        NyliumKernel.boot(platform, source, dir.resolve("cache"));

        assertEquals(1, RecordingEntrypoint.invocations);
        assertEquals("mixin:mixins.mod.json",
                platform.callOrder.get(platform.callOrder.size() - 1));
    }

    @Test
    void aModuleWithoutAnEntrypointStillBoots(@TempDir Path dir) throws Exception {
        FakePlatform platform = new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, "1.21.11");

        assertEquals("modules/mod-1.21.11.jar",
                NyliumKernel.boot(platform, outerJar(dir), dir.resolve("cache")).path());
    }

    @Test
    void aBrokenEntrypointNamesTheClassAndTheModule(@TempDir Path dir) throws Exception {
        String manifest = "module.0.path=modules/mod-1.21.11.jar\n"
                + "module.0.platforms=FABRIC\n"
                + "module.0.minecraft=1.21.11\n"
                + "module.0.entrypoint=com.example.Absent\n";
        Path outer = dir.resolve("outer.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outer))) {
            jar.putNextEntry(new JarEntry(ModuleManifest.RESOURCE));
            jar.write(manifest.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry("modules/mod-1.21.11.jar"));
            jar.write(tinyJar("newer"));
            jar.closeEntry();
        }
        ClassLoader source = new URLClassLoader(new URL[]{outer.toUri().toURL()}, null);
        FakePlatform platform = new FakePlatform(PlatformId.FABRIC, Environment.CLIENT, "1.21.11");

        io.github.intisy.nylium.api.NyliumException thrown =
                assertThrows(io.github.intisy.nylium.api.NyliumException.class,
                        () -> NyliumKernel.boot(platform, source, dir.resolve("cache")));
        assertTrue(thrown.getMessage().contains("com.example.Absent"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("modules/mod-1.21.11.jar"), thrown.getMessage());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :nylium-core:test --console=plain`
Expected: FAIL, `entrypoint()` is not defined on `ModuleDescriptor`.

- [ ] **Step 3: Add the entrypoint to ModuleDescriptor**

Add a `private final String entrypoint;` field, take it as the last constructor argument, and add:

```java
    public Optional<String> entrypoint() {
        return Optional.ofNullable(entrypoint);
    }
```

- [ ] **Step 4: Read it in ModuleManifest**

In `readModule`, before the `return`:

```java
        String entrypointText = properties.getProperty(prefix + "entrypoint");
        String entrypoint = entrypointText == null || entrypointText.trim().isEmpty()
                ? null
                : entrypointText.trim();
```

and pass `entrypoint` as the final argument to the `ModuleDescriptor` constructor.

- [ ] **Step 5: Invoke it from NyliumKernel**

In `boot`, after the mixin registration loop:

```java
        module.entrypoint().ifPresent(className -> invoke(className, module, source));
        return module;
    }

    private static void invoke(String className, ModuleDescriptor module, ClassLoader source) {
        try {
            Class.forName(className, true, source).getMethod("nyliumInit").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new NyliumException("Module '" + module.path() + "' names entrypoint '" + className
                    + "', which could not be invoked. It needs a public static void nyliumInit().", e);
        }
```

Add the `NyliumException` import.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :nylium-core:test --console=plain`
Expected: PASS, all nine kernel tests.

- [ ] **Step 7: Write the test mod**

`nylium-testmod/build.gradle`:
```groovy
dependencies {
    implementation project(':nylium-api')
}
```

`nylium-testmod/src/main/java/io/github/intisy/nylium/testmod/TestModEntry.java`:
```java
package io.github.intisy.nylium.testmod;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class TestModEntry {

    private TestModEntry() {
    }

    public static void nyliumInit() {
        String target = System.getProperty("nylium.smoke.marker");
        if (target == null) {
            return;
        }
        String report = "module=" + System.getProperty("nylium.testmod.id", "unknown") + "\n";
        try {
            Path marker = Paths.get(target);
            Files.createDirectories(marker.getParent());
            Files.write(marker, report.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

`nylium.testmod.id` is baked into each per-version module jar as a resource-driven system property in Step 9, so the marker proves *which* module ran rather than merely that one did.

- [ ] **Step 8: Correct the marker to read a bundled resource rather than a system property**

A system property would be identical across modules and prove nothing. Replace the `report` line:

```java
        String report = "module=" + moduleId() + "\n";
```

and add:

```java
    private static String moduleId() {
        try (java.io.InputStream stream =
                     TestModEntry.class.getResourceAsStream("/nylium-testmod-id.txt")) {
            if (stream == null) {
                return "unknown";
            }
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[256];
            int read;
            while ((read = stream.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "unreadable";
        }
    }
```

- [ ] **Step 9: Add the module-jar assembly tasks**

Append to `nylium-testmod/build.gradle`:

```groovy
def moduleIds = ['1.21.11', '1.21.10', '1.16.5', '1.7.10']

moduleIds.each { id ->
    tasks.register("moduleJar${id.replace('.', '')}", Jar) {
        archiveFileName = "testmod-${id}.jar"
        destinationDirectory = layout.buildDirectory.dir('modules')
        from sourceSets.main.output
        from(resources.text.fromString(id)) {
            rename '.*', 'nylium-testmod-id.txt'
        }
    }
}

tasks.register('moduleJars') {
    dependsOn moduleIds.collect { "moduleJar${it.replace('.', '')}" }
}
```

Every module jar holds the same classes and differs only in its id resource. That is exactly what the discrimination test needs, and it keeps this task independent of any real Minecraft code.

- [ ] **Step 10: Write the smoke harness**

`smoke/build.gradle`:
```groovy
dependencies {
    testImplementation project(':nylium-api')
}

tasks.named('test') {
    // Smoke tests download loader installers and launch servers; they are opt-in so the
    // ordinary build stays offline and fast.
    onlyIf { project.hasProperty('nyliumSmoke') }
}
```

`smoke/src/test/java/io/github/intisy/nylium/smoke/ServerSmokeHarness.java`:
```java
package io.github.intisy.nylium.smoke;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class ServerSmokeHarness {

    private ServerSmokeHarness() {
    }

    public static String run(Path serverDirectory, List<String> command, Path marker, Duration timeout)
            throws IOException, InterruptedException {
        Files.deleteIfExists(marker);
        Path log = serverDirectory.resolve("smoke.log");
        Process process = new ProcessBuilder(command)
                .directory(serverDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (Files.isRegularFile(marker)) {
                    return new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
                }
                if (!process.isAlive()) {
                    throw new AssertionError("The server exited before writing " + marker
                            + ". Log:\n" + read(log));
                }
                Thread.sleep(250);
            }
            throw new AssertionError("Timed out after " + timeout + " waiting for " + marker
                    + ". Log:\n" + read(log));
        } finally {
            // Nylium dispatches during preLaunch, well before world generation, so there is
            // never a reason to let the server finish booting.
            process.destroyForcibly();
            process.waitFor();
        }
    }

    private static String read(Path log) throws IOException {
        return Files.isRegularFile(log)
                ? new String(Files.readAllBytes(log), StandardCharsets.UTF_8)
                : "(no log)";
    }
}
```

- [ ] **Step 11: Register the new subprojects**

Add to `settings.gradle`:
```groovy
include 'nylium-testmod'
include 'smoke'
```

- [ ] **Step 12: Build**

Run: `./gradlew build :nylium-testmod:moduleJars --console=plain`
Expected: `BUILD SUCCESSFUL`, with four jars in `nylium-testmod/build/modules/`.

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "feat(core): invoke module entrypoints and add the smoke test harness"
```

---

## Task 12: Fabric backend and its smoke tests

**Files:**
- Create: `nylium-bootstrap-fabric/build.gradle`
- Create: `nylium-bootstrap-fabric/src/main/java/io/github/intisy/nylium/bootstrap/fabric/FabricPlatform.java`
- Create: `nylium-bootstrap-fabric/src/main/java/io/github/intisy/nylium/bootstrap/fabric/NyliumPreLaunch.java`
- Create: `smoke/src/test/java/io/github/intisy/nylium/smoke/FabricSmokeTest.java`
- Modify: `settings.gradle`

**Interfaces:**
- Consumes: `Platform` (Task 3), `NyliumKernel.boot` (Tasks 9, 11).
- Produces: `FabricPlatform implements Platform`, and `NyliumPreLaunch implements PreLaunchEntrypoint` as the `preLaunch` entrypoint named in `fabric.mod.json`.

Fabric goes first because it is the least hostile of the four: `addToClassPath` is a single documented-in-practice call, and preLaunch is precisely the window between mod discovery and mixin transformation. Getting the pattern right here makes the other three mechanical.

- [ ] **Step 1: Write the build file**

```groovy
dependencies {
    implementation project(':nylium-core')
    compileOnly 'net.fabricmc:fabric-loader:0.16.9'
    compileOnly 'org.spongepowered:mixin:0.8.7'
}
```

- [ ] **Step 2: Write FabricPlatform**

```java
package io.github.intisy.nylium.bootstrap.fabric;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.Platform;
import io.github.intisy.nylium.api.PlatformId;
import io.github.intisy.nylium.api.NyliumException;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.spongepowered.asm.mixin.Mixins;

import java.nio.file.Path;
import java.util.Optional;

final class FabricPlatform implements Platform {

    @Override
    public PlatformId id() {
        return PlatformId.FABRIC;
    }

    @Override
    public Environment environment() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT
                ? Environment.CLIENT
                : Environment.SERVER;
    }

    @Override
    public void addToClasspath(Path jar) {
        try {
            FabricLauncherBase.getLauncher().addToClassPath(jar);
        } catch (LinkageError e) {
            throw new NyliumException("This Fabric Loader does not expose addToClassPath; "
                    + "Nylium needs Fabric Loader 0.14 or newer.", e);
        }
    }

    @Override
    public void registerMixinConfig(String name) {
        Mixins.addConfiguration(name);
    }

    @Override
    public Optional<String> nativeVersionProbe() {
        return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(container -> container.getMetadata().getVersion().getFriendlyString());
    }
}
```

`FabricLauncherBase` is Fabric Loader internals rather than published API. It is the call every multi-version Fabric mod uses, and there is no public equivalent, so the `LinkageError` guard turns a future removal into a clear message instead of a stack trace.

- [ ] **Step 3: Write the preLaunch entrypoint**

```java
package io.github.intisy.nylium.bootstrap.fabric;

import io.github.intisy.nylium.core.NyliumKernel;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class NyliumPreLaunch implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        NyliumKernel.boot(
                new FabricPlatform(),
                NyliumPreLaunch.class.getClassLoader(),
                FabricLoader.getInstance().getGameDir().resolve("nylium").resolve("cache"));
    }
}
```

- [ ] **Step 4: Assemble a universal jar in the test mod**

Append to `nylium-testmod/build.gradle`:

```groovy
tasks.register('universalJar', Jar) {
    dependsOn tasks.named('moduleJars')
    archiveFileName = 'nylium-testmod-universal.jar'
    destinationDirectory = layout.buildDirectory.dir('universal')

    from project(':nylium-api').sourceSets.main.output
    from project(':nylium-core').sourceSets.main.output
    from project(':nylium-bootstrap-fabric').sourceSets.main.output

    from(layout.buildDirectory.dir('modules')) { into 'modules' }

    from(resources.text.fromString('''
module.0.path=modules/testmod-1.21.11.jar
module.0.platforms=FABRIC
module.0.minecraft=1.21.11
module.0.entrypoint=io.github.intisy.nylium.testmod.TestModEntry
module.1.path=modules/testmod-1.21.10.jar
module.1.platforms=FABRIC
module.1.minecraft=1.21.10
module.1.entrypoint=io.github.intisy.nylium.testmod.TestModEntry
'''.trim())) {
        rename '.*', 'nylium-modules.properties'
    }

    from(resources.text.fromString('''
{
  "schemaVersion": 1,
  "id": "nylium_testmod",
  "version": "0.1.0",
  "name": "Nylium Test Mod",
  "environment": "*",
  "entrypoints": { "preLaunch": ["io.github.intisy.nylium.bootstrap.fabric.NyliumPreLaunch"] },
  "depends": { "fabricloader": ">=0.14.0" }
}
'''.trim())) {
        rename '.*', 'fabric.mod.json'
    }
}
```

The module jars are *not* declared as Fabric nested jars. Fabric would try to load them as mods and fail their dependency checks; Nylium classpaths the chosen one itself, which is the entire point.

- [ ] **Step 5: Write the Fabric smoke test**

```java
package io.github.intisy.nylium.smoke;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FabricSmokeTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    private static String bootOn(String minecraftVersion) throws Exception {
        Path server = Paths.get(System.getProperty("nylium.smoke.servers"))
                .resolve("fabric-" + minecraftVersion);
        Path marker = server.resolve("nylium-marker.txt");
        return ServerSmokeHarness.run(server, Arrays.asList(
                "java",
                "-Dnylium.smoke.marker=" + marker.toAbsolutePath(),
                "-jar", "fabric-server-launch.jar",
                "nogui"), marker, TIMEOUT);
    }

    @Test
    void loadsThe12111ModuleOn12111() throws Exception {
        assertEquals("module=1.21.11", bootOn("1.21.11"));
    }

    @Test
    void loadsThe12110ModuleOn12110() throws Exception {
        assertEquals("module=1.21.10", bootOn("1.21.10"));
    }
}
```

These two tests together are the discrimination case the spec requires: one jar, two Minecraft versions, each loading its own module.

- [ ] **Step 6: Add the server provisioning task**

Append to `smoke/build.gradle`:

```groovy
def serversDir = layout.buildDirectory.dir('servers')

tasks.register('provisionFabricServers') {
    doLast {
        ['1.21.11', '1.21.10'].each { mcVersion ->
            def dir = serversDir.get().dir("fabric-${mcVersion}").asFile
            dir.mkdirs()
            def launcher = new File(dir, 'fabric-server-launch.jar')
            if (!launcher.exists()) {
                def url = "https://meta.fabricmc.net/v2/versions/loader/${mcVersion}/0.16.9/1.0.1/server/jar"
                launcher.withOutputStream { out -> new URL(url).withInputStream { out << it } }
            }
            new File(dir, 'mods').mkdirs()
            copy {
                from project(':nylium-testmod').layout.buildDirectory.file('universal/nylium-testmod-universal.jar')
                into new File(dir, 'mods')
            }
            new File(dir, 'eula.txt').text = 'eula=true\n'
        }
    }
    dependsOn ':nylium-testmod:universalJar'
}

tasks.named('test') {
    dependsOn tasks.named('provisionFabricServers')
    systemProperty 'nylium.smoke.servers', serversDir.get().asFile.absolutePath
}
```

- [ ] **Step 7: Register the subproject and run the smoke tests**

Add `include 'nylium-bootstrap-fabric'` to `settings.gradle`.

Run: `./gradlew :smoke:test -PnyliumSmoke --console=plain`
Expected: both tests PASS. If a server exits before the marker appears, the harness prints its log; the usual causes are a Fabric Loader too old for `addToClassPath` or a mistyped entrypoint in `fabric.mod.json`.

- [ ] **Step 8: Run the offline build to confirm smoke stays opt-in**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL` without downloading a server, because `:smoke:test` is skipped absent `-PnyliumSmoke`.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(fabric): dispatch on fabric loader with a two-version smoke test"
```

---

## Task 13: LaunchWrapper backend and its smoke test

**Files:**
- Create: `nylium-bootstrap-launchwrapper/build.gradle`
- Create: `nylium-bootstrap-launchwrapper/src/main/java/io/github/intisy/nylium/bootstrap/launchwrapper/LaunchWrapperPlatform.java`
- Create: `nylium-bootstrap-launchwrapper/src/main/java/io/github/intisy/nylium/bootstrap/launchwrapper/NyliumTweaker.java`
- Create: `smoke/src/test/java/io/github/intisy/nylium/smoke/LaunchWrapperSmokeTest.java`
- Modify: `nylium-testmod/build.gradle`, `smoke/build.gradle`, `settings.gradle`

**Interfaces:**
- Consumes: `Platform`, `NyliumKernel.boot`.
- Produces: `NyliumTweaker implements ITweaker`, declared through the jar manifest's `TweakClass` attribute and Forge 1.7-1.12's coremod discovery.

- [ ] **Step 1: Write the build file**

```groovy
dependencies {
    implementation project(':nylium-core')
    compileOnly('net.minecraft:launchwrapper:of-2.3') {
        exclude module: 'lwjgl'
        exclude module: 'asm-debug-all'
    }
    compileOnly 'org.spongepowered:mixin:0.8.7'
}
```

- [ ] **Step 2: Write LaunchWrapperPlatform**

```java
package io.github.intisy.nylium.bootstrap.launchwrapper;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.Platform;
import io.github.intisy.nylium.api.PlatformId;
import io.github.intisy.nylium.api.NyliumException;
import net.minecraft.launchwrapper.Launch;
import org.spongepowered.asm.mixin.Mixins;

import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.Optional;

final class LaunchWrapperPlatform implements Platform {

    @Override
    public PlatformId id() {
        return PlatformId.LAUNCHWRAPPER;
    }

    @Override
    public Environment environment() {
        // LaunchWrapper exposes no side flag, so presence of the client entry point is the
        // only signal available this early.
        return exists("net.minecraft.client.Minecraft") ? Environment.CLIENT : Environment.SERVER;
    }

    @Override
    public void addToClasspath(Path jar) {
        try {
            Launch.classLoader.addURL(jar.toUri().toURL());
        } catch (MalformedURLException e) {
            throw new NyliumException("Could not add " + jar + " to the LaunchWrapper classloader", e);
        }
    }

    @Override
    public void registerMixinConfig(String name) {
        Mixins.addConfiguration(name);
    }

    @Override
    public Optional<String> nativeVersionProbe() {
        Object version = Launch.blackboard == null ? null : Launch.blackboard.get("nylium.mcVersion");
        return Optional.ofNullable(version).map(Object::toString);
    }

    private static boolean exists(String className) {
        try {
            Class.forName(className, false, Launch.classLoader);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
```

The native probe reads a blackboard key rather than a loader API because LaunchWrapper genuinely has no version accessor. In practice the chain falls through to `MarkerClassProbe`, which is exactly why that probe exists.

- [ ] **Step 3: Write the tweaker**

```java
package io.github.intisy.nylium.bootstrap.launchwrapper;

import io.github.intisy.nylium.core.NyliumKernel;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.spongepowered.asm.launch.MixinBootstrap;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;

public final class NyliumTweaker implements ITweaker {

    private File gameDirectory = new File(".");

    @Override
    public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        if (gameDir != null) {
            gameDirectory = gameDir;
        }
    }

    @Override
    public void injectIntoClassLoader(LaunchClassLoader classLoader) {
        // Mixin must be up before any config is registered; on this era nothing else does it.
        MixinBootstrap.init();
        NyliumKernel.boot(
                new LaunchWrapperPlatform(),
                NyliumTweaker.class.getClassLoader(),
                Paths.get(gameDirectory.getAbsolutePath()).resolve("nylium").resolve("cache"));
    }

    @Override
    public String getLaunchTarget() {
        return null;
    }

    @Override
    public String[] getLaunchArguments() {
        return new String[0];
    }
}
```

- [ ] **Step 4: Add the LaunchWrapper module and manifest to the universal jar**

In `nylium-testmod/build.gradle`, inside `universalJar`, add the bootstrap output and the manifest attribute, and extend the manifest text with the 1.7.10 module:

```groovy
    from project(':nylium-bootstrap-launchwrapper').sourceSets.main.output

    manifest {
        attributes 'TweakClass': 'io.github.intisy.nylium.bootstrap.launchwrapper.NyliumTweaker'
    }
```

and append to the `nylium-modules.properties` text:
```properties
module.2.path=modules/testmod-1.7.10.jar
module.2.platforms=LAUNCHWRAPPER
module.2.minecraft=[1.7,1.12.2]
module.2.entrypoint=io.github.intisy.nylium.testmod.TestModEntry
```

The range is broad because `MarkerClassProbe` reports a floor rather than an exact version on this era. That is a deliberate consequence of the probe's limits, not an oversight.

- [ ] **Step 5: Write the smoke test**

```java
package io.github.intisy.nylium.smoke;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LaunchWrapperSmokeTest {

    @Test
    void loadsTheLegacyModuleOn1710() throws Exception {
        Path server = Paths.get(System.getProperty("nylium.smoke.servers")).resolve("forge-1.7.10");
        Path marker = server.resolve("nylium-marker.txt");

        String result = ServerSmokeHarness.run(server, Arrays.asList(
                "java",
                "-Dnylium.smoke.marker=" + marker.toAbsolutePath(),
                "-jar", "forge-server.jar",
                "nogui"), marker, Duration.ofMinutes(3));

        assertEquals("module=1.7.10", result);
    }
}
```

- [ ] **Step 6: Provision the 1.7.10 Forge server**

Append a `provisionForge1710` task to `smoke/build.gradle` that downloads the Forge 1.7.10 installer, runs it with `--installServer`, copies the universal jar into `mods/`, and writes `eula.txt`. Make `test` depend on it. Record the exact installer URL used in the task so the provisioning is reproducible.

- [ ] **Step 7: Run the smoke test**

Add `include 'nylium-bootstrap-launchwrapper'` to `settings.gradle`.

Run: `./gradlew :smoke:test -PnyliumSmoke --tests '*LaunchWrapperSmokeTest' --console=plain`
Expected: PASS. Java 8 must be the JVM running this server; if the toolchain resolves something newer, pin it in the command list.

- [ ] **Step 8: Confirm Fabric still passes**

Run: `./gradlew :smoke:test -PnyliumSmoke --console=plain`
Expected: all three smoke tests PASS, proving the LaunchWrapper bootstrap is inert under Fabric.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(launchwrapper): dispatch on the 1.7 to 1.12 tweaker era"
```

---

## Task 14: ModLauncher 8 backend and its smoke test

**Files:**
- Create: `nylium-bootstrap-modlauncher8/build.gradle`
- Create: `nylium-bootstrap-modlauncher8/src/main/java/io/github/intisy/nylium/bootstrap/ml8/Ml8Platform.java`
- Create: `nylium-bootstrap-modlauncher8/src/main/java/io/github/intisy/nylium/bootstrap/ml8/NyliumMl8Service.java`
- Create: `smoke/src/test/java/io/github/intisy/nylium/smoke/ModLauncher8SmokeTest.java`
- Modify: `nylium-testmod/build.gradle`, `smoke/build.gradle`, `settings.gradle`

**Interfaces:**
- Consumes: `Platform`, `NyliumKernel.boot`.
- Produces: `NyliumMl8Service implements ITransformationService`, registered in `META-INF/services/cpw.mods.modlauncher.api.ITransformationService`.

**The shared services file is the trap here.** ModLauncher 8 and 9 read the same service file, and `ServiceLoader` instantiates every entry. So both services are listed, and each must recognise whether it is on its own generation and no-op otherwise. `cpw.mods.jarhandling.SecureJar` exists only on ModLauncher 9+, which makes it the discriminator.

This is also why **every bootstrap emits Java 8 bytecode, including the ModLauncher 9+ one**: a Java 17 class file listed in that services file would throw `UnsupportedClassVersionError` during service loading on ModLauncher 8, taking the whole game down. The ModLauncher 9 backend therefore compiles against ModLauncher 9 APIs while targeting release 8, which means no `List.of`, no `var`, and no records in its own source.

- [ ] **Step 1: Write the build file**

```groovy
dependencies {
    implementation project(':nylium-core')
    compileOnly 'cpw.mods:modlauncher:8.1.3'
    compileOnly 'org.spongepowered:mixin:0.8.7'
}
```

- [ ] **Step 2: Write Ml8Platform**

```java
package io.github.intisy.nylium.bootstrap.ml8;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.Platform;
import io.github.intisy.nylium.api.PlatformId;
import org.spongepowered.asm.mixin.Mixins;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class Ml8Platform implements Platform {

    private final List<Path> pending = new ArrayList<>();

    @Override
    public PlatformId id() {
        return PlatformId.MODLAUNCHER_8;
    }

    @Override
    public Environment environment() {
        return exists("net.minecraft.client.Minecraft") ? Environment.CLIENT : Environment.SERVER;
    }

    @Override
    public void addToClasspath(Path jar) {
        // ModLauncher 8 takes jars back from the scanning phase rather than accepting a push,
        // so the kernel's call is recorded and handed over in NyliumMl8Service.
        pending.add(jar);
    }

    @Override
    public void registerMixinConfig(String name) {
        Mixins.addConfiguration(name);
    }

    @Override
    public Optional<String> nativeVersionProbe() {
        try {
            Class<?> mcp = Class.forName("net.minecraftforge.versions.mcp.MCPVersion");
            return Optional.ofNullable((String) mcp.getMethod("getMCVersion").invoke(null));
        } catch (ReflectiveOperationException | LinkageError e) {
            return Optional.empty();
        }
    }

    List<Path> pending() {
        return pending;
    }

    private static boolean exists(String className) {
        try {
            Class.forName(className, false, Ml8Platform.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
```

- [ ] **Step 3: Write the transformation service**

```java
package io.github.intisy.nylium.bootstrap.ml8;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class NyliumMl8Service implements ITransformationService {

    private Ml8Platform platform;

    @Override
    public String name() {
        return "nylium-ml8";
    }

    @Override
    public void initialize(IEnvironment environment) {
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) {
        if (isModLauncher9()) {
            return;
        }
        platform = new Ml8Platform();
        io.github.intisy.nylium.core.NyliumKernel.boot(
                platform,
                NyliumMl8Service.class.getClassLoader(),
                Paths.get(".").resolve("nylium").resolve("cache"));
    }

    @Override
    public Map<String, ?> getExtraJarPaths() {
        if (platform == null) {
            return Collections.emptyMap();
        }
        java.util.Map<String, Path> extra = new java.util.LinkedHashMap<>();
        int index = 0;
        for (Path jar : platform.pending()) {
            extra.put("nylium-module-" + index++, jar);
        }
        return extra;
    }

    @Override
    public List<? extends ITransformer<?>> transformers() {
        return Collections.emptyList();
    }

    private static boolean isModLauncher9() {
        try {
            Class.forName("cpw.mods.jarhandling.SecureJar", false,
                    NyliumMl8Service.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
```

If `getExtraJarPaths` is absent or differently shaped on the ModLauncher 8 version in use, substitute the equivalent from that version's `ITransformationService` and record the substitution in the commit message. The kernel contract does not change either way, because `Ml8Platform` already buffers rather than pushes.

- [ ] **Step 4: Add the 1.16.5 module and services file**

Extend `universalJar` in `nylium-testmod/build.gradle` with the ModLauncher 8 bootstrap output, a fourth module entry, and the services file listing both ModLauncher services:

```properties
module.3.path=modules/testmod-1.16.5.jar
module.3.platforms=MODLAUNCHER_8
module.3.minecraft=[1.13,1.16.5]
module.3.entrypoint=io.github.intisy.nylium.testmod.TestModEntry
```

```groovy
    from project(':nylium-bootstrap-modlauncher8').sourceSets.main.output

    from(resources.text.fromString(
            'io.github.intisy.nylium.bootstrap.ml8.NyliumMl8Service\n'
                    + 'io.github.intisy.nylium.bootstrap.ml9.NyliumMl9Service\n')) {
        into 'META-INF/services'
        rename '.*', 'cpw.mods.modlauncher.api.ITransformationService'
    }
```

The ModLauncher 9 entry is listed now even though Task 15 writes that class, so add it only once Task 15 lands; until then list the ML8 service alone and extend it in Task 15 Step 4.

- [ ] **Step 5: Write the smoke test and provisioning**

```java
package io.github.intisy.nylium.smoke;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModLauncher8SmokeTest {

    @Test
    void loadsThe1165ModuleOn1165() throws Exception {
        Path server = Paths.get(System.getProperty("nylium.smoke.servers")).resolve("forge-1.16.5");
        Path marker = server.resolve("nylium-marker.txt");

        String result = ServerSmokeHarness.run(server, Arrays.asList(
                "java",
                "-Dnylium.smoke.marker=" + marker.toAbsolutePath(),
                "-jar", "forge-server.jar",
                "nogui"), marker, Duration.ofMinutes(4));

        assertEquals("module=1.16.5", result);
    }
}
```

Add a `provisionForge1165` task mirroring `provisionForge1710`: download the Forge 1.16.5 installer, `--installServer`, copy the universal jar into `mods/`, write `eula.txt`, and make `test` depend on it.

- [ ] **Step 6: Run every smoke test**

Add `include 'nylium-bootstrap-modlauncher8'` to `settings.gradle`.

Run: `./gradlew :smoke:test -PnyliumSmoke --console=plain`
Expected: four tests PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(modlauncher8): dispatch on the 1.13 to 1.16 transformation service era"
```

---

## Task 15: ModLauncher 9+ backend and its smoke test

**Files:**
- Create: `nylium-bootstrap-modlauncher9/build.gradle`
- Create: `nylium-bootstrap-modlauncher9/src/main/java/io/github/intisy/nylium/bootstrap/ml9/Ml9Platform.java`
- Create: `nylium-bootstrap-modlauncher9/src/main/java/io/github/intisy/nylium/bootstrap/ml9/NyliumMl9Service.java`
- Create: `smoke/src/test/java/io/github/intisy/nylium/smoke/ModLauncher9SmokeTest.java`
- Modify: `nylium-testmod/build.gradle`, `smoke/build.gradle`, `settings.gradle`
- Read first: `docs/superpowers/plans/SPIKE-modlauncher9.md`

**Interfaces:**
- Consumes: `Platform`, `NyliumKernel.boot`, and the verified API calls recorded by Task 2.
- Produces: `NyliumMl9Service implements ITransformationService`, added to the shared services file.

**Implement from the spike document, not from memory.** Task 2 established which layer, which method, and which lifecycle point actually work, separately for Forge and NeoForge. Where this task's skeleton disagrees with the spike findings, the findings win.

Constraints carried from Task 14: this artifact emits **Java 8 bytecode** despite ModLauncher 9 requiring Java 17 at runtime, because it shares a `ServiceLoader` file with the ModLauncher 8 service. No `List.of`, no `var`, no records in this source.

- [ ] **Step 1: Write the build file**

```groovy
dependencies {
    implementation project(':nylium-core')
    compileOnly 'cpw.mods:modlauncher:10.0.9'
    compileOnly 'cpw.mods:securejarhandler:2.1.10'
    compileOnly 'org.spongepowered:mixin:0.8.7'
}
```

No `nyliumJavaRelease` override: release 8 is inherited from the root build deliberately.

- [ ] **Step 2: Write Ml9Platform**

```java
package io.github.intisy.nylium.bootstrap.ml9;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.Platform;
import io.github.intisy.nylium.api.PlatformId;
import org.spongepowered.asm.mixin.Mixins;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class Ml9Platform implements Platform {

    private final List<Path> pending = new ArrayList<Path>();

    @Override
    public PlatformId id() {
        return PlatformId.MODLAUNCHER_9;
    }

    @Override
    public Environment environment() {
        return exists("net.minecraft.client.Minecraft") ? Environment.CLIENT : Environment.SERVER;
    }

    @Override
    public void addToClasspath(Path jar) {
        // The module layer is assembled during scanning, so jars are buffered here and offered
        // back to ModLauncher in NyliumMl9Service.
        pending.add(jar);
    }

    @Override
    public void registerMixinConfig(String name) {
        Mixins.addConfiguration(name);
    }

    @Override
    public Optional<String> nativeVersionProbe() {
        try {
            Class<?> loader = Class.forName("net.minecraftforge.fml.loading.FMLLoader");
            Object info = loader.getMethod("versionInfo").invoke(null);
            Object version = info.getClass().getMethod("mcVersion").invoke(info);
            return Optional.ofNullable(version).map(Object::toString);
        } catch (ReflectiveOperationException | LinkageError e) {
            return Optional.empty();
        }
    }

    List<Path> pending() {
        return pending;
    }

    private static boolean exists(String className) {
        try {
            Class.forName(className, false, Ml9Platform.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
```

`FMLLoader.versionInfo()` is reflective because its return type differs across Forge and NeoForge; the spike document records which accessor each provides.

- [ ] **Step 3: Write the transformation service**

```java
package io.github.intisy.nylium.bootstrap.ml9;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class NyliumMl9Service implements ITransformationService {

    private Ml9Platform platform;

    @Override
    public String name() {
        return "nylium-ml9";
    }

    @Override
    public void initialize(IEnvironment environment) {
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) {
        if (!isModLauncher9()) {
            return;
        }
        platform = new Ml9Platform();
        io.github.intisy.nylium.core.NyliumKernel.boot(
                platform,
                NyliumMl9Service.class.getClassLoader(),
                Paths.get(".").resolve("nylium").resolve("cache"));
    }

    @Override
    public List<Resource> beginScanning(IEnvironment environment) {
        if (platform == null || platform.pending().isEmpty()) {
            return Collections.emptyList();
        }
        List<SecureJar> jars = new ArrayList<SecureJar>();
        for (Path jar : platform.pending()) {
            jars.add(SecureJar.from(jar));
        }
        return Collections.singletonList(new Resource(IModuleLayerManager.Layer.GAME, jars));
    }

    @Override
    public List<? extends ITransformer<?>> transformers() {
        return Collections.emptyList();
    }

    private static boolean isModLauncher9() {
        try {
            Class.forName("cpw.mods.jarhandling.SecureJar", false,
                    NyliumMl9Service.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
```

Substitute the layer and method that Task 2 proved, if they differ from `Layer.GAME` and `beginScanning`.

- [ ] **Step 4: Extend the services file and add the module**

In `nylium-testmod/build.gradle`, add the ModLauncher 9 bootstrap output, add its class to the services file text (the second line deferred in Task 14 Step 4), and add the module:

```properties
module.4.path=modules/testmod-1.21.11.jar
module.4.platforms=MODLAUNCHER_9
module.4.minecraft=[1.17,)
module.4.entrypoint=io.github.intisy.nylium.testmod.TestModEntry
```

Also add `META-INF/mods.toml` and `META-INF/neoforge.mods.toml` to the jar so Forge and NeoForge accept it as a mod. Both may coexist: each loader reads only its own file.

- [ ] **Step 5: Write the smoke test and provisioning**

```java
package io.github.intisy.nylium.smoke;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModLauncher9SmokeTest {

    @Test
    void loadsTheModernModuleOnNeoForge12111() throws Exception {
        Path server = Paths.get(System.getProperty("nylium.smoke.servers")).resolve("neoforge-1.21.11");
        Path marker = server.resolve("nylium-marker.txt");

        String result = ServerSmokeHarness.run(server, Arrays.asList(
                "java",
                "-Dnylium.smoke.marker=" + marker.toAbsolutePath(),
                "-jar", "neoforge-server.jar",
                "nogui"), marker, Duration.ofMinutes(4));

        assertEquals("module=1.21.11", result);
    }
}
```

Add a `provisionNeoForge12111` task following the same shape as the Forge ones, using the NeoForge installer for 1.21.11.

- [ ] **Step 6: Run the whole smoke matrix**

Add `include 'nylium-bootstrap-modlauncher9'` to `settings.gradle`.

Run: `./gradlew :smoke:test -PnyliumSmoke --console=plain`
Expected: five tests PASS: Fabric 1.21.11, Fabric 1.21.10, LaunchWrapper 1.7.10, ModLauncher 8 1.16.5, ModLauncher 9 1.21.11. This is the spec's definition of done for SP-1.

- [ ] **Step 7: Verify the offline build and the purity check**

Run: `./gradlew build --console=plain`
Expected: `BUILD SUCCESSFUL`, `checkApiPurity` executed and green, smoke skipped.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(modlauncher9): dispatch into the module layer on forge and neoforge"
```

---

## Task 16: CI and the generated README

**Files:**
- Create: `.github/workflows/build.yml`
- Create: `docs/README.template.md`
- Modify: `settings.gradle` (nothing further; final state check)

**Interfaces:**
- Consumes: a repo where `./gradlew build` is green offline and `./gradlew :smoke:test -PnyliumSmoke` is green with network.

Per the global CI rule, this repo carries **no workflow logic**. `build.yml` is a thin caller of a reusable workflow in `intisy/workflows`.

- [ ] **Step 1: Check whether a suitable reusable workflow already exists**

```bash
gh api repos/intisy/workflows/contents/.github/workflows --jq '.[].name'
```
Look for a Gradle build workflow exposing `workflow_call` with inputs for the JDK list and the branches. If one exists, note its exact path and input names and skip to Step 3.

- [ ] **Step 2: If none exists, author it in `intisy/workflows` first**

Clone `intisy/workflows`, add `.github/workflows/gradle-build.yml` with `on: workflow_call`, inputs `java_versions` (string, newline separated), `default_branch` (string, no hardcoded default beyond the caller's own), `development_branch` (string), and `gradle_tasks` (string, default `build`). Every repo-specific value is an input. Commit and push there, then return here.

Do **not** write this logic into the Nylium repo and move it later.

- [ ] **Step 3: Write the thin caller**

```yaml
name: build

on:
  push:
  pull_request:
  workflow_dispatch:

jobs:
  build:
    uses: intisy/workflows/.github/workflows/gradle-build.yml@main
    with:
      java_versions: |
        8
        17
        21
      gradle_tasks: build
```

Replace the `uses:` path, ref and input names with whatever Step 1 or Step 2 established. Add `default_branch` and `development_branch` inputs only if the reusable workflow requires them, and pass the repository's actual branch names rather than literals baked into the workflow file.

The smoke matrix is deliberately not in this workflow: it downloads several hundred megabytes of loader installers and launches four servers. Add it as a separate scheduled or dispatch-only caller once the reusable workflow supports it.

- [ ] **Step 4: Push and watch the run**

```bash
git add .github/workflows/build.yml
git commit -m "ci: call the shared gradle build workflow"
git push
gh run watch
```
Expected: green. The likely first failure is a Java 8 toolchain missing on the runner; the reusable workflow's `java_versions` input is what fixes it.

- [ ] **Step 5: Add the README template**

`README.md` is generated and lives only on the default branch, so this branch carries the template. Write `docs/README.template.md` covering: what Nylium is, the compatibility contract, the four backends and their version spans, the manifest format with a worked example, and a minimal consumer walkthrough. Do not create `README.md` by hand.

- [ ] **Step 6: Commit**

```bash
git add docs/README.template.md
git commit -m "docs: add the readme template for generation"
git push
```

---

## Self-Review Notes (author)

**Spec coverage.** Every spec section maps to a task: artifacts and the Java floor (Task 1); the `Platform` seam and `McVersion` (Task 3); version ranges and the properties manifest (Tasks 4, 5); deterministic selection with rejection diagnostics (Task 6); hash-keyed extraction with atomic landing (Task 7); the probe chain with native, `version.json` and marker-class probes (Task 8); kernel orchestration with classpath-before-mixins ordering (Task 9); `checkApiPurity` (Task 10); the three test layers (unit throughout, fake-platform in Task 9, real-launch in Tasks 12 to 15); the full five-row smoke matrix (Task 15 Step 6); the four backends (Tasks 12 to 15); thin-caller CI and the generated README (Task 16); the ModLauncher 9 risk spiked before implementation (Task 2, consumed by Task 15).

**Two deliberate deviations from the committed spec**, both improvements found while planning:

1. **Manifest format is `.properties`, not hand-rolled JSON.** `java.util.Properties` is in the JDK and module descriptors are flat, so the parser and its bug surface vanish. The spec has been updated to match.
2. **All four bootstraps emit Java 8 bytecode**, where the spec put ModLauncher 9+ at Java 17. ModLauncher 8 and 9 share one `ServiceLoader` file and `ServiceLoader` instantiates every entry, so a Java 17 class file would throw `UnsupportedClassVersionError` and kill the game on ModLauncher 8. The spec's artifact table needs the same correction.

**One gap found and closed during review:** nothing in a dispatched module would ever execute, because the loader finishes mod discovery before Nylium runs. Module entrypoints (Task 11) close this. They are added in Task 11 rather than Task 5 because the extension is far easier to motivate and test once the kernel exists.

**Known non-concreteness, and why it is unavoidable:** Tasks 13 to 15 leave three loader-installer URLs and the exact `ITransformationService` method shapes to be pinned at execution time. The ModLauncher APIs differ across Forge and NeoForge point releases, and the spike in Task 2 exists precisely to resolve them against real servers rather than guessing here. Each of those steps names what to substitute and where to record it. Every other step carries the actual code.

**Type consistency check:** `Platform`'s five methods are identical across Tasks 3, 9, 12, 13, 14 and 15. `ModuleDescriptor` gains `entrypoint()` in Task 11 and every later reference accounts for it. `NyliumKernel.boot(Platform, ClassLoader, Path)` keeps one signature from Task 9 onward. `ModuleManifest.RESOURCE` is `nylium-modules.properties` in Tasks 5, 9, 11 and 12 alike.
