# Nested Jars in Dispatched Modules Implementation Plan

Design: `docs/superpowers/specs/2026-09-08-nylium-nested-jars-design.md`.

Fixes known limitation 5. Read the design first; it records why the two obvious alternatives
(hoisting into the outer `fabric.mod.json`, runtime scanning) were rejected, and the manifest
compatibility consequence.

## Global Constraints

- Java 8 source level in `nylium-core` and `nylium-api`, as today. No lambdas or `var` in those
  modules; the existing code uses anonymous classes for exactly this reason.
- No new dependency in `nylium-core`. Nested extraction uses `java.util.zip`/`java.util.jar` only.
- `nylium-api`'s `Platform` interface does NOT change. The whole point of the chosen shape is that
  the kernel stays platform-agnostic.
- Every commit is Conventional Commits, imperative, lowercase summary, no task or phase
  archaeology in the message.

## Baseline, measured 2026-09-08

- `./gradlew build --offline` green with `:smoke:test SKIPPED`.
- Baritone's universal jar dispatches on Fabric 1.21.10/1.21.11 servers and Forge 1.21.11, and
  crashes a Fabric 1.21.10 client with `NoClassDefFoundError: dev/babbaj/pathfinder/NetherPathfinder`.
- Baritone's Fabric module jars carry `META-INF/jars/nether-pathfinder-1.4.1.jar` (982,181 bytes)
  and zero flattened `dev/babbaj` classes.

## Task 1: WITHDRAWN

Task 1 declared nested jars in the manifest from the plugin. Withdrawn while executing it, because
the manifest is rendered at configuration time from a plain `@Input` String and the module jars do
not exist then. See "Corrected while executing: the kernel discovers" in the design for the full
reasoning and for why the objections to runtime discovery turned out to be weak.

The one part worth keeping was its Step 1, the before image: Baritone's current
`nylium-modules.properties` carries 16 lines, four modules of four keys, and no `nested` key. Under
the corrected design that file is expected to stay **byte-identical**, which is now a check in
Task 4 rather than a diff to inspect here.

Nothing in `nylium-gradle` changes.

## Task 2: Discover, extract and classpath a module's nested jars

Files: `ModuleExtractor`, `NyliumKernel`. Neither `ModuleManifest` nor `ModuleDescriptor` changes.

- [x] **Step 1: List a module jar's nested entries**
      New method reading the already-extracted module jar and returning its direct
      `META-INF/jars/*.jar` entry names, sorted for determinism. Only direct children: a deeper
      `META-INF/jars/a/b.jar` is not a shape `include` produces, so treat it as a hard error rather
      than silently ignoring it.
- [x] **Step 2: Extract one nested entry**
      Reuse `landInPlace` and the `<name>-<sha16>.jar` naming so the atomic-move race handling and
      content addressing are not reimplemented, and an unchanged nested jar is extracted once
      across launches. Read the entry with `JarFile`, not by streaming the module through
      `ZipInputStream`.
- [x] **Step 3: Guard the cache file name**
      The entry name becomes a file name in the cache directory, so apply the same rejection
      `ModuleExtractor.fileName` already applies to a module path: no empty base, no `.` or `..`.
      A zip entry can legally carry `..`, so this is not theoretical.
- [x] **Step 4: Wire it into boot**
      In `NyliumKernel.boot`, after `platform.addToClasspath(extracted)`, add each nested jar too.
      It must happen before `whenModuleLoadable` so the classes exist before any mixin or entrypoint
      runs; keep that ordering evident in the code rather than commented.
- [x] **Step 5: Unit tests**
      A module jar with a nested jar yields it; one without yields nothing; extraction produces a
      readable jar whose content matches the nested bytes; a second call is idempotent and keeps the
      same cache name; a nested entry under a subdirectory is rejected; a `..` entry is rejected.
- [x] **Step 6: Confirm the whole build is still green**
      `./gradlew build --offline`, expecting `:smoke:test SKIPPED`.
- [x] **Step 7: Commit**

## Task 3: Prove it in the conformance mod

The design's key point: a module that bundles a nested jar without using it passes either way.

- [x] **Step 1: Add a tiny library the module can depend on**
      A single class with one static method, built as its own jar inside `nylium-conformance` and
      `include`d into one module, so no external coordinate is needed.
- [x] **Step 2: Have that module's entrypoint call into it**
      The entrypoint writes its marker only after calling the library's method, so an unreachable
      nested jar means no marker, which is what the harness already asserts on.
- [x] **Step 3: Extend the conformance report**
      Add a report key for the nested-jar module and assert it in `ConformanceSmokeTest`, so a
      missing key fails rather than being silently absent.
- [x] **Step 4: Run the matrix**
      `./gradlew :smoke:test -PnyliumSmoke -PnyliumSmokeMod=conformance --rerun-tasks` with the
      markers deleted first. `--rerun-tasks` is not optional: with unchanged inputs the task reports
      UP-TO-DATE and launches no server, so a bare BUILD SUCCESSFUL proves nothing.
- [x] **Step 5: Prove the test can fail**
      Temporarily revert Task 2's classpath addition, re-run, and confirm the new assertion fails.
      Restore it. A regression test never observed failing is not yet a regression test.
- [x] **Step 6: Commit**

## Task 4: Verify against the real failure

- [x] **Step 1: Publish this Nylium to mavenLocal**
      Same coordinates Baritone's `:universal` already resolves.
- [x] **Step 2: Rebuild Baritone's universal jar**
      Note Baritone now sets `org.gradle.configureondemand=true`, and that a whole-tree
      configuration does not fit in this machine's memory; build the loader nodes individually.
- [x] **Step 3: Confirm the module manifest is unchanged**
      Read `nylium-modules.properties` out of the rebuilt jar and expect it byte-identical to
      Task 1's before image: 16 lines, no `nested` key. The corrected design changes no wire
      format, so a difference here means something unintended moved.
- [x] **Step 4: Boot a production Fabric 1.21.10 client**
      `baritone/scripts/launch-production-client.ps1`, universal jar copied into the game
      directory's `mods/`, extraction cache deleted first. Require the main menu with no
      `NoClassDefFoundError`.
- [x] **Step 5: Confirm the native actually loaded, not just the class**
      The design's named open question. `NetherPathfinderContext.isSupported()` returns the result
      of `NetherPathfinder.isThisSystemSupported()`, so a resolved class with an unloadable native
      is a different failure from the one being fixed. Establish which happened rather than reading
      the absence of the old crash as success.
- [x] **Step 6: Re-run the three server boots**
      They passed before this change and must still pass; nested extraction now runs on the Fabric
      path where it previously did not.
- [x] **Step 7: No commit**

## Task 5: Documentation

- [x] **Step 1: Move limitation 5 into the kernel design spec**
      It is currently handoff-only. Record it as fixed, with the bytecode-level reason, in the same
      style as the other four.
- [x] **Step 2: Update `CONTENT.md`**
      The README source states all known limitations; limitation 5 was never added there.
- [x] **Step 3: Left the Gradle plugin design spec alone**
      Nothing in the plugin changed. Recorded as a step so the next reader knows that was checked
      rather than forgotten.
- [x] **Step 4: Update both handoffs**
      Nylium's limitation list and Baritone's "The Fabric modules crash the client" section, which
      becomes a fixed record rather than a blocker.
- [x] **Step 5: Commit**

## Deviations to report rather than absorb

Stop and report, do not work around, if any of these happen:

- A nested jar on the classpath is still not enough for the consumer, because Fabric resolves the
  dependency through mod metadata rather than the classpath (Task 4 Step 4).
- The native library inside `nether-pathfinder` cannot load from a kernel-extracted jar (Task 4
  Step 5). That is a different limitation, not this one, and needs its own record.
- Reverting the classpath addition does NOT fail the new conformance assertion (Task 3 Step 5),
  which would mean the test proves nothing.
- Any of the three server boots regresses (Task 4 Step 6).
