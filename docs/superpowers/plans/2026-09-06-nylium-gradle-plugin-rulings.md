# Nylium Gradle Plugin (SP-2) - Execution Rulings

**Date:** 2026-09-06
**Status:** Complete
**Plan:** `2026-09-06-nylium-gradle-plugin.md`
**Spec:** `../specs/2026-09-06-nylium-gradle-plugin-design.md`

Every decision taken during execution without pausing for the owner, with its reasoning and what it
costs if wrong. Preserved from the execution ledger before the scratch workspace was deleted, so no
decision dies with it. Written for someone reworking this later who needs to know why, not what.

## Outcome

Nine tasks, 27 plus 12 commits, 68 tests across 13 classes. The plugin assembles a universal jar
from a `nylium { }` declaration, replacing the hand-rolled packaging in `nylium-testmod`.

**The acceptance gate is exact, not approximate.** A plugin-built universal jar is byte-identical in
**every entry** to the hand-rolled reference jar, with an empty allowlist: entry sets, all five
embedded module jars, the module manifest, both `META-INF/services` files, the `TweakClass` manifest
attribute, and every embedded class entry. `fabric.mod.json` is compared semantically by design.

**The four-backend smoke matrix passes against the plugin-built jar**, verified twice by the
controller, the second time after the jar assembly was restructured. Fabric 1.21.10 selected module
1.21.10, Fabric 1.21.11 selected 1.21.11, Forge 1.7.10 selected 1.7.10, Forge 1.16.5 selected
1.16.5, Forge 1.21.11 selected 1.21.11-ml9 and applied a real mixin. Jar identity was proven by file
size on all five servers, not assumed: the harness renames the override to a fixed filename, so a
marker alone could not say which jar booted.

## The five bugs the process caught, and where each hid

Worth recording because each was invisible to the layer above it.

1. **Manifest indices were alphabetical, not declaration order.** `NamedDomainObjectContainer`
   iterates sorted by name. Found only by the differential, because it lived in the *seam* between
   two components that were each correct alone: Task 2's tests declared a single module so order was
   unobservable, and Task 3's tests build `ResolvedModule` lists directly, bypassing the container.
   The manifest index is also the final tie-break in module selection, so this was not merely a
   byte-equality problem.
2. **The verification stamp was shipping inside the universal jar.** Task 7 wrote its `@OutputFile`
   to `build/nylium/`, which Task 6's jar copies wholesale. No earlier test looked inside an
   assembled jar.
3. **Gradle does not clean a removed task's output.** Confirmed by probe, not assumed. Dropping a
   platform left its service file in `build/nylium/` and the sweep shipped it, so a consumer who
   dropped `MODLAUNCHER_9` would have shipped an `ILaunchPluginService` entry naming a class no
   longer in the jar. Fixed structurally by consuming each writer's own output provider.
4. **Eager task realization produced a silently unbootable jar.** `generated` was filled in
   `afterEvaluate` but read from the jar's configuration action, so realization during script
   evaluation yielded a jar with no manifest, no metadata and no embedded classes, with a green
   build. Found by asking a reviewer to judge the *reasoning* behind a fix rather than its outcome.
5. **No publication existed anywhere in the repo**, so the publish task the gate depends on was a
   no-op. Found while wiring the gate.

## Rulings

### Pre-flight (before any task ran)

1. **Task 8 does not modify `nylium-testmod/build.gradle`.** Its file list said "modify" but the
   paths are reachable from `nylium-gradle/build.gradle`. The hand-rolled block is the gate's
   reference artifact and the repo rule is to leave existing code alone.
2. **A snippet that omits imports is still the implementer's to complete.** Task 6's fragment used a
   bare `File`. Cost if wrong: a local compile error.
3. **Registering a task inside another task's `configure` is rejected on newer Gradle.** Fixed in
   the plan before dispatch: register writers into a list, then `dependsOn(writers)` once.

### Correctness of the plan itself

4. **Take the execution-time `project.version` finding even though the plan mandated the code.**
   Gradle documents `Task.project` at execution time as failing in Gradle 10 and as
   configuration-cache incompatible. This is a plugin other builds apply, so that is not optional.
5. **Take the unpinned-charset finding.** Normally Minor, but `ModuleManifest` carries an explicit
   `@implNote` about exactly this bug class, so it is a known local trap.
6. **`maybeCreate` does not reject duplicate module names.** The brief asserted the container
   rejects them *and* used the one method that does not, so it justified skipping the very test that
   would have exposed the gap. Switched to `create`.
   - The API claim was settled by **assertion in a test**, not on the controller's authority, after
     SP-1's record of four wrong API claims. The implementer observed the real message.
7. **Reject a blank `modulePrefix` loudly.** It would yield `modules/-1.21.11.jar`. SP-1 established
   "reject the ambiguity loudly" after a module silently vanished through a comparable collision.
8. **Park the eager `requiredId()` evaluation.** Every consumer needs a mod id anyway, so the eager
   call costs nothing real. Told the implementer explicitly to leave it, so it would not be fixed by
   reflex.
9. **Cover the `environment` and non-zero `priority` emission branches.** The reader rejects a
   malformed value only at game launch, so an untested branch in that wire format is what a later
   refactor breaks silently.
10. **Take the import cleanups.** Consistency, and free.
11. **Take two of three Minor test gaps in Task 4.** A test asserting only that `minecraft` is
    absent would pass with a wrong `fabricloader` default; only `Json.quote`'s quote path was
    exercised while `description` is consumer-supplied free text. The escaping test asserts the
    value survives a gson round trip, so it proves validity *and* fidelity.
12. **Park the `addOptional` trim asymmetry.** Not silently mutating a consumer's string is
    defensible, and changing it would alter output for no benefit.

### Behaviour and API surface

13. **Fix the unconfigured-project trap with a targeted guard, not a restructure.** `afterEvaluate`
    fires for every task invocation, so applying the plugin before writing a `nylium { }` block
    broke `tasks`, `help`, `projects`, `dependencies` and `clean`. Fail-fast is legitimate only when
    the failing command needs the missing configuration. Guarded the **empty** case only; a
    declared-but-wrong module still fails everywhere, which is desirable.
    - Deferring task *registration* was ruled out: which writers exist depends on the platform union.
    - Timed deliberately: Tasks 6 and 7 extend the same block, so the trap would have propagated.
14. **Prove staleness for more than one of four generated files**, assert `ILaunchPluginService`
    absence alongside `ITransformationService`, and import `ArrayList`.
15. **Fix the configuration-cache incompatibility, and require it measured.** No `.getProject()`
    call existed, so this was not the Task 1 defect; it was a live `Project` captured in an
    execution-time lambda. Fixed for consistency: the plan's own justification for the Task 1 fix
    applies identically. **A real `--configuration-cache` run was required and wired permanently**,
    because replacing one unverified claim with another buys nothing.
16. **Set `DuplicatesStrategy.FAIL`.** Promoted from a reviewer's forward-looking note. The default
    `INCLUDE` means a path collision silently emits duplicate entries whose winner depends on copy
    order, and it would let the entry-set differential absorb a duplicate.
17. **Assert group and version in the embed check**, since a hardcoded version bypassing
    `pluginVersion()` would otherwise pass and is testable in process.
18. **Assert `nylium-bootstrap-modlauncher8` absence too**, the one bootstrap the assertions skipped.
19. **Park the `path()` slash assumption and `NyliumPlugin`'s size.** The former defends against a
    producer that does not exist; the latter had not crossed the threshold.

### Module verification

20. **Name the module in the one error path that omitted it.** The plan's global constraints require
    it and the other three messages do it.
21. **Close the fixture landmine immediately.** `UpToDateFunctionalTest` wrote `module.jar` as plain
    text, the identical trap already fixed in the configuration-cache fixture. It passed only
    because that test never reached verification, which is an accident of the task graph rather than
    a property anyone asserted.
22. **Test the scenario the plugin exists for.** Every fixture set `jar` from a static file, none
    from a task output, so the implicit task dependency rested on documentation. Baritone will write
    `tasks.named('remapJar').flatMap { it.archiveFile }`, and without propagation verification runs
    against a jar that does not exist yet. The required test invokes `nyliumVerifyModules` **without
    naming the producer** and asserts the producer executed, so it proves propagation rather than
    absence of a crash.
23. **Match the sibling task's `UncheckedIOException` wrapping.**
24. **Test that `.../nylium/apiextra/Foo.class` is accepted**, since the trailing slash in
    `API_PREFIX` is exactly the character a later simplification removes.

### The acceptance gate

25. **Close the hole the order fix opened.** `getModules()` is public and advertised, so the Groovy
    container form bypassed `moduleOrder` while both emptiness guards read the container. Every
    module declared that way would vanish, writing an **empty** manifest and shipping a jar of only
    api and core classes, successfully. Before the fix such a module landed at the wrong index;
    after it, it disappeared. Fixed with `whenObjectAdded` plus a size cross-check.
26. **Scope the publication to the six subprojects that need it.** In the shared `subprojects` block
    it created a second publication at identical coordinates for `nylium-gradle` alongside
    `java-gradle-plugin`'s own, and would have published the test fixture and smoke harness.
27. **Compare entry *content*, not just entry names.** The gate content-compared nine entries and
    name-compared hundreds of class entries, so a stale `nylium-core` would have passed. Until this
    landed, the honest claim was "reproduces the artifact's shape". A named allowlist was required
    for any legitimate difference; it is empty.
28. **Assert the `TweakClass` attribute specifically**, since the manifest is excluded from the byte
    comparison and a misspelling there breaks the Forge 1.7.10 boot path with a green gate.
29-32. **Four one-line Minors:** remove the `nylium.test.version` property nothing reads; put the
    `build/nylium` contract where the next author will read it; give the configuration-cache test its
    own fixture directory; compare sorted entry-name lists rather than sets.
33. **Park two Minors.** Unifying `MODULE_IDS` guards a scenario `entrySetsAreEqual` still catches;
    collecting every byte mismatch is polish since the message names the path.
34. **Park the `fabric.mod.json` byte-compare gap: it is by design.** The spec chose semantic
    comparison because key order in a hand-written literal is not a contract worth pinning.
35. **Unify the two build-script artifact lists** via `ext.embeddedArtifactPaths`. Two copies with
    no cross-check can drift silently.
36. **Leave the third list alone.** `BOOTSTRAPS` holds Maven coordinates resolved inside a
    downstream consumer's build, with no access to this repo's scripts. A different kind of thing.

### Hand-over

37. **Controller scoping error, corrected.** Cutting the smoke run from Task 9 also cut the
    persistence of the plugin-built jar that the run needs.
38. **Gate `mod.id` validation on FABRIC; accept two other concerns.** Unconditional validation
    against Fabric's pattern is an over-reach: consumers declaring only LaunchWrapper or ModLauncher
    never emit a `fabric.mod.json`, and rejecting their build would be a **new** failure mode
    introduced by a hardening fix. `environment` stays unconditional, since that value only ever
    reaches Fabric metadata.
    - **Accepted:** `:nylium-gradle:test` is never up to date, because the reference jar is not
      reproducible between runs (SP-1's `resources.text.fromString` defect still lives in the
      hand-rolled block, which must stay hand-rolled or the differential becomes circular). A gate
      that always runs beats one that can silently skip.
    - **Accepted:** no registration into the legacy `archives` configuration.
39. **Fix the false realization-timing invariant rather than park it.** See bug 4 above. Not
    parkable because the affected idioms are ordinary and SP-3 is next to apply the plugin. The
    false `@implNote` was **deleted** rather than softened: a comment recording a wrong invariant as
    fact is worse than none.
    - Also fixed the side effect it exposed: wiring `assemble` in `apply()` broke `./gradlew build`
      on an unconfigured project, contradicting the property the empty-module guard protects.

## Three claims corrected by agents, worth knowing

The controller was wrong three times and was corrected with evidence each time:

- `maybeCreate` versus `create` for duplicate rejection (ruling 6).
- The realization-timing invariant (ruling 39).
- **`tasks.withType(Jar) { }` does not realize on Gradle 8.14.4.** Passed along from a reviewer as a
  common idiom that would trigger the bug; measured not to. `tasks.getByName` does, and reproduced
  both consequences. Both idioms are kept in the fixture.

## Two practices that repeatedly earned their cost

- **Handing an unknown over as a question, not an instruction.** Rulings 15 and 39, and the
  stale-output probe, all produced findings precisely because the agent was told to measure and
  report rather than to implement a presumed answer.
- **Mutation-validating a test.** Twice unprompted, twice on request: revert the fix, observe the
  test fail at a named line, restore. A test that has never been seen to fail is not yet evidence.
  The controller applied the same standard to itself: a smoke run returning `BUILD SUCCESSFUL` with
  `:smoke:test UP-TO-DATE` proved nothing and was re-run with `--rerun-tasks`.
