# Rutter Gradle Packaging Plugin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A published Gradle plugin (`io.github.intisy.rutter`) that assembles a Rutter universal jar from a declaration, replacing the hand-rolled packaging in `rutter-testmod/build.gradle`.

**Architecture:** A new `rutter-gradle` subproject built with `java-gradle-plugin`. A `rutter { }` extension collects a `mod { }` block and a named container of `module { }` blocks; validation reuses the kernel's own `VersionRange.parse` and `PlatformId.valueOf` rather than reimplementing them. Every generated file is written by a task with a real `@Input` and `@OutputFile`, which is the structural fix for SP-1's untracked-input staleness defect. Which Rutter pieces get embedded is derived from the union of declared platforms.

**Tech Stack:** Gradle 7.6+ (Groovy DSL in builds, Java in the plugin), `java-gradle-plugin`, Gradle TestKit, JUnit 5, gson (test scope only).

**Spec:** `docs/superpowers/specs/2026-09-06-rutter-gradle-plugin-design.md`

## Global Constraints

- **Java 8 bytecode.** The root build sets `options.release = 8` for every subproject and wires `checkClassFileVersion` into `check`. No `var`, no `List.of`, no records, no diamond-with-anonymous-class.
- **No `net.minecraft` types anywhere in this subproject.** It never sees Minecraft.
- **Do not reimplement version or platform parsing.** Call `VersionRange.parse` and `PlatformId.valueOf`. `VersionRange.parse` throws a subclass of `io.github.intisy.rutter.api.RutterException`, so catch `RutterException`.
- **Manifest wire format, exactly as `ModuleManifest` reads it:** keys are `module.<index>.<field>`; `platforms` and `mixins` are comma separated and trimmed; `platforms` and `environment` are matched with a case-sensitive `valueOf`, so emit `PlatformId.name()` and `Environment.name()`; absent `priority` means 0. Required fields are `path`, `platforms`, `minecraft`.
- **Comments: default to zero.** Only a genuinely non-obvious *why*, on the declaration it explains, as a doc comment with `@implNote` for internal rationale. A 3-or-more-line inline block is a smell: extract a named method instead.
- **NEVER use en-dashes or em-dashes** anywhere: code, comments, commit messages, docs, output.
- **Conventional Commits:** `type(scope): summary`, imperative, lowercase summary, no trailing period, no task or plan archaeology in the message. Commit with the repository's configured identity; never pass `-c user.email`, `-c user.name`, or `--author`.
- **Never delete existing code without asking.** In particular the hand-rolled `universalJar`, `moduleJars` and `moduleJar*` tasks in `rutter-testmod/build.gradle` stay in place for the whole plan; they are the differential's reference artifact. Removing them is the owner's decision, not a step here.
- **Offline default.** A plain `./gradlew build` must not download a Minecraft server or launch one. The smoke matrix stays behind `-PrutterSmoke`.
- **READMEs are generated.** Never hand-write `README.md`; documentation edits go to `CONTENT.md`.

## Reference values, verbatim

These strings are load bearing. Copy them, do not retype them from memory.

| Purpose | Value |
| --- | --- |
| Fabric preLaunch entrypoint | `io.github.intisy.rutter.bootstrap.fabric.RutterPreLaunch` |
| LaunchWrapper manifest attribute | `TweakClass` = `io.github.intisy.rutter.bootstrap.launchwrapper.RutterTweaker` |
| ML8 service impl | `io.github.intisy.rutter.bootstrap.ml8.RutterMl8Service` |
| ML9 service impl | `io.github.intisy.rutter.bootstrap.ml9.RutterMl9TransformationService` |
| ML9 launch plugin impl | `io.github.intisy.rutter.bootstrap.ml9.RutterMl9LaunchPlugin` |
| ITransformationService file | `META-INF/services/cpw.mods.modlauncher.api.ITransformationService` |
| ILaunchPluginService file | `META-INF/services/cpw.mods.modlauncher.serviceapi.ILaunchPluginService` |
| Manifest resource name | `rutter-modules.properties` (`ModuleManifest.RESOURCE`) |

Bootstrap subproject per platform: `FABRIC` to `rutter-bootstrap-fabric`, `LAUNCHWRAPPER` to `rutter-bootstrap-launchwrapper`, `MODLAUNCHER_8` to `rutter-bootstrap-modlauncher8`, `MODLAUNCHER_9` to `rutter-bootstrap-modlauncher9`. `NEOFORGE` has no bootstrap and must be rejected.

## File Structure

```
settings.gradle                                        Modify: include 'rutter-gradle'
rutter-gradle/build.gradle                             Create
rutter-gradle/src/main/java/io/github/intisy/rutter/gradle/
    RutterPlugin.java                                  Create  plugin entry point
    RutterExtension.java                               Create  rutter { }, validation entry
    ModSpec.java                                       Create  mod { }
    ModuleSpec.java                                    Create  module('name') { }
    ResolvedModule.java                                Create  validated, immutable
    Platforms.java                                     Create  name parsing, NEOFORGE rejection
    ManifestRenderer.java                              Create  modules -> properties text
    FabricMetadataRenderer.java                        Create  mod -> fabric.mod.json text
    ServiceRenderer.java                               Create  platforms -> service file bodies
    Json.java                                          Create  minimal string escaping
    RutterTextFileTask.java                            Create  @Input content, @OutputFile
    RutterVerifyModulesTask.java                       Create  module jar inspection
rutter-gradle/src/test/java/io/github/intisy/rutter/gradle/
    RutterPluginApplyTest.java                         Create
    ValidationTest.java                                Create
    ManifestRendererTest.java                          Create
    FabricMetadataRendererTest.java                    Create
    ServiceRendererTest.java                           Create
    UpToDateFunctionalTest.java                        Create  TestKit
    VerifyModulesTest.java                             Create
    DifferentialTest.java                              Create  TestKit, acceptance gate
rutter-gradle/src/test/resources/
    expected-testmod-manifest.properties               Create  copied from the proven literal
rutter-testmod/build.gradle                            Modify: expose module jar paths to tests only
smoke/build.gradle                                     Modify: allow an explicit jar path (Task 9)
CONTENT.md                                             Modify: plugin usage plus inherited limits
```

`buildSrc` already uses the package `io.github.intisy.rutter.gradle` for `ApiPurityTask` and `ClassFileVersionTask`. Class names here do not collide with those two, and buildSrc is never published, so sharing the package name is safe. Do not move or rename the buildSrc classes.

---

### Task 1: Subproject skeleton and plugin registration

**Files:**
- Modify: `settings.gradle`
- Create: `rutter-gradle/build.gradle`
- Create: `rutter-gradle/src/main/java/io/github/intisy/rutter/gradle/RutterPlugin.java`
- Create: `rutter-gradle/src/main/java/io/github/intisy/rutter/gradle/RutterExtension.java`
- Test: `rutter-gradle/src/test/java/io/github/intisy/rutter/gradle/RutterPluginApplyTest.java`

**Interfaces:**
- Produces: plugin id `io.github.intisy.rutter`; extension named `rutter` of type `RutterExtension`; a `rutter-gradle-version.properties` resource on the plugin's own classpath carrying `version=<project.version>`.

- [ ] **Step 1: Add the subproject**

In `settings.gradle`, after `include 'rutter-bootstrap-modlauncher9'`:

```groovy
include 'rutter-gradle'
```

- [ ] **Step 2: Write the build script**

Create `rutter-gradle/build.gradle`:

```groovy
plugins {
    id 'java-gradle-plugin'
}

dependencies {
    implementation project(':rutter-api')
    implementation project(':rutter-core')

    testImplementation gradleTestKit()
    testImplementation 'com.google.code.gson:gson:2.10.1'
}

gradlePlugin {
    plugins {
        rutter {
            id = 'io.github.intisy.rutter'
            implementationClass = 'io.github.intisy.rutter.gradle.RutterPlugin'
        }
    }
}

def versionFile = layout.buildDirectory.file('generated/rutter-version/rutter-gradle-version.properties')
def rutterVersion = providers.provider { project.version.toString() }

/**
 * @implNote The plugin resolves the embedded runtime pieces at its own version, so the version has
 *     to reach it at execution time; a generated resource is the only channel that survives being
 *     applied from a published jar.
 */
def writeRutterVersion = tasks.register('writeRutterVersion') {
    inputs.property('version', rutterVersion)
    outputs.file(versionFile)
    doLast {
        def file = versionFile.get().asFile
        file.parentFile.mkdirs()
        file.setText("version=${rutterVersion.get()}\n", 'UTF-8')
    }
}
```

The action reads the captured provider and never touches `project`. Reaching `Task.project` at execution time is deprecated, is documented to fail in Gradle 10, and is incompatible with the configuration cache, which matters for a plugin other builds apply. The charset is pinned for the same reason `ModuleManifest` pins it: an unpinned default is a known trap in this repo.

```groovy

tasks.named('processResources') {
    from(writeRutterVersion)
}
```

The root build already applies `java-library` and `maven-publish` to every subproject, sets `options.release = 8`, and registers `checkClassFileVersion`. Do not repeat any of that here.

- [ ] **Step 3: Write the failing test**

Create `rutter-gradle/src/test/java/io/github/intisy/rutter/gradle/RutterPluginApplyTest.java`:

```java
package io.github.intisy.rutter.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class RutterPluginApplyTest {

    @Test
    void registersTheExtension() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.rutter");
        assertNotNull(project.getExtensions().findByName("rutter"));
    }
}
```

- [ ] **Step 4: Run it and watch it fail**

Run: `./gradlew :rutter-gradle:test --offline`
Expected: FAIL, plugin id not found or `RutterPlugin` missing.

- [ ] **Step 5: Write the plugin and a minimal extension**

Create `RutterPlugin.java`:

```java
package io.github.intisy.rutter.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

public class RutterPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getExtensions().create("rutter", RutterExtension.class, project);
    }
}
```

Create `RutterExtension.java` with only what this task needs; Task 2 fills it in:

```java
package io.github.intisy.rutter.gradle;

import org.gradle.api.Project;

public class RutterExtension {

    private final Project project;

    public RutterExtension(Project project) {
        this.project = project;
    }

    Project project() {
        return project;
    }
}
```

- [ ] **Step 6: Run the test and the class-file check**

Run: `./gradlew :rutter-gradle:check --offline`
Expected: PASS, including `checkClassFileVersion`.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle rutter-gradle
git commit -m "feat(gradle): add the rutter gradle plugin subproject"
```

---

### Task 2: The DSL and configuration-time validation

**Files:**
- Create: `ModSpec.java`, `ModuleSpec.java`, `ResolvedModule.java`, `Platforms.java`
- Modify: `RutterExtension.java`
- Test: `ValidationTest.java`

**Interfaces:**
- Consumes: plugin and extension from Task 1.
- Produces: `RutterExtension.mod(Action<ModSpec>)`, `RutterExtension.module(String, Action<ModuleSpec>)`, `RutterExtension.getModules()` returning `NamedDomainObjectContainer<ModuleSpec>`, and `List<ResolvedModule> RutterExtension.resolve()` which validates and throws `InvalidUserDataException` on any violation. `ResolvedModule` exposes `name()`, `path()`, `platforms()`, `minecraft()`, `environment()` (nullable), `mixins()`, `priority()`, `entrypoint()` (nullable), `jar()` returning `Provider<RegularFile>`.

- [ ] **Step 1: Write the failing tests**

Create `ValidationTest.java`. One test per rejection, each asserting the message names the offending module:

```java
package io.github.intisy.rutter.gradle;

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

    private RutterExtension extensionWith(String platform, String minecraft) {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.rutter");
        RutterExtension rutter = (RutterExtension) project.getExtensions().getByName("rutter");
        rutter.mod(mod -> {
            mod.getId().set("demo");
            mod.getName().set("Demo");
            mod.getVersion().set("1.0.0");
        });
        rutter.module("1.21.11", module -> {
            module.getJar().set(new File(project.getProjectDir(), "module.jar"));
            module.getPlatforms().set(Collections.singletonList(platform));
            module.getMinecraft().set(minecraft);
        });
        return rutter;
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
        project.getPlugins().apply("io.github.intisy.rutter");
        RutterExtension rutter = (RutterExtension) project.getExtensions().getByName("rutter");
        rutter.mod(mod -> mod.getId().set("demo"));
        rutter.module("1.21.11", module -> {
            module.getJar().set(new File(project.getProjectDir(), "module.jar"));
            module.getPlatforms().set(Arrays.<String>asList());
            module.getMinecraft().set("1.21.11");
        });
        assertThrows(InvalidUserDataException.class, rutter::resolve);
    }

    @Test
    void rejectsAnEmptyModuleSet() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.rutter");
        RutterExtension rutter = (RutterExtension) project.getExtensions().getByName("rutter");
        rutter.mod(mod -> mod.getId().set("demo"));
        assertThrows(InvalidUserDataException.class, rutter::resolve);
    }

    @Test
    void rejectsAMissingModId() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.rutter");
        RutterExtension rutter = (RutterExtension) project.getExtensions().getByName("rutter");
        rutter.module("1.21.11", module -> {
            module.getJar().set(new File(project.getProjectDir(), "module.jar"));
            module.getPlatforms().set(Collections.singletonList("FABRIC"));
            module.getMinecraft().set("1.21.11");
        });
        assertThrows(InvalidUserDataException.class, rutter::resolve);
    }

    @Test
    void rejectsAnUnknownEnvironment() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.rutter");
        RutterExtension rutter = (RutterExtension) project.getExtensions().getByName("rutter");
        rutter.mod(mod -> mod.getId().set("demo"));
        rutter.module("1.21.11", module -> {
            module.getJar().set(new File(project.getProjectDir(), "module.jar"));
            module.getPlatforms().set(Collections.singletonList("FABRIC"));
            module.getMinecraft().set("1.21.11");
            module.getEnvironment().set("BOTH");
        });
        assertThrows(InvalidUserDataException.class, rutter::resolve);
    }

    @Test
    void rejectsAModuleWithoutAJar() {
        Project project = ProjectBuilder.builder().build();
        project.getPlugins().apply("io.github.intisy.rutter");
        RutterExtension rutter = (RutterExtension) project.getExtensions().getByName("rutter");
        rutter.mod(mod -> mod.getId().set("demo"));
        rutter.module("1.21.11", module -> {
            module.getPlatforms().set(Collections.singletonList("FABRIC"));
            module.getMinecraft().set("1.21.11");
        });
        assertThrows(InvalidUserDataException.class, rutter::resolve);
    }
}
```

Add one more test, `rejectsADuplicateModuleName`, declaring `module('1.21.11')` twice and asserting it throws. The container rejects the duplicate only because `module(...)` calls `create`; `maybeCreate` would merge both blocks into one spec silently. Do not add a second uniqueness check inside `resolve()`, and do not assume the rejection without asserting it.

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew :rutter-gradle:test --offline`
Expected: FAIL to compile, `mod`, `module` and `resolve` do not exist.

- [ ] **Step 3: Write `ModSpec`**

```java
package io.github.intisy.rutter.gradle;

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
     * any version and Rutter, not the loader, reports an unsupported one.
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
package io.github.intisy.rutter.gradle;

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
package io.github.intisy.rutter.gradle;

import io.github.intisy.rutter.api.PlatformId;
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
            throw new InvalidUserDataException("Rutter module '" + moduleName
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
            throw new InvalidUserDataException("Rutter module '" + moduleName
                    + "' declares an unknown platform '" + declared + "'. Known platforms are "
                    + Arrays.toString(PlatformId.values()) + ".", e);
        }
        if (platform == PlatformId.NEOFORGE) {
            throw new InvalidUserDataException("Rutter module '" + moduleName
                    + "' declares NEOFORGE, which has no Rutter bootstrap yet (SP-1b). A jar built"
                    + " with it would never dispatch on NeoForge, so it is rejected rather than"
                    + " shipped.");
        }
        return platform;
    }
}
```

- [ ] **Step 6: Write `ResolvedModule`**

```java
package io.github.intisy.rutter.gradle;

import io.github.intisy.rutter.api.Environment;
import io.github.intisy.rutter.api.PlatformId;
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

- [ ] **Step 7: Fill in `RutterExtension`**

```java
package io.github.intisy.rutter.gradle;

import io.github.intisy.rutter.api.Environment;
import io.github.intisy.rutter.api.PlatformId;
import io.github.intisy.rutter.api.RutterException;
import io.github.intisy.rutter.core.VersionRange;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class RutterExtension {

    private final Project project;
    private final ModSpec mod;
    private final NamedDomainObjectContainer<ModuleSpec> modules;

    public RutterExtension(Project project) {
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
                    "The Rutter mod id is not set. Set it in the rutter { mod { id = ... } } block.");
        }
        return id.trim();
    }

    List<ResolvedModule> resolve() {
        String prefix = modulePrefix();
        if (modules.isEmpty()) {
            throw new InvalidUserDataException(
                    "Rutter declares no modules. Add at least one rutter { module('...') { } } block.");
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
            throw new InvalidUserDataException("Rutter module '" + name
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
     *     {@code RutterException}.
     */
    private void validateRange(String moduleName, String raw) {
        try {
            VersionRange.parse(raw);
        } catch (RutterException e) {
            throw new InvalidUserDataException("Rutter module '" + moduleName
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
            throw new InvalidUserDataException("Rutter module '" + moduleName
                    + "' declares an unknown environment '" + raw + "'. Known environments are "
                    + Arrays.toString(Environment.values()) + ".", e);
        }
    }

    private String required(String value, String moduleName, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new InvalidUserDataException(
                    "Rutter module '" + moduleName + "' is missing '" + field + "'.");
        }
        return value.trim();
    }

    private String emptyToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
```

- [ ] **Step 8: Run the tests**

Run: `./gradlew :rutter-gradle:check --offline`
Expected: PASS, all validation tests green.

- [ ] **Step 9: Commit**

```bash
git add rutter-gradle
git commit -m "feat(gradle): validate the rutter module declaration"
```

---

### Task 3: Render the module manifest

**Files:**
- Create: `ManifestRenderer.java`
- Create: `rutter-gradle/src/test/resources/expected-testmod-manifest.properties`
- Test: `ManifestRendererTest.java`

**Interfaces:**
- Consumes: `ResolvedModule` from Task 2.
- Produces: `static String ManifestRenderer.render(List<ResolvedModule>)`, emitting `key=value` lines separated by `\n` with a trailing `\n`.

- [ ] **Step 1: Capture the proven manifest as the expected fixture**

Copy the `rutter-modules.properties` literal out of `rutter-testmod/build.gradle` verbatim into `rutter-gradle/src/test/resources/expected-testmod-manifest.properties`, and add one trailing newline. That literal is the artifact proven on five Minecraft servers, so it, not a fresh expectation, is what the renderer is measured against. Do not edit the block in `rutter-testmod/build.gradle`.

- [ ] **Step 2: Write the failing test**

Create `ManifestRendererTest.java`:

```java
package io.github.intisy.rutter.gradle;

import io.github.intisy.rutter.api.PlatformId;
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
                null, mixins, 0, "io.github.intisy.rutter.testmod.TestModEntry", null);
    }

    @Test
    void reproducesTheProvenTestModManifest() throws IOException {
        List<ResolvedModule> modules = new ArrayList<ResolvedModule>();
        modules.add(module("1.21.11", PlatformId.FABRIC, "1.21.11", Collections.<String>emptyList()));
        modules.add(module("1.21.10", PlatformId.FABRIC, "1.21.10", Collections.<String>emptyList()));
        modules.add(module("1.7.10", PlatformId.LAUNCHWRAPPER, "[1.7,1.12.2]", Collections.<String>emptyList()));
        modules.add(module("1.16.5", PlatformId.MODLAUNCHER_8, "[1.13,1.16.5]", Collections.<String>emptyList()));
        modules.add(module("1.21.11-ml9", PlatformId.MODLAUNCHER_9, "1.21.11",
                Collections.singletonList("mixins.rutter-testmod-ml9.json")));

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

Run: `./gradlew :rutter-gradle:test --tests '*ManifestRendererTest*' --offline`
Expected: FAIL, `ManifestRenderer` does not exist.

- [ ] **Step 4: Write the renderer**

```java
package io.github.intisy.rutter.gradle;

import io.github.intisy.rutter.api.PlatformId;

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

Run: `./gradlew :rutter-gradle:test --tests '*ManifestRendererTest*' --offline`
Expected: PASS.

- [ ] **Step 6: Prove the output is readable by the kernel**

Add to `ManifestRendererTest`:

```java
    @Test
    void rendersWhatTheKernelCanRead() {
        List<ResolvedModule> modules = new ArrayList<ResolvedModule>();
        modules.add(module("1.21.11", PlatformId.FABRIC, "1.21.11", Collections.<String>emptyList()));
        io.github.intisy.rutter.core.ModuleManifest manifest =
                io.github.intisy.rutter.core.ModuleManifest.read(
                        new java.io.StringReader(ManifestRenderer.render(modules)));
        assertEquals(1, manifest.modules().size());
        assertEquals("modules/testmod-1.21.11.jar", manifest.modules().get(0).path());
    }
```

This is the round trip that matters: the renderer's output is parsed by the same class the game uses, so a format drift fails here rather than at launch.

Add two more round-trip cases, because otherwise no test executes the `environment` or non-zero `priority` emission branches at all, and those are exactly the values the reader rejects at launch rather than at build time. One module with a non-null `Environment`, one with a non-zero `priority`, each asserting the value survives the round trip: `ModuleDescriptor.environment()` returns an `Optional<Environment>` and `priority()` returns an `int`.

Run: `./gradlew :rutter-gradle:test --tests '*ManifestRendererTest*' --offline`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add rutter-gradle
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
package io.github.intisy.rutter.gradle;

import io.github.intisy.rutter.api.PlatformId;
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
        assertEquals("io.github.intisy.rutter.bootstrap.ml8.RutterMl8Service\n"
                        + "io.github.intisy.rutter.bootstrap.ml9.RutterMl9TransformationService\n",
                ServiceRenderer.transformationServices(
                        set(PlatformId.MODLAUNCHER_8, PlatformId.MODLAUNCHER_9)));
    }

    @Test
    void listsOnlyTheDeclaredModLauncherService() {
        assertEquals("io.github.intisy.rutter.bootstrap.ml9.RutterMl9TransformationService\n",
                ServiceRenderer.transformationServices(set(PlatformId.MODLAUNCHER_9)));
    }

    @Test
    void emitsNoServiceFileWithoutModLauncher() {
        assertNull(ServiceRenderer.transformationServices(set(PlatformId.FABRIC)));
        assertNull(ServiceRenderer.launchPlugins(set(PlatformId.FABRIC)));
    }

    @Test
    void emitsTheLaunchPluginOnlyForModLauncher9() {
        assertEquals("io.github.intisy.rutter.bootstrap.ml9.RutterMl9LaunchPlugin\n",
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
package io.github.intisy.rutter.gradle;

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
        mod.getId().set("rutter_testmod");
        mod.getName().set("Rutter Test Mod");
        mod.getVersion().set("0.1.0");
        mod.getEnvironment().set("*");
        mod.getFabricLoaderVersion().set(">=0.14.0");
        return mod;
    }

    private JsonObject render(ModSpec mod) {
        return JsonParser.parseString(FabricMetadataRenderer.render(mod)).getAsJsonObject();
    }

    @Test
    void declaresRuttersPreLaunchEntrypoint() {
        JsonArray preLaunch = render(spec()).getAsJsonObject("entrypoints").getAsJsonArray("preLaunch");
        assertEquals(1, preLaunch.size());
        assertEquals("io.github.intisy.rutter.bootstrap.fabric.RutterPreLaunch",
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
        assertEquals("rutter_testmod", json.get("id").getAsString());
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

Run: `./gradlew :rutter-gradle:test --tests '*Renderer*' --offline`
Expected: FAIL, classes do not exist.

- [ ] **Step 3: Write `Json`**

```java
package io.github.intisy.rutter.gradle;

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
package io.github.intisy.rutter.gradle;

import io.github.intisy.rutter.api.PlatformId;

import java.util.Set;

final class ServiceRenderer {

    static final String ML8_SERVICE = "io.github.intisy.rutter.bootstrap.ml8.RutterMl8Service";
    static final String ML9_SERVICE =
            "io.github.intisy.rutter.bootstrap.ml9.RutterMl9TransformationService";
    static final String ML9_LAUNCH_PLUGIN =
            "io.github.intisy.rutter.bootstrap.ml9.RutterMl9LaunchPlugin";

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
package io.github.intisy.rutter.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class FabricMetadataRenderer {

    static final String PRE_LAUNCH = "io.github.intisy.rutter.bootstrap.fabric.RutterPreLaunch";

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

Run: `./gradlew :rutter-gradle:check --offline`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add rutter-gradle
git commit -m "feat(gradle): render the fabric and modlauncher metadata"
```

---

### Task 5: Write the generated files through tracked tasks

**Files:**
- Create: `RutterTextFileTask.java`
- Modify: `RutterPlugin.java`
- Test: `UpToDateFunctionalTest.java`

**Interfaces:**
- Produces: task `rutterManifest` writing `rutter-modules.properties`; tasks `rutterFabricModJson`, `rutterTransformationServices`, `rutterLaunchPlugins` writing their respective files when their platform is declared; lifecycle task `rutterMetadata` depending on whichever of those exist. All are instances of `RutterTextFileTask` with `@Input` content and an `@OutputFile`.

- [ ] **Step 1: Write `RutterTextFileTask`**

```java
package io.github.intisy.rutter.gradle;

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
public abstract class RutterTextFileTask extends DefaultTask {

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

- [ ] **Step 2: Register the generators in `RutterPlugin`**

Replace `RutterPlugin.apply` with the following. Everything that needs the declaration is deferred to `afterEvaluate`, because the platform union is not known until the build script has run.

```java
package io.github.intisy.rutter.gradle;

import io.github.intisy.rutter.api.PlatformId;
import io.github.intisy.rutter.core.ModuleManifest;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class RutterPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        final RutterExtension rutter =
                project.getExtensions().create("rutter", RutterExtension.class, project);

        final TaskProvider<Task> metadata = project.getTasks().register("rutterMetadata", task ->
                task.setDescription("Generates the Rutter manifest and loader metadata."));

        project.afterEvaluate(evaluated -> {
            if (rutter.getModules().isEmpty()) {
                failWhenInvokedWithNoModules(metadata);
                return;
            }

            List<ResolvedModule> modules = rutter.resolve();
            Set<PlatformId> platforms = platformUnion(modules);

            List<TaskProvider<RutterTextFileTask>> writers =
                    new java.util.ArrayList<TaskProvider<RutterTextFileTask>>();
            writers.add(writer(evaluated, "rutterManifest", ModuleManifest.RESOURCE,
                    ManifestRenderer.render(modules)));
            if (platforms.contains(PlatformId.FABRIC)) {
                writers.add(writer(evaluated, "rutterFabricModJson", "fabric.mod.json",
                        FabricMetadataRenderer.render(rutter.getMod())));
            }
            String services = ServiceRenderer.transformationServices(platforms);
            if (services != null) {
                writers.add(writer(evaluated, "rutterTransformationServices",
                        "META-INF/services/cpw.mods.modlauncher.api.ITransformationService",
                        services));
            }
            String launchPlugins = ServiceRenderer.launchPlugins(platforms);
            if (launchPlugins != null) {
                writers.add(writer(evaluated, "rutterLaunchPlugins",
                        "META-INF/services/cpw.mods.modlauncher.serviceapi.ILaunchPluginService",
                        launchPlugins));
            }
            metadata.configure(task -> task.dependsOn(writers));
        });
    }

    /**
     * @implNote {@code afterEvaluate} runs for every task invocation, including plain introspection
     *     such as {@code tasks} or {@code help}, so an unconfigured project must not fail here;
     *     only invoking {@code rutterMetadata} itself should fail, with an actionable message.
     */
    private static void failWhenInvokedWithNoModules(TaskProvider<Task> metadata) {
        metadata.configure(task -> task.doFirst(ignored -> {
            throw new InvalidUserDataException("Rutter is applied but declares no modules. Add at"
                    + " least one rutter { module('...') { } } block.");
        }));
    }

    static Set<PlatformId> platformUnion(List<ResolvedModule> modules) {
        Set<PlatformId> platforms = new LinkedHashSet<PlatformId>();
        for (ResolvedModule module : modules) {
            platforms.addAll(module.platforms());
        }
        return platforms;
    }

    private static TaskProvider<RutterTextFileTask> writer(Project project, String taskName,
                                                           String relativePath, String content) {
        Provider<String> body = project.provider(() -> content);
        return project.getTasks().register(taskName, RutterTextFileTask.class, task -> {
            task.getContent().set(body);
            task.getDestination().set(
                    project.getLayout().getBuildDirectory().file("rutter/" + relativePath));
        });
    }
}
```

- [ ] **Step 3: Write the functional test**

Create `UpToDateFunctionalTest.java`. It builds a fixture project twice and asserts the second run is up to date, then changes the declaration and asserts a rebuild. This is the regression test for the staleness defect.

```java
package io.github.intisy.rutter.gradle;

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
                + "plugins { id 'io.github.intisy.rutter' }\n"
                + "rutter {\n"
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
                .withArguments("rutterMetadata")
                .build();
    }

    @Test
    void isUpToDateOnASecondRunAndRebuildsAfterAChange() throws IOException {
        fixture("1.21.11");
        assertEquals(TaskOutcome.SUCCESS, run().task(":rutterManifest").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, run().task(":rutterManifest").getOutcome());

        File manifest = projectDir.resolve("build/rutter/rutter-modules.properties").toFile();
        assertTrue(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8)
                .contains("1.21.11"));

        fixture("1.21.10");
        assertEquals(TaskOutcome.SUCCESS, run().task(":rutterManifest").getOutcome());
        assertTrue(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8)
                .contains("1.21.10"));
    }

    @Test
    void emitsNoModLauncherServiceFileForAFabricOnlyMod() throws IOException {
        fixture("1.21.11");
        run();
        assertTrue(Files.exists(projectDir.resolve("build/rutter/fabric.mod.json")));
        assertTrue(!Files.exists(projectDir.resolve(
                "build/rutter/META-INF/services/cpw.mods.modlauncher.api.ITransformationService")));
    }
}
```

Add three more cases. Two cover the empty-module guard, and both halves are needed: applying the plugin with no `rutter { }` block at all must leave a plain introspection command such as `tasks` succeeding, and invoking `rutterMetadata` on that same project must fail with the actionable message. Asserting only the first half would also pass if the plugin silently registered nothing. The third extends the up-to-date-then-rebuild assertion to `fabric.mod.json`, so the regression this task exists to prevent is proven for more than one of the four generated files, and asserts `ILaunchPluginService` is absent alongside `ITransformationService`, since the two share their gating.

- [ ] **Step 4: Run it**

Run: `./gradlew :rutter-gradle:check --offline`
Expected: PASS. If TestKit cannot resolve the plugin, confirm `java-gradle-plugin` is applied so `withPluginClasspath()` has generated metadata; do not add a manual classpath.

- [ ] **Step 5: Commit**

```bash
git add rutter-gradle
git commit -m "feat(gradle): generate the metadata through tracked tasks"
```

---

### Task 6: Embed the needed Rutter parts and assemble the jar

**Files:**
- Modify: `RutterPlugin.java`
- Test: extend `UpToDateFunctionalTest.java` or add `UniversalJarFunctionalTest.java`

**Interfaces:**
- Produces: resolvable configuration `rutterEmbed`; task `rutterUniversalJar` of type `Jar` whose archive contains the embedded Rutter classes, each module jar at its manifest `path`, the generated metadata, and the `TweakClass` attribute when LaunchWrapper is declared.

- [ ] **Step 1: Read the plugin's own version**

Add to `RutterPlugin`:

```java
    private static String pluginVersion() {
        java.io.InputStream stream = RutterPlugin.class.getResourceAsStream(
                "/rutter-gradle-version.properties");
        if (stream == null) {
            throw new IllegalStateException(
                    "rutter-gradle-version.properties is missing from the plugin jar");
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
            throw new IllegalStateException("rutter-gradle-version.properties declares no version");
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
        map.put(PlatformId.FABRIC, "rutter-bootstrap-fabric");
        map.put(PlatformId.LAUNCHWRAPPER, "rutter-bootstrap-launchwrapper");
        map.put(PlatformId.MODLAUNCHER_8, "rutter-bootstrap-modlauncher8");
        map.put(PlatformId.MODLAUNCHER_9, "rutter-bootstrap-modlauncher9");
        return map;
    }

    private static org.gradle.api.artifacts.Configuration embedConfiguration(
            Project project, Set<PlatformId> platforms) {
        org.gradle.api.artifacts.Configuration embed =
                project.getConfigurations().maybeCreate("rutterEmbed");
        embed.setCanBeConsumed(false);
        embed.setCanBeResolved(true);
        String version = pluginVersion();
        addEmbed(project, embed, "rutter-api", version);
        addEmbed(project, embed, "rutter-core", version);
        for (PlatformId platform : platforms) {
            String artifact = BOOTSTRAPS.get(platform);
            if (artifact == null) {
                throw new org.gradle.api.InvalidUserDataException(
                        "No Rutter bootstrap exists for platform " + platform + ".");
            }
            addEmbed(project, embed, artifact, version);
        }
        return embed;
    }

    private static void addEmbed(Project project,
                                 org.gradle.api.artifacts.Configuration embed,
                                 String artifact, String version) {
        embed.getDependencies().add(project.getDependencies()
                .create("io.github.intisy.rutter:" + artifact + ":" + version));
    }
```

- [ ] **Step 3: Register the jar task**

Append inside `afterEvaluate`, after the metadata wiring:

```java
            final org.gradle.api.artifacts.Configuration embed =
                    embedConfiguration(evaluated, platforms);
            final boolean launchWrapper = platforms.contains(PlatformId.LAUNCHWRAPPER);

            evaluated.getTasks().register("rutterUniversalJar",
                    org.gradle.jvm.tasks.Jar.class, jar -> {
                jar.setDescription("Assembles the Rutter universal jar.");
                jar.getArchiveClassifier().set("universal");
                jar.dependsOn(metadata);

                jar.from(evaluated.provider(() -> {
                    java.util.List<Object> trees = new java.util.ArrayList<Object>();
                    for (File artifact : embed.getFiles()) {
                        trees.add(evaluated.zipTree(artifact));
                    }
                    return trees;
                }), copy -> copy.exclude("META-INF/MANIFEST.MF", "META-INF/*.SF",
                        "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/maven/**",
                        "module-info.class"));

                jar.from(evaluated.getLayout().getBuildDirectory().dir("rutter"));

                for (ResolvedModule module : modules) {
                    final String path = module.path();
                    jar.from(module.jar(), copy -> {
                        copy.into(path.substring(0, path.lastIndexOf('/')));
                        copy.rename(".*", path.substring(path.lastIndexOf('/') + 1));
                    });
                }

                if (launchWrapper) {
                    jar.getManifest().getAttributes().put("TweakClass",
                            "io.github.intisy.rutter.bootstrap.launchwrapper.RutterTweaker");
                }
            });
```

The excludes are not cosmetic. Unpacking published jars would otherwise contribute each artifact's own `META-INF/MANIFEST.MF`, which both collides between artifacts and diverges from the reference jar the Task 8 differential compares against.

- [ ] **Step 4: Write the wiring test**

Assembling a real jar needs a repository holding the Rutter artifacts, which Task 8 sets up. What is testable now is the wiring, with `ProjectBuilder`: that the configuration and task exist, that the embed set follows the declared platforms, and that the LaunchWrapper attribute appears only when it should. Add `UniversalJarWiringTest.java`:

```java
package io.github.intisy.rutter.gradle;

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
        project.getPlugins().apply("io.github.intisy.rutter");
        RutterExtension rutter = (RutterExtension) project.getExtensions().getByName("rutter");
        rutter.mod(mod -> {
            mod.getId().set("demo");
            mod.getVersion().set("1.0.0");
        });
        rutter.module("1.21.11", module -> {
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
                : project.getConfigurations().getByName("rutterEmbed").getDependencies()) {
            names.add(dependency.getName());
        }
        return names;
    }

    @Test
    void embedsOnlyTheBootstrapForTheDeclaredPlatform() {
        Set<String> embedded = embeddedArtifacts(projectWith("FABRIC"));
        assertTrue(embedded.contains("rutter-api"));
        assertTrue(embedded.contains("rutter-core"));
        assertTrue(embedded.contains("rutter-bootstrap-fabric"));
        assertFalse(embedded.contains("rutter-bootstrap-modlauncher9"));
        assertFalse(embedded.contains("rutter-bootstrap-launchwrapper"));
    }

    @Test
    void addsTheTweakClassAttributeOnlyForLaunchWrapper() {
        Jar withLaunchWrapper = (Jar) projectWith("LAUNCHWRAPPER").getTasks()
                .getByName("rutterUniversalJar");
        assertEquals("io.github.intisy.rutter.bootstrap.launchwrapper.RutterTweaker",
                withLaunchWrapper.getManifest().getAttributes().get("TweakClass"));

        Jar fabricOnly = (Jar) projectWith("FABRIC").getTasks().getByName("rutterUniversalJar");
        assertFalse(fabricOnly.getManifest().getAttributes().containsKey("TweakClass"));
    }

    @Test
    void registersTheJarTask() {
        assertNotNull(projectWith("FABRIC").getTasks().findByName("rutterUniversalJar"));
    }
}
```

If `ProjectInternal.evaluate()` proves unusable, trigger `afterEvaluate` the way the surrounding tests do and record what you used; the assertions are what matter, not the trigger.

- [ ] **Step 5: Run**

Run: `./gradlew :rutter-gradle:check --offline`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add rutter-gradle
git commit -m "feat(gradle): assemble the universal jar from declared platforms"
```

---

### Task 7: Verify the module jars

**Files:**
- Create: `RutterVerifyModulesTask.java`
- Modify: `RutterPlugin.java`
- Test: `VerifyModulesTest.java`

**Interfaces:**
- Produces: task `rutterVerifyModules`, which `rutterUniversalJar` depends on. Fails when a declared mixin config is absent from its module jar, or when a module jar contains `rutter-modules.properties` or any `io/github/intisy/rutter/api/` entry.

- [ ] **Step 1: Write the failing tests**

`VerifyModulesTest.java` builds small jars in a temp directory with `java.util.zip.ZipOutputStream` and asserts each rejection, plus one passing case. Build one jar containing `mixins.demo.json`, one without it, and one containing `io/github/intisy/rutter/api/PlatformId.class`.

```java
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
```

Note this test targets a plain `ModuleJarInspector` helper, not the task, so the rules are testable without a Gradle build. Create `ModuleJarInspector.java` alongside the task; the task is a thin wrapper over it.

- [ ] **Step 2: Run and watch them fail**

Run: `./gradlew :rutter-gradle:test --tests '*VerifyModulesTest*' --offline`
Expected: FAIL, `ModuleJarInspector` does not exist.

- [ ] **Step 3: Write `ModuleJarInspector`**

```java
package io.github.intisy.rutter.gradle;

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

    private static final String API_PREFIX = "io/github/intisy/rutter/api/";

    private ModuleJarInspector() {
    }

    static void verify(String moduleName, File jar, List<String> declaredMixins) {
        Set<String> entries = entries(jar);
        for (String mixin : declaredMixins) {
            if (!entries.contains(mixin)) {
                throw new InvalidUserDataException("Rutter module '" + moduleName + "' declares the"
                        + " mixin config '" + mixin + "', but " + jar.getName()
                        + " does not contain it. The config has to be inside the module jar, since"
                        + " that is where the platform looks for it.");
            }
        }
        if (entries.contains("rutter-modules.properties")) {
            throw new InvalidUserDataException("Rutter module '" + moduleName + "' contains its own"
                    + " rutter-modules.properties. Only the universal jar carries a manifest.");
        }
        for (String entry : entries) {
            if (entry.startsWith(API_PREFIX)) {
                throw new InvalidUserDataException("Rutter module '" + moduleName + "' bundles the"
                        + " rutter api (" + entry + "). The universal jar already provides it, so"
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
package io.github.intisy.rutter.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
```

Give the task a `MapProperty<String, java.util.List<String>>` of module name to declared mixins, a `ConfigurableFileCollection` of the module jars keyed in the same order, and an `@OutputFile` stamp file so the task can be up to date. In the action, resolve each module jar and call `ModuleJarInspector.verify`, then write the stamp. Register it in `afterEvaluate` and add `jar.dependsOn(verify)` to `rutterUniversalJar`.

- [ ] **Step 5: Run**

Run: `./gradlew :rutter-gradle:check --offline`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add rutter-gradle
git commit -m "feat(gradle): reject module jars that cannot work at runtime"
```

---

### Task 8: The differential acceptance gate

**Files:**
- Modify: `rutter-gradle/build.gradle`
- Modify: `rutter-testmod/build.gradle` (additive only: expose paths, change nothing existing)
- Create: `DifferentialTest.java`

**Interfaces:**
- Consumes: everything above, plus the hand-rolled `universalJar` output at `rutter-testmod/build/universal/rutter-testmod-universal.jar` and the module jars in `rutter-testmod/build/modules/`.
- Produces: a green differential proving the plugin reproduces the proven artifact.

- [ ] **Step 1: Publish the Rutter artifacts to a test repository**

In `rutter-gradle/build.gradle`, add a file repository publication target and make the test depend on publishing the six embedded artifacts into it:

```groovy
def testRepository = layout.buildDirectory.dir('test-repo')

subprojectsToPublish = [':rutter-api', ':rutter-core', ':rutter-bootstrap-fabric',
                        ':rutter-bootstrap-launchwrapper', ':rutter-bootstrap-modlauncher8',
                        ':rutter-bootstrap-modlauncher9']
```

Add to each of those projects, from `rutter-gradle/build.gradle` using `project(path).afterEvaluate`, a `maven { name = 'rutterTest'; url = testRepository }` publishing repository, or add it once in the root build for all subprojects. Prefer the root build: it is one block, and every subproject already has `maven-publish` applied.

In the root `build.gradle`, inside the existing `subprojects` block, add:

```groovy
    publishing {
        repositories {
            maven {
                name = 'rutterTest'
                url = rootProject.layout.buildDirectory.dir('test-repo')
            }
        }
    }
```

- [ ] **Step 2: Wire the test inputs**

In `rutter-gradle/build.gradle`:

```groovy
tasks.named('test') {
    dependsOn ':rutter-testmod:universalJar'
    dependsOn ':rutter-api:publishAllPublicationsToRutterTestRepository'
    dependsOn ':rutter-core:publishAllPublicationsToRutterTestRepository'
    dependsOn ':rutter-bootstrap-fabric:publishAllPublicationsToRutterTestRepository'
    dependsOn ':rutter-bootstrap-launchwrapper:publishAllPublicationsToRutterTestRepository'
    dependsOn ':rutter-bootstrap-modlauncher8:publishAllPublicationsToRutterTestRepository'
    dependsOn ':rutter-bootstrap-modlauncher9:publishAllPublicationsToRutterTestRepository'

    systemProperty 'rutter.test.repo',
            rootProject.layout.buildDirectory.dir('test-repo').get().asFile.absolutePath
    systemProperty 'rutter.test.version', project.version
    systemProperty 'rutter.test.modules',
            project(':rutter-testmod').layout.buildDirectory.dir('modules').get().asFile.absolutePath
    systemProperty 'rutter.test.reference',
            project(':rutter-testmod').layout.buildDirectory
                    .file('universal/rutter-testmod-universal.jar').get().asFile.absolutePath
}
```

Verify the exact publish task name with `./gradlew :rutter-api:tasks --all --offline` before relying on it; Gradle derives it from the repository name and it must match.

- [ ] **Step 3: Write the differential test**

`DifferentialTest.java` builds a fixture that declares the same five modules the test mod does, in manifest order, with `modulePrefix = 'testmod'`, pointing each `jar` at the corresponding file in `rutter.test.modules`, and the same mod identity (`id = 'rutter_testmod'`, `name = 'Rutter Test Mod'`, `version = '0.1.0'`, `environment = '*'`, `fabricLoaderVersion = '>=0.14.0'`). It then compares the produced jar with the reference:

1. **Entry sets are equal.** Compare the two sorted entry-name sets and assert equality, reporting the symmetric difference on failure.
2. **Every module jar is byte identical**, compared by reading both entries fully.
3. **`rutter-modules.properties` is equal** after normalizing line endings and trailing whitespace.
4. **`fabric.mod.json` is semantically equal**, parsed with gson and compared as `JsonObject`.
5. **Both service files are equal** after the same normalization as (3).

Assert each as its own test method so a failure names which invariant broke.

- [ ] **Step 4: Run the differential**

Run: `./gradlew :rutter-gradle:test --tests '*DifferentialTest*' --offline`
Expected: PASS. A failure here is real: it means the plugin does not reproduce an artifact already proven on five Minecraft servers. Do not weaken an assertion to make it pass. If entry sets differ only by embedded jar metadata, extend the exclude list in Task 6 rather than relaxing the comparison.

- [ ] **Step 5: Full build**

Run: `./gradlew build --offline`
Expected: PASS with `:smoke:test SKIPPED` and no Minecraft server downloads in the task graph.

- [ ] **Step 6: Commit**

```bash
git add rutter-gradle rutter-testmod/build.gradle build.gradle
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

Add an optional `-PrutterSmokeJar=<path>` to `smoke/build.gradle` that overrides the universal jar the harness installs, defaulting to the current hand-rolled artifact so the existing behaviour is unchanged. Keep the whole smoke matrix behind `-PrutterSmoke`, and keep provisioning gated on the dependency edge, not only `onlyIf`.

- [ ] **Step 2: Run the matrix against the plugin-built jar**

Have the differential test copy its produced jar to `rutter-gradle/build/universal/` under a stable name, then run:

```bash
./gradlew :smoke:test -PrutterSmoke -PrutterSmokeJar=<absolute path to the plugin built jar>
```

Expected: the same five markers SP-1 recorded. Delete the marker files first so a stale marker cannot pass for a fresh one. Record the observed markers in the task report.

- [ ] **Step 3: Document the plugin**

Add a `CONTENT.md` section covering: applying the plugin, the full `rutter { }` surface with every field, the derived module path, and what is generated per platform. State the three inherited limitations at the point where a consumer would declare `MODLAUNCHER_8`, `MODLAUNCHER_9` or an `environment`. Do not touch `README.md`; it is generated on the default branch.

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
- **Known soft spots, flagged rather than hidden.** Three things are asserted from documentation rather than measured, and the implementer must verify each before building on it: the exact `publishAllPublicationsToRutterTestRepository` task name (Step 2 of Task 8 says to check), whether `options.release = 8` compiles cleanly against the Gradle API on this Gradle version (Task 1 finds out immediately), and whether `withPluginClasspath()` needs anything beyond `java-gradle-plugin` (Task 5). None is load bearing for the design; each is a build detail with an obvious fallback.
