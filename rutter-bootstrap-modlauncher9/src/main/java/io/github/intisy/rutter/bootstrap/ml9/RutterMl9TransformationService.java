package io.github.intisy.rutter.bootstrap.ml9;

import cpw.mods.jarhandling.SecureJar;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;
import io.github.intisy.rutter.core.RutterKernel;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * @implNote ModLauncher 8 and ModLauncher 9+ share this class's {@code ServiceLoader} file, and
 * {@code ServiceLoader} instantiates every entry regardless of which generation is actually
 * running, so this no-ops when {@code cpw.mods.jarhandling.SecureJar}, a class that exists only on
 * ModLauncher 9+, is absent; mirrors {@code RutterMl8Service}'s discriminator in the other direction.
 * @implNote The spike found the injected module is not loadable until
 * {@code ILaunchPluginService.initializeLaunch}, so {@code RutterKernel.boot} only extracts the
 * module and stashes it on {@link Ml9Platform} here; {@link #beginScanning} hands the extracted jar
 * to ModLauncher as a {@code Resource(Layer.GAME, ...)}, and {@link RutterMl9LaunchPlugin} drives
 * activation once the GAME layer actually exists.
 */
public final class RutterMl9TransformationService implements ITransformationService {

    private Ml9Platform platform;

    @Override
    public String name() {
        return "rutter-ml9";
    }

    @Override
    public void initialize(IEnvironment environment) {
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) throws IncompatibleEnvironmentException {
        if (!isModLauncher9OrNewer()) {
            return;
        }
        Ml9Platform created = new Ml9Platform(env);
        this.platform = created;
        Ml9Bridge.platform = created;
        Ml9Bridge.module = RutterKernel.boot(
                created,
                RutterMl9TransformationService.class.getClassLoader(),
                Paths.get(".").resolve("rutter").resolve("cache"));
        watchForALaunchThatNeverActivates();
    }

    /**
     * @implNote {@link RutterMl9LaunchPlugin} is the only place activation can happen, and a launch
     * plugin cannot be discovered from {@code mods/} on this generation, so a jar installed there
     * boots the kernel from this service and then goes quiet forever. This is the same shape of
     * backstop {@code RutterTweaker} uses on LaunchWrapper: without it, the documented deployment
     * limitation looks like a module that simply did nothing.
     */
    private static void watchForALaunchThatNeverActivates() {
        Runtime.getRuntime().addShutdownHook(new Thread("rutter-ml9-activation-watchdog") {
            @Override
            public void run() {
                if (!Ml9Bridge.activated) {
                    System.err.println("[Rutter] WARNING: shutting down without ever activating "
                            + Ml9Bridge.module + "; RutterMl9LaunchPlugin.initializeLaunch never "
                            + "ran. ModLauncher 9+ discovers an ILaunchPluginService only from its "
                            + "boot module layer, so a jar dropped into mods/ reaches this service "
                            + "but never that plugin, and the module's mixin configs and entrypoint "
                            + "are never registered. Put the jar on the launch classpath instead.");
                }
            }
        });
    }

    @Override
    public List<Resource> beginScanning(IEnvironment environment) {
        Ml9Platform active = this.platform;
        if (active == null) {
            System.err.println("[Rutter] WARNING: beginScanning ran without onLoad having booted "
                    + "the kernel, so no module is offered into the GAME layer and nothing will "
                    + "load.");
            return Collections.emptyList();
        }
        SecureJar jar = SecureJar.from(active.pendingModulePath());
        active.moduleName(jar.name());
        return Collections.singletonList(
                new Resource(IModuleLayerManager.Layer.GAME, Collections.singletonList(jar)));
    }

    @Override
    public List<Resource> completeScan(IModuleLayerManager layerManager) {
        Ml9Bridge.moduleLayerManager = layerManager;
        return Collections.emptyList();
    }

    @Override
    public List<ITransformer> transformers() {
        return Collections.emptyList();
    }

    private static boolean isModLauncher9OrNewer() {
        try {
            Class.forName("cpw.mods.jarhandling.SecureJar", false,
                    RutterMl9TransformationService.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
