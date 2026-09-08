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

## Task 1: Declare nested jars in the manifest

Files: `ModuleJarInspector`, `ResolvedModule`, `NyliumPlugin`, `ManifestRenderer`, `ModuleSpec` if
its shape requires it.

- [ ] **Step 1: Record the current manifest for a module with a nested jar**
      Build Baritone's universal jar, extract `nylium-modules.properties`, keep it as the before
      image. Expect no `nested` key anywhere.
- [ ] **Step 2: Have the inspector return nested jar names**
      `ModuleJarInspector.entries` already builds the full entry set. Expose the
      `META-INF/jars/*.jar` subset. Only direct children, not `META-INF/jars/a/b.jar`, since that
      is not a shape `include` produces; reject a deeper one rather than silently ignoring it.
- [ ] **Step 3: Carry the list into `ResolvedModule`**
      Same treatment as `mixins`: unmodifiable list, empty by default.
- [ ] **Step 4: Emit the `nested` key**
      `ManifestRenderer`, guarded on non-empty exactly like `mixins`, comma-separated, stable order
      (jar entry order, which is deterministic for a Gradle-built jar).
- [ ] **Step 5: Validate declared against present**
      Reject a declared nested jar absent from the module jar, with the same message shape as the
      missing-mixin-config error.
- [ ] **Step 6: Confirm the manifest changed as intended**
      Rebuild Baritone's universal jar, extract the manifest, diff against Step 1. Expect exactly
      two new lines, one per Fabric module, and none for the Forge modules.
- [ ] **Step 7: Commit**

## Task 2: Extract nested jars and put them on the classpath

Files: `ModuleManifest`, `ModuleDescriptor`, `ModuleExtractor`, `NyliumKernel`.

- [ ] **Step 1: Parse the `nested` field**
      `readModule` consumes `prefix + "nested"` through the existing `split(value(...))` pair so the
      unknown-key validator keeps working. `ModuleDescriptor` gains `nestedJars()` returning an
      unmodifiable list.
- [ ] **Step 2: Reject an empty or traversing entry**
      A `nested` entry of `..`, an absolute path, or one containing a separator must fail loudly.
      `ModuleExtractor.fileName` already guards the module path this way; nested entries need the
      same treatment, since the value ends up as a file name in the cache directory.
- [ ] **Step 3: Extract a nested entry**
      New method on `ModuleExtractor` taking the already-extracted module jar and an entry name,
      reusing `landInPlace` and the `<name>-<sha16>.jar` naming. Read the entry with `JarFile`
      rather than streaming the whole module through `ZipInputStream`.
- [ ] **Step 4: Wire it into boot**
      In `NyliumKernel.boot`, after `platform.addToClasspath(extracted)`, extract each nested entry
      and add it too. Must happen before `whenModuleLoadable`, so ordering is not incidental: state
      it in a comment only if the code cannot make it obvious.
- [ ] **Step 5: Fail with a useful message**
      A declared nested entry missing from the module jar at runtime should name the module and the
      entry, in the style of the existing "manifest names module X but no such entry exists".
- [ ] **Step 6: Unit tests**
      Manifest parses `nested` and rejects a traversing value; extraction produces a real jar,
      is idempotent on a second call, and an unchanged nested jar keeps its cache name.
- [ ] **Step 7: Commit**

## Task 3: Prove it in the conformance mod

The design's key point: a module that bundles a nested jar without using it passes either way.

- [ ] **Step 1: Add a tiny library the module can depend on**
      A single class with one static method, built as its own jar inside `nylium-conformance` and
      `include`d into one module, so no external coordinate is needed.
- [ ] **Step 2: Have that module's entrypoint call into it**
      The entrypoint writes its marker only after calling the library's method, so an unreachable
      nested jar means no marker, which is what the harness already asserts on.
- [ ] **Step 3: Extend the conformance report**
      Add a report key for the nested-jar module and assert it in `ConformanceSmokeTest`, so a
      missing key fails rather than being silently absent.
- [ ] **Step 4: Run the matrix**
      `./gradlew :smoke:test -PnyliumSmoke -PnyliumSmokeMod=conformance --rerun-tasks` with the
      markers deleted first. `--rerun-tasks` is not optional: with unchanged inputs the task reports
      UP-TO-DATE and launches no server, so a bare BUILD SUCCESSFUL proves nothing.
- [ ] **Step 5: Prove the test can fail**
      Temporarily revert Task 2's classpath addition, re-run, and confirm the new assertion fails.
      Restore it. A regression test never observed failing is not yet a regression test.
- [ ] **Step 6: Commit**

## Task 4: Verify against the real failure

- [ ] **Step 1: Publish this Nylium to mavenLocal**
      Same coordinates Baritone's `:universal` already resolves.
- [ ] **Step 2: Rebuild Baritone's universal jar**
      Note Baritone now sets `org.gradle.configureondemand=true`, and that a whole-tree
      configuration does not fit in this machine's memory; build the loader nodes individually.
- [ ] **Step 3: Confirm the module manifest carries `nested`**
      Read `nylium-modules.properties` out of the rebuilt jar.
- [ ] **Step 4: Boot a production Fabric 1.21.10 client**
      `baritone/scripts/launch-production-client.ps1`, universal jar copied into the game
      directory's `mods/`, extraction cache deleted first. Require the main menu with no
      `NoClassDefFoundError`.
- [ ] **Step 5: Confirm the native actually loaded, not just the class**
      The design's named open question. `NetherPathfinderContext.isSupported()` returns the result
      of `NetherPathfinder.isThisSystemSupported()`, so a resolved class with an unloadable native
      is a different failure from the one being fixed. Establish which happened rather than reading
      the absence of the old crash as success.
- [ ] **Step 6: Re-run the three server boots**
      They passed before this change and must still pass; nested extraction now runs on the Fabric
      path where it previously did not.
- [ ] **Step 7: No commit**

## Task 5: Documentation

- [ ] **Step 1: Move limitation 5 into the kernel design spec**
      It is currently handoff-only. Record it as fixed, with the bytecode-level reason, in the same
      style as the other four.
- [ ] **Step 2: Update `CONTENT.md`**
      The README source states all known limitations; limitation 5 was never added there.
- [ ] **Step 3: Update the Gradle plugin design spec**
      The `nested` field and the manifest compatibility consequence belong next to the existing
      "Metadata deliberately not generated" reasoning.
- [ ] **Step 4: Update both handoffs**
      Nylium's limitation list and Baritone's "The Fabric modules crash the client" section, which
      becomes a fixed record rather than a blocker.
- [ ] **Step 5: Commit**

## Deviations to report rather than absorb

Stop and report, do not work around, if any of these happen:

- A nested jar on the classpath is still not enough for the consumer, because Fabric resolves the
  dependency through mod metadata rather than the classpath (Task 4 Step 4).
- The native library inside `nether-pathfinder` cannot load from a kernel-extracted jar (Task 4
  Step 5). That is a different limitation, not this one, and needs its own record.
- Reverting the classpath addition does NOT fail the new conformance assertion (Task 3 Step 5),
  which would mean the test proves nothing.
- Any of the three server boots regresses (Task 4 Step 6).
