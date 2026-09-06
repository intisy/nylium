# READ FIRST: Nylium handoff

**Written 2026-09-06.** SP-1 (the kernel) and SP-2 (the Gradle packaging plugin) are both complete.
This file is the entry point for any new session. Everything below is verifiable from the repo;
nothing depends on a prior conversation.

**Renamed from Rutter to Nylium on 2026-09-06.** The rename is mechanical and complete in the
working tree, but the 103 commits on `development` predate it and still say "rutter", as do the
`master` commits. Nothing was ever published under either name, so no coordinate or consumer
broke.

## Where things are

- Repo: `F:\Documents\GitHub\intisy\minecraft\mods\Nylium`
- **Published 2026-09-06 as <https://github.com/intisy/nylium>**, public, default branch `master`.
- Branches: `master` (default, what the README generator renders onto) and `development` (where
  work happens). Both are currently at the same commit; `development` was fast-forwarded into
  `master` at publish time.
- CI: `Test` is green on both branches. `Smoke` and `Generate README` are `workflow_dispatch` only.
- Licensed **Apache-2.0**. The choice is load-bearing: Nylium is shadowed into consumer mod jars, so
  a copyleft license would be viral into every consuming mod and defeat the point. Apache-2.0 also
  carries an explicit patent grant and is unambiguous about redistribution in binary form, which is
  exactly what shadowing is.
- Build: `./gradlew build --offline` is green with `:smoke:test SKIPPED`.
- Smoke matrix: `./gradlew :smoke:test -PnyliumSmoke`, optionally `-PnyliumSmokeJar=<abs path>` to
  test a specific universal jar. **Add `--rerun-tasks`**: with unchanged inputs the task reports
  `UP-TO-DATE` and launches no server, so a bare `BUILD SUCCESSFUL` proves nothing.

## Read these, in this order

1. `docs/superpowers/specs/2026-09-05-nylium-program-overview.md` - what Nylium is, the founding
   compatibility contract, the five sub-projects and their order.
2. `docs/superpowers/specs/2026-09-05-nylium-kernel-design.md` - SP-1's design, amended repeatedly
   during execution. **The three known limitations are recorded here with bytecode evidence.**
3. `docs/superpowers/plans/SPIKE-modlauncher9.md` - the ModLauncher 9 spike findings. Implement from
   this, never from memory; it corrected several wrong assumptions.
4. `docs/superpowers/plans/2026-09-05-nylium-kernel-rulings.md` - all 72 decisions taken during
   execution without pausing, each with reasoning and what it costs if wrong. Read this if you want
   to rework something.
5. `docs/superpowers/plans/2026-09-05-nylium-kernel.md` - the executed plan. **Its code samples are
   unreliable**: several APIs it names do not exist. See "Plan defects" below.
6. `docs/superpowers/specs/2026-09-06-nylium-gradle-plugin-design.md` - SP-2's design. Read it before
   changing the plugin: several choices look like omissions without it, notably the generated
   `fabric.mod.json` carrying no `mixins` key and no `depends.minecraft`.
7. `docs/superpowers/plans/2026-09-06-nylium-gradle-plugin-rulings.md` - SP-2's 39 rulings, the five
   bugs the process caught and where each hid, and three claims that agents corrected with
   measurements. Read this before reworking the plugin.
8. `docs/superpowers/plans/2026-09-06-nylium-gradle-plugin.md` - SP-2's executed plan, amended
   throughout as defects in it were found. Its code is more trustworthy than SP-1's, but the rulings
   file records where it was wrong.

## What is proven, and how

One universal jar, four loader families, five real Minecraft servers. Verified by marker files
written on disk by the dispatched module's own entrypoint, re-checked after deleting them and
forcing a full re-run:

| Server | Marker |
| --- | --- |
| Fabric 1.21.10 | `module=1.21.10` |
| Fabric 1.21.11 | `module=1.21.11` |
| Forge 1.7.10 (LaunchWrapper) | `module=1.7.10` |
| Forge 1.16.5 (ModLauncher 8) | `module=1.16.5` |
| Forge 1.21.11 (ModLauncher 9+) | `module=1.21.11-ml9` and `mixin=applied` |

The Fabric pair is the load-bearing case: the same jar selecting a *different* module per version is
what proves dispatch rather than mere loading. `mixin=applied` comes from a real
`@Mixin(targets="net.minecraft.server.Main")` whose injected callback ran.

## Three known limitations, each needing its own spike

All three are documented in the design spec with the bytecode analysis behind them. None is a
silent bug; all are stated in `CONTENT.md` (the README source) too.

1. **ModLauncher 8 dispatches but cannot reach Minecraft classes.** Its `ITransformationService` is
   discovered by a classloader that is a *sibling* of the one hosting the game, and its hook fires
   before that loader is constructed. A consumer needing live game-class access or working mixins
   cannot ship on Forge 1.13-1.16 through this backend. Candidate route:
   `additionalClassesLocator()`/`additionalResourcesLocator()` plus a companion
   `ILaunchPluginService`. **A fix cannot be verified until a test module exists that actually
   touches a game class** - the current marker-writing module passes either way.
2. **ModLauncher 9+ modules cannot be installed by dropping a jar in `mods/`.** Launch plugins are
   enumerated from the boot layer inside `Launcher.<init>`, before Forge's mod-directory scan, and
   `ILaunchPluginService` is never a `mods/` discovery trigger. Deployment gap, not correctness.
   Candidate route: piggyback Forge's own mixin launch plugin via a `mods.toml` declaration or a
   `MixinConfigs` manifest attribute, which is how ordinary Forge mods do it.
3. **`Platform.environment()` cannot detect CLIENT on either ModLauncher backend.** Same classloader
   topology as (1). `IEnvironment.Keys.LAUNCHTARGET` is the right signal but is measured empty at
   `onLoad`, only populating at `initialize`/`beginScanning`. Fixing it needs kernel boot moved to a
   later hook on two proven backends. CLIENT is unverified on *all* backends; no client smoke test
   exists anywhere.

## Plan defects, so you do not repeat them

The executed plan asserted several APIs from memory that do not exist. Verify against the real jar
before writing code against any API the plan names:

- `cpw.mods:securejarhandler` is now `net.minecraftforge:securemodules`; `cpw.mods:modlauncher`
  is now `net.minecraftforge:modlauncher` for ModLauncher 9+.
- ModLauncher 8's `ITransformationService` has **no** `getExtraJarPaths()` and no jar-injection hook
  at all. The working route is a reflective `addURL` on its discovery `URLClassLoader`.
- `net.minecraft:launchwrapper:of-2.3` does not resolve; use `1.12`.
- The plan's `MarkerClassProbe` table had a **backwards** entry (`net.minecraft.block.Blocks` mapped
  to 1.9; the 1.13 flattening means that class is 1.13-and-later). It was deleted.

## Non-obvious invariants, all enforced

- **Every artifact emits Java 8 bytecode.** Both ModLauncher services share one
  `META-INF/services` file and `ServiceLoader` instantiates every entry, so a Java 17 class file
  there throws `UnsupportedClassVersionError` and kills a ModLauncher 8 game. Enforced by
  `checkClassFileVersion`, wired into `check` for all eight subprojects. No `List.of`, no `var`,
  no records anywhere.
- **`nylium-api` exposes no `net.minecraft` type**, enforced by `checkApiPurity` (ASM scan covering
  supertypes, interfaces, fields, methods, generics, throws, and annotations including parameter
  annotations). This is what makes the founding compatibility contract enforceable.
- **`nylium-api` and `nylium-core` have zero runtime dependencies and no logging.** Loader APIs are
  `compileOnly` in the bootstraps. Confirmation lines are printed by the bootstraps, not core.
- **Smoke provisioning is gated on the dependency EDGE**, not just with `onlyIf`, because `onlyIf`
  never gates an edge. A plain `./gradlew build` must not download servers; verify that if you
  touch `smoke/build.gradle`.
- **Server JVMs come from per-version toolchain system properties** (`nylium.smoke.java.8`,
  `.21`), never `java.home`. The Gradle daemon here runs Java 17, which can neither boot 1.21.x
  nor is wanted for 1.7.10.

## Pending decisions that are the owner's, not an agent's

- The smoke matrix is a `workflow_dispatch`-only CI caller by design, not a per-push gate: it
  provisions four Minecraft servers. `.github/workflows/smoke.yml`.
- **Retire `nylium-testmod`'s hand-rolled `universalJar`?** The plugin reproduces it byte for byte,
  so it could. But it is the acceptance gate's reference artifact: replace it and the differential
  compares the plugin against itself. Recommendation is to keep it until SP-3 gives a second real
  consumer. Doing it in-repo also needs a dependency-substitution rule, since the plugin resolves
  the six pieces as Maven coordinates.

## Traps that will bite a fresh session

Each of these produced a green result that meant nothing, or nearly did.

- **A root `./gradlew build` never runs buildSrc's tests.** buildSrc is compiled as an implicit
  included build, so its 21 tests in `ApiPurityTaskTest` and `ClassFileVersionScannerTest` are
  skipped entirely by a plain `build`, and stale `buildSrc/build/test-results` XMLs from an
  earlier run look exactly like fresh passes. Run `./gradlew :buildSrc:test` explicitly and check
  the XML mtimes. `-p buildSrc` does **not** work: buildSrc declares no `gradleApi()` dependency
  and relies on Gradle injecting it, so standalone it fails with "package org.gradle.api does not
  exist" - a failure that looks like a real defect and is not one.

- **A smoke run can pass without launching anything.** With unchanged inputs `:smoke:test` reports
  `UP-TO-DATE`, so `BUILD SUCCESSFUL` proves nothing: no server starts and no marker is written.
  Always `--rerun-tasks`, delete the markers first, and read them afterwards.
- **The markers cannot tell you which jar booted.** The harness renames any `-PnyliumSmokeJar`
  override to `nylium-testmod-universal.jar`. Discriminate by file size: `stat` the installed jar in
  each server directory against the jar you meant to test.
- **`:nylium-gradle:test` is never up to date, deliberately.** `nylium-testmod`'s hand-rolled block
  still emits its manifest through `resources.text.fromString`, so the reference jar is not
  reproducible between runs. A gate that always runs beats one that can silently skip. Do not
  "fix" it by touching that block.
- **An empty `grep -c` result looks exactly like a missing thing**, and a bare `grep -c failure` on
  a JUnit XML matches the `failures="0"` attribute. Read the context, not the count.
- **`grep -c` returning 0 exits non-zero** and will break a `&&` chain, skipping the command you
  actually cared about.

## SP-2 items deliberately left undone

All recorded with reasoning in the rulings file; none is a defect.

- `NyliumPlugin` carries five concerns at around 240 lines. The natural split is a `NyliumEmbed`
  class and a `UniversalJar` factory. Worth doing when the file is next touched, not as a gate.
- `mod.version` falls back to `"0.0.0"` rather than `project.version`. An explicit visible default
  is defensible; changing it is a behaviour change, not a fix.
- `nyliumEmbed` declares no attributes and non-strict versions. Not reachable from a normal consumer
  build, but an `allprojects` `resolutionStrategy.force` on `io.github.intisy.nylium:*` could embed
  a kernel the generated metadata was not written for.
- The `f3e273e` commit subject is around 130 characters. Rewriting history is the owner's call.
- **One measurement is single-version:** `tasks.withType(Jar) { }` was found *not* to realize on
  Gradle 8.14.4. `tasks.getByName` does. If the plugin is ever tested on another Gradle, re-check.

## What comes next

Per the program overview's ordering:

- **SP-2 Gradle packaging plugin: DONE.** `io.github.intisy.nylium` assembles a universal jar from a
  `nylium { }` declaration. Its acceptance gate proves a plugin-built jar is byte-identical in every
  entry to the hand-rolled reference, and the four-backend smoke matrix passes against the
  plugin-built jar. See `specs/2026-09-06-nylium-gradle-plugin-design.md`,
  `plans/2026-09-06-nylium-gradle-plugin.md` and its rulings file.
- **SP-3 Baritone universal jar - this is what the owner originally asked for, and it is now
  unblocked by SP-2.** Unblocked for
  Fabric and Forge 1.17+; partially blocked on Forge 1.13-1.16 by limitation (1); blocked for
  NeoForge until SP-1b.
- **SP-1b** NeoForge backend - NeoForge ships no ModLauncher at all and needs a fifth bootstrap over
  its own `IModFileCandidateLocator`, behind its own spike.
- **SP-1c** (implied, not yet specced) the ModLauncher 8 visibility spike from limitation (1).
- **SP-4/SP-5** unified loader API and the Minecraft facade.
