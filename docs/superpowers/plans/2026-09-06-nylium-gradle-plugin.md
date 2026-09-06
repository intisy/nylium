# Nylium Gradle Packaging Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A published Gradle plugin (`io.github.intisy.nylium`) that assembles a Nylium universal jar from a declaration, replacing the hand-rolled packaging in `nylium-testmod/build.gradle`.

**Architecture:** A new `nylium-gradle` subproject built with `java-gradle-plugin`. A `nylium { }` extension collects a `mod { }` block and a named container of `module { }` blocks; validation reuses the kernel's own `VersionRange.parse` and `PlatformId.valueOf` rather than reimplementing them. Every generated file is written by a task with a real `@Input` and `@OutputFile`, which is the structural fix for SP-1's untracked-input staleness defect. Which Nylium pieces get embedded is derived from the union of declared platforms.

**Tech Stack:** Gradle 7.6+ (Groovy DSL in builds, Java in the plugin), `java-gradle-plugin`, Gradle TestKit, JUnit 5, gson (test scope only).

**Spec:** `docs/superpowers/specs/2026-09-06-nylium-gradle-plugin-design.md`

## Global Constraints

- **Java 8 bytecode.** The root build sets `options.release = 8` for every subproject and wires `checkClassFileVersion` into `check`. No `var`, no `List.of`, no records, no diamond-with-anonymous-class.
- **No `net.minecraft` types anywhere in this subproject.** It never sees Minecraft.
- **Do not reimplement version or platform parsing.** Call `VersionRange.parse` and `PlatformId.valueOf`. `VersionRange.parse` throws a subclass of `io.github.intisy.nylium.api.NyliumException`, so catch `NyliumException`.
- **Manifest wire format, exactly as `ModuleManifest` reads it:** keys are `module.<index>.<field>`; `platforms` and `mixins` are comma separated and trimmed; `platforms` and `environment` are matched with a case-sensitive `valueOf`, so emit `PlatformId.name()` and `Environment.name()`; absent `priority` means 0. Required fields are `path`, `platforms`, `minecraft`.
- **Comments: default to zero.** Only a genuinely non-obvious *why*, on the declaration it explains, as a doc comment with `@implNote` for internal rationale. A 3-or-more-line inline block is a smell: extract a named method instead.
- **NEVER use en-dashes or em-dashes** anywhere: code, comments, commit messages, docs, output.
- **Conventional Commits:** `type(scope): summary`, imperative, lowercase summary, no trailing period, no task or plan archaeology in the message. Commit with the repository's configured identity; never pass `-c user.email`, `-c user.name`, or `--author`.
- **Never delete existing code without asking.** In particular the hand-rolled `universalJar`, `moduleJars` and `moduleJar*` tasks in `nylium-testmod/build.gradle` stay in place for the whole plan; they are the differential's reference artifact. Removing them is the owner's decision, not a step here.
- **Offline default.** A plain `./gradlew build` must not download a Minecraft server or launch one. The smoke matrix stays behind `-PnyliumSmoke`.
- **READMEs are generated.** Never hand-write `README.md`; documentation edits go to `CONTENT.md`.

## Reference values, verbatim

These strings are load bearing. Copy them, do not retype them from memory.

| Purpose | Value |
| --- | --- |
| Fabric preLaunch entrypoint | `io.github.intisy.nylium.bootstrap.fabric.NyliumPreLaunch` |
| LaunchWrapper manifest attribute | `TweakClass` = `io.github.intisy.nylium.bootstrap.launchwrapper.NyliumTweaker` |
| ML8 service impl | `io.github.intisy.nylium.bootstrap.ml8.NyliumMl8Service` |
| ML9 service impl | `io.github.intisy.nylium.bootstrap.ml9.NyliumMl9TransformationService` |
| ML9 launch plugin impl | `io.github.intisy.nylium.bootstrap.ml9.NyliumMl9LaunchPlugin` |
| ITransformationService file | `META-INF/services/cpw.mods.modlauncher.api.ITransformationService` |
| ILaunchPluginService file | `META-INF/services/cpw.mods.modlauncher.serviceapi.ILaunchPluginService` |
| Manifest resource name | `nylium-modules.properties` (`ModuleManifest.RESOURCE`) |

Bootstrap subproject per platform: `FABRIC` to `nylium-bootstrap-fabric`, `LAUNCHWRAPPER` to `nylium-bootstrap-launchwrapper`, `MODLAUNCHER_8` to `nylium-bootstrap-modlauncher8`, `MODLAUNCHER_9` to `nylium-bootstrap-modlauncher9`. `NEOFORGE` has no bootstrap and must be rejected.

## File Structure

```
settings.gradle                                        Modify: include 'nylium-gradle'
nylium-gradle/build.gradle                             Create
nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/
    NyliumPlugin.java                                  Create  plugin entry point
    NyliumExtension.java                               Create  nylium { }, validation entry
    ModSpec.java                                       Create  mod { }
    ModuleSpec.java                                    Create  module('name') { }
    ResolvedModule.java                                Create  validated, immutable
    Platforms.java                                     Create  name parsing, NEOFORGE rejection
    ManifestRenderer.java                              Create  modules -> properties text
    FabricMetadataRenderer.java                        Create  mod -> fabric.mod.json text
    ServiceRenderer.java                               Create  platforms -> service file bodies
    Json.java                                          Create  minimal string escaping
    NyliumTextFileTask.java                            Create  @Input content, @OutputFile
    NyliumVerifyModulesTask.java                       Create  module jar inspection
nylium-gradle/src/test/java/io/github/intisy/nylium/gradle/
    NyliumPluginApplyTest.java                         Create
    ValidationTest.java                                Create
    ManifestRendererTest.java                          Create
    FabricMetadataRendererTest.java                    Create
    ServiceRendererTest.java                           Create
    UpToDateFunctionalTest.java                        Create  TestKit
    VerifyModulesTest.java                             Create
    DifferentialTest.java                              Create  TestKit, acceptance gate
nylium-gradle/src/test/resources/
    expected-testmod-manifest.properties               Create  copied from the proven literal
nylium-testmod/build.gradle                            Modify: expose module jar paths to tests only
smoke/build.gradle                                     Modify: allow an explicit jar path (Task 9)
CONTENT.md                                             Modify: plugin usage plus inherited limits
```

`buildSrc` already uses the package `io.github.intisy.nylium.gradle` for `ApiPurityTask` and `ClassFileVersionTask`. Class names here do not collide with those two, and buildSrc is never published, so sharing the package name is safe. Do not move or rename the buildSrc classes.

---

### Task 1: Subproject skeleton and plugin registration

**Files:**
- Modify: `settings.gradle`
- Create: `nylium-gradle/build.gradle`
- Create: `nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/NyliumPlugin.java`
- Create: `nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/NyliumExtension.java`
- Test: `nylium-gradle/src/test/java/io/github/intisy/nylium/gradle/NyliumPluginApplyTest.java`

**Interfaces:**
- Produces: plugin id `io.github.intisy.nylium`; extension named `nylium` of type `NyliumExtension`; a `nylium-gradle-version.properties` resource on the plugin's own classpath carrying `version=<project.version>`.

- [ ] **Step 1: Add the subproject**

In `settings.gradle`, after `include 'nylium-bootstrap-modlauncher9'`:

```groovy
include 'nylium-gradle'
```

- [ ] **Step 2: Write the build script**

Create `nylium-gradle/build.gradle`:

```groovy
plugins {
    id 'java-gradle-plugin'
}

dependencies {
    implementation project(':nylium-api')
    implementation project(':nylium-core')

    testImplementation gradleTestKit()
    testImplementation 'com.google.code.gson:gson:2.10.1'
}

gradlePlugin {
    plugins {
        nylium {
            id = 'io.github.intisy.nylium'
            implementationClass = 'io.github.intisy.nylium.gradle.NyliumPlugin'
        }
    }
}

def versionFile = layout.buildDirectory.file('generated/nylium-version/nylium-gradle-version.properties')
def nyliumVersion = providers.provider { project.version.toString() }

/**
 * @implNote The plugin resolves the embedded runtime pieces at its own version, so the version has
 *     to reach it at execution time; a generated resource is the only channel that survives being
 *     applied from a published jar.
 */
def writeNyliumVersion = tasks.register('writeNyliumVersion') {
    inputs.property('version', nyliumVersion)
    outputs.file(versionFile)
    doLast {
        def file = versionFile.get().asFile
        file.parentFile.mkdirs()
        file.setText("version=${nyliumVersion.get()}\n", 'UTF-8')
    }
}
```

The action reads the captured provider and never touches `project`. Reaching `Task.project` at execution time is deprecated, is documented to fail in Gradle 10, and is incompatible with the configuration cache, which matters for a plugin other builds apply. The charset is pinned for the same reason `ModuleManifest` pins it: an unpinned default is a known trap in this repo.

```groovy

tasks.named('processResources') {
    from(writeNyliumVersion)
}
```

The root build already applies `java-library` and `maven-publish` to every subproject, sets `options.release = 8`, and registers `checkClassFileVersion`. Do not repeat any of that here.

- [ ] **Step 3: Write the failing test**

Create `nylium-gradle/src/test/java/io/github/intisy/nylium/gradle/NyliumPluginApplyTest.java`:

```java
package io.github.intisy.nylium.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class NyliumPluginApplyTest {

    @Test
    void registersTheExtension() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.nylium");
        assertNotNull(project.getExtensions().findByName("nylium"));
    }
}
```

- [ ] **Step 4: Run it and watch it fail**

Run: `./gradlew :nylium-gradle:test --offline`
Expected: FAIL, plugin id not found or `NyliumPlugin` missing.

- [ ] **Step 5: Write the plugin and a minimal extension**

Create `NyliumPlugin.java`:

```java
package io.github.intisy.nylium.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class NyliumPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getExtensions().create("nylium", NyliumExtension.class, project);
    }
}
```

Create `NyliumExtension.java` with only what this task needs; Task 2 fills it in:

```java
package io.github.intisy.nylium.gradle;

import org.gradle.api.Project;

public class NyliumExtension {

    private final Project project;

    public NyliumExtension(Project project) {
        this.project = project;
    }

    Project project() {
        return project;
    }
}
```

- [ ] **Step 6: Run the test and the class-file check**

Run: `./gradlew :nylium-gradle:check --offline`
Expected: PASS, including `checkClassFileVersion`.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle nylium-gradle
git commit -m "feat(gradle): add the nylium gradle plugin subproject"
```

---

### Task 2: The DSL and configuration-time validation

**Files:**
- Create: `ModSpec.java`, `ModuleSpec.java`, `ResolvedModule.java`, `Platforms.java`
- Modify: `NyliumExtension.java`
- Test: `ValidationTest.java`

**Interfaces:**
- Consumes: plugin and extension from Task 1.
- Produces: `NyliumExtension.mod(Action<ModSpec>)`, `NyliumExtension.module(String, Action<ModuleSpec>)`, `NyliumExtension.getModules()` returning `NamedDomainObjectContainer<ModuleSpec>`, and `List<ResolvedModule> NyliumExtension.resolve()` which validates and throws `InvalidUserDataException` on any violation. `ResolvedModule` exposes `name()`, `path()`, `platforms()`, `minecraft()`, `environment()` (nullable), `mixins()`, `priority()`, `entrypoint()` (nullable), `jar()` returning `Provider<RegularFile>`.

- [ ] **Step 1: Write the failing tests**

Create `ValidationTest.java`. One test per rejection, each asserting the message names the offending module:

```java
package io.github.intisy.nylium.gradle;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationTest {

    private NyliumExtension extensionWith(String platform, String minecraft) {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.nylium");
        NyliumExtension nylium = (NyliumExtension) project.getExtensions().getByName("nylium");
        nylium.mod(mod -> {
            mod.getId().set("demo");
            mod.getName().set("Demo");
            mod.getVersion().set("1.0.0");
        });
        nylium.module("1.21.11", module -> {
            module.getJar().set(new File(project.getProjectDir(), "module.jar"));
            module.getPlatforms().set(Collections.singletonList(platform));
            module.getMinecraft().set(minecraft);
        });
        return nylium;
    }

    @Test
    void acceptsAValidModule() {
        List<ResolvedModule> modules = extensionWith("FABRIC", "1.21.11").resolve();
        assertEquals(1, modules.size());
        assertEquals("modules/demo-1.21.11.jar", modules.get(0).path());
    }

    @Test
    void acceptsLowerCasePlatformNames() {
        List<ResolvedModule> modules = extensionWith("fabric", "1.21.11").resolve();
        assertTrue(modules.get(0).platforms().toString().contains("FABRIC"));
    }

    @Test
    void rejectsAMalformedVersionRange() {
        InvalidUserDataException thrown = assertThrows(InvalidUserDataException.class,
                () -> extensionWith("FABRIC", "[1.21.11").resolve());
        assertTrue(thrown.getMessage().contains("1.21.11"));
    }

    @Test
    void rejectsAnUnknownPlatform() {
        InvalidUserDataException thrown = assertThrows(InvalidUserDataException.class,
                () -> extensionWith("QUILT", "1.21.11").resolve());
        assertTrue(thrown.getMessage().contains("QUILT"));
    }

    @Test
    void rejectsNeoforgeAndNamesTheBlockingSubProject() {
        InvalidUserDataException thrown = assertThrows(InvalidUserDataException.class,
                () -> extensionWith("NEOFORGE", "1.21.11").resolve());
        assertTrue(thrown.getMessage().contains("SP-1b"));
    }

    @Test
    void rejectsAModuleWithNoPlatforms() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.nylium");
        NyliumExtension nylium = (NyliumExtension) project.getExtensions().getByName("nylium");
        nylium.mod(mod -> mod.getId().set("demo"));
        nylium.module("1.21.11", module -> {
            module.getJar().set(new File(project.getProjectDir(), "module.jar"));
            module.getPlatforms().set(Arrays.<String>asList());
            module.getMinecraft().set("1.21.11");
        });
        assertThrows(InvalidUserDataException.class, nylium::resolve);
    }

    @Test
    void rejectsAnEmptyModuleSet() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.nylium");
        NyliumExtension nylium = (NyliumExtension) project.getExtensions().getByName("nylium");
        nylium.mod(mod -> mod.getId().set("demo"));
        assertThrows(InvalidUserDataException.class, nylium::resolve);
    }

    @Test
    void rejectsAMissingModId() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.nylium");
        NyliumExtension nylium = (NyliumExtension) project.getExtensions().getByName("nylium");
        nylium.module("1.21.11", module -> {
            module.getJar().set(new File(project.getProjectDir(), "module.jar"));
            module.getPlatforms().set(Collections.singletonList("FABRIC"));
            module.getMinecraft().set("1.21.11");
        });
        assertThrows(InvalidUserDataException.class, nylium::resolve);
    }

    @Test
    void rejectsAnUnknownEnvironment() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.nylium");
        NyliumExtension nylium = (NyliumExtension) project.getExtensions().getByName("nylium");
        nylium.mod(mod -> mod.getId().set("demo"));
        nylium.module("1.21.11", module -> {
            module.getJar().set(new File(project.getProjectDir(), "module.jar"));
            module.getPlatforms().set(Collections.singletonList("FABRIC"));
            module.getMinecraft().set("1.21.11");
            module.getEnvironment().set("BOTH");
        });
        assertThrows(InvalidUserDataException.class, nylium::resolve);
    }

    @Test
    void rejectsAModuleWithoutAJar() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.nylium");
        NyliumExtension nylium = (NyliumExtension) project.getExtensions().getByName("nylium");
        nylium.mod(mod -> mod.getId().set("demo"));
        nylium.module("1.21.11", module -> {
            module.getPlatforms().set(Collections.singletonList("FABRIC"));
            module.getMinecraft().set("1.21.11");
        });
        assertThrows(InvalidUserDataException.class, nylium::resolve);
    }
}
```

Add one more test, `rejectsADuplicateModuleName`, declaring `module('1.21.11')` twice and asserting it throws. The container rejects the duplicate only because `module(...)` calls `create`; `maybeCreate` would merge both blocks into one spec silently. Do not add a second uniqueness check inside `resolve()`, and do not assume the rejection without asserting it.

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew :nylium-gradle:test --offline`
Expected: FAIL to compile, `mod`, `module` and `resolve` do not exist.

- [ ] **Step 3: Write `ModSpec`**

```java
package io.github.intisy.nylium.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

public abstract class ModSpec {

    public abstract Property<String> getId();

    public abstract Property<String> getName();

    public abstract Property<String> getVersion();

    public abstract Property<String> getDescription();

    public abstract Property<String> getLicense();

    public abstract Property<String> getIcon();

    public abstract Property<String> getEnvironment();

    public abstract Property<String> getFabricLoaderVersion();

    /**
     * A Fabric version predicate for Minecraft, left unset by default so the universal jar loads on
     * any version and Nylium, not the loader, reports an unsupported one.
     */
    public abstract Property<String> getMinecraftDependency();

    /** Prefix for the module file names inside the jar; defaults to {@link #getId()}. */
    public abstract Property<String> getModulePrefix();

    public abstract ListProperty<String> getAuthors();

    public abstract MapProperty<String, String> getContact();
}
```

Gradle instantiates abstract property getters, so no constructor or backing fields are needed.

- [ ] **Step 4: Write `ModuleSpec`**

```java
package io.github.intisy.nylium.gradle;

import org.gradle.api.Named;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

public abstract class ModuleSpec implements Named {

    private final String name;

    public ModuleSpec(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    public abstract RegularFileProperty getJar();

    public abstract ListProperty<String> getPlatforms();

    public abstract Property<String> getMinecraft();

    public abstract Property<String> getEnvironment();

    public abstract ListProperty<String> getMixins();

    public abstract Property<Integer> getPriority();

    public abstract Property<String> getEntrypoint();
}
```

- [ ] **Step 5: Write `Platforms`**

```java
package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.api.PlatformId;
import org.gradle.api.InvalidUserDataException;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class Platforms {

    private Platforms() {
    }

    static Set<PlatformId> parse(String moduleName, List<String> declared) {
        if (declared.isEmpty()) {
            throw new InvalidUserDataException("Nylium module '" + moduleName
                    + "' declares no platforms. Known platforms are "
                    + Arrays.toString(PlatformId.values()) + ".");
        }
        Set<PlatformId> platforms = new LinkedHashSet<PlatformId>();
        for (String each : declared) {
            platforms.add(single(moduleName, each));
        }
        return platforms;
    }

    private static PlatformId single(String moduleName, String declared) {
        String text = declared.trim().toUpperCase(Locale.ROOT);
        PlatformId platform;
        try {
            platform = PlatformId.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new InvalidUserDataException("Nylium module '" + moduleName
                    + "' declares an unknown platform '" + declared + "'. Known platforms are "
                    + Arrays.toString(PlatformId.values()) + ".", e);
        }
        if (platform == PlatformId.NEOFORGE) {
            throw new InvalidUserDataException("Nylium module '" + moduleName
                    + "' declares NEOFORGE, which has no Nylium bootstrap yet (SP-1b). A jar built"
                    + " with it would never dispatch on NeoForge, so it is rejected rather than"
                    + " shipped.");
        }
        return platform;
    }
}
```

- [ ] **Step 6: Write `ResolvedModule`**

```java
package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.PlatformId;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;

import java.util.Collections;
import java.util.List;
import java.util.Set;

final class ResolvedModule {

    private final String name;
    private final String path;
    private final Set<PlatformId> platforms;
    private final String minecraft;
    private final Environment environment;
    private final List<String> mixins;
    private final int priority;
    private final String entrypoint;
    private final Provider<RegularFile> jar;

    ResolvedModule(String name, String path, Set<PlatformId> platforms, String minecraft,
                   Environment environment, List<String> mixins, int priority, String entrypoint,
                   Provider<RegularFile> jar) {
        this.name = name;
        this.path = path;
        this.platforms = Collections.unmodifiableSet(platforms);
        this.minecraft = minecraft;
        this.environment = environment;
        this.mixins = Collections.unmodifiableList(mixins);
        this.priority = priority;
        this.entrypoint = entrypoint;
        this.jar = jar;
    }

    String name() {
        return name;
    }

    String path() {
        return path;
    }

    Set<PlatformId> platforms() {
        return platforms;
    }

    String minecraft() {
        return minecraft;
    }

    Environment environment() {
        return environment;
    }

    List<String> mixins() {
        return mixins;
    }

    int priority() {
        return priority;
    }

    String entrypoint() {
        return entrypoint;
    }

    Provider<RegularFile> jar() {
        return jar;
    }
}
```

- [ ] **Step 7: Fill in `NyliumExtension`**

```java
package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.api.Environment;
import io.github.intisy.nylium.api.PlatformId;
import io.github.intisy.nylium.api.NyliumException;
import io.github.intisy.nylium.core.VersionRange;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class NyliumExtension {

    private final Project project;
    private final ModSpec mod;
    private final NamedDomainObjectContainer<ModuleSpec> modules;

    public NyliumExtension(Project project) {
        this.project = project;
        this.mod = project.getObjects().newInstance(ModSpec.class);
        this.modules = project.getObjects().domainObjectContainer(ModuleSpec.class);
    }

    public void mod(Action<? super ModSpec> action) {
        action.execute(mod);
    }

    public ModSpec getMod() {
        return mod;
    }

    /**
     * @implNote Creates rather than gets-or-creates, because {@code maybeCreate} would silently
     *     merge two blocks that declare the same module name instead of rejecting them.
     */
    public void module(String name, Action<? super ModuleSpec> action) {
        action.execute(modules.create(name));
    }

    public NamedDomainObjectContainer<ModuleSpec> getModules() {
        return modules;
    }

    Project project() {
        return project;
    }

    String modulePrefix() {
        return mod.getModulePrefix().getOrElse(requiredId());
    }

    private String requiredId() {
        String id = mod.getId().getOrNull();
        if (id == null || id.trim().isEmpty()) {
            throw new InvalidUserDataException(
                    "The Nylium mod id is not set. Set it in the nylium { mod { id = ... } } block.");
        }
        return id.trim();
    }

    List<ResolvedModule> resolve() {
        String prefix = modulePrefix();
        if (modules.isEmpty()) {
            throw new InvalidUserDataException(
                    "Nylium declares no modules. Add at least one nylium { module('...') { } } block.");
        }
        List<ResolvedModule> resolved = new ArrayList<ResolvedModule>();
        for (ModuleSpec spec : modules) {
            resolved.add(resolveOne(spec, prefix));
        }
        return resolved;
    }

    private ResolvedModule resolveOne(ModuleSpec spec, String prefix) {
        String name = spec.getName();
        if (!spec.getJar().isPresent()) {
            throw new InvalidUserDataException("Nylium module '" + name
                    + "' has no jar. Set module.jar to the built, already remapped module jar.");
        }
        Set<PlatformId> platforms = Platforms.parse(name, spec.getPlatforms().get());
        String minecraft = required(spec.getMinecraft().getOrNull(), name, "minecraft");
        validateRange(name, minecraft);
        return new ResolvedModule(name, "modules/" + prefix + "-" + name + ".jar", platforms,
                minecraft, environment(name, spec.getEnvironment().getOrNull()),
                spec.getMixins().get(), spec.getPriority().getOrElse(0),
                emptyToNull(spec.getEntrypoint().getOrNull()), spec.getJar());
    }

    /**
     * @implNote Parsed with the kernel's own parser rather than a copy, so a range the game would
     *     reject cannot pass the build; {@code VersionRange.parse} signals with a subclass of
     *     {@code NyliumException}.
     */
    private void validateRange(String moduleName, String raw) {
        try {
            VersionRange.parse(raw);
        } catch (NyliumException e) {
            throw new InvalidUserDataException("Nylium module '" + moduleName
                    + "' declares an unusable Minecraft range '" + raw + "': " + e.getMessage(), e);
        }
    }

    private Environment environment(String moduleName, String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Environment.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidUserDataException("Nylium module '" + moduleName
                    + "' declares an unknown environment '" + raw + "'. Known environments are "
                    + Arrays.toString(Environment.values()) + ".", e);
        }
    }

    private String required(String value, String moduleName, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new InvalidUserDataException(
                    "Nylium module '" + moduleName + "' is missing '" + field + "'.");
        }
        return value.trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
```

- [ ] **Step 8: Run the tests**

Run: `./gradlew :nylium-gradle:check --offline`
Expected: PASS, all validation tests green.

- [ ] **Step 9: Commit**

```bash
git add nylium-gradle
git commit -m "feat(gradle): validate the nylium module declaration"
```

---

### Task 3: Render the module manifest

**Files:**
- Create: `ManifestRenderer.java`
- Create: `nylium-gradle/src/test/resources/expected-testmod-manifest.properties`
- Test: `ManifestRendererTest.java`

**Interfaces:**
- Consumes: `ResolvedModule` from Task 2.
- Produces: `static String ManifestRenderer.render(List<ResolvedModule>)`, emitting `key=value` lines separated by `\n` with a trailing `\n`.

- [ ] **Step 1: Capture the proven manifest as the expected fixture**

Copy the `nylium-modules.properties` literal out of `nylium-testmod/build.gradle` verbatim into `nylium-gradle/src/test/resources/expected-testmod-manifest.properties`, and add one trailing newline. That literal is the artifact proven on five Minecraft servers, so it, not a fresh expectation, is what the renderer is measured against. Do not edit the block in `nylium-testmod/build.gradle`.

- [ ] **Step 2: Write the failing test**

Create `ManifestRendererTest.java`:

```java
package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.api.PlatformId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManifestRendererTest {

    private static ResolvedModule module(String name, PlatformId platform, String minecraft,
                                         List<String> mixins) {
        Set<PlatformId> platforms = new LinkedHashSet<PlatformId>();
        platforms.add(platform);
        return new ResolvedModule(name, "modules/testmod-" + name + ".jar", platforms, minecraft,
                null, mixins, 0, "io.github.intisy.nylium.testmod.TestModEntry", null);
    }

    @Test
    void reproducesTheProvenTestModManifest() throws IOException {
        List<ResolvedModule> modules = new ArrayList<ResolvedModule>();
        modules.add(module("1.21.11", PlatformId.FABRIC, "1.21.11", Collections.<String>emptyList()));
        modules.add(module("1.21.10", PlatformId.FABRIC, "1.21.10", Collections.<String>emptyList()));
        modules.add(module("1.7.10", PlatformId.LAUNCHWRAPPER, "[1.7,1.12.2]", Collections.<String>emptyList()));
        modules.add(module("1.16.5", PlatformId.MODLAUNCHER_8, "[1.13,1.16.5]", Collections.<String>emptyList()));
        modules.add(module("1.21.11-ml9", PlatformId.MODLAUNCHER_9, "1.21.11",
                Collections.singletonList("mixins.nylium-testmod-ml9.json")));

        assertEquals(normalize(expected()), normalize(ManifestRenderer.render(modules)));
    }

    @Test
    void ordersMultiplePlatformsAndOmitsDefaults() {
        Set<PlatformId> platforms = new LinkedHashSet<PlatformId>(
                Arrays.asList(PlatformId.FABRIC, PlatformId.LAUNCHWRAPPER));
        ResolvedModule module = new ResolvedModule("wide", "modules/demo-wide.jar", platforms,
                "[1.7,)", null, Collections.<String>emptyList(), 0, null, null);
        assertEquals("module.0.path=modules/demo-wide.jar\n"
                + "module.0.platforms=FABRIC,LAUNCHWRAPPER\n"
                + "module.0.minecraft=[1.7,)\n", ManifestRenderer.render(
                        Collections.singletonList(module)));
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").trim();
    }

    private static String expected() throws IOException {
        InputStream stream = ManifestRendererTest.class.getResourceAsStream(
                "/expected-testmod-manifest.properties");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        stream.close();
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 3: Run and watch it fail**

Run: `./gradlew :nylium-gradle:test --tests '*ManifestRendererTest*' --offline`
Expected: FAIL, `ManifestRenderer` does not exist.

- [ ] **Step 4: Write the renderer**

```java
package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.api.PlatformId;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class ManifestRenderer {

    private ManifestRenderer() {
    }

    /**
     * @implNote Field order matches the manifest the kernel's smoke matrix already proves, and the
     *     index is the module's position, so nothing in the wire format is hand maintained.
     */
    static String render(List<ResolvedModule> modules) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < modules.size(); index++) {
            ResolvedModule module = modules.get(index);
            String prefix = "module." + index + ".";
            append(out, prefix + "path", module.path());
            append(out, prefix + "platforms", join(module.platforms()));
            append(out, prefix + "minecraft", module.minecraft());
            if (module.environment() != null) {
                append(out, prefix + "environment", module.environment().name());
            }
            if (!module.mixins().isEmpty()) {
                append(out, prefix + "mixins", String.join(",", module.mixins()));
            }
            if (module.priority() != 0) {
                append(out, prefix + "priority", Integer.toString(module.priority()));
            }
            if (module.entrypoint() != null) {
                append(out, prefix + "entrypoint", module.entrypoint());
            }
        }
        return out.toString();
    }

    private static String join(Set<PlatformId> platforms) {
        List<String> names = new ArrayList<String>();
        for (PlatformId platform : platforms) {
            names.add(platform.name());
        }
        return String.join(",", names);
    }

    private static void append(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(value).append('\n');
    }
}
```

- [ ] **Step 5: Run the tests**

Run: `./gradlew :nylium-gradle:test --tests '*ManifestRendererTest*' --offline`
Expected: PASS.

- [ ] **Step 6: Prove the output is readable by the kernel**

Add to `ManifestRendererTest`:

```java
    @Test
    void rendersWhatTheKernelCanRead() {
        List<ResolvedModule> modules = new ArrayList<ResolvedModule>();
        modules.add(module("1.21.11", PlatformId.FABRIC, "1.21.11", Collections.<String>emptyList()));
        io.github.intisy.nylium.core.ModuleManifest manifest =
                io.github.intisy.nylium.core.ModuleManifest.read(
                        new java.io.StringReader(ManifestRenderer.render(modules)));
        assertEquals(1, manifest.modules().size());
        assertEquals("modules/testmod-1.21.11.jar", manifest.modules().get(0).path());
    }
```

This is the round trip that matters: the renderer's output is parsed by the same class the game uses, so a format drift fails here rather than at launch.

Add two more round-trip cases, because otherwise no test executes the `environment` or non-zero `priority` emission branches at all, and those are exactly the values the reader rejects at launch rather than at build time. One module with a non-null `Environment`, one with a non-zero `priority`, each asserting the value survives the round trip: `ModuleDescriptor.environment()` returns an `Optional<Environment>` and `priority()` returns an `int`.

Run: `./gradlew :nylium-gradle:test --tests '*ManifestRendererTest*' --offline`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add nylium-gradle
git commit -m "feat(gradle): render the module manifest from the declaration"
```

---

### Task 4: Render the loader metadata

**Files:**
- Create: `FabricMetadataRenderer.java`, `ServiceRenderer.java`, `Json.java`
- Test: `FabricMetadataRendererTest.java`, `ServiceRendererTest.java`

**Interfaces:**
- Produces:
  - `static String FabricMetadataRenderer.render(ModSpec)`
  - `static String ServiceRenderer.transformationServices(Set<PlatformId>)` returning null when neither ModLauncher platform is present
  - `static String ServiceRenderer.launchPlugins(Set<PlatformId>)` returning null when MODLAUNCHER_9 is absent

- [ ] **Step 1: Write the failing tests**

`ServiceRendererTest.java`:

```java
package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.api.PlatformId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServiceRendererTest {

    private static Set<PlatformId> set(PlatformId... platforms) {
        return new LinkedHashSet<PlatformId>(Arrays.asList(platforms));
    }

    @Test
    void listsBothModLauncherServicesInProvenOrder() {
        assertEquals("io.github.intisy.nylium.bootstrap.ml8.NyliumMl8Service\n"
                        + "io.github.intisy.nylium.bootstrap.ml9.NyliumMl9TransformationService\n",
                ServiceRenderer.transformationServices(
                        set(PlatformId.MODLAUNCHER_8, PlatformId.MODLAUNCHER_9)));
    }

    @Test
    void listsOnlyTheDeclaredModLauncherService() {
        assertEquals("io.github.intisy.nylium.bootstrap.ml9.NyliumMl9TransformationService\n",
                ServiceRenderer.transformationServices(set(PlatformId.MODLAUNCHER_9)));
    }

    @Test
    void emitsNoServiceFileWithoutModLauncher() {
        assertNull(ServiceRenderer.transformationServices(set(PlatformId.FABRIC)));
        assertNull(ServiceRenderer.launchPlugins(set(PlatformId.FABRIC)));
    }

    @Test
    void emitsTheLaunchPluginOnlyForModLauncher9() {
        assertEquals("io.github.intisy.nylium.bootstrap.ml9.NyliumMl9LaunchPlugin\n",
                ServiceRenderer.launchPlugins(set(PlatformId.MODLAUNCHER_9)));
        assertNull(ServiceRenderer.launchPlugins(set(PlatformId.MODLAUNCHER_8)));
    }

    @Test
    void emitsNothingForAnEmptyPlatformSet() {
        assertNull(ServiceRenderer.transformationServices(Collections.<PlatformId>emptySet()));
    }
}
```

`FabricMetadataRendererTest.java` asserts, by parsing the output with gson:

```java
package io.github.intisy.nylium.gradle;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricMetadataRendererTest {

    private ModSpec spec() {
        Project project = ProjectBuilder.builder().build();
        ModSpec mod = project.getObjects().newInstance(ModSpec.class);
        mod.getId().set("nylium_testmod");
        mod.getName().set("Nylium Test Mod");
        mod.getVersion().set("0.1.0");
        mod.getEnvironment().set("*");
        mod.getFabricLoaderVersion().set(">=0.14.0");
        return mod;
    }

    private JsonObject render(ModSpec mod) {
        return JsonParser.parseString(FabricMetadataRenderer.render(mod)).getAsJsonObject();
    }

    @Test
    void declaresNyliumsPreLaunchEntrypoint() {
        JsonArray preLaunch = render(spec()).getAsJsonObject("entrypoints").getAsJsonArray("preLaunch");
        assertEquals(1, preLaunch.size());
        assertEquals("io.github.intisy.nylium.bootstrap.fabric.NyliumPreLaunch",
                preLaunch.get(0).getAsString());
    }

    @Test
    void declaresNoMixins() {
        assertFalse(render(spec()).has("mixins"));
    }

    @Test
    void omitsTheMinecraftDependencyByDefault() {
        assertFalse(render(spec()).getAsJsonObject("depends").has("minecraft"));
    }

    @Test
    void honoursAnExplicitMinecraftDependency() {
        ModSpec mod = spec();
        mod.getMinecraftDependency().set(">=1.16.5");
        assertEquals(">=1.16.5",
                render(mod).getAsJsonObject("depends").get("minecraft").getAsString());
    }

    @Test
    void escapesQuotesInText() {
        ModSpec mod = spec();
        mod.getDescription().set("A \"quoted\" mod");
        assertEquals("A \"quoted\" mod", render(mod).get("description").getAsString());
    }

    @Test
    void carriesTheIdentityFields() {
        JsonObject json = render(spec());
        assertEquals(1, json.get("schemaVersion").getAsInt());
        assertEquals("nylium_testmod", json.get("id").getAsString());
        assertEquals("0.1.0", json.get("version").getAsString());
        assertTrue(json.has("name"));
    }
}
```

Add two more cases, because otherwise each of these tests can pass while the document is wrong in a
way it does not look at. `omitsTheMinecraftDependencyByDefault` asserts only that `minecraft` is
absent, so assert separately that `depends.fabricloader` is present and defaults to `>=0.14.0`. And
only the quote path of `Json.quote` is exercised, so add a case whose `description` contains a
backslash and a newline, asserting the value survives a gson round trip unchanged; that proves the
escaping emits valid JSON and preserves the value, rather than merely that some escape sequence
appears.

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew :nylium-gradle:test --tests '*Renderer*' --offline`
Expected: FAIL, classes do not exist.

- [ ] **Step 3: Write `Json`**

```java
package io.github.intisy.nylium.gradle;

final class Json {

    private Json() {
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
            }
        }
        return out.append('"').toString();
    }
}
```

- [ ] **Step 4: Write `ServiceRenderer`**

```java
package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.api.PlatformId;

import java.util.Set;

final class ServiceRenderer {

    static final String ML8_SERVICE = "io.github.intisy.nylium.bootstrap.ml8.NyliumMl8Service";
    static final String ML9_SERVICE =
            "io.github.intisy.nylium.bootstrap.ml9.NyliumMl9TransformationService";
    static final String ML9_LAUNCH_PLUGIN =
            "io.github.intisy.nylium.bootstrap.ml9.NyliumMl9LaunchPlugin";

    private ServiceRenderer() {
    }

    /**
     * @implNote Both ModLauncher backends share this one service file, which is why its contents
     *     depend on the declared platform set rather than being a fixed resource.
     */
    static String transformationServices(Set<PlatformId> platforms) {
        StringBuilder out = new StringBuilder();
        if (platforms.contains(PlatformId.MODLAUNCHER_8)) {
            out.append(ML8_SERVICE).append('\n');
        }
        if (platforms.contains(PlatformId.MODLAUNCHER_9)) {
            out.append(ML9_SERVICE).append('\n');
        }
        return out.length() == 0 ? null : out.toString();
    }

    static String launchPlugins(Set<PlatformId> platforms) {
        return platforms.contains(PlatformId.MODLAUNCHER_9)
                ? ML9_LAUNCH_PLUGIN + "\n"
                : null;
    }
}
```

- [ ] **Step 5: Write `FabricMetadataRenderer`**

```java
package io.github.intisy.nylium.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class FabricMetadataRenderer {

    static final String PRE_LAUNCH = "io.github.intisy.nylium.bootstrap.fabric.NyliumPreLaunch";

    private FabricMetadataRenderer() {
    }

    /**
     * @implNote No {@code mixins} key is emitted on purpose: a module's mixin configs live inside
     *     the module jar and are registered through the platform, so naming them here would point
     *     Fabric Loader at resources the outer jar does not contain.
     */
    static String render(ModSpec mod) {
        List<String> lines = new ArrayList<String>();
        lines.add("  \"schemaVersion\": 1");
        lines.add("  \"id\": " + Json.quote(mod.getId().get()));
        lines.add("  \"version\": " + Json.quote(mod.getVersion().getOrElse("0.0.0")));
        addOptional(lines, "name", mod.getName().getOrNull());
        addOptional(lines, "description", mod.getDescription().getOrNull());
        addAuthors(lines, mod.getAuthors().get());
        addContact(lines, mod.getContact().get());
        addOptional(lines, "license", mod.getLicense().getOrNull());
        addOptional(lines, "icon", mod.getIcon().getOrNull());
        lines.add("  \"environment\": " + Json.quote(mod.getEnvironment().getOrElse("*")));
        lines.add("  \"entrypoints\": { \"preLaunch\": [" + Json.quote(PRE_LAUNCH) + "] }");
        lines.add("  \"depends\": " + depends(mod));
        return "{\n" + String.join(",\n", lines) + "\n}\n";
    }

    private static String depends(ModSpec mod) {
        List<String> entries = new ArrayList<String>();
        entries.add("\"fabricloader\": "
                + Json.quote(mod.getFabricLoaderVersion().getOrElse(">=0.14.0")));
        String minecraft = mod.getMinecraftDependency().getOrNull();
        if (minecraft != null && !minecraft.trim().isEmpty()) {
            entries.add("\"minecraft\": " + Json.quote(minecraft.trim()));
        }
        return "{ " + String.join(", ", entries) + " }";
    }

    private static void addOptional(List<String> lines, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            lines.add("  " + Json.quote(key) + ": " + Json.quote(value));
        }
    }

    private static void addAuthors(List<String> lines, List<String> authors) {
        if (authors.isEmpty()) {
            return;
        }
        List<String> quoted = new ArrayList<String>();
        for (String author : authors) {
            quoted.add(Json.quote(author));
        }
        lines.add("  \"authors\": [" + String.join(", ", quoted) + "]");
    }

    private static void addContact(List<String> lines, Map<String, String> contact) {
        if (contact.isEmpty()) {
            return;
        }
        List<String> entries = new ArrayList<String>();
        for (Map.Entry<String, String> entry : contact.entrySet()) {
            entries.add(Json.quote(entry.getKey()) + ": " + Json.quote(entry.getValue()));
        }
        lines.add("  \"contact\": { " + String.join(", ", entries) + " }");
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew :nylium-gradle:check --offline`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add nylium-gradle
git commit -m "feat(gradle): render the fabric and modlauncher metadata"
```

---

### Task 5: Write the generated files through tracked tasks

**Files:**
- Create: `NyliumTextFileTask.java`
- Modify: `NyliumPlugin.java`
- Test: `UpToDateFunctionalTest.java`

**Interfaces:**
- Produces: task `nyliumManifest` writing `nylium-modules.properties`; tasks `nyliumFabricModJson`, `nyliumTransformationServices`, `nyliumLaunchPlugins` writing their respective files when their platform is declared; lifecycle task `nyliumMetadata` depending on whichever of those exist. All are instances of `NyliumTextFileTask` with `@Input` content and an `@OutputFile`.

- [ ] **Step 1: Write `NyliumTextFileTask`**

```java
package io.github.intisy.nylium.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @implNote The generated text is a declared {@code @Input} and the destination a declared
 *     {@code @OutputFile}, so a changed declaration always rebuilds the jar. The kernel's own
 *     packaging emitted these files through {@code resources.text.fromString}, whose backing temp
 *     path changes every configuration and is therefore not a tracked input at all, which let a
 *     stale module survive a rebuild.
 */
public abstract class NyliumTextFileTask extends DefaultTask {

    @Input
    public abstract Property<String> getContent();

    @OutputFile
    public abstract RegularFileProperty getDestination();

    @TaskAction
    public void write() {
        Path target = getDestination().get().getAsFile().toPath();
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, getContent().get().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + target, e);
        }
    }
}
```

- [ ] **Step 2: Register the generators in `NyliumPlugin`**

Replace `NyliumPlugin.apply` with the following. Everything that needs the declaration is deferred to `afterEvaluate`, because the platform union is not known until the build script has run.

```java
package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.api.PlatformId;
import io.github.intisy.nylium.core.ModuleManifest;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class NyliumPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        final NyliumExtension nylium =
                project.getExtensions().create("nylium", NyliumExtension.class, project);

        final TaskProvider<Task> metadata = project.getTasks().register("nyliumMetadata", task ->
                task.setDescription("Generates the Nylium manifest and loader metadata."));

        project.afterEvaluate(evaluated -> {
            if (nylium.getModules().isEmpty()) {
                failWhenInvokedWithNoModules(metadata);
                return;
            }

            List<ResolvedModule> modules = nylium.resolve();
            Set<PlatformId> platforms = platformUnion(modules);

            List<TaskProvider<NyliumTextFileTask>> writers =
                    new java.util.ArrayList<TaskProvider<NyliumTextFileTask>>();
            writers.add(writer(evaluated, "nyliumManifest", ModuleManifest.RESOURCE,
                    ManifestRenderer.render(modules)));
            if (platforms.contains(PlatformId.FABRIC)) {
                writers.add(writer(evaluated, "nyliumFabricModJson", "fabric.mod.json",
                        FabricMetadataRenderer.render(nylium.getMod())));
            }
            String services = ServiceRenderer.transformationServices(platforms);
            if (services != null) {
                writers.add(writer(evaluated, "nyliumTransformationServices",
                        "META-INF/services/cpw.mods.modlauncher.api.ITransformationService",
                        services));
            }
            String launchPlugins = ServiceRenderer.launchPlugins(platforms);
            if (launchPlugins != null) {
                writers.add(writer(evaluated, "nyliumLaunchPlugins",
                        "META-INF/services/cpw.mods.modlauncher.serviceapi.ILaunchPluginService",
                        launchPlugins));
            }
            metadata.configure(task -> task.dependsOn(writers));
        });
    }

    /**
     * @implNote {@code afterEvaluate} runs for every task invocation, including plain introspection
     *     such as {@code tasks} or {@code help}, so an unconfigured project must not fail here;
     *     only invoking {@code nyliumMetadata} itself should fail, with an actionable message.
     */
    private static void failWhenInvokedWithNoModules(TaskProvider<Task> metadata) {
        metadata.configure(task -> task.doFirst(ignored -> {
            throw new InvalidUserDataException("Nylium is applied but declares no modules. Add at"
                    + " least one nylium { module('...') { } } block.");
        }));
    }

    static Set<PlatformId> platformUnion(List<ResolvedModule> modules) {
        Set<PlatformId> platforms = new LinkedHashSet<PlatformId>();
        for (ResolvedModule module : modules) {
            platforms.addAll(module.platforms());
        }
        return platforms;
    }

    private static TaskProvider<NyliumTextFileTask> writer(Project project, String taskName,
                                                           String relativePath, String content) {
        Provider<String> body = project.provider(() -> content);
        return project.getTasks().register(taskName, NyliumTextFileTask.class, task -> {
            task.getContent().set(body);
            task.getDestination().set(
                    project.getLayout().getBuildDirectory().file("nylium/" + relativePath));
        });
    }
}
```

- [ ] **Step 3: Write the functional test**

Create `UpToDateFunctionalTest.java`. It builds a fixture project twice and asserts the second run is up to date, then changes the declaration and asserts a rebuild. This is the regression test for the staleness defect.

```java
package io.github.intisy.nylium.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpToDateFunctionalTest {

    @TempDir
    Path projectDir;

    private void write(String relativePath, String content) throws IOException {
        Path target = projectDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    private void fixture(String minecraft) throws IOException {
        write("settings.gradle", "rootProject.name = 'fixture'\n");
        write("module.jar", "not a real jar for this test\n");
        write("build.gradle", ""
                + "plugins { id 'io.github.intisy.nylium' }\n"
                + "nylium {\n"
                + "    mod { id = 'demo'; name = 'Demo'; version = '1.0.0' }\n"
                + "    module('" + minecraft + "') {\n"
                + "        jar = layout.projectDirectory.file('module.jar')\n"
                + "        platforms = ['FABRIC']\n"
                + "        minecraft = '" + minecraft + "'\n"
                + "    }\n"
                + "}\n");
    }

    private BuildResult run() {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("nyliumMetadata")
                .build();
    }

    @Test
    void isUpToDateOnASecondRunAndRebuildsAfterAChange() throws IOException {
        fixture("1.21.11");
        assertEquals(TaskOutcome.SUCCESS, run().task(":nyliumManifest").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, run().task(":nyliumManifest").getOutcome());

        File manifest = projectDir.resolve("build/nylium/nylium-modules.properties").toFile();
        assertTrue(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8)
                .contains("1.21.11"));

        fixture("1.21.10");
        assertEquals(TaskOutcome.SUCCESS, run().task(":nyliumManifest").getOutcome());
        assertTrue(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8)
                .contains("1.21.10"));
    }

    @Test
    void emitsNoModLauncherServiceFileForAFabricOnlyMod() throws IOException {
        fixture("1.21.11");
        run();
        assertTrue(Files.exists(projectDir.resolve("build/nylium/fabric.mod.json")));
        assertTrue(!Files.exists(projectDir.resolve(
                "build/nylium/META-INF/services/cpw.mods.modlauncher.api.ITransformationService")));
    }
}
```

Add three more cases. Two cover the empty-module guard, and both halves are needed: applying the plugin with no `nylium { }` block at all must leave a plain introspection command such as `tasks` succeeding, and invoking `nyliumMetadata` on that same project must fail with the actionable message. Asserting only the first half would also pass if the plugin silently registered nothing. The third extends the up-to-date-then-rebuild assertion to `fabric.mod.json`, so the regression this task exists to prevent is proven for more than one of the four generated files, and asserts `ILaunchPluginService` is absent alongside `ITransformationService`, since the two share their gating.

- [ ] **Step 4: Run it**

Run: `./gradlew :nylium-gradle:check --offline`
Expected: PASS. If TestKit cannot resolve the plugin, confirm `java-gradle-plugin` is applied so `withPluginClasspath()` has generated metadata; do not add a manual classpath.

- [ ] **Step 5: Commit**

```bash
git add nylium-gradle
git commit -m "feat(gradle): generate the metadata through tracked tasks"
```

---

### Task 6: Embed the needed Nylium parts and assemble the jar

**Files:**
- Modify: `NyliumPlugin.java`
- Test: extend `UpToDateFunctionalTest.java` or add `UniversalJarFunctionalTest.java`

**Interfaces:**
- Produces: resolvable configuration `nyliumEmbed`; task `nyliumUniversalJar` of type `Jar` whose archive contains the embedded Nylium classes, each module jar at its manifest `path`, the generated metadata, and the `TweakClass` attribute when LaunchWrapper is declared.

- [ ] **Step 1: Read the plugin's own version**

Add to `NyliumPlugin`:

```java
    private static String pluginVersion() {
        java.io.InputStream stream = NyliumPlugin.class.getResourceAsStream(
                "/nylium-gradle-version.properties");
        if (stream == null) {
            throw new IllegalStateException(
                    "nylium-gradle-version.properties is missing from the plugin jar");
        }
        java.util.Properties properties = new java.util.Properties();
        try {
            properties.load(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
            stream.close();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        String version = properties.getProperty("version");
        if (version == null || version.trim().isEmpty()) {
            throw new IllegalStateException("nylium-gradle-version.properties declares no version");
        }
        return version.trim();
    }
```

- [ ] **Step 2: Create the embed configuration inside `afterEvaluate`**

```java
    private static final java.util.Map<PlatformId, String> BOOTSTRAPS = bootstraps();

    private static java.util.Map<PlatformId, String> bootstraps() {
        java.util.Map<PlatformId, String> map =
                new java.util.EnumMap<PlatformId, String>(PlatformId.class);
        map.put(PlatformId.FABRIC, "nylium-bootstrap-fabric");
        map.put(PlatformId.LAUNCHWRAPPER, "nylium-bootstrap-launchwrapper");
        map.put(PlatformId.MODLAUNCHER_8, "nylium-bootstrap-modlauncher8");
        map.put(PlatformId.MODLAUNCHER_9, "nylium-bootstrap-modlauncher9");
        return map;
    }

    private static org.gradle.api.artifacts.Configuration embedConfiguration(
            Project project, Set<PlatformId> platforms) {
        org.gradle.api.artifacts.Configuration embed =
                project.getConfigurations().maybeCreate("nyliumEmbed");
        embed.setCanBeConsumed(false);
        embed.setCanBeResolved(true);
        String version = pluginVersion();
        addEmbed(project, embed, "nylium-api", version);
        addEmbed(project, embed, "nylium-core", version);
        for (PlatformId platform : platforms) {
            String artifact = BOOTSTRAPS.get(platform);
            if (artifact == null) {
                throw new org.gradle.api.InvalidUserDataException(
                        "No Nylium bootstrap exists for platform " + platform + ".");
            }
            addEmbed(project, embed, artifact, version);
        }
        return embed;
    }

    private static void addEmbed(Project project,
                                 org.gradle.api.artifacts.Configuration embed,
                                 String artifact, String version) {
        embed.getDependencies().add(project.getDependencies()
                .create("io.github.intisy.nylium:" + artifact + ":" + version));
    }
```

- [ ] **Step 3: Register the jar task**

Append inside `afterEvaluate`, after the metadata wiring:

```java
            final org.gradle.api.artifacts.Configuration embed =
                    embedConfiguration(evaluated, platforms);
            final boolean launchWrapper = platforms.contains(PlatformId.LAUNCHWRAPPER);

            evaluated.getTasks().register("nyliumUniversalJar",
                    org.gradle.jvm.tasks.Jar.class, jar -> {
                jar.setDescription("Assembles the Nylium universal jar.");
                jar.getArchiveClassifier().set("universal");
                jar.dependsOn(metadata);
                jar.setDuplicatesStrategy(DuplicatesStrategy.FAIL);

                final ArchiveOperations archives = archiveOperations;
                jar.from(evaluated.provider(() -> {
                    java.util.List<Object> trees = new java.util.ArrayList<Object>();
                    for (File artifact : embed.getFiles()) {
                        trees.add(archives.zipTree(artifact));
                    }
                    return trees;
                }), copy -> copy.exclude("META-INF/MANIFEST.MF", "META-INF/*.SF",
                        "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/maven/**",
                        "module-info.class"));

                jar.from(evaluated.getLayout().getBuildDirectory().dir("nylium"));

                for (ResolvedModule module : modules) {
                    final String path = module.path();
                    jar.from(module.jar(), copy -> {
                        copy.into(path.substring(0, path.lastIndexOf('/')));
                        copy.rename(".*", path.substring(path.lastIndexOf('/') + 1));
                    });
                }

                if (launchWrapper) {
                    jar.getManifest().getAttributes().put("TweakClass",
                            "io.github.intisy.nylium.bootstrap.launchwrapper.NyliumTweaker");
                }
            });
```

The excludes are not cosmetic. Unpacking published jars would otherwise contribute each artifact's own `META-INF/MANIFEST.MF`, which both collides between artifacts and diverges from the reference jar the Task 8 differential compares against.

Three details in that block are load bearing:

- **`zipTree` comes from a constructor-injected `ArchiveOperations`, not from the `Project`.** The provider runs when the `Jar` task builds its file tree, which is execution time, so closing over a `Project` there is configuration-cache incompatible. A Gradle service is safe to hold across that boundary. Inject it with `@Inject public NyliumPlugin(ArchiveOperations archiveOperations)`.
- **`DuplicatesStrategy.FAIL`.** The default is `INCLUDE`, so a path collision between two embedded jars, or between a jar and the metadata tree, would silently emit duplicate entries whose winner depends on copy order. Failing loudly names the collision instead, and keeps the Task 8 entry-set differential from quietly absorbing a duplicate.
- The embedded artifacts are resolved from `nyliumEmbed` at execution time, so a plain `./gradlew tasks` never resolves them.

- [ ] **Step 4: Write the wiring test**

Assembling a real jar needs a repository holding the Nylium artifacts, which Task 8 sets up. What is testable now is the wiring, with `ProjectBuilder`: that the configuration and task exist, that the embed set follows the declared platforms, and that the LaunchWrapper attribute appears only when it should. Add `UniversalJarWiringTest.java`:

```java
package io.github.intisy.nylium.gradle;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.jvm.tasks.Jar;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniversalJarWiringTest {

    private Project projectWith(String platform) {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.nylium");
        NyliumExtension nylium = (NyliumExtension) project.getExtensions().getByName("nylium");
        nylium.mod(mod -> {
            mod.getId().set("demo");
            mod.getVersion().set("1.0.0");
        });
        nylium.module("1.21.11", module -> {
            module.getJar().set(new File(project.getProjectDir(), "module.jar"));
            module.getPlatforms().set(Collections.singletonList(platform));
            module.getMinecraft().set("1.21.11");
        });
        ((org.gradle.api.internal.project.ProjectInternal) project).evaluate();
        return project;
    }

    private Set<String> embeddedArtifacts(Project project) {
        Set<String> names = new LinkedHashSet<String>();
        for (Dependency dependency
                : project.getConfigurations().getByName("nyliumEmbed").getDependencies()) {
            names.add(dependency.getName());
        }
        return names;
    }

    @Test
    void embedsOnlyTheBootstrapForTheDeclaredPlatform() {
        Set<String> embedded = embeddedArtifacts(projectWith("FABRIC"));
        assertTrue(embedded.contains("nylium-api"));
        assertTrue(embedded.contains("nylium-core"));
        assertTrue(embedded.contains("nylium-bootstrap-fabric"));
        assertFalse(embedded.contains("nylium-bootstrap-modlauncher9"));
        assertFalse(embedded.contains("nylium-bootstrap-launchwrapper"));
    }

    @Test
    void addsTheTweakClassAttributeOnlyForLaunchWrapper() {
        Jar withLaunchWrapper = (Jar) projectWith("LAUNCHWRAPPER").getTasks()
                .getByName("nyliumUniversalJar");
        assertEquals("io.github.intisy.nylium.bootstrap.launchwrapper.NyliumTweaker",
                withLaunchWrapper.getManifest().getAttributes().get("TweakClass"));

        Jar fabricOnly = (Jar) projectWith("FABRIC").getTasks().getByName("nyliumUniversalJar");
        assertFalse(fabricOnly.getManifest().getAttributes().containsKey("TweakClass"));
    }

    @Test
    void registersTheJarTask() {
        assertNotNull(projectWith("FABRIC").getTasks().findByName("nyliumUniversalJar"));
    }
}
```

If `ProjectInternal.evaluate()` proves unusable, trigger `afterEvaluate` the way the surrounding tests do and record what you used; the assertions are what matter, not the trigger.

Assert the embedded dependencies' group and version as well as their name, since a hardcoded version bypassing `pluginVersion()` would otherwise pass unnoticed and the version is resolvable in process from a classpath resource. Include `nylium-bootstrap-modlauncher8` in the FABRIC-only absence assertions, which otherwise skip exactly one bootstrap.

Add a separate functional test that runs `nyliumUniversalJar` with `--configuration-cache` twice and asserts a cold store followed by a warm reuse. Without it, configuration-cache safety is an argument rather than a measurement.

- [ ] **Step 5: Run**

Run: `./gradlew :nylium-gradle:check --offline`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add nylium-gradle
git commit -m "feat(gradle): assemble the universal jar from declared platforms"
```

---

### Task 7: Verify the module jars

**Files:**
- Create: `NyliumVerifyModulesTask.java`
- Modify: `NyliumPlugin.java`
- Test: `VerifyModulesTest.java`

**Interfaces:**
- Produces: task `nyliumVerifyModules`, which `nyliumUniversalJar` depends on. Fails when a declared mixin config is absent from its module jar, or when a module jar contains `nylium-modules.properties` or any `io/github/intisy/nylium/api/` entry.

- [ ] **Step 1: Write the failing tests**

`VerifyModulesTest.java` builds small jars in a temp directory with `java.util.zip.ZipOutputStream` and asserts each rejection, plus one passing case. Build one jar containing `mixins.demo.json`, one without it, and one containing `io/github/intisy/nylium/api/PlatformId.class`.

```java
package io.github.intisy.nylium.gradle;

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
    void rejectsAModuleThatShadowedTheNyliumApi() throws IOException {
        Path jar = jarContaining("shadowed.jar",
                "io/github/intisy/nylium/api/PlatformId.class");
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> ModuleJarInspector.verify("1.21.11", jar.toFile(),
                        Collections.<String>emptyList()));
        assertTrue(thrown.getMessage().contains("nylium"));
    }

    @Test
    void rejectsAModuleCarryingItsOwnManifest() throws IOException {
        Path jar = jarContaining("nested.jar", "nylium-modules.properties");
        assertThrows(RuntimeException.class, () -> ModuleJarInspector.verify("1.21.11",
                jar.toFile(), Collections.<String>emptyList()));
    }
}
```

Note this test targets a plain `ModuleJarInspector` helper, not the task, so the rules are testable without a Gradle build. Create `ModuleJarInspector.java` alongside the task; the task is a thin wrapper over it.

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew :nylium-gradle:test --tests '*VerifyModulesTest*' --offline`
Expected: FAIL, `ModuleJarInspector` does not exist.

- [ ] **Step 3: Write `ModuleJarInspector`**

```java
package io.github.intisy.nylium.gradle;

import org.gradle.api.InvalidUserDataException;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class ModuleJarInspector {

    private static final String API_PREFIX = "io/github/intisy/nylium/api/";

    private ModuleJarInspector() {
    }

    static void verify(String moduleName, File jar, List<String> declaredMixins) {
        Set<String> entries = entries(jar);
        for (String mixin : declaredMixins) {
            if (!entries.contains(mixin)) {
                throw new InvalidUserDataException("Nylium module '" + moduleName + "' declares the"
                        + " mixin config '" + mixin + "', but " + jar.getName()
                        + " does not contain it. The config has to be inside the module jar, since"
                        + " that is where the platform looks for it.");
            }
        }
        if (entries.contains("nylium-modules.properties")) {
            throw new InvalidUserDataException("Nylium module '" + moduleName + "' contains its own"
                    + " nylium-modules.properties. Only the universal jar carries a manifest.");
        }
        for (String entry : entries) {
            if (entry.startsWith(API_PREFIX)) {
                throw new InvalidUserDataException("Nylium module '" + moduleName + "' bundles the"
                        + " nylium api (" + entry + "). The universal jar already provides it, so"
                        + " the module must not shade it.");
            }
        }
    }

    private static Set<String> entries(File jar) {
        Set<String> names = new LinkedHashSet<String>();
        try {
            JarFile file = new JarFile(jar);
            try {
                Enumeration<JarEntry> enumeration = file.entries();
                while (enumeration.hasMoreElements()) {
                    names.add(enumeration.nextElement().getName());
                }
            } finally {
                file.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the module jar " + jar, e);
        }
        return names;
    }
}
```

- [ ] **Step 4: Write the task and wire it**

```java
package io.github.intisy.nylium.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
```

Give the task a `@Nested ListProperty<ModuleToVerify>`, where `ModuleToVerify` is a managed nested type holding `@Input` module name, `@Input` mixins and `@InputFile` jar **together**, plus an `@OutputFile` stamp file so the task can be up to date. In the action, loop the nested values, call `ModuleJarInspector.verify` for each, then write the stamp. Register it in `afterEvaluate` and add `jar.dependsOn(verify)` to `nyliumUniversalJar`.

**Order tracking must be impossible to bypass.** `NamedDomainObjectContainer` iterates sorted by name, not by insertion, so the manifest index cannot come from iterating the container. Track declaration order with a list populated by `modules.whenObjectAdded(spec -> moduleOrder.add(spec.getName()))` in the extension's constructor, and do **not** also append inside `module(String, Action)` or names double up. A callback is required rather than appending in `module(...)`, because `getModules()` is public: a module created through the container directly would otherwise never reach the list and, since `resolve()` iterates the list, would vanish from the manifest entirely while both emptiness guards still saw it in the container. Add a cross-check in `resolve()` that throws when `moduleOrder.size() != modules.size()`, so the two sources of truth cannot diverge unnoticed, and a test that declares a module through `getModules().create(...)` and asserts it reaches the manifest.

Keeping the three fields in one value type is the point. A map of mixins keyed by name plus a separately populated file collection has no type-level guarantee the two stay aligned, so a later change to one population order would silently pair a jar with another module's mixin list, producing either a false pass or an error naming the wrong module.

`@InputFile` on the nested jar property is also what carries the implicit task dependency: a consumer writing `jar = tasks.named('remapJar').flatMap { it.archiveFile }` gets `remapJar` wired as a producer automatically, so verification cannot run before the jar is built. Add a functional test that proves this rather than assuming it: declare a producer task in the fixture, wire a module's `jar` from its output, invoke `nyliumVerifyModules` **without** naming the producer, and assert the producer actually executed. Also add a test that a jar containing `io/github/intisy/nylium/apiextra/Foo.class` is accepted, since the trailing slash in `API_PREFIX` is what makes that correct and is exactly the character a later simplification removes.

Pass `moduleName` into the entry-reading helper so an unreadable jar's error names the module too, matching the other three messages, and wrap the stamp write's `IOException` as `UncheckedIOException` the way `NyliumTextFileTask` does.

Every fixture that declares a module jar must write a **real** jar, via a small `emptyJar` helper. A text file named `module.jar` works only until something opens it, and then fails with an `UncheckedIOException` pointing at a fixture nobody suspects.

- [ ] **Step 5: Run**

Run: `./gradlew :nylium-gradle:check --offline`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add nylium-gradle
git commit -m "feat(gradle): reject module jars that cannot work at runtime"
```

---

### Task 8: The differential acceptance gate

**Files:**
- Modify: `nylium-gradle/build.gradle`
- Modify: `nylium-testmod/build.gradle` (additive only: expose paths, change nothing existing)
- Create: `DifferentialTest.java`

**Interfaces:**
- Consumes: everything above, plus the hand-rolled `universalJar` output at `nylium-testmod/build/universal/nylium-testmod-universal.jar` and the module jars in `nylium-testmod/build/modules/`.
- Produces: a green differential proving the plugin reproduces the proven artifact.

- [ ] **Step 1: Publish the Nylium artifacts to a test repository**

In `nylium-gradle/build.gradle`, add a file repository publication target and make the test depend on publishing the six embedded artifacts into it:

```groovy
def testRepository = layout.buildDirectory.dir('test-repo')

subprojectsToPublish = [':nylium-api', ':nylium-core', ':nylium-bootstrap-fabric',
                        ':nylium-bootstrap-launchwrapper', ':nylium-bootstrap-modlauncher8',
                        ':nylium-bootstrap-modlauncher9']
```

Put the repository in the root build's `subprojects` block, but **gate the publication itself to those six projects**. The root build applies `java-library` to all nine subprojects, so `components.java` resolves everywhere: an ungated publication would also publish the `nylium-testmod` fixture jar and the `smoke` harness under the real group, and would give `nylium-gradle` a second publication at the same coordinates as `java-gradle-plugin`'s own `pluginMaven`, which Gradle reports as publications overwriting each other.

Declare the list once as `ext.embeddedArtifactPaths` in the root build and have `nylium-gradle/build.gradle` read `rootProject.ext.embeddedArtifactPaths`. Two copies of the same six paths in two build scripts can drift silently, and nothing would catch it.

Note that no publication exists anywhere in this repo by default: `maven-publish` is applied but declares nothing, so the publish task is a no-op until an explicit `maven(MavenPublication) { from components.java }` is added.

In the root `build.gradle`, inside the existing `subprojects` block, add:

```groovy
    publishing {
        repositories {
            maven {
                name = 'nyliumTest'
                url = rootProject.layout.buildDirectory.dir('test-repo')
            }
        }
    }
```

- [ ] **Step 2: Wire the test inputs**

In `nylium-gradle/build.gradle`:

```groovy
tasks.named('test') {
    dependsOn ':nylium-testmod:universalJar'
    dependsOn ':nylium-api:publishAllPublicationsToNyliumTestRepository'
    dependsOn ':nylium-core:publishAllPublicationsToNyliumTestRepository'
    dependsOn ':nylium-bootstrap-fabric:publishAllPublicationsToNyliumTestRepository'
    dependsOn ':nylium-bootstrap-launchwrapper:publishAllPublicationsToNyliumTestRepository'
    dependsOn ':nylium-bootstrap-modlauncher8:publishAllPublicationsToNyliumTestRepository'
    dependsOn ':nylium-bootstrap-modlauncher9:publishAllPublicationsToNyliumTestRepository'

    systemProperty 'nylium.test.repo',
            rootProject.layout.buildDirectory.dir('test-repo').get().asFile.absolutePath
    systemProperty 'nylium.test.version', project.version
    systemProperty 'nylium.test.modules',
            project(':nylium-testmod').layout.buildDirectory.dir('modules').get().asFile.absolutePath
    systemProperty 'nylium.test.reference',
            project(':nylium-testmod').layout.buildDirectory
                    .file('universal/nylium-testmod-universal.jar').get().asFile.absolutePath
}
```

Verify the exact publish task name with `./gradlew :nylium-api:tasks --all --offline` before relying on it; Gradle derives it from the repository name and it must match.

- [ ] **Step 3: Write the differential test**

`DifferentialTest.java` builds a fixture that declares the same five modules the test mod does, in manifest order, with `modulePrefix = 'testmod'`, pointing each `jar` at the corresponding file in `nylium.test.modules`, and the same mod identity (`id = 'nylium_testmod'`, `name = 'Nylium Test Mod'`, `version = '0.1.0'`, `environment = '*'`, `fabricLoaderVersion = '>=0.14.0'`). It then compares the produced jar with the reference:

1. **Entry sets are equal.** Compare the two sorted entry-name sets and assert equality, reporting the symmetric difference on failure.
2. **Every module jar is byte identical**, compared by reading both entries fully.
3. **`nylium-modules.properties` is equal** after normalizing line endings and trailing whitespace.
4. **`fabric.mod.json` is semantically equal**, parsed with gson and compared as `JsonObject`.
5. **Both service files are equal** after the same normalization as (3).

Assert each as its own test method so a failure names which invariant broke.

- [ ] **Step 4: Run the differential**

Run: `./gradlew :nylium-gradle:test --tests '*DifferentialTest*' --offline`
Expected: PASS. A failure here is real: it means the plugin does not reproduce an artifact already proven on five Minecraft servers. Do not weaken an assertion to make it pass. If entry sets differ only by embedded jar metadata, extend the exclude list in Task 6 rather than relaxing the comparison.

- [ ] **Step 5: Full build**

Run: `./gradlew build --offline`
Expected: PASS with `:smoke:test SKIPPED` and no Minecraft server downloads in the task graph.

- [ ] **Step 6: Commit**

```bash
git add nylium-gradle nylium-testmod/build.gradle build.gradle
git commit -m "test(gradle): prove the plugin reproduces the proven universal jar"
```

---

### Task 9: Confirm on real servers and document

**Files:**
- Modify: `smoke/build.gradle`
- Modify: `CONTENT.md`

**Interfaces:**
- Produces: an opt-in path to point the smoke matrix at the plugin-built jar, and the plugin's documented usage including the three inherited limitations.

- [ ] **Step 1: Let the smoke harness accept an explicit jar**

Add an optional `-PnyliumSmokeJar=<path>` to `smoke/build.gradle` that overrides the universal jar the harness installs, defaulting to the current hand-rolled artifact so the existing behaviour is unchanged. Keep the whole smoke matrix behind `-PnyliumSmoke`, and keep provisioning gated on the dependency edge, not only `onlyIf`.

- [ ] **Step 2: Run the matrix against the plugin-built jar**

Have the differential test copy its produced jar to `nylium-gradle/build/universal/` under a stable name, then run:

```bash
./gradlew :smoke:test -PnyliumSmoke -PnyliumSmokeJar=<absolute path to the plugin built jar>
```

Expected: the same five markers SP-1 recorded. Delete the marker files first so a stale marker cannot pass for a fresh one. Record the observed markers in the task report.

- [ ] **Step 3: Document the plugin**

Add a `CONTENT.md` section covering: applying the plugin, the full `nylium { }` surface with every field, the derived module path, and what is generated per platform. State the three inherited limitations at the point where a consumer would declare `MODLAUNCHER_8`, `MODLAUNCHER_9` or an `environment`. Do not touch `README.md`; it is generated on the default branch.

- [ ] **Step 4: Commit**

```bash
git add smoke/build.gradle CONTENT.md
git commit -m "docs(gradle): document the packaging plugin and its limits"
```

---

## Self-review notes

Checked before execution:

- **Spec coverage.** Every spec section maps to a task: DSL to Task 2, selective embedding to Task 6, generated artifacts to Tasks 3, 4 and 5, the six validation rules to Tasks 2 and 7, the three test layers to Tasks 2 through 8, migration to the standing constraint that nothing is deleted.
- **Type consistency.** `ResolvedModule` accessors are used with the same names in Tasks 3, 6 and 7. `ModSpec` getters used by `FabricMetadataRenderer` all exist in the Task 2 definition. `ServiceRenderer` returns null rather than an empty string for absent platforms, and every caller checks for null.
- **Known soft spots, flagged rather than hidden.** Three things are asserted from documentation rather than measured, and the implementer must verify each before building on it: the exact `publishAllPublicationsToNyliumTestRepository` task name (Step 2 of Task 8 says to check), whether `options.release = 8` compiles cleanly against the Gradle API on this Gradle version (Task 1 finds out immediately), and whether `withPluginClasspath()` needs anything beyond `java-gradle-plugin` (Task 5). None is load bearing for the design; each is a build detail with an obvious fallback.
