# Rutter SP-1: Kernel - Design

**Date:** 2026-09-05
**Status:** Approved (design), pending spec review
**Repo:** `minecraft/mods/Rutter` (`intisy/rutter`)
**Program context:** see `2026-09-05-rutter-program-overview.md`

## Goal

Ship the dispatch foundation: a single jar that boots on LaunchWrapper, ModLauncher 8,
ModLauncher 9+ and Fabric, detects the running Minecraft version and environment, selects a
matching precompiled module from an embedded manifest, puts it on the correct classloader,
and registers its mixin configs. Proven by a test consumer mod booting on all four
platforms in CI.

Every later sub-project depends on this seam, so SP-1's job is to get the seam right, not to
be feature-rich.

## Non-Goals

- The unified loader API (SP-4). SP-1 exposes only platform, Minecraft version, and
  environment.
- The Minecraft facade and its injected interfaces (SP-5).
- The Gradle packaging plugin (SP-2). SP-1's test jar is assembled by hand-written Gradle in
  the test mod.
- Runtime remapping, downloading, auto-update.

## Artifacts

| Artifact | Purpose | Java | Dependencies |
| --- | --- | --- | --- |
| `rutter-api` | Public surface consumers compile against, plus the `Platform` SPI | 8 | none |
| `rutter-core` | Manifest parsing, version probes, selection, extraction, orchestration | 8 | `rutter-api` |
| `rutter-bootstrap-launchwrapper` | `ITweaker` entry point | 8 | api, core |
| `rutter-bootstrap-modlauncher8` | `ITransformationService` entry point | 8 | api, core |
| `rutter-bootstrap-modlauncher9` | `ITransformationService` plus module layer injection | 17 | api, core |
| `rutter-bootstrap-fabric` | `PreLaunchEntrypoint` | 8 | api, core |
| `rutter-testmod` | Verification consumer, not published | per target | api |

`rutter-api` and `rutter-core` are split so consumers compile against the API alone and the
purity check has a single unambiguous target.

`rutter-core` hand-rolls a minimal JSON reader (roughly 200 lines) rather than depending on
Gson. Nothing is guaranteed to be on the classpath this early, particularly under
LaunchWrapper. EssentialLoader hand-rolls for the same reason.

## Architecture

### Layers

- **stage0** = the four `rutter-bootstrap-*` artifacts. Each implements its loader's entry
  contract and its own native version probe, then hands control to the kernel. Minimal by
  design, because this is the code that can never be relocated later.
- **stage1** = `rutter-core`. All logic, platform-agnostic, testable with no game running.
- **stage2** = the consumer's precompiled modules. Not Rutter code.

### The seam

```java
public interface Platform {
    PlatformId id();
    Environment environment();
    void addToClasspath(Path jar);
    void registerMixinConfig(String name);
    Optional<String> nativeVersionProbe();
}
```

`rutter-core` depends on nothing else about a platform. Keeping this interface small is the
main design constraint of SP-1: anything that can live in core must live in core, because
core is written once and each backend is written four times.

### Version detection

A probe chain rather than one answer per platform, because no single strategy is reliable
across twenty versions. Fabric Loader itself falls through eight strategies.

Order: the platform's native probe first, then generic probes shared by every backend.

- `NativeProbe` - contributed by the backend (Fabric's `minecraft` mod container,
  `FMLLoader` version info reflectively).
- `VersionJsonProbe` - reads `version.json` from the Minecraft jar or classpath.
- `MarkerClassProbe` - a table mapping class presence to version bounds.

`McVersion` must order **both** version schemes correctly: the legacy `1.21.11` form and the
year-based `26.2` form, where any year-based version is newer than any `1.x`. This is a
purpose-built comparator with explicit test vectors, not a semver parse.

### Module selection

The manifest is embedded in the outer jar. Each descriptor carries a path, a platform
constraint, a Minecraft version **range**, an environment constraint, its mixin configs, and
a priority.

Ranges matter more than they first appear: they let one module serve several Minecraft
versions where its bytecode is compatible, and that is the primary lever a consumer has on
jar size.

Selection is deterministic: filter by platform, environment and version, order by constraint
specificity then declared priority, first match wins. Ambiguity resolves by priority and is
warned about.

A miss must produce a `NoCompatibleModuleException` naming the detected platform, version and
environment, and listing every candidate with the reason it was rejected. The difference
between a usable library and a support burden lives almost entirely in this message.

### Module extraction

Nested jars must exist as real files: Fabric extracts them, and ModLauncher needs a path.
Core extracts the selected module to a cache directory keyed by content hash, reuses it when
the hash matches, and guards concurrent launches with an atomic rename.

### Per-backend responsibilities

| Backend | Entry contract | `addToClasspath` | Mixin registration |
| --- | --- | --- | --- |
| LaunchWrapper | `ITweaker.injectIntoClassLoader` | `Launch.classLoader.addURL` | after `MixinBootstrap.init()` |
| ModLauncher 8 | `ITransformationService` via `ServiceLoader` | jars returned from the scanning phase | service init |
| ModLauncher 9+ | `ITransformationService` plus `SecureJar` via `IModuleLayerManager` | `beginScanning` / `completeScan` resource lists | `IMixinConnector` or service init |
| Fabric | `PreLaunchEntrypoint` | `FabricLauncherBase.getLauncher().addToClassPath` | `Mixins.addConfiguration` in preLaunch |

**All four bootstraps coexist inside one outer jar.** Each must therefore be inert when its
platform is absent: a bootstrap is discovered only by its own loader's mechanism, and must
guard any platform class access behind a `Class.forName` capability check before touching it.
A bootstrap that hard-fails off-platform breaks every other platform in the same jar.

Mixin registration must happen after the module is on the classpath and before mixin
transformation begins. That window differs per backend and is the second most likely source
of subtle breakage after module layer injection.

## API purity check

A Gradle task `checkApiPurity`, wired into `check`, ASM-scans the built `rutter-api` jar and
fails if `net/minecraft/` appears in any class signature, method signature, field type,
supertype, generic argument or annotation.

This is the mechanical enforcement of the program's founding compatibility contract. It runs
from the first commit, before there is any API worth protecting, because retrofitting purity
onto an already-leaked API is not practical.

Of the contract's four rules, SP-1 can enforce rules 1 through 3 (purity, semver,
deprecation cycles). Rule 4, running the previous release's test mod against current Rutter,
has nothing to run against until SP-1 has shipped a release. It is therefore wired up as part
of SP-2, against SP-1's release as its first baseline.

## Testing and Verification

Three layers, in increasing cost:

1. **Unit, no game.** Version parsing and ordering across both schemes, range matching,
   manifest parsing, selector rules including rejection messages, extractor cache reuse and
   concurrent extraction.
2. **Fake-platform integration.** The kernel booted against an in-memory `Platform`,
   asserting the correct module was selected and the expected configs registered. No
   Minecraft involved, so this covers the majority of kernel logic cheaply.
3. **Real-launch smoke matrix.** The test mod launched headless per backend, asserting a
   marker that proves the correct module loaded.

Minimum smoke matrix for SP-1 to be done:

| Backend | Minecraft version |
| --- | --- |
| LaunchWrapper | 1.7.10 |
| ModLauncher 8 | 1.16.5 |
| ModLauncher 9+ | 1.21.11 |
| Fabric | 1.21.11 |
| Fabric (discrimination case) | 1.21.10 |

The final row exists because four passing single-version launches would not prove that
selection actually discriminates. Two Fabric versions in one jar, each loading its own
module, is the smallest test that does.

**Definition of done:** all four backends boot the test mod and load the correct module
across that matrix, in CI, with `checkApiPurity` green.

## Risks

- **ModLauncher 9+ module layer injection is the highest risk.** It differs across Forge and
  NeoForge versions and is the least documented of the four. Mitigation: it is spiked first,
  before the rest of the kernel is written. If it proves infeasible for some Forge range,
  that backend's declared version span narrows and the limitation is recorded rather than
  worked around.
- **Four bootstraps in one jar.** Mitigated by capability guards, and specifically covered by
  the smoke matrix, since every launch exercises three inert bootstraps alongside one active
  one.
- **Two version schemes.** `1.x` versus `26.x` ordering is a correctness trap with a silent
  failure mode: a wrong module loads and the game crashes deep in mixin application.
  Mitigated by explicit comparator test vectors.
- **LaunchWrapper era mixin versions.** Mixin 0.7 and 0.8 differ; the LaunchWrapper backend
  may need per-era handling.
- **Java 8 floor.** `rutter-api`, `rutter-core` and three bootstraps must emit Java 8
  bytecode while the build itself runs on a modern JDK. The ModLauncher 9+ backend is the
  sole exception at Java 17.

## Repository and CI

- Per the global CI rule, `.github/workflows/*` in this repo are **thin callers only**, each
  a `uses:` of a reusable workflow in `intisy/workflows`. If a suitable reusable
  `workflow_call` build does not already exist there, it is authored in `intisy/workflows`
  first, never written here and moved later.
- No workflow hardcodes a branch name. The default branch and the development branch are
  workflow inputs, defaulting to the repository's own default branch.
- `README.md` is generated and lives only on the default branch. The development branch
  carries the template the generator renders.
