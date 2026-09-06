# Nylium SP-1: Controller Rulings

Decisions taken during execution of `2026-09-05-nylium-kernel.md` without pausing for the
owner, each with its reasoning and what it costs if wrong. Preserved from the execution ledger
before that scratch workspace was deleted. 72 rulings across 16 tasks.

Read this if you want to rework anything I decided on your behalf.

## T1 writes only the `junit5Runs` test; the no-op `compilesToJava8Bytecode` and its Step 8 deletion are dropped - a test asserting `"1.8"` against a ternary that yields `"1.8"` either way verifies nothing, and the review rubric treats it as a defect. Cost if wrong: none; bytecode targeting is verified by T10's purity task and by the ML8 smoke test in T14.


## T2's spike compiles at release 8 with no `nyliumJavaRelease` override and uses `Collections.singletonList`/`Collections.emptyList` instead of `List.of`; the spike additionally records whether release-8 compilation against ModLauncher 9 APIs succeeds. The Global Constraints require Java 8 bytecode everywhere, and the spike exists to de-risk exactly this backend, so it must validate the constraint rather than sidestep it. Cost if wrong: if release 8 cannot compile against ModLauncher 9 APIs, the shared-services-file design in R7 collapses and both ML backends need separate discovery mechanisms - which is precisely what the spike is for.


## T5's `indices()` orders numerically rather than lexicographically. As written, a manifest with ten or more modules would order "10" before "2" and change which module wins a specificity tie. SP-1's own manifests stop at module.4 so no test would catch it, but SP-3 (Baritone, 13+ versions) would hit it immediately. Cost if wrong: none; numeric ordering is a strict superset of correct behaviour here.


## T10's Step 7 creates no `Leak.java`. The step contradicts itself, and the reason it gives is sound: `nylium-api` cannot compile against Minecraft by construction, so a live violation is unconstructible. The negative case is the `buildSrc` suite, which compiles real fixtures against a stub `net.minecraft.Level`. Cost if wrong: the purity task's failure path is proven only at unit level, not end to end. Accepted - the inability to compile against Minecraft is itself a second layer of the same guarantee.


## T11 implements the resource-reading `TestModEntry` from Step 8 directly and skips Step 7's system-property variant. A property would be identical across every module and would prove nothing about which module ran, which is the entire purpose of the marker. Cost if wrong: none; Step 8 was already the plan's own correction.


## every server-provisioning task carries the same `onlyIf { project.hasProperty('nyliumSmoke') }` gate as `:smoke:test`. Gating only `test` leaves its `dependsOn` provisioning to run regardless, so a plain `./gradlew build` would download several hundred megabytes of loader installers - directly contradicting T12 Step 8, which expects the offline build to stay offline. Cost if wrong: none.


## T14 writes the services file listing the ModLauncher 8 service alone; T15 Step 4 extends it to both. T14 Step 4 states both positions and its second one is correct, since `NyliumMl9Service` does not exist until T15 and `ServiceLoader` would fail on the missing class. Cost if wrong: none.


## the missing `(scope)` in Task 1's commit message is a real finding and the plan was wrong,
not the implementer. CLAUDE.md states the format as `type(scope): summary` for ALL repos and ALL
projects, and my plan's own commit messages are inconsistent about it (Tasks 3-9, 12-15 carry
scopes; Tasks 1, 10, 16 and the docs commits do not). Fixed by amending, since the branch has no
remote and this is its only code commit. Every remaining task's dispatch will carry the scope
requirement so the plan's scopeless messages are corrected at source. Cost if wrong: if the user
reads Conventional Commits' optional-scope rule as permitting bare `build:`, I have added a
cosmetic segment to a dozen commit subjects. Cheap either way, and it matches their worked example.


## a fix whose diff is empty by construction gets no reviewer seat. This finding's fix is a
commit-message amendment with a byte-identical tree, so a scoped re-review would be handed an empty
diff and could verify nothing. I verify it directly with `git log -1 --format=%s`. Cost if wrong: a
content change could ride along inside an amend unnoticed, so the verification checks the tree hash
as well as the subject line, which closes that gap.


## `Platform` gains two Java 8 DEFAULT methods so the synchronous kernel survives -
`default void whenModuleLoadable(Runnable activation) { activation.run(); }` and
`default ClassLoader moduleClassLoader(ClassLoader source) { return source; }`. The kernel becomes
`platform.whenModuleLoadable(() -> invoke(entrypoint, module, platform.moduleClassLoader(source)))`.
Fabric, LaunchWrapper and ML8 inherit the immediate behaviour and need no code; only ML9 overrides,
deferring to `initializeLaunch` and returning the GAME layer's loader. Chosen over splitting `boot`
into two public calls because that would force every backend to change and would let a backend
forget the second call silently. Affects Tasks 3, 9, 11 and 15, all still unimplemented, so the
briefs are amended at dispatch and there is no rework. Cost if wrong: a `Runnable` cannot return a
value or a typed failure to the platform; the kernel's `invoke` throws `NyliumException`, which
propagates inline on three backends and at `initializeLaunch` on ML9. If a platform later needs to
abort the launch with its own error type, this needs a functional interface instead.


## NeoForge is a FIFTH backend, not an ML9 variant, and SP-1 does not ship it. My spec and
program overview both assert "NeoForge from 1.20.4 on the same ModLauncher 9+ infrastructure";
the spike disproves that for 21.11.x. So ML9 is scoped to Forge 1.17+ only, `PlatformId` gains a
`NEOFORGE` constant now (so SP-3 can author manifests without an API bump), and the NeoForge
bootstrap becomes SP-1b, opening with its own spike of `IModFileCandidateLocator` lifecycle timing
which this spike flagged as uncharacterised. Cost if wrong: Baritone ships NeoForge jars, so a
universal Baritone jar cannot cover NeoForge until SP-1b lands, delaying part of SP-3's coverage.
The alternative was bolting an uncharacterised fifth backend into SP-1 with no spike, inside the
shared jar where a bootstrap that hard-fails off-platform breaks every other platform. Not worth it.


## Task 15's smoke row becomes Forge 1.21.11-61.1.5 (the build the spike verified) instead of
NeoForge 1.21.11. The matrix keeps five rows and SP-1's definition of done is unchanged in shape.
Cost if wrong: none; the NeoForge row moves to SP-1b where it belongs.


## Task 15's smoke test must register one real mixin and assert it applied. The spike inferred
"mixins apply" from classloader identity alone, which is suggestive but not proof, and it said so.
Cost if wrong: none, this only adds evidence.


## ML9 support is declared as Forge 1.21.x verified, Forge 1.17-1.20.x source-compatible but
UNVERIFIED, and that wording goes in the spec's coverage table rather than being left implicit.
`initializeLaunch` has only a two-argument form on the older `cpw.mods:modlauncher:10.0.9`, so
Task 15 resolves it reflectively across both arities. SP-1 does not add four more server installs
to prove the older range; the smoke matrix has one ML9 row by design. Cost if wrong: a user on
Forge 1.18 could hit an untested path. Mitigated by the honest wording and by the reflective arity
handling; the alternative is roughly quadrupling SP-1's smoke runtime for a range no consumer needs
until SP-3 folds those versions in.


## Task 15's brief uses `net.minecraftforge:modlauncher:10.2.4` and
`net.minecraftforge:securemodules:2.2.24` with the `TargetJvmVersion = 21` compileClasspath
override. Task 14's ML8 coordinates (`cpw.mods:modlauncher:8.1.3`) are a different artifact line
and are unaffected, but they are equally unverified and Task 14 must confirm them at dispatch.
Cost if wrong: a build failure at Task 15 or 14, caught immediately.


## the missing Javadoc on `Platform`'s two default methods is a valid Important finding, not a
style quibble. The zero-comment default carries an explicit carve-out for non-obvious *why* placed
on the declaration it explains, and nothing in those two signatures hints that they exist because
one loader cannot load a module when the others can. Fixed in round 1. Cost if wrong: two lines of
comment.


## `isExact()` and `isClosed()` move from Task 5 into Task 4, with tests. The plan has Task 5
Step 4 appending them to the `VersionRange` class that Task 4 creates, which splits one type's
definition across two tasks and two review seats for no benefit, and leaves Task 4's reviewer
looking at an incomplete type. They are `VersionRange`'s own behaviour and belong with it. Task 5
then only consumes them. Cost if wrong: none, Task 5's dispatch says they already exist.


## the `entrypoint` field moves from Task 11 into Task 5, for descriptor and manifest parsing
only; Task 11 keeps the kernel-side invocation. Same principle as the isExact/isClosed move: the
manifest format is Task 5's deliverable and a public contract that SP-2's Gradle plugin will
generate, so specifying it in one place and reviewing it as a unit beats having Task 11 silently
extend a format already declared finished. Cost if wrong: `entrypoint()` is read by nothing until
Task 11, six tasks later, so a reviewer could fairly call it an unrequested field. Mitigated by
stating in Task 5's dispatch that it is deliberate and why.


## the Important finding is a bug MY amendment 2 introduced, and it is worth the round. Making
the index comparator numeric changed `TreeSet`'s notion of equality, so `module.02` and `module.2`
compare as 0 and the second module is silently discarded - no exception, no duplicate-index error,
the module simply is not in `modules()`. The failure mode is the worst available here: selection
later fails and the rejection message lists only the survivors, so the module the author is
debugging never appears in the diagnostic that exists to explain the failure. Fixed by rejecting the
ambiguity loudly (two different raw tokens normalizing to the same key throws, naming both tokens)
rather than resolving it silently. Cost if wrong: a manifest that deliberately mixes padded and
unpadded forms of the same index is now rejected instead of half-working. That is the intended
behaviour; a consistently padded manifest (01..13) has no collisions and is unaffected.


## the unknown-`environment` test rides along in this round; the `optionalTrimmed` DRY helper
does not. Line drawn at risk, not at severity: a test addition cannot change production behaviour,
while the helper refactors parsing code that just reviewed correct, and mixing a refactor into a
round that is fixing data loss is how fix rounds produce new bugs. Cost if wrong: the duplication
survives to the final review, which is where I have pointed it.


## escalating the re-review's first Minor (a 3-line inline comment) to a fix round, against the
loop rule that Minors never extend the loop. CLAUDE.md does not treat this as taste: it names a 3+
line inline comment block as a smell and prescribes the remedy (extract, or a Javadoc @implNote on
the declaration). I have been placing that rule in the global constraints of every dispatch, so
leaving a clear breach in the code would make the rule decorative, and the cost is a comment move.
Directed to `indices()` as an @implNote, since the constraint explains the method's whole shape and
that is where a future reader would start if they tried to simplify it back to a comparator-driven
TreeSet. The message-wording nit rides along: same file, same round, zero risk.
Cost if wrong: one extra round spent on non-functional text.


## this round gets no reviewer seat. The diff is confined to comments and one message string,
neither of which is compiled into behaviour, so a reviewer would be verifying prose. I verify it
myself instead by confirming the diff changes no non-comment, non-string line and the build stays
green. Consistent with the precedent set at Task 1 for a commit-message-only fix. Cost if wrong: a
logic change could ride along inside a nominally comment-only diff, which is exactly what my
line-level inspection is checking for.


## the Important finding is valid and my brief's fault. The miss test uses a manifest whose
candidates all fail the platform check, so the version-range and environment rejection messages are
asserted by nothing. This task's stated deliverable IS the rejection message, so two of its three
branches were unverified. Fixed with tests asserting the specific declared range and declared
environment appear in the message. Cost if wrong: two test methods.


## escalating one of the Minors into the same round. `selectionIsStableAcrossRepeatedCalls`
proves idempotency, not stability: a per-call re-sort would pass it identically. The property I
actually relied on, and explicitly told the implementer not to break, is that manifest order is the
final tie-break because `List.sort` is stable. Nothing tested it, so a later swap to a non-stable
sort would pass the suite. Added a test with two candidates equal in specificity and priority,
asserting the earlier manifest position wins. Cost if wrong: one test method, and it pins a real
guarantee rather than an incidental one.


## fix now rather than deferring to the final review, and reopen Task 5 to do it. Deferring a
silent-failure defect for ten tasks risks it being triaged away, and SP-3's Baritone manifests will
be authored against this format. Reopening the original implementer is right because it owns the
file and its context is intact. Cost if wrong: strict unknown-key rejection could break forward
compatibility if a newer Nylium added a field and an older kernel read the manifest. Judged not a
real risk here, because SP-2's plugin generates the manifest and the kernel into the same jar, so
manifest/kernel version skew is close to impossible.


## the two inert `module.N.specificity=5` lines in Task 6's tie-break test stop being a
deferred Minor and must be deleted in the same change. Once unknown keys are rejected they would
make that test throw. The deferred Minor and the new Important turned out to be one issue, and
fixing either forces the other; that coupling is the reason this is not deferrable.


## resume the same implementer rather than escalating to a fresh one on a stronger model,
despite this being Task 5's fourth fix round overall and the cap being five. The escalation rule
exists because a loop surviving three resumes usually means the implementer cannot see its own
problem. That is not the situation: rounds 1 and 2 closed findings from the original review, round 3
closed a defect discovered later in a different task's review, and this is the first time the
implementer has failed an instruction I gave. Treating the reopen as a fresh work item, this is its
round 2, not the task's round 4. Cost if wrong: if this round also fails to close, I escalate then,
having spent one resume. Watching for non-convergence.


## prescribed a structural fix rather than accepting two matching lists. Every `module.*` read
routes through one helper that records the key it touched, and the unknown-key sweep then rejects
any `module.<index>.<field>` key that was never consumed. Recognition becomes derived from reading,
so drift is impossible in both directions: a field added to the reader is automatically recognised,
and a recognised-but-unread field cannot exist. `KNOWN_FIELDS` disappears. Chosen over named
constants shared by both sites, which would remove the duplicated literals but still permit the
harmful direction (a constant in the set that nothing reads is silently accepted and ignored, which
is the original bug for that one field). Cost if wrong: error ordering changes, since a missing
required field now throws before the sweep runs; called out explicitly so no existing assertion gets
weakened to accommodate it.


## the `finally`-masking finding is valid and is my amendment's fault. I asked for temp-file
cleanup on every failure path without accounting for a throwing `finally` replacing the exception
already in flight. The irony is that the amendment exists so a disk-full launch does not litter the
game directory, and disk-full is exactly the case where the cleanup delete also fails and buries the
original cause. Fixed by swallowing IOException from the cleanup. Chose plain `catch (IOException
ignored)` over `addSuppressed` because inside a `finally` there is no reference to the primary
exception without restructuring, and restructuring buys nothing here. Cost if wrong: a genuinely
undeletable temp file now goes unreported. Acceptable: it is housekeeping, and the primary failure
is what the user needs to see.


## both Minors ride along this round rather than being deferred. The `AccessDeniedException`
rationale is exactly the non-obvious *why* the project's comment policy carves out, and the reviewer
noted a maintainer would otherwise have to guess why a permissions exception is treated as a race
signal; the import tidy touches the same catch clause being edited. Same risk-not-severity line I
drew at Task 5: additions and text in code already under the knife ride along, refactors of settled
code do not.


## the prose-GREEN-evidence finding is being fixed rather than noted, because this is its third
occurrence in the plan. The point of verbatim output is that a reviewer can confirm a real run
without re-running the suite, and a hand-authored list of ticks is precisely what cannot be
confirmed. Directed at machine-produced output (`--console=plain -i`, or the JUnit XML testcase
listing). Cost if wrong: none.


## harden `fileName()` to split on either separator and reject an empty, `.` or `..` basename,
but explicitly NOT treat this as a security fix. Reaching it needs a jar whose entry name contains
backslashes, and anyone who can deliver that jar can simply put arbitrary code in the module Nylium
is about to load and run. It is also unreachable from a hand-written manifest, since `extract()`
calls `read()` first and an unmatched path fails there long before `fileName()`. So: robustness,
worth two lines now rather than a confused bug report later. No test required for the backslash case;
constructing such a jar entry is more machinery than the risk justifies. Cost if wrong: I have spent
two lines and one instruction on a path nobody hits.


## spending round 2 on the commit message's mood. `fix(core): exception cleanup, path traversal
hardening, and documentation` is a noun-phrase list where the binding instructions require
imperative mood. This is the third explicit CLAUDE.md rule breach in the plan (commit scope at Task
1, comment shape at Task 5, mood here) and I enforced the first two, so waving this one through
would be inconsistent. Amended in place, tree untouched. Cost if wrong: one resume on text.


## the `.jar`-only-basename behaviour change is accepted, not reverted. A module path whose
final segment is exactly `.jar` now throws where the pre-fix code silently wrote a cache file with
an empty name prefix. The re-reviewer judged this consistent with the mandate to reject an empty
result and I agree: a nameless module is a malformed manifest, and a clear diagnostic beats silently
producing an oddly-named file. Reachable from a hand-written manifest, unlike the backslash case, so
it is a real change rather than a theoretical one. Cost if wrong: someone with a genuinely
name-less module entry is now rejected instead of served; no realistic build produces that.


## the rejection message's parenthetical is being corrected in the same round. It reads "path
ends with separator or is empty", which describes only one of the two routes now that a
suffix-collapse also lands there. This is the message a third party reads when their build emits a
bad manifest, and this plan has treated message accuracy as a deliverable throughout. Cost if
wrong: a string edit.


## concern 1 accepted, and it is a defect in my brief. The brief's `MarkerClassProbe` test built
an anonymous ClassLoader returning `Object.class` from `findClass`, which is incompatible with the
brief's own production call `Class.forName(name, false, loader)` on JDK 21, since that verifies the
resolved class name against the requested one. The implementer switched to `loader.loadClass(name)`
and reported it instead of hiding it. Correct on the merits too, not merely convenient:
`loadClass(String)` delegates to `loadClass(name, false)`, so it neither links nor initialises, and
initialising a marker class would run a Minecraft registry class's static initialiser during early
startup - far worse than a failed probe. Cost if wrong: none identified.


## concern 2 accepted. Closing the `URLClassLoader` in the test is a harness correctness fix;
Windows holds the jar open otherwise and `@TempDir` cleanup fails after assertions have passed. No
assertion changed.


## the `net.minecraft.block.Blocks -> 1.9` marker is REMOVED, not merely doubted. The
implementer suspected it; I checked and it is backwards. The 1.13 flattening moved this class:
`net.minecraft.init.Blocks` is the 1.7-1.12 name, `net.minecraft.block.Blocks` is 1.13-plus. So my
entry maps a post-flattening class to a pre-flattening floor. It is harmless today only by accident
of ordering - markers are consulted newest-first and `net.minecraft.util.registry.Registry` matches
first on 1.13-plus so the wrong entry never fires - which is exactly the kind of latent trap worth
removing: one reordering or one added marker away from reporting a 1.13 runtime as 1.9 and selecting
a module for the wrong era. Nothing is lost functionally since it never matched what it claimed to.
Cost if wrong: if some namespace does expose `block.Blocks` at 1.9, that runtime now falls through to
the `init.Blocks` floor of 1.7.10, which lands in the same LaunchWrapper module range anyway.


## the two-entry table's coarseness gets written down as an `@implNote` rather than left implied.
The probe identifies an ERA FLOOR, not a version, so a caller needing precision within 1.7-1.12 must
not depend on it and the module ranges that rely on it are deliberately broad. This is now the honest
description of the design, and Task 13's Forge 1.7.10 smoke test is what will confirm the surviving
`init.Blocks` entry empirically.


## round 2 for the `// empty` block-label comment. One line, but the comment rule says NEVER
label a block, and I have already held rounds over comment shape (Task 5) and commit mood (Task 7).
Enforcing two and waving the third through would make the enforcement arbitrary rather than a rule.
Cost if wrong: one line of text.


## the unparseable-branch message asymmetry is fixed rather than deferred, despite being a
plan-mandated Minor, because of which path it is. "Probe threw" is rare; "found a version Nylium
cannot read" is the likely real-world case, since it is what a snapshot produces. `McVersion.parse`
already generates exactly the sentence the user needs (release versions only, not snapshots or
pre-releases) and the chain was discarding it, so the one message shown when the game will not start
was withholding the reason. A string concatenation is a poor thing to trade that for. Cost if wrong:
a slightly longer failure message.


## do NOT rewrite history now; do it as the last step before the workspace is deleted. Two
reasons. `git rebase -i` is unavailable in this environment, so a rewrite means `filter-branch
--msg-filter`, which rewrites every SHA on the branch - and every commit range recorded in this
ledger is the recovery map if my context is compacted. Trading the recovery map for six stylistic
subjects mid-plan is a bad deal. At the finish step the workspace is deleted anyway and git history
becomes the record, so the SHA churn costs nothing there. Cost if wrong: if the session dies before
the finish step, six commits keep non-imperative subjects on an unpushed local branch, which the
user can fix in one scripted pass.


## stop propagating it. For Tasks 10-16 I override the plan's commit-message text at dispatch
with imperative wording rather than letting the implementer copy a defective brief and then spending
a fix round undoing it, which is what happened at T7 and T9.


## the Critical is valid and is a gap against my brief's own requirement text. The brief names
annotations as an equally-breaking leak vector alongside supertype, interface, field, generic and
throws; every one of those is implemented and tested, and annotations alone are neither. An
unscanned vector is worse than no scanner, because the whole point of this task is assurance about
the single guarantee the library rests on. Note the mechanical cause: `visitField`/`visitMethod`
return `null`, so nested annotation visits never occur. Cost if wrong: none.


## annotation VALUES referencing a Minecraft class constant (`@Foo(Level.class)`) are
explicitly out of scope, recorded rather than left ambiguous. Only a consumer reflecting over the
annotation would care, it is far-fetched, and covering it means recursively walking
`AnnotationVisitor.visit`/`visitArray` for marginal return. Cost if wrong: a leak via annotation
value passes the check; acceptable given how contrived that shape is.


## class-level visibility gating is fixed despite being currently unreachable. The reviewer's
argument is the one I would have made myself: `nylium-api` cannot compile against Minecraft at all,
but if that were sufficient reasoning it would equally excuse not writing the scanner. Gating class
checks on the same predicate the members use makes the semantics consistent and the scanner reusable
for SP-4 and SP-5, which will add API modules that may sit on a Minecraft-adjacent classpath. Cost if
wrong: a non-public class's Minecraft supertype goes unflagged, which is correct by the design intent.


## `ApiPurityTask`'s I/O logic gets extracted to a static function and tested directly, rather
than pulling Gradle's `ProjectBuilder` into the test suite. The task is the part that runs in CI and
was covered only by a manual run captured in a report. Extraction also separates I/O from Gradle
wiring, which is better structure independent of testability. Cost if wrong: one more small static
method in the scanner's file.


## RED evidence is mandatory for this round specifically, not just preferred. A test for a
vector that was previously unscanned proves nothing unless it is seen to fail before the scanner
changes; a test written after the fix could assert almost anything and pass.


## parameter annotations (`visitParameterAnnotation`) get closed now, not deferred, and the
reasoning differs deliberately from my earlier exclusion. I ruled annotation VALUES out of scope
because covering them needs recursive `AnnotationVisitor` walking for marginal return. Parameter
annotations are one more override on a `MethodVisitor` that now exists, with no recursion, so
"cover what is cheap to cover" applies and the cost/benefit is not comparable. Leaving one named
vector open in a check whose only purpose is assurance reproduces the original Critical in miniature.
Cost if wrong: one override and one test.


## the report's third GREEN block must be corrected, not merely noted. Its pasted output shows
`:buildSrc:compileJava UP-TO-DATE` while the surrounding narrative claims a main-source change was
made just before that run, which cannot both be true. The code is not in doubt (I verified the check
executes and the 14/14 count independently, and the re-review verified every finding), so this is a
report-accuracy issue rather than a false claim about behaviour. It still matters: the entire reason
this plan demands machine-produced output is so a reviewer can trust it WITHOUT re-running
everything, and a staged narrative that contradicts its own evidence destroys exactly that property.
This is also the second evidence irregularity in one report from this agent, the first being the
fabricated line it self-caught, so the standard tightens rather than relaxes. Told it that admitting
"captured out of order" is far more useful than an unreconcilable story.


## round 2 gets no reviewer seat, verified by me instead. The diff is 18 insertions across
exactly two files; the new `visitParameterAnnotation` sits inside the same anonymous `MethodVisitor`
that already carries `visitAnnotation` and `visitTypeAnnotation`, so it inherits the identical
`isPublicSurface` early-return gating the re-review verified one round ago; the test compiles a real
fixture and asserts findings are non-empty, consistent with its siblings. A full seat for a
pattern-identical override in an already-verified visitor is waste. Cost if wrong: a gating mistake
in one override; mitigated by the fact that gating is positional here, not repeated per override.


## hand this to the task reviewer as a named risk with the raw observation rather than
diagnosing it myself. My leading hypothesis is that the `moduleJar*` tasks do not declare the
generated id text as a tracked input, so Gradle can consider them up-to-date and leave jars built
during an earlier iteration (before the `.asFile()` fix) in place. But that is a hypothesis, the
reviewer has the task definitions in front of it, and stating a conclusion I have not established
would be worse than stating the evidence. Cost if wrong: one reviewer examines an input declaration
that turns out to be fine, and the anomaly is attributed to iteration debris.


## fix by generating the id into a stable deterministic per-id path under
`layout.buildDirectory` with `outputs.file(...)`, NOT by adding `inputs.property("moduleId", id)`.
The stable file fixes the input tracking and also removes the unbounded `build/tmp/resource` debris
the review raised as a separate minor; the property fixes only the up-to-date check and leaves the
non-deterministic `from()` source. Told the implementer explicitly not to add both, since a
content-hashed stable path makes the property redundant noise. Cost if wrong: slightly more build
script than a one-line property.


## required four pasted build-behaviour verifications rather than a unit test - fresh build with
ids listed, an unchanged rerun showing UP-TO-DATE, deletion of one generated id file showing the task
reruns rather than leaving a stale jar, and confirmation no new temp debris appears. This is
incremental-build behaviour; no unit test in this project can catch it, and the failure mode is
precisely a jar that looks fine while being wrong. Automating it would need Gradle TestKit, which is
heavier than the risk warrants at this stage.


## the `callOrder.add("entrypoint")` Minor rides along. It converts an ordering assertion that
currently leans on reading the implementation into one that stands on its own, for two lines, in a
test file already being touched. Same risk-not-severity line I have drawn since Task 5.


## no reviewer seat for this round; I verified it empirically instead, which is stronger
evidence than diff-reading for an incremental-build fix. Ran five scenarios myself: fresh build from
cleared modules (all four ids correct and distinct), unchanged rerun (all tasks UP-TO-DATE), deletion
of one generated id (generator reran, jar stayed UP-TO-DATE because the regenerated content hashes
identical, jar content correct), corruption of one generated id (generator detected and repaired it,
jar correct), and temp-debris check (none). Diff confined to two files and matches the direction
exactly: stable `generated/moduleId/<id>/` path with `outputs.file`, `resources.text` machinery gone,
no redundant `inputs.property`.


## resumed with instructions to run the smoke tests in the FOREGROUND, splitting per test with
`--tests` if a wall-clock limit forces it, rather than backgrounding again. Backgrounding is what
stalled it, and a subagent cannot be woken by its own detached job here. Cost if wrong: a long
foreground run occupies the agent, which is the correct trade when the alternative is a stall.


## fix both Important findings NOW rather than deferring to Tasks 13-15 where the reviewer said
they could be addressed. Both seams live in files Task 12 owns, both would arrive at Task 13 as
blockers rather than as choices, and a brief written for Task 13 would simply rediscover them at
higher cost. Cost if wrong: one extra round on a task already Approved.


## the toolchain fix passes per-version launcher paths as system properties rather than
splitting `:smoke:test` into one `Test` task per Java cohort. Gradle allows exactly one
`javaLauncher` per `Test` task, so the cohort split was the reviewer's other option, but the test JVM
does not need to match the target at all - only the SPAWNED SERVER does. Keeping one test task and
telling it where each JVM lives is less machinery and scales to any number of target versions.
Also directed lazy resolution so a missing Java 8 toolchain fails the smoke run with a clear message
instead of breaking the configuration phase of every ordinary build. Cost if wrong: if a future smoke
test needs the TEST JVM itself on an old Java, the cohort split becomes necessary after all; nothing
in 1.7.10-1.21.11 suggests that.


## gate the dependency EDGE at configuration time, not the tasks. My original amendment fixed
the symptom rather than the shape: `onlyIf` gates an action and never an edge, which is why the same
gap reappeared one level down at `dependsOn ':nylium-testmod:universalJar'`. Wrapping the
`dependsOn` in `if (project.hasProperty('nyliumSmoke'))` removes the edge entirely when smoke is off,
so nothing downstream is dragged in. Explicitly refused to put `onlyIf` on `universalJar` itself,
which would make an explicitly requested `./gradlew :nylium-testmod:universalJar` silently skip -
worse than the cost being fixed. Cost if wrong: none identified.


## no reviewer seat for this round; verified empirically, which is stronger than diff-reading
for changes whose whole substance is build-graph shape and live server behaviour. Two checks, both
mine:
  1. `./gradlew build --offline` now contains ONLY `:smoke:test SKIPPED` - no provisionFabricServers,
     no universalJar, no moduleJar*, no generateModuleId*. Absent from the graph entirely, not
     merely skipped, which is what gating the edge rather than the actions buys.
  2. Deleted both marker files, then ran `:smoke:test -PnyliumSmoke --rerun-tasks` forcing full
     re-execution: BUILD SUCCESSFUL in 12s, both markers freshly written as [module=1.21.10] and
     [module=1.21.11]. Deleting first mattered - it is the same trap I fell into at Task 11 with a
     mistyped path, where a leftover file would have produced a reassuring result that proved
     nothing.
Task 12: complete (commits 4fcbd55..5a0dd55, review clean, 2 parked minors)


## fix the silent-inertness finding, and note precisely why it is the one place the deviation is
weaker than what it replaced. A tweaker's `injectIntoClassLoader` ALWAYS runs; a transformer firing
on a specific class runs only if that class loads. The trade of an unconditional hook for a
conditional one was correct because it bought correct timing, but the conditionality has to become
observable. Required both halves: a confirmation line naming the selected module on success, and a
JVM shutdown hook warning if boot never happened. The shutdown hook fires too late to save the
session, which is exactly the point - it converts an unexplainable "the mod did nothing" report into
a one-line answer. Directed `System.out`/`System.err` rather than a logging framework, since
`nylium-core` carries zero dependencies by design and a stage-zero bootstrap depending on the host's
logging setup is the fragility this project keeps avoiding. Cost if wrong: two printed lines.


## client-side entry path stays UNVERIFIED and deferred, not closed. Launching a headless
Minecraft client is disproportionate to the risk, and the path is architecturally symmetric with the
server one through the same class-probe mechanism. Cost if wrong: a client-side regression would go
unnoticed by CI. Recording it rather than pretending it is covered.


## fix the harness flake now rather than carrying it into Tasks 14 and 15. This harness is the
instrument behind every test in SP-1's definition of done - two Fabric, one LaunchWrapper, two more
coming. A flaky instrument does not just cost retries: once a suite is known to flake, a GENUINE
dispatch regression gets dismissed as "that flake again", which defeats the entire purpose of the
matrix. Cost if wrong: one round spent on a race that might not recur.


## fix in the WRITER, not the reader. Directed an atomic temp-file-plus-`ATOMIC_MOVE` write in
`TestModEntry`, following `ModuleExtractor`'s existing precedent in this codebase, which already
handles `AtomicMoveNotSupportedException` and Windows surfacing a lost race as
`AccessDeniedException`. Explicitly forbade also adding re-poll tolerance in the harness: that would
paper over the same race from the other end, and two overlapping mechanisms for one problem is how
the next reader loses track of which is load-bearing. One correct writer beats a forgiving reader.
Same principle as refusing the redundant `inputs.property` at Task 11.


## no reviewer seat. Diff confined to one method in `TestModEntry`, following a pattern already
reviewed clean in this repo, and validated empirically far beyond what diff-reading could establish:
six consecutive green runs by the implementer, plus my own independent run with all three markers
deleted first and `--rerun-tasks` forcing full re-execution. All three rewritten correctly.
Task 13: complete (commits 5a0dd55..01d2601, review clean, 2 parked minors)


## do NOT attempt the retarget in this task; record ML8 as "dispatch verified, game-class
visibility unresolved" and require a spike before any consumer ships on 1.13-1.16. The decisive
reason is verifiability: the smoke test's entrypoint writes a marker and never touches a game class,
so it passes identically whether a retarget works or not. A fix that cannot be distinguished from a
non-fix is a guess wearing a commit message. Confirming the reviewer's suggested route
(`additionalClassesLocator`/`additionalResourcesLocator` for visibility, a companion
`ILaunchPluginService` as ML8's analogue of ML9's `initializeLaunch`, `moduleClassLoader` returning
the real game loader) requires first building a module that genuinely touches a Minecraft class and
mixins into one. That is the same weight as the Task 2 spike. The honest reading is that ML8 needed
its own spike and my plan never budgeted one - my omission, not the implementer's.
Cost if wrong: SP-3 cannot ship Baritone coverage for 1.13-1.16 until that spike lands. Accepted,
because the alternative is shipping a backend I have been told cannot work for its actual purpose.


## the other three backends are NOT implicated, and it is worth saying why rather than assuming
symmetry. Fabric adds to Knot via `FabricLauncherBase.addToClassPath`, which IS the game loader.
LaunchWrapper adds to `Launch.classLoader`, which IS the transforming loader, and its boot fires
from inside a transformer on `MinecraftServer` - so Minecraft classes are demonstrably present by
then. ML9's spike established `Layer.GAME` plus `initializeLaunch` deliberately. ML8 is uniquely
wrong because it is the one generation where discovery and game loading are separate sibling
instances and the hook fires before the second exists.


## fix only the missing success-confirmation line now. It matters more on this backend than any
other precisely BECAUSE visibility is unresolved: when someone debugs a module that loads but cannot
see game classes, the first fact they need is that dispatch itself succeeded. Reviewer also
confirmed the failure paths are already sound (no exception table around `onLoad`, so a thrown
`NyliumException` crashes the bootstrap loudly rather than being swallowed).


## overrode the implementer's rationale on the duplication finding and sided with the reviewer.
It avoided sharing `writeAtomically` to keep from touching already-proven code, which is an instinct
I have honoured elsewhere in this plan. But both files sit in the same Gradle module, so extraction
moves a private helper and updates two call sites without altering `TestModEntry`'s tested logic at
all. Carrying two copies of an atomic-write routine that must stay in agreement is the larger risk.
Cost if wrong: a ~20 line refactor in test-support code.


## both Minors ride along. The silent `mods.toml` omission is almost certainly correct, but it
is the ONE deviation in this diff without an `@implNote` justifying itself, and consistency of that
habit is what makes the habit worth having. The launch plugin's missing self-check is empirically safe
but coupled; a direct check is independently auditable for two classes that now share one services
file.


## `uses: ...@main` is NOT a breach of the no-hardcoded-branch rule, and the reviewer reached
that independently rather than deferring to my read. The rule targets a workflow referencing its OWN
repo's branch model and taking the default and development branches as inputs; a `uses:` ref names a
different repository's reusable workflow and a ref is mandatory syntax there, so it is a dependency
version pin. `main` is `intisy/workflows`'s verified actual default. A SHA pin would be stronger
supply-chain hygiene and is recorded as a Minor, not required by any stated rule.
Task 16: minor (deferred): `@main` rather than a tag or SHA pin on both callers.
Task 16: minor (deferred): `readme.yml` redundantly passes `repository:` which is already the
reusable workflow's default; harmless, arguably clearer.
Task 16: complete (commits c0be120..8135191, review clean, 2 parked minors)


