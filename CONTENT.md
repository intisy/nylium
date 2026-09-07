## Compatibility contract

> A mod that uses only Nylium's API keeps working on newer Minecraft versions by bumping the
> Nylium version. Nothing else changes.

That promise is scoped to Nylium's own API surface, because that is the only scope in which it
can be enforced rather than merely hoped for. Four rules make it real:

1. No `net.minecraft` type ever appears in Nylium's public API, not as a parameter, return,
   field, supertype or generic argument. `checkApiPurity`, a Gradle task wired into `check`,
   ASM scans the built `nylium-api` jar and fails the build if one leaks in. This is mechanical
   enforcement, not discipline: a plain `./gradlew build` cannot pass with a leaked type.
2. Semver on the API. Breaking changes require a major version bump.
3. Removals require a deprecation cycle spanning at least one major version.
4. CI compiles and runs the previous release's consumer test mod against current Nylium on the
   newest Minecraft version. This rule has nothing to run against until a first release exists,
   so it is not yet wired up.

## The four backends

Nylium ships one universal jar containing a precompiled module per platform and Minecraft
version range. All four backends below are built and proven on real servers from that single
jar.

| Backend | Verified on | Marker |
| --- | --- | --- |
| Fabric | 1.21.11 and 1.21.10 servers | `module=1.21.11`, `module=1.21.10` |
| LaunchWrapper | Forge 1.7.10 | `module=1.7.10` |
| ModLauncher 8 | Forge 1.16.5 | `module=1.16.5` |
| ModLauncher 9+ | Forge 1.21.11-61.1.5 | `module=1.21.11-ml9`, plus `mixin=applied` from a real mixin |

The Fabric row is the discrimination case: two different Minecraft versions in the same jar,
each resolving to its own module, is the smallest proof that selection actually discriminates
rather than always picking the same candidate.

Each backend is proven on exactly the version listed above (two for Fabric). The wider ranges each
backend covers in source are compatible but unrun, and Forge 1.17 to 1.20.x is explicitly declared
unverified rather than supported.

ModLauncher 9+ currently covers Forge only. The NeoForge loader runs no ModLauncher at all, so
NeoForge support is a separate backend and not part of this kernel yet.

## The manifest format

A module manifest is a flat `.properties` resource named `nylium-modules.properties`, embedded
in the outer jar. `java.util.Properties` ships in the JDK and module descriptors are flat
records with no nesting, so a hand-rolled parser buys nothing.

Each module is a numbered group of `module.<index>.<field>` keys:

```properties
module.0.path=modules/mymod-fabric.jar
module.0.platforms=FABRIC
module.0.minecraft=[1.20,1.21.11]
module.0.entrypoint=com.example.mymod.FabricModule

module.1.path=modules/mymod-forge-1.16.jar
module.1.platforms=MODLAUNCHER_8
module.1.minecraft=[1.13,1.16.5]
module.1.mixins=mixins.mymod.json
module.1.entrypoint=com.example.mymod.Forge116Module
```

- `path`, `platforms` and `minecraft` are all required. `platforms` is a comma-separated list of
  `LAUNCHWRAPPER`, `MODLAUNCHER_8`, `MODLAUNCHER_9` or `FABRIC`. `NEOFORGE` parses too, but it is
  reserved for the deferred NeoForge backend: no backend can report that platform yet, so a module
  declaring it is dead weight in the jar.
- `minecraft` is a version range: an exact version (`1.21.11`), or bracket notation
  (`[1.20,1.21.11]`, `[1.20,)`) where `[` and `]` are inclusive and `(` and `)` are exclusive.
- `environment` (`CLIENT` or `SERVER`), `mixins` (comma-separated config names), `priority`
  (an integer, for resolving ties) and `entrypoint` (a class with a public static
  `void nyliumInit()`) are optional. Note that both ModLauncher backends report SERVER
  unconditionally, so a `CLIENT` module cannot match there; see the limitations below.
- An unrecognised key, or two indices that collide after normalisation (`01` and `1`, for
  example), fail the manifest with a specific error rather than being silently ignored.

Selection is deterministic: filter by platform, environment and version, order by constraint
specificity then declared priority, first match wins. A miss produces an exception naming the
detected platform, version and environment, and listing every candidate with the reason it was
rejected.

## Consumer walkthrough

A consumer can apply the Gradle packaging plugin documented below, or wire this up by hand the
same way `nylium-testmod` does in this repository:

1. Build one module jar per platform and Minecraft version range you support. Each module's
   entrypoint class needs a public static `void nyliumInit()`.
2. Write a `nylium-modules.properties` manifest describing every module, as above.
3. Bundle the manifest, the module jars, and the `nylium-bootstrap-*` artifact for each loader
   you target into one outer jar, wired to that loader's own entry contract (Fabric's
   `preLaunchEntrypoint`, LaunchWrapper's tweaker, ModLauncher's `ITransformationService`).
4. Each bootstrap already calls `NyliumKernel.boot(platform, classLoader, cacheDirectory)` from
   its loader's own hook: on boot, Nylium detects the platform and Minecraft version, selects the
   matching module, extracts it to a hash-keyed cache directory, puts it on the classpath,
   registers its mixin configs, and invokes its entrypoint.

Two loader-specific requirements come on top of that, and both bite at runtime rather than at
build time:

- **A mixin config used with the ModLauncher 9+ backend must set `"target": "DEFAULT"`** (or
  another valid phase) in its JSON. Registration happens late enough there that a config with no
  target has no current phase to fall back on and throws a `NullPointerException` inside Mixin's
  own `MixinConfig.onLoad`.
- **The LaunchWrapper backend needs Mixin on the runtime classpath**, which Forge 1.7.10 does not
  ship. A consumer targeting that era has to put a Mixin jar there itself; this repository's own
  smoke provisioning synthesises a launcher jar whose `Class-Path` adds one.

## The Gradle packaging plugin

Everything the consumer walkthrough above does by hand, that is, writing the manifest, wiring
each loader's own entry contract, and bundling the right Nylium pieces, a Gradle plugin does
from a declaration instead. Nothing is published anywhere yet: this section documents the
plugin as it exists in this repository, not a coordinate you can resolve today.

### Applying the plugin

```groovy
plugins {
    id 'io.github.intisy.nylium'
}
```

The plugin id is `io.github.intisy.nylium`, implemented by `nylium-gradle` in this repository.
Until a release exists, applying it means building `nylium-gradle` and resolving it from wherever
you published that build yourself, not from Maven Central or any other public repository.

Applying it also applies Gradle's `base` plugin, which is what gives the generated jar its
`archiveBaseName` and `destinationDirectory` conventions. That decides where the artifact lands:
with the `java` plugin also applied it goes to `build/libs`, and with `base` alone to
`build/distributions`.

Gradle 8.x is the tested floor. Every functional and differential test runs through the wrapper
this repository ships, and nothing older has been exercised.

### Tasks

Every task the plugin registers sits in the `nylium` group, so plain `./gradlew tasks` lists
them. A full declaration registers seven.

| Task | Does |
| --- | --- |
| `nyliumManifest` | writes `nylium-modules.properties` |
| `nyliumFabricModJson`, `nyliumTransformationServices`, `nyliumLaunchPlugins` | write the loader metadata for the declared platforms, so which of them exist depends on the declaration |
| `nyliumMetadata` | aggregates the writers above |
| `nyliumVerifyModules` | inspects the module jars |
| `nyliumUniversalJar` | assembles the universal jar |

`nyliumUniversalJar` is wired into `assemble`, so a plain `./gradlew build` produces it.

It is registered eagerly, so a consumer can configure it from its own build script body without
an `afterEvaluate` wrapper:

```groovy
tasks.named('nyliumUniversalJar') {
    archiveBaseName = 'mymod'
    from('LICENSE')
}
```

### The `nylium { }` surface

```groovy
nylium {
    mod {
        id = 'mymod'
        name = 'My Mod'
        version = '1.0.0'
        environment = '*'
        fabricLoaderVersion = '>=0.14.0'
        modulePrefix = 'mymod'
        description = 'An example mod'
        license = 'MIT'
        icon = 'icon.png'
        authors = ['Alice', 'Bob']
        contact = [homepage: 'https://example.com']
        minecraftDependency = '>=1.20'
    }

    module('1.21.11') {
        jar = file('build/modules/mymod-1.21.11.jar')
        platforms = ['FABRIC']
        minecraft = '1.21.11'
        entrypoint = 'com.example.mymod.FabricModule'
    }

    module('1.16.5') {
        jar = file('build/modules/mymod-1.16.5.jar')
        platforms = ['MODLAUNCHER_8']
        minecraft = '[1.13,1.16.5]'
        environment = 'SERVER'
        mixins = ['mixins.mymod.json']
        priority = 0
        entrypoint = 'com.example.mymod.Forge116Module'
    }
}
```

`mod { }` takes `id`, `name`, `version`, `environment`, `fabricLoaderVersion`, `modulePrefix`,
`description`, `license`, `icon`, `authors`, `contact` and `minecraftDependency`. Only `id` is
required; every other field falls back to a sensible default or is omitted from generated
output when unset.

Each `module('name') { }` takes `jar` (the already-built, already-remapped module jar for that
platform and Minecraft range), `platforms` and `minecraft` (both required), plus the optional
`environment`, `mixins`, `priority` and `entrypoint`. `platforms` is a list because one module
can serve more than one loader when its bytecode works unchanged on both, most commonly
`MODLAUNCHER_8` and `MODLAUNCHER_9` together.

**ModLauncher 8 (Forge 1.13 to 1.16) dispatches but cannot reach Minecraft classes.** Dispatch
is verified on a real Forge 1.16.5 server: the correct module is selected, classpathed and its
entrypoint invoked. But a module declared with `platforms = ['MODLAUNCHER_8']` that needs to
touch a Minecraft class, or mixin into one, cannot currently work through this backend, because
of a classloader ordering problem in the kernel itself; see the kernel's own design spec for the
full analysis.

**A module built for `MODLAUNCHER_9` cannot currently be installed by dropping a jar in
`mods/`.** The universal jar must be placed on the classpath directly (an explicit `-cp` plus
the loader's own shim main class) rather than dropped in as an ordinary mod, because launch
plugins are discovered from ModLauncher's boot module layer before Forge's `mods/` folder
scanning ever runs.

**`environment()` cannot detect CLIENT on either ModLauncher backend**, so a module declared
with `environment = 'CLIENT'` never matches while the game is running on `MODLAUNCHER_8` or
`MODLAUNCHER_9`. Both backends answer SERVER unconditionally, because the loader has not yet
published its actual launch target at the hook Nylium boots from. Declaring those platforms does
not by itself break the module: one declaring `platforms = ['FABRIC', 'MODLAUNCHER_9']` with
`environment = 'CLIENT'` still matches on Fabric.

`NEOFORGE` is rejected by the plugin outright, at configuration time, because no NeoForge
bootstrap exists yet.

### Content-addressed dedupe

With two or more declared modules, the plugin deduplicates entries across their module jars by
default: every distinct entry (by content, not by name) is stored once, inside the universal jar,
and each module becomes a small index naming which stored entries it needs. The kernel rebuilds a
real module jar from that index into its extraction cache the first time a given module is
selected, and reuses the cached jar on every later launch. The runtime shape is unchanged: one jar
is put on the classpath exactly as before, so this is a storage optimisation, not a behaviour
change.

Set `nylium { dedupe = false }` to restore whole, undeduped module jars, exactly as the plugin
produced before this feature existed. This is an intentional escape hatch: dedupe changes what
happens on a module's first launch (a one-time rebuild, well under a second for a module of a
few hundred entries), and a consumer hitting trouble in the field has a one-line route back to the
simpler, previously proven shape. A single declared module is never deduped regardless of this
setting, since an index would be pure indirection for it.

Measured on the conformance mod that exercises every Nylium feature (8 modules, a handful of shared
classes each): the deduped universal jar is about 24.8% smaller than the same declaration built
undeduped. The saving scales with how much duplicate content a mod actually has and with entry
size: each distinct stored entry carries a fixed overhead of roughly 234 bytes of zip metadata, so a
module built from very many very small entries can grow rather than shrink under dedupe. Measured
against a real multi-version Minecraft mod's compiled bytecode (adjacent Minecraft versions, where
duplication is highest), roughly half of all jar entries were byte-identical between the two
versions.

### What is derived, not written by hand

A module's path inside the jar and its manifest index are both computed, not part of the DSL:

- **Path:** `modules/<modulePrefix>-<name>.jar`, where `modulePrefix` defaults to the mod id.
- **Manifest index:** the order you declare `module('...') { }` blocks in your build script, not
  alphabetical order.

This is the plugin's main advantage over copying `nylium-testmod`'s hand-written build block:
those two details are exactly what a hand-rolled build gets to typo or drift on module by module.

### What is generated per declared platform

- The module manifest (`nylium-modules.properties`) is generated regardless of which platforms
  are declared.
- `fabric.mod.json` is generated only if any module declares `FABRIC`.
- The shared `META-INF/services/cpw.mods.modlauncher.api.ITransformationService` file gets an
  entry for `MODLAUNCHER_8` and a separate entry for `MODLAUNCHER_9`, whichever are declared;
  both backends share the one service file.
- `META-INF/services/cpw.mods.modlauncher.serviceapi.ILaunchPluginService` is generated only if
  `MODLAUNCHER_9` is declared.
- The `TweakClass` manifest attribute is set only if `LAUNCHWRAPPER` is declared.

### Two deliberate choices in the generated `fabric.mod.json`

Both look like omissions on first read; neither is.

- **No `mixins` key.** A module's mixin configs live inside that module's own jar and are
  registered through the platform at runtime, not through Fabric Loader's own mixin discovery.
  Naming them in the outer jar's `fabric.mod.json` would point Fabric Loader at resources the
  outer jar does not contain.
- **No `depends.minecraft` by default.** A pinned Minecraft version in `fabric.mod.json` would
  make Fabric Loader refuse the jar on every Minecraft version except that one, which defeats
  the point of a universal jar. Set `mod { minecraftDependency = '...' }` explicitly if your mod
  genuinely needs a floor.

### Validation

The plugin fails the build rather than shipping a jar that cannot dispatch.

Rejected while the build is being configured, before any task runs:

- Every `minecraft` range is parsed with the kernel's own `VersionRange` parser, so a range the
  game would reject cannot pass the build either.
- An unknown platform name is rejected, naming the platforms it does know about.
- `NEOFORGE` is rejected outright, as above.
- A duplicate module name and a module with no declared platforms are both rejected with a
  specific message rather than silently accepted.
- A module with no `jar` is rejected: there is nothing to embed.
- A missing `mod { id }` is rejected. An id Fabric Loader would refuse is rejected too, but only
  when some module declares `FABRIC`, the same condition that generates the `fabric.mod.json` the
  id lands in; it then has to match `^[a-z][a-z0-9-_]{1,63}$`. To a mod targeting only
  LaunchWrapper or ModLauncher the id is just a module file name prefix, so Fabric's rule does not
  apply to it.
- A blank `mod { modulePrefix }` is rejected. Leave it unset to default to the mod id.
- A `mod { environment }` other than `*`, `client` or `server` is rejected. Fabric Loader accepts
  exactly those three and refuses anything else at launch with "Invalid environment type"; the
  value is matched case insensitively and written out lower case.
- A module `environment` other than `CLIENT` or `SERVER` is rejected. That is a different
  vocabulary from the mod-level field above, because it is Nylium's own rather than Fabric's.

Rejected by `nyliumVerifyModules`, which has to open the module jars and therefore runs at
execution time:

- A declared mixin config that is absent from its own module jar is rejected.
- A module jar that bundles the Nylium API itself is rejected: the universal jar already
  provides it, so a module that shades it in duplicates classes the platform expects to find in
  exactly one place.
- A module jar carrying its own `nylium-modules.properties` is rejected: only the universal jar
  carries a manifest, and a module with one has shadowed Nylium in by accident.

Applying the plugin with no `nylium { }` block at all is inert rather than rejected: `tasks`,
`help`, `clean` and `build` all still succeed, and `assemble` is wired to the jar only once a
declaration exists. Invoking one of Nylium's own tasks is what fails, and it fails when the task
runs, telling you to declare a module.

## Known limitations

This is a working kernel with three documented gaps, not a finished product:

- **ModLauncher 8: dispatch works, but a module cannot see Minecraft classes.** Dispatch is
  verified on a real Forge 1.16.5 server: the correct module is selected, classpathed and its
  entrypoint invoked. But `ITransformationService` discovery and the game's own classloader are
  built as sibling classloaders, and the discovery hook fires before the game classloader
  exists, so a module that needs to touch a Minecraft class, or mixin into one, cannot
  currently work through this backend. Fixing this needs its own spike, which is recorded in the
  SP-1 design spec rather than in any issue tracker.
- **Neither ModLauncher backend can tell a client from a server.** Both answer `SERVER`, so a
  module declaring `environment=CLIENT` never matches on Forge 1.13+. The loader knows the answer
  and publishes it as its launch target, but measured on real 1.16.5 and 1.21.11 servers that value
  is not set yet at the hook Nylium boots from; it appears one hook later. Fixing it means booting
  the kernel later on those backends, which needs its own task.
- **A ModLauncher 9+ module cannot currently be installed by dropping it into `mods/`.**
  `ILaunchPluginService` is discovered only from ModLauncher's boot module layer, which is
  built from the literal JVM classpath, not from Forge's own `mods/` folder scanning. The
  universal jar must currently be placed on the classpath directly (an explicit `-cp` plus the
  loader's shim main class) rather than dropped in as an ordinary mod.
- **The LaunchWrapper backend (Forge 1.7.10 through 1.12.2) dispatches correctly and then the
  server fails to launch.** `NyliumBootTransformer` bootstraps Mixin from inside its own transform
  call, and Mixin registers a new transformer into the list LaunchWrapper's own class loader is
  currently iterating, which crashes that load with a `ConcurrentModificationException`. This is
  independent of what a module does: it reproduces with a module whose entrypoint only writes a
  marker file. Fixing it needs its own spike.
