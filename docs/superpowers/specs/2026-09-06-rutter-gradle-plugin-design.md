# Rutter Gradle Packaging Plugin (SP-2) - Design

**Date:** 2026-09-06
**Status:** Approved (design)
**Repo:** `minecraft/mods/Rutter` (`intisy/rutter`)
**Program:** `2026-09-05-rutter-program-overview.md`
**Depends on:** SP-1 (kernel), complete

## Goal

A published Gradle plugin that assembles a Rutter universal jar from a declaration, replacing the
hand-rolled packaging that SP-1 proved out in `rutter-testmod/build.gradle`.

## Why this exists

The hand-rolled block works, and it is exactly the wrong thing to hand a consumer. Its
`rutter-modules.properties` is a literal string sitting beside the `moduleIds` list it describes,
so five module paths, five entrypoints and five indices are all maintained by hand next to the data
they duplicate. Nothing checks that `module.3.path` names a jar the build actually produces. The
manifest is emitted through `resources.text.fromString`, which SP-1 found regenerates a fresh temp
path on every configuration, so it is not a tracked task input and a stale jar can survive a
rebuild.

A consumer also cannot reach the pieces the way the test mod does: `universalJar` pulls Rutter's
classes through `project(':rutter-api').sourceSets.main.output`, which only works inside this repo.

## Non-goals

- **Not a remapper.** Modules arrive pre-remapped, per the program's standing non-goal.
- **No Baritone integration.** That is SP-3. Keeping it out keeps SP-2 reviewable.
- **No `mods.toml` or `neoforge.mods.toml` generation.** See "Metadata deliberately not generated".
- **No new version algebra.** The plugin calls `VersionRange.parse`; it never reimplements it.

## Structure

New subproject `rutter-gradle`, published as `io.github.intisy.rutter:rutter-gradle`, exposing
plugin id `io.github.intisy.rutter`.

- Java 8 bytecode, like every other artifact here, so `checkClassFileVersion` covers it unchanged
  and old consumer builds can apply it. The ModLauncher `ServiceLoader` reason does not apply to a
  plugin, but consistency costs nothing.
- Gradle 7.6 floor. Lazy task configuration, the Provider API and `layout.buildDirectory` are all
  present there, and nothing newer is needed. **Superseded as built:** every TestKit run uses this
  repository's own wrapper, so 7.6 is never exercised and 8.x is the tested floor. `CONTENT.md`
  documents 8.x.
- `gradleApi()` is `compileOnly`. `rutter-core` is a real dependency, because validation reuses the
  kernel's own parsers.

**The plugin and the embedded library ship as one version.** The plugin resolves the runtime pieces
at its own version, so "bump the Rutter version" stays the single action the founding compatibility
contract promises.

## The DSL

```groovy
rutter {
    mod {
        id      = 'baritone'
        name    = 'Baritone'
        version = project.version
        // optional passthrough: description, authors, contact, license, icon, environment, custom
    }

    module('1.21.11') {
        jar        = tasks.named('remapJar').flatMap { it.archiveFile }
        platforms  = ['FABRIC']
        minecraft  = '1.21.11'
        entrypoint = 'baritone.RutterEntry'
        mixins     = ['mixins.baritone.json']
        // optional: environment, priority
    }
}
```

The module block mirrors `ModuleDescriptor` exactly: `platforms` and `minecraft` required,
`environment`, `mixins`, `priority` and `entrypoint` optional. Two fields are **derived and never
written by hand**: the module's `path` inside the jar, and its manifest index (assigned in
declaration order). That removes the whole class of drift the literal manifest invites.

`jar` is a `RegularFile` provider, not a project reference. Baritone's per-version output comes from
unimined's `remapJar`, and a plugin that built module jars itself would have to reimplement
remapping. A provider accepts any toolchain and matches the pre-remapped-modules non-goal.

## Selective embedding

The plugin creates a resolvable `rutterEmbed` configuration holding `rutter-api`, `rutter-core`,
and one `rutter-bootstrap-*` per **declared** platform, then unpacks it into the universal jar.

This is the consumer's "only the parts we need", derived rather than configured, and it is not
merely an optimisation: the two ModLauncher backends share a single
`META-INF/services/cpw.mods.modlauncher.api.ITransformationService` file, so which entries that file
contains is a function of which platforms were declared.

## Generated artifacts

| Artifact | Emitted when |
| --- | --- |
| `rutter-modules.properties` | always |
| `fabric.mod.json` | FABRIC declared |
| `META-INF/services/...ITransformationService` | MODLAUNCHER_8 or MODLAUNCHER_9 declared, one line each |
| `META-INF/services/...ILaunchPluginService` | MODLAUNCHER_9 declared |
| `TweakClass` manifest attribute | LAUNCHWRAPPER declared |

The generated `fabric.mod.json` differs structurally from a normal mod's, which is why the plugin
generates it rather than merging the consumer's:

- **`entrypoints.preLaunch`** is Rutter's, so the kernel boots before the game.
- **No `mixins` block.** Per-version mixin configs live inside the module jar and are registered
  through `Platform.registerMixinConfig`. A config named in the outer jar would not be found there.
- **No `depends.minecraft` at all** by default, overridable in `mod {}` for a consumer who wants a
  floor.

That last default is the point of the exercise. Baritone's committed `fabric.mod.json` pins
`"depends": {"minecraft": ["1.21.11"]}` and its `mods.toml` pins `versionRange="[1.21.11]"`; only
`${version}` is expanded at build time, so those are hardcoded literals. A universal jar carrying
either would be refused by the loader on every version but one.

Omitting the key entirely, rather than emitting `"*"`, is deliberate on two counts: it is what the
test mod proven on five servers actually ships, so the default reproduces a known-good artifact
byte for byte; and it avoids computing a Fabric version predicate from a union of Rutter ranges,
which would be a second version algebra and a place to be subtly wrong. The cost is that an
out-of-range launch fails with `NoCompatibleModuleException` instead of a loader refusal, which
names the problem better anyway.

### Metadata deliberately not generated

`mods.toml` and `neoforge.mods.toml` are **not** emitted. Rutter's ModLauncher backends reach
ModLauncher's own SPI and never enter FML's mod-loading pipeline, which is why the proven test mod
ships no `mods.toml` at all. SP-1's limitation (2) records `mods.toml` as the *candidate* route to
making ML9 modules installable from `mods/`, and that is unresolved. Generating metadata whose
semantics are still open would bake in a guess.

## Validation, at configuration time where possible

This is where the plugin earns its keep over a copied build block. Each rejection gets a test.

1. Every `minecraft` string is parsed with the kernel's `VersionRange.parse`, so a malformed range
   fails the build instead of the game.
2. Platform names are parsed against `PlatformId`; unknown names are rejected.
3. `NEOFORGE` is rejected explicitly, naming SP-1b. `PlatformId` has the constant but no bootstrap
   exists, so accepting it would emit a jar that silently never dispatches on NeoForge.
4. Duplicate module names, and an empty module set, are rejected.
5. A `mixins` entry absent from its own module jar is rejected. This is the exact bug class behind
   limitation (2), and it can only be checked by looking inside the jar, so it runs at execution time.
6. A module jar containing `rutter-modules.properties` or Rutter API classes is rejected: that means
   the consumer shadowed Rutter into a module by accident.

## Tasks

- `rutterManifest` writes `rutter-modules.properties` from a `@Nested` list of module specs with
  annotated inputs and a real `@OutputFile`. Being a properly declared task is the fix for SP-1's
  untracked-input staleness defect, and it gets an explicit up-to-date-then-rebuild test.
- `rutterMetadata` writes the generated loader files for the declared platforms.
- `rutterUniversalJar` assembles: embedded Rutter parts, module jars under `modules/`, generated
  metadata, and the `TweakClass` attribute when LaunchWrapper is in play.

## Testing

Three layers, ordered by how much they can actually catch:

1. **Unit.** Manifest rendering, index assignment, and one test per validation rejection above.
2. **Functional, via Gradle TestKit.** Apply the plugin to a synthetic project, build, assert the
   jar's entry set; then assert `UP-TO-DATE` on a second run and a genuine rebuild after a module
   changes.
3. **Differential, against the known-good artifact.** Rebuild `rutter-testmod`'s universal jar
   through the plugin and require it to match the hand-rolled jar: identical entry set, identical
   bytes for every generated metadata file, identical bytes for each embedded module jar, and
   identical bytes for **every remaining entry**, skipping only directory entries, the manifest, and
   entries a more specific assertion already covers.

Content-comparing only the metadata while name-comparing the hundreds of embedded class entries
would let a stale or wrong-version `rutter-core` pass, reducing the claim from "reproduces the
artifact" to "reproduces its shape". Any legitimate difference belongs in a named allowlist rather
than a loosened assertion; as built the allowlist is empty, so the reproduction is exact. The
`TweakClass` manifest attribute gets its own assertion, because the manifest is excluded from the
byte comparison and a misspelling there would break the Forge 1.7.10 boot path with an otherwise
green gate.

`rutter-gradle` stays an ordinary subproject and the fixture applies the plugin through TestKit's
`withPluginClasspath()`. That is TestKit's intended mechanism, it needs no publishing step, and it
avoids restructuring `settings.gradle` into a composite build just so a sibling can apply a plugin,
which would also take `rutter-gradle` outside the root conventions that give every artifact here its
`checkClassFileVersion` and Java 8 floor.

Layer 3 is the acceptance gate, and it is chosen because it can fail: it compares against an
artifact already proven on five real Minecraft servers rather than against assertions written by the
same hand as the code. A **strict content** differential, not merely a matching entry list, is what
makes it load bearing; with it green, re-running the four-backend smoke matrix against the
plugin-built jar is confirmation rather than proof, so it stays a single opt-in run instead of a
per-change gate that would download four Minecraft servers.

## Migration

The hand-rolled `universalJar` block stays until the plugin-built jar passes the smoke matrix. Per
this repo's standing rule, **removing it is a question for the owner, not a step in the plan.**

## Inherited limitations

SP-2 changes none of SP-1's three recorded limitations and must not appear to. A consumer declaring
`MODLAUNCHER_8` gets a jar that dispatches but cannot reach Minecraft classes; `environment` is
undetectable as CLIENT on both ModLauncher backends; ML9 modules are not installable from `mods/`.
The plugin's README section states all three at the point where a consumer declares those platforms.
