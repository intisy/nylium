# READ FIRST: Nylium handoff

**Written 2026-09-06.** SP-1 (the kernel) and SP-2 (the Gradle packaging plugin) are both complete.
This file is the entry point for any new session. Everything below is verifiable from the repo;
nothing depends on a prior conversation.

**Renamed from Rutter to Nylium on 2026-09-06.** The rename is mechanical and complete in the
working tree, but the 103 commits on `development` predate it and still say "rutter", as do the
`main` commits. Nothing was ever published under either name, so no coordinate or consumer
broke.

## Where things are

- Repo: `F:\Documents\GitHub\intisy\minecraft\mods\Nylium`
- **Published 2026-09-06 as <https://github.com/intisy/nylium>**, public, default branch `main`.
- Branches: exactly two long-lived, `main` (default, what the README generator renders onto) and
  `development` (where work happens); anything else is a short-lived feature branch off
  `development`. Both are currently at the same commit.
- CI: `Test` is green on both branches. `Smoke` and `Generate README` are `workflow_dispatch` only.
- Licensed **Apache-2.0**. The choice is load-bearing: Nylium is shadowed into consumer mod jars, so
  a copyleft license would be viral into every consuming mod and defeat the point. Apache-2.0 also
  carries an explicit patent grant and is unambiguous about redistribution in binary form, which is
  exactly what shadowing is.
- Build: `./gradlew build --offline` is green with `:smoke:test SKIPPED`.
- Smoke matrix: `./gradlew :smoke:test -PnyliumSmoke`, optionally `-PnyliumSmokeJar=<abs path>` to
  test a specific universal jar. **Add `--rerun-tasks`**: with unchanged inputs the task reports
  `UP-TO-DATE` and launches no server, so a bare `BUILD SUCCESSFUL` proves nothing.
- `nylium-conformance` is a separate Gradle build (its own `settings.gradle`, not a subproject of
  the root build), driven by `./gradlew conformanceUniversalJar`, a root-level `GradleBuild` task
  that invokes it as a nested build. It has to be separate because a Gradle plugin defined inside
  a build cannot be applied to a sibling subproject of that same build; `nylium-conformance` applies
  the published `io.github.intisy.nylium` plugin the same way any real external consumer would,
  which is stronger evidence than testing the plugin against a subproject of its own build. It
  exercises all four platforms, an adjacent-Fabric discrimination pair, an exact-versus-ranged
  version constraint, a priority tie, a `CLIENT`-versus-`SERVER` module, and a ModLauncher 9 mixin,
  across 8 declared modules.

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

One universal jar, four loader families, five real Minecraft servers. **Re-verified under the
Nylium name on 2026-09-06**, 5 tests, 0 failures. Verified by marker files
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

## Four known limitations, each needing its own spike

All four are documented in the kernel design spec with the bytecode analysis behind them. None is a
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
4. **The LaunchWrapper backend dispatches correctly and then the server fails to launch.** Found
   2026-09-06 by the SP-2b conformance mod, measured on Forge 1.7.10. `NyliumBootTransformer`
   bootstraps Mixin from inside its own `transform()` call, and `MixinBootstrap.init()` registers a
   new transformer into the same `ArrayList` that `LaunchClassLoader.runTransformers` is currently
   iterating, so LaunchWrapper's own subsequent top-level load of
   `net.minecraft.server.MinecraftServer` dies with `ConcurrentModificationException`, surfacing as
   `ClassNotFoundException`. Reproduces identically with `nylium-testmod`, whose entrypoint only
   writes a marker file, so it is independent of anything a module does. **It has always been this
   way, and the smoke harness structurally masks it**: the harness force-kills the process the
   instant a module's marker appears, and every module's marker is written before this crash, so
   every LaunchWrapper smoke run in this project's history has been green over a server that goes on
   to die. This blocks LaunchWrapper, Forge 1.7.10 through 1.12.2, not just the conformance mod. A
   fix is constrained: `NyliumBootTransformer`'s own first `@implNote` already records that
   bootstrapping Mixin earlier was tried and made the tweaker's own transformer registration fail
   bytecode verification. Needs its own spike; explicitly out of scope for SP-2b. See the kernel
   design spec for the full stack and reasoning.

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

- **The smoke harness installs by filename, so a renamed jar does not replace the old one.** After
  the Rutter to Nylium rename every server directory held BOTH
  `rutter-testmod-universal.jar` and `nylium-testmod-universal.jar`, plus a stale `rutter/`
  extraction cache. Only ModLauncher 8 failed, and it failed hard: it is the one backend where
  `ServiceLoader` instantiates every entry in the shared `META-INF/services` file, so the stale
  service booted, named an entrypoint that no longer existed, and killed the JVM before the live
  module could write its marker. The other three raced past it because the harness force-kills as
  soon as a marker appears. If you rename anything again, clear `smoke/build/servers/*/mods/` and
  the per-name extraction cache first, or the failure will look like a kernel regression.

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
- **The smoke matrix now takes `-PnyliumSmokeMod=conformance` (default `testmod`), and both mods'
  tests are tagged (`@Tag("testmod")` / `@Tag("conformance")`).** `-PnyliumSmokeMod` is validated
  fail-fast at configuration time (only `testmod` or `conformance` is accepted), but the tag itself
  is not: `useJUnitPlatform { includeTags smokeMod }` with an unknown tag runs zero tests and
  reports `BUILD SUCCESSFUL`, silently green over nothing. Always check the test count in the JUnit
  XML, not just the exit code.
- **A shared class reading a per-version compile-time constant silently bakes in the wrong value.**
  `public static final String X = "..."` is a constant variable under JLS 4.12.4, so `javac` inlines
  it at the reference site's own compile time. The conformance mod's shared entrypoint compiled once
  against an identity stub, and every module reported the stub's value even though the stub was
  never packaged; "nothing packages the stub" was true and did not matter, because the stub's value,
  not its class file, leaked. Fix: return such values from methods, never expose them as constants.
  This generalises to any shared-source-plus-per-version-overlay design, which is exactly Baritone's
  Stonecutter pattern; see the content-addressed dedupe design spec's "Measured result" section.
- **The smoke matrix can never exercise the dedupe cache-HIT path.** `clearInstalledMods` wipes each
  server's extraction cache on every provisioning run, and provisioning is never up to date, so
  every server the matrix boots does so cold. Do not read a passing acceptance run as evidence about
  cache-hit behaviour; it says nothing about it either way.
- **LaunchWrapper (Forge 1.7.10-1.12.2) crashes after correct dispatch; the smoke harness cannot
  see it.** See "Four known limitations" above, limitation 4. The harness force-kills on marker
  appearance, and the marker is written before the crash, so a green LaunchWrapper row has never
  meant the server kept running.

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
- **SP-2b content-addressed dedupe: DONE.** See "Candidate 1 is implemented" above. Proven on the
  conformance mod's own jar and on Baritone's real bytecode (Task 10 measurement, recorded in the
  design spec).
- **SP-3 Baritone universal jar: ASSEMBLED 2026-09-08, not yet run.** Its blocker is gone. Baritone's
  loaders are now Stonecutter dimensions, so one `./gradlew build` produces seven remapped loader
  jars across 1.21.10 and 1.21.11, and `:universal` turns four of them into a Nylium universal jar.
  See the Baritone repo's `docs/superpowers/HANDOFF.md` and
  `docs/superpowers/specs/2026-09-08-loader-stonecutter-nodes-design.md`.
  **Baritone is therefore the second real consumer**, which is what the "Pending decisions" item
  above was waiting for before `nylium-testmod`'s hand-rolled `universalJar` could be retired.
  What the jar carries: Fabric on both versions and Forge on both versions, the latter declared as
  `MODLAUNCHER_9`. What it deliberately omits, and why the coverage question is still open:
  NeoForge is held back by SP-1b, and LaunchWrapper by limitation (4). Forge 1.13-1.16 remains
  partially blocked by limitation (1), though Baritone has no node in that range yet.
  **Dedupe measured 43.3 percent smaller on Baritone's real four-module jar** (7,039,630 bytes
  undeduped against 3,991,203 deduped), beating the 24.8 percent from the conformance mod and the
  33.7 percent from Baritone's two `common` node jars, because four whole loader jars share more.
  Two useful confirmations for the plugin: `entrypoint` really is optional (Baritone declares none
  and works purely through mixins), and a consumer project needs its OWN `repositories` block, since
  `nyliumEmbed` resolves `nylium-api`, `nylium-core` and the bootstraps as Maven coordinates. That
  is worth stating in the plugin's design doc; the current worked example does not show it.
  **Nothing has been booted.** The jar is assembled and its manifest read back, but no server has
  launched it, so this is not yet evidence that Baritone dispatches.
- **SP-1b** NeoForge backend - NeoForge ships no ModLauncher at all and needs a fifth bootstrap over
  its own `IModFileCandidateLocator`, behind its own spike.
- **SP-1c** (implied, not yet specced) the ModLauncher 8 visibility spike from limitation (1).
- **SP-4/SP-5** unified loader API and the Minecraft facade.

## The duplication gap, and what it measures

**Raised by the owner 2026-09-06: "using Nylium I want no duplicate code for the versions."**
This is a real design limitation, not a packaging oversight.

`NyliumKernel.boot()` selects exactly ONE module, extracts it and classpaths it. There is no shared
layer, so every module jar must be self-contained. A consumer targeting N Minecraft versions ships
N complete copies of itself inside one universal jar. For Baritone at 18 targets that is 18 copies.

**Measured on Baritone's two built Stonecutter nodes, not estimated:**

| Measurement | Value |
| --- | --- |
| Shared source files | 354 |
| Of those, referencing `net.minecraft` | 192 |
| Of those, free of `net.minecraft` | 162 (46%) |
| Compiled classes per version node | 261 |
| **Byte-identical between 1.21.10 and 1.21.11** | **247 (94.6%)** |
| Differing | 13 |
| Present in only one | 1 |

The 94.6% is the number that matters. Byte-identity beats a source-level "is it MC-free" split
(46%) because most classes that *reference* Minecraft still compile identically when the symbols
did not change between adjacent versions. Note the caveat: 1.21.10 and 1.21.11 are adjacent, so
identity is unusually high; 1.16.5 against 1.21.11 would be far lower. The mechanism is still
correct, the ratio just varies per pair.

### Candidate 1 is implemented: SP-2b, content-addressed dedupe

The owner picked candidate 1 below and it is done. See
`docs/superpowers/specs/2026-09-06-nylium-content-addressed-dedupe-design.md` (the design, including
the "Measured result" section with the Baritone and conformance-jar numbers) and
`docs/superpowers/plans/2026-09-06-nylium-content-addressed-dedupe.md` (the executed plan). Every
distinct entry across a mod's module jars is stored once in a content-addressed object store inside
the universal jar; each module becomes an index; the kernel rebuilds a real jar in its extraction
cache at first launch. Controlled by `nylium { dedupe = ... }`, on by default for two or more
modules. Proven on real bytecode two ways: the conformance mod's own jar (24.8% smaller deduped)
and Baritone's two built common nodes (measured in Task 10, see the design spec).

Candidate 2, the SP-5 Minecraft facade plus SP-4 unified loader API, remains the deeper, still-open
fix described below; it is a separate program and was not touched by this work.

### Candidate 2, not started: the deeper fix

**SP-5 Minecraft facade plus SP-4 unified loader API.** Baritone's 192 MC-touching files are
per-version *only* because they name `net.minecraft` types directly. A facade would let one source
compile once. Much larger than candidate 1, and it attacks duplicate SOURCE rather than duplicate
BYTECODE. Complementary to candidate 1, not a replacement for it: candidate 1 collapses duplicate
compiled output regardless of source shape, and this would reduce how much duplicate output there
is to collapse in the first place.
