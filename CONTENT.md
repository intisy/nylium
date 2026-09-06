## Compatibility contract

> A mod that uses only Rutter's API keeps working on newer Minecraft versions by bumping the
> Rutter version. Nothing else changes.

That promise is scoped to Rutter's own API surface, because that is the only scope in which it
can be enforced rather than merely hoped for. Four rules make it real:

1. No `net.minecraft` type ever appears in Rutter's public API, not as a parameter, return,
   field, supertype or generic argument. `checkApiPurity`, a Gradle task wired into `check`,
   ASM scans the built `rutter-api` jar and fails the build if one leaks in. This is mechanical
   enforcement, not discipline: a plain `./gradlew build` cannot pass with a leaked type.
2. Semver on the API. Breaking changes require a major version bump.
3. Removals require a deprecation cycle spanning at least one major version.
4. CI compiles and runs the previous release's consumer test mod against current Rutter on the
   newest Minecraft version. This rule has nothing to run against until a first release exists,
   so it is not yet wired up.

## The four backends

Rutter ships one universal jar containing a precompiled module per platform and Minecraft
version range. All four backends below are built and proven on real servers from that single
jar.

| Backend | Verified on | Marker |
| --- | --- | --- |
| Fabric | 1.21.11 and 1.21.10 servers | `module=1.21.11`, `module=1.21.10` |
| LaunchWrapper | Forge 1.7.10 | `module=1.7.10` |
| ModLauncher 8 | Forge 1.16.5 | `module=1.16.5` |
| ModLauncher 9+ | Forge 1.21.11-61.1.5 | `module=1.21.11-ml9`, plus `mixin=applied` from a real mixin |

The Fabric row is the discrimination case: two different Minecraft versions in the same jar,
each resolving to its own module, is the smallest proof that selection actually discriminates
rather than always picking the same candidate.

ModLauncher 9+ currently covers Forge only. The NeoForge loader runs no ModLauncher at all, so
NeoForge support is a separate backend and not part of this kernel yet.

## The manifest format

A module manifest is a flat `.properties` resource named `rutter-modules.properties`, embedded
in the outer jar. `java.util.Properties` ships in the JDK and module descriptors are flat
records with no nesting, so a hand-rolled parser buys nothing.

Each module is a numbered group of `module.<index>.<field>` keys:

```properties
module.0.path=modules/mymod-fabric.jar
module.0.platforms=FABRIC
module.0.minecraft=[1.20,1.21.11]
module.0.entrypoint=com.example.mymod.FabricModule

module.1.path=modules/mymod-forge-1.16.jar
module.1.platforms=MODLAUNCHER_8
module.1.minecraft=[1.13,1.16.5]
module.1.mixins=mixins.mymod.json
module.1.entrypoint=com.example.mymod.Forge116Module
```

- `path` and `platforms` are required. `platforms` is a comma-separated list of
  `LAUNCHWRAPPER`, `MODLAUNCHER_8`, `MODLAUNCHER_9`, `NEOFORGE` or `FABRIC`.
- `minecraft` is a version range: an exact version (`1.21.11`), or bracket notation
  (`[1.20,1.21.11]`, `[1.20,)`) where `[` and `]` are inclusive and `(` and `)` are exclusive.
- `environment` (`CLIENT` or `SERVER`), `mixins` (comma-separated config names), `priority`
  (an integer, for resolving ties) and `entrypoint` (a class with a public static
  `void rutterInit()`) are optional.
- An unrecognised key, or two indices that collide after normalisation (`01` and `1`, for
  example), fail the manifest with a specific error rather than being silently ignored.

Selection is deterministic: filter by platform, environment and version, order by constraint
specificity then declared priority, first match wins. A miss produces an exception naming the
detected platform, version and environment, and listing every candidate with the reason it was
rejected.

## Consumer walkthrough

There is no packaging plugin yet (that is future work), so today a consumer wires this up by
hand, the same way `rutter-testmod` does in this repository:

1. Build one module jar per platform and Minecraft version range you support. Each module's
   entrypoint class needs a public static `void rutterInit()`.
2. Write a `rutter-modules.properties` manifest describing every module, as above.
3. Bundle the manifest, the module jars, and the `rutter-bootstrap-*` artifact for each loader
   you target into one outer jar, wired to that loader's own entry contract (Fabric's
   `preLaunchEntrypoint`, LaunchWrapper's tweaker, ModLauncher's `ITransformationService`).
4. That is the whole integration surface. Each bootstrap already calls
   `RutterKernel.boot(platform, classLoader, cacheDirectory)` from its loader's own hook: on
   boot, Rutter detects the platform and Minecraft version, selects the matching module,
   extracts it to a hash-keyed cache directory, puts it on the classpath, registers its mixin
   configs, and invokes its entrypoint.

## Known limitations

This is a working kernel with two documented gaps, not a finished product:

- **ModLauncher 8: dispatch works, but a module cannot see Minecraft classes.** Dispatch is
  verified on a real Forge 1.16.5 server: the correct module is selected, classpathed and its
  entrypoint invoked. But `ITransformationService` discovery and the game's own classloader are
  built as sibling classloaders, and the discovery hook fires before the game classloader
  exists, so a module that needs to touch a Minecraft class, or mixin into one, cannot
  currently work through this backend. Fixing this needs its own spike, tracked separately.
- **A ModLauncher 9+ module cannot currently be installed by dropping it into `mods/`.**
  `ILaunchPluginService` is discovered only from ModLauncher's boot module layer, which is
  built from the literal JVM classpath, not from Forge's own `mods/` folder scanning. The
  universal jar must currently be placed on the classpath directly (an explicit `-cp` plus the
  loader's shim main class) rather than dropped in as an ordinary mod.
