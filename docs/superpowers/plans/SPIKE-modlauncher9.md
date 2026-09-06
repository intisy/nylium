# Spike: ModLauncher 9+ module layer injection

Status: complete. Throwaway spike code deleted; this document is the deliverable.

Question asked: which ModLauncher 9+ API actually gets a jar into a module layer such that its
classes are loadable and its mixins apply, and does that API differ between Forge and NeoForge?

Answer in one line: on Forge it is `ITransformationService.beginScanning` returning a
`Resource(Layer.GAME, ...)`, and the injected classes are **not** loadable until after the entire
`ITransformationService` lifecycle has finished; on NeoForge 21.11 ModLauncher does not exist at
all and the question does not apply.

## Verified against

| Loader | Version | Minecraft | ModLauncher | Module/jar handling |
| --- | --- | --- | --- | --- |
| Forge | `1.21.11-61.1.5` (server) | 1.21.11 | `net.minecraftforge:modlauncher:10.2.4` | `net.minecraftforge:securemodules:2.2.24` |
| NeoForge | `21.11.45` (server) | 1.21.11 | none | own `net.neoforged.fml.classloading` |

Both servers were installed with the official installers (`--installServer`) and booted to
`Done (...)! For help, type "help"` with the spike on the launch classpath. JDK 21.0.11 (Temurin).

Additionally checked for source compatibility only (not run): upstream
`cpw.mods:modlauncher:10.0.9` + `cpw.mods:securejarhandler:2.1.10`, the coordinates used by Forge
for Minecraft 1.17 to 1.20.x.

## Finding 0: the dependency coordinates in the task brief are wrong

The brief specifies `cpw.mods:modlauncher:10.0.9` and `cpw.mods:securejarhandler:2.1.10`. Those
are the **upstream** coordinates. Forge for Minecraft 1.21.11 does not use them. It uses its own
fork:

```groovy
compileOnly 'net.minecraftforge:modlauncher:10.2.4'
compileOnly 'net.minecraftforge:securemodules:2.2.24'
```

The Java package names are unchanged by the fork: `cpw.mods.modlauncher.api.*` and
`cpw.mods.jarhandling.SecureJar` still exist under those exact names. Only the Maven group and
artifact ids moved. A backend that spans Forge 1.17 to 1.21.x therefore compiles against one set
of package names but must be *built* against whichever artifact coordinates match the target
range.

## Finding 1: release 8 bytecode against ModLauncher 9+ works

This was the controller's second question, because the ModLauncher 9+ backend shares a
`META-INF/services` file with the ModLauncher 8 backend and `ServiceLoader` instantiates every
listed entry, so a Java 17 class file there would throw `UnsupportedClassVersionError` on
ModLauncher 8.

**It works.** `javac --release 8` compiles a full `ITransformationService` implementation plus an
`ILaunchPluginService` implementation against ModLauncher 10.2.4 and securemodules 2.2.24, and
emits class files with `major version: 52`. This holds even though:

- `ITransformationService.Resource` is a *record* (extends `java.lang.Record`, a Java 16+ type)
  and the spike constructs one with `new Resource(layer, list)`;
- both jars are Java 17 bytecode and carry a `module-info.class`.

javac does not object to either. The same is true of the upstream `cpw.mods` coordinates, and of
the NeoForge SPI (see Finding 6).

Two obstacles have to be worked around, both verified:

### 1a. Gradle variant resolution refuses the dependency before javac runs

With `options.release = 8`, Gradle stamps `TargetJvmVersion = 8` on the compile classpath and
rejects ModLauncher, which publishes `org.gradle.jvm.version = 16`:

```
Could not resolve net.minecraftforge:modlauncher:10.2.4.
  > Dependency resolution is looking for a library compatible with JVM runtime version 8,
    but 'net.minecraftforge:modlauncher:10.2.4' is only compatible with JVM runtime version 16 or newer.
```

This is a build-tool constraint, not a bytecode constraint. Raise the attribute on the compile
classpath only; `options.release = 8` is left untouched and the emitted bytecode stays at 52:

```groovy
import org.gradle.api.attributes.java.TargetJvmVersion

configurations.compileClasspath {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}
```

Task 15 must copy this. Without it the module will not resolve at all.

### 1b. `IModuleLayerManager.getLayer` cannot be called directly at release 8

Its return type is `Optional<ModuleLayer>`, and `java.lang.ModuleLayer` is not in the release-8
platform:

```
error: cannot access ModuleLayer
        return manager.getLayer(IModuleLayerManager.Layer.GAME).get().findLoader(module);
  class file for java.lang.ModuleLayer not found
```

This is the **only** ModLauncher 9+ API the spike found that release 8 cannot express directly.
Reflection over it compiles at release 8 and works at runtime (the spike used exactly this and it
returned the correct classloader):

```java
Optional<?> layer = (Optional<?>) IModuleLayerManager.class
        .getMethod("getLayer", IModuleLayerManager.Layer.class)
        .invoke(manager, IModuleLayerManager.Layer.GAME);
ClassLoader loader = (ClassLoader) layer.get().getClass()
        .getMethod("findLoader", String.class)
        .invoke(layer.get(), moduleName);
```

Note `findModule` / `findLoader` are resolved off the concrete `ModuleLayer` instance, which is
public, so no `setAccessible` is needed.

## Finding 2: the brief's skeleton is one signature off

`transformers()` on both ModLauncher 10.2.4 and upstream 10.0.9 is declared
`List<ITransformer> transformers()` with a **raw** `ITransformer`, not
`List<? extends ITransformer<?>>`:

```
error: transformers() in SpikeService cannot implement transformers() in ITransformationService
  return type List<? extends ITransformer<?>> is not compatible with List<ITransformer>
```

Everything else in the brief's skeleton is correct. `beginScanning` is called, `Resource` is
`Resource(IModuleLayerManager.Layer, List<SecureJar>)`, and `SecureJar.from(Path)` exists (as
`from(Path...)` varargs, so a single-`Path` call site compiles unchanged) on both 2.2.24 and
upstream 2.1.10.

## Finding 3: Forge layer matrix

`beginScanning` is called on Forge and the returned `Resource` is accepted without error for every
one of the four `Layer` constants. Acceptance is not the same as effect:

| Layer | Resource accepted | Module ever appears | Loaded by | Mixins would apply |
| --- | --- | --- | --- | --- |
| `BOOT` | yes | **never** (silently dropped) | - | - |
| `SERVICE` | yes | **never** (silently dropped) | - | - |
| `PLUGIN` | yes | yes | `SecureModuleClassLoader[LAYER PLUGIN]` | **no** |
| `GAME` | yes | yes | `TransformingClassLoader[TRANSFORMER]` | yes |

`BOOT` and `SERVICE` produce no error, no warning, and no module. Those layers are already built by
the time `beginScanning` runs, so the resource is discarded. Task 15 must not treat a successful
`beginScanning` return as proof of injection.

**`GAME` is the correct layer.** A `GAME`-layer class is loaded by
`TransformingClassLoader[TRANSFORMER]`, which is the loader Mixin transforms through, so mixins in
the injected jar can apply. A `PLUGIN`-layer class is loaded by
`SecureModuleClassLoader[LAYER PLUGIN]`, which is not a transforming loader, so mixins would not
apply to it. `PLUGIN` therefore works for plain classloading only and is the wrong choice for
Nylium.

Caveat on the mixin half: the spike proved the *classloader identity*, not an applied mixin.
Registering a mixin config requires a real mod file, which is out of a spike's scope. The
classloader identity is strong evidence but Task 15 should confirm with one real mixin.

## Finding 4: on Forge, injected classes are not loadable during the service phase

This is the finding that matters most for the kernel design, and it contradicts what the brief
assumed.

Observed lifecycle order on Forge 61.1.5 / ModLauncher 10.2.4:

```
ITransformationService.onLoad
ITransformationService.initialize
ITransformationService.beginScanning     <- the SecureJar is handed over here
ITransformationService.completeScan
ITransformationService.transformers
--- ModLauncher builds the PLUGIN and GAME layers and the TransformingClassLoader here ---
ILaunchPluginService.initializeLaunch
ILaunchPluginService.handlesClass  (per game class, thereafter)
```

Note `onLoad` runs **before** `initialize`.

With `Layer.GAME`, at every one of the five `ITransformationService` callbacks:

```
SPIKE PROBE beginScanning forName=FAIL java.lang.ClassNotFoundException: payload.Payload
SPIKE PROBE beginScanning tccl=FAIL java.lang.ClassNotFoundException: payload.Payload
SPIKE PROBE beginScanning layer=GAME absent
```

`getLayer(Layer.GAME)` returns `Optional.empty()` throughout the service phase, including from
`completeScan(IModuleLayerManager)`. The layer does not exist yet.

The **earliest point at which a `GAME`-layer class is loadable** is
`ILaunchPluginService.initializeLaunch`:

```
SPIKE PROBE launchPlugin.initializeLaunch1Arg layer=GAME hasPayloadModule=true \
  loadViaLayer=OK loader=TransformingClassLoader[TRANSFORMER]@1220813917
```

and it remains loadable for every subsequent `handlesClass` call. With `Layer.PLUGIN` the module
becomes visible one step earlier, at `completeScan`, but on the non-transforming loader.

### Consequence for `NyliumKernel.boot`

`NyliumKernel.boot` as designed classpaths the module, registers its mixin configs, and invokes its
entrypoint synchronously in one call. **That sequence cannot complete in one call on ModLauncher
9+.** The module hand-off must happen in `beginScanning`, and the entrypoint invocation cannot
happen until `ILaunchPluginService.initializeLaunch` at the earliest. The `Platform` interface
needs a deferred-invocation capability for this backend: `boot` can only enqueue the entrypoint,
and something later has to run it.

The realistic invocation point is `ILaunchPluginService.initializeLaunch`. It is reachable from a
second service the same jar already declares, it is the first point where the module is loadable,
and it is still before any game class is transformed. Recommended shape:

- `META-INF/services/cpw.mods.modlauncher.api.ITransformationService` -> injects the jar in
  `beginScanning`, stashes the `IEnvironment`/`IModuleLayerManager` in a static.
- `META-INF/services/cpw.mods.modlauncher.serviceapi.ILaunchPluginService` -> in
  `initializeLaunch`, resolve the loader reflectively off `getLayer(GAME)` and invoke the
  entrypoint.

Two hazards, both hit during the spike:

- **Do not use `Class.forName(name)` from the service or plugin class.** It resolves against the
  *caller's* loader, which lives in the boot/service layer and does not read the injected module.
  It failed at every single probe point, in every configuration. Always resolve the loader from
  the `GAME` layer and use the three-argument `Class.forName(name, true, loader)`.
- **Do not use the thread context classloader from inside `initializeLaunch`.** The TCCL there is
  the `TransformingClassLoader`, and loading through it re-enters `handlesClass`, which produced:

  ```
  java.lang.LinkageError: loader 'TRANSFORMER' @48c4245d attempted duplicate class definition for
  payload.Payload. (payload.Payload is in module payload of loader 'TRANSFORMER' @48c4245d,
  parent loader 'bootstrap')
  ```

  Resolving via `getLayer(GAME).findLoader(module)` in the same position worked cleanly.

## Finding 5: the module must not also be on the launch classpath

If the injected jar is *also* on the JVM `-cp`, the boot layer derives a module of the same name
and layer resolution fails hard at startup:

```
java.lang.module.ResolutionException: Module payload reads another module named payload
	at cpw.mods.modlauncher.ModuleLayerHandler.build(ModuleLayerHandler.java:76)
	at cpw.mods.modlauncher.TransformationServicesHandler.buildTransformingClassLoader(...)
	at cpw.mods.modlauncher.Launcher.run(Launcher.java:112)
```

Nylium's own jar is on the classpath (it has to be, for `ServiceLoader` to find the service). The
payload it injects must be a distinct artifact, or a nested jar, and must not appear on the
classpath itself.

## Finding 6: NeoForge 21.11 has no ModLauncher, so the ML9+ backend does not apply

NeoForge 21.11.45 does not use ModLauncher at all. Evidence:

- Launch is `net.neoforged.fml.startup.Server` from a plain `-classpath` (no `--module-path`,
  no `cpw.mods` entry). Compare Forge, which launches `net.minecraftforge.bootstrap.ForgeBootstrap`
  and then `cpw.mods.modlauncher.Launcher`.
- All 71 jars in the installed server's `libraries/` were scanned: **zero** contain any
  `cpw/mods/` class. There is no `ITransformationService`, no `IModuleLayerManager`, and no
  `cpw.mods.jarhandling.SecureJar` anywhere in the distribution. The only trace is a dead
  `META-INF/services/cpw.mods.modlauncher.api.ITransformationService` entry inside
  `sponge-mixin-0.16.5+mixin.0.8.7.jar`, a Fabric-built artifact; the interface it names is not
  present, so nothing can load it.
- The spike jar was put on the NeoForge launch classpath with its
  `META-INF/services/cpw.mods.modlauncher.api.ITransformationService` intact. The server booted to
  `Done (1.207s)!` and emitted **zero** `SPIKE` lines. `beginScanning` was never called; the
  service was never discovered.

So the answer to "does the API differ between Forge and NeoForge" is stronger than the brief
anticipated: on this version there is no shared API to differ. NeoForge 21.11 needs its own
backend, not a ModLauncher 9+ backend.

### What NeoForge 21.11 offers instead

For the spec's backend coverage table, the equivalent surface is `net.neoforged.neoforgespi`,
shipped in `net.neoforged.fancymodloader:loader:10.0.36`. Jar injection:

```java
public interface IModFileCandidateLocator extends IOrderedProvider {
    void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline);
}
```

registered via `META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator`,
and the injection call is
`pipeline.addPath(Path, ModFileDiscoveryAttributes, IncompatibleFileReporting)`. Related SPIs the
loader also declares: `IModFileReader`, `IDependencyLocator`,
`net.neoforged.neoforgespi.language.IModLanguageLoader`. Class transformation moved to
`net.neoforged.neoforgespi.transformation.ClassProcessorProvider`.

NeoForge still builds module layers, with its own implementation
(`net.neoforged.fml.classloading.ModuleClassLoader`,
`net.neoforged.fml.classloading.transformation.TransformingClassLoader`) rather than
securemodules. This spike did not characterise its lifecycle timing; that is a separate spike if
the NeoForge backend needs it.

A `IModFileCandidateLocator` implementation compiles at `--release 8` and emits
`major version: 52`, so the shared-services-file constraint is satisfiable on this backend too.

## Coverage table input

| Backend | Range verified | Verdict |
| --- | --- | --- |
| ModLauncher 9+ (Forge) | Forge `1.21.11-61.1.5`, ModLauncher 10.2.4 | supported, `Layer.GAME`, deferred entrypoint |
| ModLauncher 9+ (Forge, older) | `cpw.mods:modlauncher:10.0.9` + `securejarhandler:2.1.10` | source-compatible, compiles at release 8. `ILaunchPluginService` has only the two-argument `initializeLaunch(ITransformerLoader, NamedPath[])`; 10.2.4 added a one-argument form and deprecated the two-argument one for removal. On 10.2.4 the spike overrode each form in a separate run and **both** were invoked, so overriding the two-argument form covers the whole range. Not run on 10.0.9; needs a runtime check before the range is claimed. |
| ModLauncher 9+ (NeoForge) | NeoForge `21.11.45` | **unsupported**: ModLauncher absent, service never discovered, no error raised |
| `Layer.BOOT` / `Layer.SERVICE` on Forge | Forge `1.21.11-61.1.5` | **unsupported**: silently accepted, never injected |
