# SP-2b design: content-addressed dedupe, and the conformance mod that proves it

**Written 2026-09-06.** This is sub-project **SP-2b**, taken from the two candidate designs recorded
in the handoff's "The duplication gap" section. The owner picked candidate 1, content-addressed
dedupe, and asked for a mod exercising every Nylium feature to be built first and applied to
Baritone afterwards. Candidate 2, the SP-5 Minecraft facade, remains a separate program and is not
touched here.

## The problem, restated from measurement

`NyliumKernel.boot()` selects exactly one module, extracts it and classpaths it. There is no shared
layer, so every module jar must be self-contained: a consumer targeting N Minecraft versions ships N
complete copies of itself inside one universal jar. For Baritone at 18 targets that is 18 copies.

Measured on Baritone's two built Stonecutter nodes, 247 of 261 compiled classes (94.6%) are
byte-identical between 1.21.10 and 1.21.11. The ratio falls as versions grow apart, but the
mechanism does not: whatever is byte-identical is duplicated storage today.

This design attacks duplicate **bytecode**. It does not reduce duplicate **source**; that is SP-5's
job, and the two are complementary rather than alternatives.

## Approach chosen, and the two it beat

Three shapes were considered. All three win the same bytes; they differ in what they cost at
runtime.

**A. Shared jars on the classpath.** Group entries by their exact owner set, one shared jar per
group, and have the kernel classpath a module's shared jars alongside the module.

This was the handoff's sketch and it is feasible. The objection expected against it, that two
automatic modules in one ModLauncher 9 layer cannot share a package, does not apply: `SecureJar.from`
is varargs (`from(Path...)`), verified against `net.minecraftforge:securemodules:2.2.24`, so several
paths become one module with one name and `findLoader(name)` keeps working.

It was still rejected. It changes the runtime contract on all four backends, and every piece of
evidence in the handoff was gathered against a one-jar-per-launch world. Mixin resolution,
ModLauncher 8's reflective `addURL`, and ModLauncher 9's union filesystem each gain a degree of
freedom that would have to be re-proven.

**B. Content-addressed object store, re-merged at extraction. Chosen.** Store each distinct entry
once; each module becomes an index; the kernel rebuilds one jar in its cache. Identical storage win
to A with no grouping heuristic, and the runtime shape is unchanged: one jar, one classpath entry,
exactly what the five-server matrix already proves. No bootstrap, `Platform`, Mixin or classloader
behaviour changes. The cost is a rebuild on first launch of a given module, which is bounded and
measured rather than assumed.

**C. One shared jar for the all-modules intersection.** The YAGNI baseline. Trivial, but it shares
only what every module has in common, which for Baritone's real spread (1.16.5 against 1.21.11) is
close to nothing. It underdelivers precisely where the duplication is worst.

## Wire format

The universal jar gains one directory and one file kind.

```
nylium/objects/<sha256hex>        one blob per distinct entry content, no extension
modules/<prefix>-<name>.index     the module's entry list
```

Blobs carry no extension deliberately: nothing on any loader's classpath scan can mistake one for a
class or a resource.

The index is line-oriented UTF-8. A type character, then fields, with the entry name as the rest of
the line, so a name containing spaces needs no quoting:

```
# nylium-index 1
f <sha256hex> <entryName>
d <entryName>/
```

The header pins the format version so a future kernel rejects a format it does not know instead of
misreading it. Lines are in the source jar's entry order, so a rebuild reproduces that order. An
entry name is written exactly as the jar holds it, never rewritten, so a directory's trailing slash
comes from the jar rather than from the writer. Directory entries are represented because Gradle's
`Jar` emits them and a faithful rebuild should too; they have no content and therefore no blob. An
entry name containing a newline is rejected at build time, named, rather than silently corrupting
the index.

`module.N.path` in `nylium-modules.properties` points at either a `.jar`, today's shape, or a
`.index`. There is deliberately no new manifest key: the extractor branches on the suffix. The
plugin embeds `nylium-api` and `nylium-core` at exactly its own version, so the kernel reading a jar
is always the kernel that shipped with the plugin that wrote it: new-kernel-plus-old-jar is the only
reachable direction and it works, and old-kernel-plus-new-jar cannot arise. Lockstep embedding, not
suffix dispatch, is the actual compatibility guarantee here; the real payoff of dispatching on the
suffix is that `dedupe = false` needs no kernel change at all.

The rebuild deliberately does not preserve every zip metadata a source module jar may have carried:
entry timestamps (every rebuilt entry becomes 1980-01-01), the compression method (`STORED` becomes
`DEFLATED`), extra fields, entry comments, and local-header ordering where it diverges from
central-directory ordering. None of these carries meaning for a module jar built by this plugin.
Signing is unaffected: a JAR signature digests uncompressed entry content, which the rebuild
preserves exactly, never the compression method, timestamps or header order.

## Kernel changes

Confined to `ModuleExtractor`. `NyliumKernel.boot`, `ModuleSelector`, `ModuleManifest`,
`ModuleDescriptor`, `Platform` and all four bootstraps are untouched.

- A `.jar` path takes today's code path unchanged.
- A `.index` path is read, and the cache file name derives from **the index's own digest**, not the
  rebuilt jar's, so cache identity does not depend on zip-writing determinism.
- Each blob is streamed from `source.getResourceAsStream("nylium/objects/<hash>")` into a jar in the
  cache, reusing the existing atomic-move handling including its Windows `AccessDeniedException`
  race case.
- A missing blob raises a `NyliumException` naming both the entry and the hash.
- No runtime hash verification. A blob's name IS its content hash, so a wrong blob is
  unrepresentable: any resource found at `nylium/objects/<hash>` necessarily has that content, on
  any loader, even when two Nylium-based mods share a classloader and `getResourceAsStream` resolves
  the blob from the other jar's copy. The only reachable failure is a missing blob, which
  `ModuleAssembler` already reports naming both the entry and the hash.

## Plugin changes

- A new `NyliumDedupeTask` takes the module jars as `@InputFiles` and emits `objects/` plus one
  `.index` per module into an `@OutputDirectory`, hashing every entry once.
- `fillUniversalJar` copies `objects/` to `nylium/objects` and the indexes to `modules/`.
- `ResolvedModule.path()` carries the `.index` name when dedupe is on.
- `ModuleJarInspector.verify` still runs against the original module jars and is unaffected.

Control is `nylium { dedupe = ... }`, a `Property<Boolean>` defaulting to on for two or more modules
and off for one, where an index is pure indirection. The toggle is an escape hatch on purpose:
dedupe changes the runtime path, and a consumer hitting trouble in the field needs a one-line route
back to the shape the smoke matrix proved.

**`DifferentialTest`'s fixture pins `dedupe = false`.** It builds a five-module project and compares
byte for byte against `nylium-testmod`'s hand-rolled reference; an on-by-default dedupe would change
the plugin's output and break the acceptance gate. The pin carries an `@implNote` saying why, since
it otherwise reads as an oversight.

## The conformance mod

`nylium-conformance`, a new sibling subproject built **by** the Gradle plugin. This also gives the
plugin the second real consumer the handoff wants before anything retires `nylium-testmod`'s
hand-rolled `universalJar`. That testmod is not modified; it stays the differential reference, and
replacing it would make the plugin compare against itself.

Source is split so dedupe has something real to collapse: `src/main/java` is shared bulk compiled
into every module, and a small `src/module<Id>/java` per module stays unique.

Modules cover all four platforms, the adjacent-Fabric pair that discriminates dispatch from mere
loading, an exact version against a ranged one, a priority tie, a declared `CLIENT` module that must
lose to the `SERVER` one on a server, and the ModLauncher 9 mixin config.

The entrypoint writes a properties report rather than a single-line marker, so the smoke tests assert
per feature instead of "a marker appeared":

| Key | What it proves |
| --- | --- |
| `module` | which module the kernel actually selected |
| `entrypoint` | the entrypoint was invoked |
| `sharedClass` | a class dedupe collapsed actually loaded, rather than being folded away at compile time and never referenced |
| `uniqueClass` | this module's unique source survived the merge |
| `loader` | the platform, inferred from visible loader classes |
| `mcClass` | `reachable` or `unavailable`, per backend |

The ModLauncher 9 mixin config registering and applying is proved separately, by a `mixin` key in a
different file, `nylium-conformance-mixin.properties`, not by a key in this report.

`sharedClass` and `uniqueClass` prove dedupe restored the right content for the shared and the
per-module case respectively. The merge's end-to-end proof is broader than these two keys alone:
`ConformanceEntry`, `LoaderProbe`, `McClassProbe` and `ReportWriter` are themselves collapsed shared
blobs, and this whole report existing at all is evidence they demonstrably executed.

`mcClass` asserts the **known** state per backend rather than a uniform expectation: `reachable` on
Fabric, LaunchWrapper and ModLauncher 9, `unavailable` on ModLauncher 8. Encoding limitation (1) as
an assertion means the day it is fixed the suite reports it, instead of staying quietly green either
way.

A module cannot ask which platform it is on or what version was detected: the kernel hands that to
the bootstrap, not to the module. That is SP-4's job. Conformance infers the loader from visible
classes rather than widening the API for it, and the gap is recorded here rather than fixed.

## Verification plan

**Phase 0, control.** Build the conformance mod against the current plugin, dedupe absent, and run
the five-server matrix. This proves the mod before dedupe exists, so a later failure is attributable.
Skipping it means debugging the mod and the format at once.

**Phase 1, kernel units.** Index round trip; rejection of an unknown header and of an entry name
containing a newline; `ModuleExtractor` rebuilding a single module declared as an `.index` and
asserting its entry names and order; a missing blob raising a named exception; the cache-hit path
not rewriting. The stronger entry-for-entry-equal claim, same names in the same order with the same
content, is covered separately, in `nylium-gradle`'s `DedupeWriterTest` against a two-module
fixture, not here against a three-module one.

**Phase 2, plugin functionals**, following the existing functional-test patterns including
configuration cache and up-to-date behaviour. Object count equals distinct entry-content count; every
index reconstructs its module entry for entry, meaning the same names in the same order with the same
content, which is the strongest claim available since zip metadata such as timestamps and compression
level is not reproduced and carries no meaning here; a deduped jar is strictly smaller than the same
jar undeduped; `dedupe = false` reproduces today's output exactly, which is what `DifferentialTest`
now rests on; a single module defaults to no dedupe.

**Phase 3, acceptance.** The conformance mod with dedupe on, across all five servers, asserting every
report key. Run with `--rerun-tasks` and the markers deleted first, per the handoff's trap. The
harness also gains a `mods/` clear before each install, which is the real fix for the stale-jar trap
that made the Rutter to Nylium rename look like a kernel regression.

**Phase 4, measurement.** Baritone's two common node jars both build today, so run the dedupe task
over them and record the actual ratio in this spec and in both repos' handoffs. Evidence, not a test,
since it reaches into another repo.

## Invariants that must survive

- **Java 8 bytecode everywhere.** New kernel code lands in `nylium-core`, which shares a
  `META-INF/services` file with a ModLauncher 8 game. No `List.of`, no `var`, no records.
  `checkClassFileVersion` enforces it.
- **`nylium-api` exposes no `net.minecraft` type.** Untouched by this work; `checkApiPurity` is
  unaffected.
- **Zero runtime dependencies and no logging in `nylium-api` and `nylium-core`.** SHA-256 comes from
  `MessageDigest`, which `ModuleExtractor` already uses.
- **A plain `./gradlew build` must not download servers.** Smoke provisioning stays gated on the
  dependency edge.

## Risks, each to be measured rather than assumed

- **First-launch rebuild cost.** Roughly 260 entries for a Baritone-sized module. Expected well under
  a second; measure and record.
- **Blob lookup volume on ModLauncher 9.** Around 260 `getResourceAsStream` calls replace one, on the
  service-layer loader. The single-jar read there is proven, the volume is not.
- **Index and objects drifting apart.** A build bug would produce a jar that fails only at runtime.
  Caught by the Phase 2 reconstruct-and-compare test, which is why no runtime verification is needed.
- **Two mods in one server's `mods/` directory.** The handoff records this failing hard on
  ModLauncher 8, where `ServiceLoader` instantiates every entry in the shared services file. The
  `mods/` clear in Phase 3 is a prerequisite, not a nicety.

## Measured result

**Conformance jar (Task 8).** Deduped 78,217 bytes against undeduped 104,036 bytes, 24.8% smaller,
17 objects across 8 modules. This is modest, and it should be: the conformance mod's shared source
is a handful of small classes, so per-blob overhead (below) eats a real share of the win at this
scale. Accepted as the honest figure for this mod rather than tuning the fixture to inflate it.

**Baritone (Task 10, Step 1).** Both common Stonecutter nodes were built for real
(`./gradlew :common:1.21.10:build :common:1.21.11:build`, both `BUILD SUCCESSFUL`) and the two
node jars were measured directly with `DedupeWriter`, a throwaway test that is not part of this
repository (it depends on another repo's build output and was deleted after the measurement):

| Jar | Size | Entries (incl. directories) |
| --- | --- | --- |
| `common/versions/1.21.10` | 835,252 bytes | 533 |
| `common/versions/1.21.11` | 836,121 bytes | 534 |
| **Combined** | **1,671,373 bytes** | **1,067** |

| Store | Value |
| --- | --- |
| Object store total (raw blobs on disk, uncompressed) | 1,961,552 bytes |
| Distinct blobs (object count) | 494 |

The object-store total is **larger** than the two jars combined, by about 17.4%
(1,961,552 against 1,671,373). This is not a regression in the mechanism: the object store here is
raw, uncompressed bytes on disk, while the two source jars are zip-deflated. Comparing an
uncompressed store against two compressed jars understates dedupe and is not the number a real
universal jar would ship at; a real build re-compresses the deduped blobs into the final jar the
same way the conformance measurement above did, where the size comparison is apples to apples
(deduped jar vs undeduped jar, both compressed the same way).

The apples-to-apples figure the brief calls for is blob count against summed entry count: **494
distinct blobs against 1,067 total entries**, meaning 46.3% of entries are unique content and the
remaining 53.7% are duplicates that collapse to a shared blob. Of the 1,067 entries, 132 (66 per
jar) are directory entries, which carry no content and therefore produce no blob regardless of
dedupe; restricting the comparison to the 935 file entries gives 494 distinct blobs against 935
file entries, 52.8% unique. Either way, roughly half of Baritone's per-version file content is
byte-identical across these two adjacent versions, consistent with the handoff's earlier
class-level measurement of 94.6% byte-identical compiled classes for this same pair (that number
counted only compiled `.class` files; this one counts every jar entry, including resources,
manifests and refmaps, which duplicate less consistently than compiled bytecode does).

**The headline figure: how much smaller is the actual jar.** The entry-count ratio above answers
"what fraction of entries are duplicates"; it does not by itself answer "how much smaller is the
jar", which is the question the owner actually asked ("using Nylium I want no duplicate code for
the versions"). That number is measurable today without SP-3, because the plugin consumes arbitrary
pre-built module jars: a throwaway two-module consumer project (`plugins { id 'base'; id
'io.github.intisy.nylium' version '0.1.0-SNAPSHOT' }`, a `repositories` block pointing at the root
`build/test-repo`, and two `module(...)` blocks whose `jar = file(...)` pointed straight at
Baritone's two built node jars, declared `FABRIC` with `minecraft = '1.21.10'` / `'1.21.11'` so the
declaration validates; nothing is launched) built `nyliumUniversalJar` twice, once with
`dedupe = false` and once with dedupe on (the default for two modules), from the same two node
jars measured above. Built under a system temp directory, never inside either repository, and
deleted after measuring.

| Build | Universal jar size |
| --- | --- |
| Undeduped (`dedupe = false`) | 1,594,345 bytes |
| Deduped (default) | 1,057,001 bytes |
| **Difference** | **537,344 bytes, 33.7% smaller** |

Both jars are zip-compressed by the same `nyliumUniversalJar` task, so this is the apples-to-apples
comparison the raw object-store number above could not make. The deduped jar carries 494 distinct
blobs (matching the object count measured directly with `DedupeWriter` above), and is **33.7%
smaller** than the same declaration built with dedupe off. This is the number that directly answers
the duplication question: a substantial, real reduction, consistent with 53.7% of entries collapsing
against a per-blob overhead of roughly 234 bytes that is negligible next to Baritone's
multi-kilobyte compiled classes. For reference, the two source node jars measured above total
1,671,373 bytes combined; the deduped universal jar (1,057,001 bytes) is smaller than either
individual node jar copied twice would be, and the undeduped universal jar (1,594,345 bytes) is
close to, but not identical to, the two node jars' combined size, because the universal jar adds
its own manifest and `fabric.mod.json` on top of the two embedded module jars.

**Caveat carried over from the handoff, and applying to the 33.7% figure above too:** 1.21.10 and
1.21.11 are adjacent versions, so this ratio is unusually favourable. A distant pair, such as 1.16.5
against 1.21.11, would show far less byte-identical content, and the mechanism's win would be
correspondingly smaller. The measurement
above is real, but it is a best case, not a typical one.

**The per-blob overhead floor.** Each distinct blob costs roughly 234 bytes of fixed zip metadata
once it is packed into a real jar: a blob's entry name is `nylium/objects/` plus 64 hex characters,
79 characters total, and zip stores the entry name twice, once in the 30-byte local file header and
again in the 46-byte central directory header (`(30 + 79) + (46 + 79)`, 234 bytes per distinct blob
beyond its own content). This means the dedupe win scales with entry
size and duplication count: it is largest for large, highly duplicated entries (Baritone's compiled
classes) and smallest, or even negative, for many small entries (the conformance mod's few tiny
shared classes; see Task 5's ruling R11, where a fixture with a single 12-byte shared payload
produced a deduped jar that was legitimately *larger* than the undeduped one, because three
79-character blob names outweighed two tiny module jars). A module made of very many very small
entries could grow rather than shrink under this scheme. The untaken mitigation is a shorter hash
prefix with collision handling, which would shrink the per-blob overhead at the cost of needing a
collision policy; this was not implemented and remains open.

**The constant-folding trap, and why it generalises.** A shared class that reads a per-version
compile-time constant silently bakes in the compile-time value rather than the runtime one.
`public static final String ID = "..."` is a *constant variable* under JLS 4.12.4, so `javac`
inlines its literal value at every reference site, at the referencing class's own compile time, not
at the referenced class's. The conformance mod's shared `ConformanceEntry` (in `src/main/java`,
compiled once and shipped to every module) read `ModuleIdentity.ID`/`ModuleIdentity.UNIQUE`, and
because `main` compiles against the `identityStub` source set's identity stub, every module's
compiled `ConformanceEntry.class` had the stub's literal values `"stub"`/`"unique-stub"` burned in,
regardless of which module's real `ModuleIdentity` class was actually on the classpath at runtime.
Every one of the five smoke servers reported `module=stub` even though the stub class was never
packaged into any module jar; the earlier structural argument, "no `Jar` task reads the stub's
output, so packaging is prevented by construction," was correct and insufficient, because the
stub's *value*, not its class file, is what leaked. The fix is to return such values from methods
(`ModuleIdentity.id()`, `ModuleIdentity.unique()`) rather than expose them as constants: a method
call compiles to `invokestatic`, resolved against whichever class is actually on the classpath at
call time, and cannot be folded.

**This generalises to any consumer using shared source with per-version overlays, which is exactly
Baritone's Stonecutter pattern.** Baritone's `common` source set is shared across Stonecutter
version nodes with per-version overlay files providing the pieces that differ. Any shared class
that reads a `public static final` constant from a per-version overlay class is exposed to the same
trap: the shared class compiles once, against one node's overlay, and every other node's copy of
that shared class silently keeps the first node's compile-time value. This is a hazard specific to
shared-source-plus-overlay designs, not to Nylium's dedupe mechanism itself; dedupe only made it
visible sooner, because it forces exactly one compiled copy where a Stonecutter build would
otherwise generate one per node.

**The acceptance evidence.** Phase 0 (dedupe off) and Phase 3 (dedupe on) both pass 5/5 across all
five real Minecraft servers, and their report files are byte-for-byte identical across all five
servers, verified by both `diff -q` and `sha256sum`. That equality is the core proof this design
exists to produce: the jar dedupe rebuilds at extraction time is indistinguishable from a whole,
undeduped module jar at runtime. Every module's dedicated blob-backed jar reassembles into an
object containing its own distinct identity, not another module's.

**The two cost measurements, labelled honestly as indicative rather than measured.** Both numbers
are n=2 and hand-run outside Gradle, because the prescribed in-Gradle method proved unusable: the
smoke harness's `clearInstalledMods` step (added to fix the stale-extraction-cache trap) deletes a
server's entire `<serverDirectory>/nylium` cache directory, cache included, on every provisioning
run, and provisioning is never up to date, so `:smoke:test` cannot isolate a genuine cache hit from
a second cold run. To get an isolated measurement, the already-provisioned Fabric 1.21.11 server
was launched directly with the harness's own toolchain and command line, bypassing Gradle (and
therefore `clearInstalledMods`) entirely:

- **First-launch rebuild cost:** roughly 200 to 650 ms at n=2 (two cold/warm pairs measured 222 ms
  and 647 ms of difference respectively), well under a second as predicted.
- **ModLauncher 9 blob-lookup overhead:** the 8-entry conformance module's ModLauncher 9 test took
  about 13% longer wall time than the testmod's single-entry module on the same backend (14.84s
  against 13.076s, a 1.76s difference on top of a 13+ second JVM/Forge boot), each measured once.

**A limitation of the acceptance run itself: the smoke matrix can never exercise the dedupe
cache-HIT path.** For the same reason the cost measurement above needed a workaround,
`clearInstalledMods` wipes each server's extraction cache before every provisioning run, and
provisioning always re-runs, so every server the matrix boots does so cold. Phase 0 and Phase 3
both prove correctness on a cache miss; neither can prove anything about a cache hit's runtime
behaviour, because the harness structurally cannot produce one.

**The per-backend `mcClass` values, as measured** (whether the module could observe
`net.minecraft.server.MinecraftServer` from the kernel's boot hook):

| Backend | `mcClass` |
| --- | --- |
| Fabric (both 1.21.10 and 1.21.11) | `reachable` |
| ModLauncher 9 | `reachable` |
| ModLauncher 8 | `unavailable` (known limitation 1) |
| LaunchWrapper | `unsafe-to-probe` (new limitation 4, see the kernel design spec) |

## Out of scope

- Baritone's own universal jar. SP-3 stays blocked on making each loader a Stonecutter-versioned
  project, per that repo's handoff. This design is applied to Baritone afterwards, as the owner
  asked.
- Any reduction in duplicate source. That is SP-5.
- Retiring `nylium-testmod`'s hand-rolled `universalJar`. Reconsider once the conformance mod has
  passed the matrix, not before.
