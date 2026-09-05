# Rutter - Program Overview

**Date:** 2026-09-05
**Status:** Approved (decomposition and compatibility contract)
**Repo:** `minecraft/mods/Rutter` (`intisy/rutter`)

## What Rutter is

A library that lets a Minecraft mod ship as **one jar** that boots on **every mod loader**
across **Minecraft 1.7 through 26.2**, and that exposes **every loader capability through a
single unified API** so a consuming mod never calls a loader's own API.

A rutter is a mariner's book of sailing directions: the table that tells you how to get
there from wherever you currently are. That is the library's job.

## The founding compatibility contract

> A mod that uses **only** Rutter's API keeps working on newer Minecraft versions by bumping
> the Rutter version. Nothing else changes.

The promise is scoped to Rutter's own API surface because that is the only scope in which it
can be enforced rather than merely hoped for. Four rules make it real:

1. **No `net.minecraft` type ever appears in Rutter's public API** - not as a parameter,
   return, field, supertype, or generic argument. A leaked `ResourceLocation` would make
   Rutter's own signatures change per version and the contract would be void. Enforced by an
   ASM scan of the published API jar in `check`, not by discipline.
2. **Semver on the API.** Breaking changes require a major.
3. **Removals require a deprecation cycle** spanning at least one major.
4. **CI compiles and runs the previous release's consumer test mod against current Rutter on
   the newest Minecraft version.** This is what actually enforces rule 1; without it the
   contract is a wish.

## How the Minecraft surface is abstracted without a performance cost

Rutter wraps Minecraft's API, but never with wrapper objects, which would be fatal on hot
paths (a pathfinder does millions of block lookups per tick).

- **Injected interfaces, not wrappers.** Rutter declares `RutterLevel`, `RutterEntity`,
  `RutterChunk` as interfaces, then uses per-version mixins to make the real Minecraft
  classes implement them. At runtime a `RutterLevel` *is* the `Level` instance: an interface
  cast, not an allocation.
- **Primitives on hot paths.** Positions travel as packed `long` or `(int x, int y, int z)`;
  block states travel as `int` state ids from the global palette. Zero allocation, and it is
  the shape a pathfinder wants anyway.

Consequence worth recording: Rutter is itself a per-version project (`rutter-mc-<version>`
modules carry those mixins), dispatched by the same mechanism it offers consumers.

## What Rutter deliberately is not

- **Not a runtime remapper.** Modules arrive pre-remapped by the consumer's build. A single
  compiled jar cannot serve Fabric and Forge at the same Minecraft version because their
  runtime mapping namespaces differ (Fabric runs intermediary, Forge and NeoForge run SRG).
  Consumers land on roughly *version x 2 namespaces* rather than *version x 4 loaders*.
  Bridging that gap is what Sinytra Connector does and is out of scope.
- **No downloading or auto-update.** Everything ships inside the consumer's jar.
- **Not a mod.** No gameplay content.

## The four platform backends

The 1.7-to-26.2 span is not thirty special cases. It is four bootstrap families:

| Backend | Covers |
| --- | --- |
| LaunchWrapper | 1.7 - 1.12 |
| ModLauncher 8 | 1.13 - 1.16 |
| ModLauncher 9+ | 1.17+, and NeoForge from 1.20.4 on the same infrastructure |
| Fabric | 1.14+, and Quilt |

## Sub-projects

Rutter is too large for one spec. Each sub-project gets its own spec, plan, and
implementation cycle.

| | Sub-project | Delivers | Depends on |
| --- | --- | --- | --- |
| SP-1 | **Rutter kernel** | Dispatch, four bootstrap backends, manifest and module selection, version probe chain, API purity check, CI. A test mod boots on all four platforms. | - |
| SP-2 | **Gradle packaging plugin** | Turnkey `rutter { }` block assembling the universal jar: modules, manifest, multi-loader metadata. | SP-1 |
| SP-3 | **Baritone universal jar** | One Baritone file for all versions and loaders, with the fork's 8 custom features intact. | SP-1, SP-2 |
| SP-4 | **Unified loader API** | The enumerated complete loader capability surface across all four backends. | SP-1 |
| SP-5 | **Minecraft facade** | Injected interfaces and primitive hot paths, version-stable, grown per version. | SP-1 |

**Ordering rationale:** SP-3 delivers the single Baritone jar before the two largest
sub-projects start. SP-4 and SP-5 then progressively shrink how much per-version code
Baritone needs, but shipping does not wait on them.

## Prior art

- [EssentialLoader](https://github.com/SparkUniverse/EssentialLoader) - the closest existence
  proof. Staged `stage0`/`stage1`/`stage2` bootstrap already spanning LaunchWrapper,
  ModLauncher 8, ModLauncher 9+ and Fabric. Essential-specific rather than a third-party
  library, and carries auto-update machinery Rutter does not need.
- [OneConfigLoader](https://github.com/Polyfrost/OneConfigLoader) - same staged pattern.
- [FabricMultiVersionHelper](https://github.com/Klotzi111/FabricMultiVersionHelper) -
  conditional mixin loading for a single multi-version Fabric jar. One backend only.
- [gXLg/MultiVersion](https://github.com/gXLg/MultiVersion) - reflection and wrapper
  strategies for multi-version Fabric mods.
- Architectury - proves the loader-abstraction half, but per Minecraft version and without a
  cross-version compatibility promise.

No four-backend dispatcher packaged as a library for third parties appears to exist.
