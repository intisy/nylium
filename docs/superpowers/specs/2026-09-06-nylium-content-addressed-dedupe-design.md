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
`.index`. There is deliberately no new manifest key: the extractor branches on the suffix, an old jar
and a new jar are both readable, and the format stays self-describing.

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
- No runtime hash verification. It would double the read cost to guard against a build bug that the
  build-time reconstruct-and-compare gate catches for free.

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
| `sharedClass` | a class dedupe collapsed loaded and returned its constant |
| `uniqueClass` | this module's unique source survived the merge |
| `loader` | the platform, inferred from visible loader classes |
| `mixin` | the ModLauncher 9 mixin config registered and applied |
| `mcClass` | `reachable` or `unavailable`, per backend |

`sharedClass` and `uniqueClass` together are the end-to-end proof of the merge: one says the
collapsed blob was restored correctly, the other says per-module content survived.

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
containing a newline; `ModuleExtractor` rebuilding a jar entry-for-entry equal to its source across a
synthetic three-module overlapping set; a missing blob raising a named exception; the cache-hit path
not rewriting.

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

## Out of scope

- Baritone's own universal jar. SP-3 stays blocked on making each loader a Stonecutter-versioned
  project, per that repo's handoff. This design is applied to Baritone afterwards, as the owner
  asked.
- Any reduction in duplicate source. That is SP-5.
- Retiring `nylium-testmod`'s hand-rolled `universalJar`. Reconsider once the conformance mod has
  passed the matrix, not before.
