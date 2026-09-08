# Nested Jars in Dispatched Modules (SP-2c) - Design

**Written 2026-09-08.** Fixes known limitation 5, recorded in the handoff the same day.

## Goal

A module that ships a dependency the way an ordinary Fabric mod does, with Loom/unimined
`include`, must work after dispatch. Today it does not: the dependency's bytes ride along inside the
module and are unreachable, so the consumer loads, initialises, and then dies the first time it
touches that dependency.

## Why this exists

Found by booting Baritone's universal jar on a real Fabric 1.21.10 client. Dispatch worked, all 20
mixins applied against intermediary names, Baritone initialised and printed to chat, and then the
client crashed:

```
MixinMinecraft postInit -> BaritoneAPI.<clinit> -> Baritone.<init> -> registerProcess
  -> ElytraProcess.create -> NetherPathfinderContext.isSupported
  -> NoClassDefFoundError: dev/babbaj/pathfinder/NetherPathfinder
```

Fabric Loader unpacks a jar's nested `META-INF/jars` during **mod discovery**. The kernel extracts a
module and adds it to the classpath at **prelaunch**, after discovery is over, and the generated
outer `fabric.mod.json` carries no `jars` key of its own. A jar nested inside a jar is on no
classpath, so the bytes are present and unusable.

This is not an exotic shape. `include` is the idiomatic way a Fabric mod ships a dependency, so any
such consumer hits it. Forge and NeoForge consumers that shade their dependencies flat are
unaffected, measured on Baritone's own module jars: the two Fabric modules carry 2
`META-INF/jars` entries and 0 flattened `dev/babbaj` classes, while the Forge and NeoForge modules
carry 0 and 62 respectively.

**No server test can catch this class of bug** when the consumer's affected code is client-side.
Baritone's mixins are all client-only, so on a dedicated server `BaritoneAPI` never initialises and
the missing class is never touched, which is exactly why three green server boots preceded this
discovery.

## Non-goals

- **A nested jar that is itself a mod.** This design makes a nested *library* reachable on the
  classpath. A nested jar with its own `fabric.mod.json` entrypoints would need mod registration,
  which the kernel does not do for the module itself either.
- **Forge JarJar** (`META-INF/jarjar/` plus its `metadata.json`). The design extends to it by
  changing only which entries the plugin declares, with no kernel change, but no Forge consumer
  needs it today and shipping untested support would be speculation.
- **Version conflicts between modules.** Each module's nested jars are extracted only when that
  module is selected, so two modules bundling different versions of one library never meet. This
  falls out of the design rather than being solved by it.

## Shape: the plugin discovers, the manifest declares, the kernel extracts

This mirrors how mixin configs already work, which is the strongest argument for it: a consumer
declares them, the plugin validates them against the module jar's real contents at package time,
and the kernel reads them out of the manifest at boot.

- **The plugin discovers.** `ModuleJarInspector` already enumerates every entry of every module jar
  for its duplicate-entry and shading checks, so the `META-INF/jars/*.jar` names are a filter over
  data it computes anyway. Nothing new is read.
- **The manifest declares.** A `nested` field per module, comma-separated, same shape as `mixins`,
  emitted only when non-empty.
- **The kernel extracts.** After the module lands in the extraction cache, each declared nested
  entry is extracted from it into the same cache and added to the classpath, before
  `whenModuleLoadable` fires so the classes exist before any mixin or entrypoint runs.

### Why not hoist into the outer `fabric.mod.json`

Adding each module's nested jars to the universal jar's own `jars` array would let Fabric Loader
unpack them at discovery, using the loader's own mechanism, which is superficially the most
idiomatic answer. Rejected because the universal jar declares every module at once: it would unpack
every version's dependencies on every launch, and two modules bundling the same file name at
different versions would collide with no way to tell Loader which belongs to the selected module.
It is also Fabric-only, where the chosen shape is not.

### Why not scan at runtime

Having the kernel scan the extracted module for `META-INF/jars/*.jar` needs no manifest change and
no plugin change, and would even fix jars built before this design. Rejected because it extracts
whatever happens to sit under that prefix rather than what the author declared as a dependency, it
gives no build-time failure when something is wrong, and it would put Fabric layout knowledge in a
kernel that is otherwise platform-agnostic.

## Manifest compatibility

`ModuleManifest` rejects unrecognised keys by design, so **a universal jar built by the new plugin
cannot be read by an older kernel**: it would fail with `Unknown key 'module.0.nested'`. This is not
a real break, because the plugin embeds the kernel it ships with into the same universal jar, so the
two always travel together. It does mean a consumer must not pin an older `nylium-core` against a
newer plugin. The failure is loud and names the key, which is the behaviour that field is there for.

## Extraction and caching

Nested extraction reuses `ModuleExtractor`'s existing machinery rather than adding a second path:
the content-addressed `<name>-<sha16>.jar` naming, so an unchanged nested jar is extracted once
across launches, and `landInPlace`, so the atomic-move race handling (including the Windows
`AccessDeniedException` case) is not reimplemented.

The nested jar is read from the module jar already extracted on disk, not from the object store,
because by that point a real jar exists. Note this composes with dedupe for free: a nested jar is
one entry, so it is one blob, and an identical nested dependency across several modules is stored
once in the universal jar.

## Validation

At package time, a declared nested jar that is not present in the module jar is rejected, the same
way a declared mixin config that is absent is rejected today. Since the plugin discovers the list
from the jar itself, this can only fire if discovery and validation drift apart, which is exactly
the drift worth failing on.

## Testing

Unit tests in `nylium-core` for manifest parsing of `nested`, for nested extraction, and for
extraction idempotence, plus the existing unknown-key rejection still holding.

**The conformance mod is where this actually gets proven.** A module that carries an `include`d
dependency and an entrypoint that *touches a class from it*, so the smoke matrix fails when the
nested jar is unreachable. A module that merely bundles a nested jar without using it would pass
whether or not the fix works, which is the same structural blindness that let limitation 4 hide
behind green runs for this project's entire history.

Final acceptance is the real failure: rebuild Baritone's universal jar against this Nylium and boot
a production Fabric 1.21.10 client with
`baritone/scripts/launch-production-client.ps1`, requiring the main menu with no
`NoClassDefFoundError`.

## One thing to confirm during execution, not assume

`nether-pathfinder` ships native libraries inside its jar and extracts them itself at load time.
Classpath presence is very probably sufficient, but "the class resolved" is not the same claim as
"the native loaded", and only the second one makes Baritone's elytra process work. Confirm it on the
real client run rather than inferring it from the absence of a `NoClassDefFoundError`.
