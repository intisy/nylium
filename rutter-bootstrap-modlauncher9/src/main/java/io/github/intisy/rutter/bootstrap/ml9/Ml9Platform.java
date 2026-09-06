package io.github.intisy.rutter.bootstrap.ml9;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import io.github.intisy.rutter.api.Environment;
import io.github.intisy.rutter.api.Platform;
import io.github.intisy.rutter.api.PlatformId;
import io.github.intisy.rutter.api.RutterException;
import org.spongepowered.asm.mixin.Mixins;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * @implNote The spike found the injected module is not loadable until
 * {@code ILaunchPluginService.initializeLaunch}, well after {@link io.github.intisy.rutter.core.RutterKernel#boot}
 * returns, so {@link #addToClasspath} and {@link #whenModuleLoadable} only stash what they are given;
 * {@link RutterMl9LaunchPlugin} drives the actual hand-off and activation once the GAME layer exists.
 */
final class Ml9Platform implements Platform {

    private final Environment environment;

    private volatile Path pendingModulePath;
    private volatile String moduleName;
    private volatile Runnable pendingActivation;
    private volatile ClassLoader resolvedLoader;

    Ml9Platform(IEnvironment env) {
        this.environment = environmentOf(env);
    }

    @Override
    public PlatformId id() {
        return PlatformId.MODLAUNCHER_9;
    }

    @Override
    public Environment environment() {
        return environment;
    }

    /**
     * @implNote The SERVICE layer this class is loaded into is a parent of the GAME layer, which is
     * why {@link #activate} has to go and find the game class loader reflectively; probing for
     * {@code net.minecraft.client.Minecraft} from here therefore reports SERVER even on a client.
     * ModLauncher publishes its own {@code --launchTarget} argument as
     * {@code IEnvironment.Keys.LAUNCHTARGET} before any service's {@code onLoad} runs, so that
     * value is available this early and does distinguish {@code forgeclient} from
     * {@code forgeserver}. The class probe remains as the fallback for an embedding that publishes
     * no launch target at all.
     */
    private static Environment environmentOf(IEnvironment env) {
        Optional<String> launchTarget = env.getProperty(IEnvironment.Keys.LAUNCHTARGET.get());
        if (!launchTarget.isPresent()) {
            return exists("net.minecraft.client.Minecraft") ? Environment.CLIENT : Environment.SERVER;
        }
        return launchTarget.get().toLowerCase(Locale.ROOT).contains("client")
                ? Environment.CLIENT
                : Environment.SERVER;
    }

    @Override
    public void addToClasspath(Path jar) {
        this.pendingModulePath = jar;
    }

    /**
     * @implNote A single-argument {@code Mixins.addConfiguration(String)} call resolves its
     * {@code MixinEnvironment} through the config's own {@code target} field; a config with no
     * {@code target} set NPEs inside Mixin 0.8.7's {@code MixinConfig.onLoad} when registered this
     * late, because there is no current phase for it to fall back to. Every mixin config used with
     * this backend must set {@code "target": "DEFAULT"} (or another valid phase) in its JSON.
     */
    @Override
    public void registerMixinConfig(String name) {
        Mixins.addConfiguration(name);
    }

    /**
     * @implNote Unlike ModLauncher 8, this resolves cleanly at {@code onLoad} time, before the GAME
     * layer exists: {@code net.minecraftforge.versions.mcp.MCPVersion} is on the SERVICE-layer
     * class loader from the start. Its {@code <clinit>} logs a burst of internal log4j plugin
     * lookup {@code ClassCastException}s the first time any class triggers logger initialisation
     * this early; log4j catches and reports each one itself, so {@code getMCVersion()} still
     * returns normally.
     */
    @Override
    public Optional<String> nativeVersionProbe() {
        try {
            Class<?> mcp = Class.forName("net.minecraftforge.versions.mcp.MCPVersion", false,
                    Ml9Platform.class.getClassLoader());
            return Optional.ofNullable((String) mcp.getMethod("getMCVersion").invoke(null));
        } catch (ReflectiveOperationException | LinkageError e) {
            return Optional.empty();
        }
    }

    @Override
    public void whenModuleLoadable(Runnable activation) {
        this.pendingActivation = activation;
    }

    @Override
    public ClassLoader moduleClassLoader(ClassLoader source) {
        ClassLoader resolved = this.resolvedLoader;
        return resolved != null ? resolved : source;
    }

    Path pendingModulePath() {
        Path path = this.pendingModulePath;
        if (path == null) {
            throw new RutterException("beginScanning ran before RutterKernel.boot extracted a module to hand off.");
        }
        return path;
    }

    void moduleName(String name) {
        this.moduleName = name;
    }

    /**
     * @implNote {@code IModuleLayerManager.getLayer} returns {@code Optional<ModuleLayer>}, and
     * {@code java.lang.ModuleLayer} is not part of the release 8 platform this class compiles
     * against, so both the layer lookup and {@code findLoader} are called reflectively. See the
     * spike doc, Finding 1b.
     */
    void activate(IModuleLayerManager manager) {
        try {
            Method getLayer = IModuleLayerManager.class.getMethod("getLayer", IModuleLayerManager.Layer.class);
            Object layerOptional = getLayer.invoke(manager, IModuleLayerManager.Layer.GAME);
            Optional<?> optional = (Optional<?>) layerOptional;
            if (!optional.isPresent()) {
                throw new RutterException("The GAME module layer was not present when ModLauncher 9+ "
                        + "invoked initializeLaunch; the spike's Layer.GAME timing assumption did not hold.");
            }
            Object moduleLayer = optional.get();
            Object loader = moduleLayer.getClass().getMethod("findLoader", String.class)
                    .invoke(moduleLayer, moduleName);
            this.resolvedLoader = (ClassLoader) loader;
        } catch (ReflectiveOperationException e) {
            throw new RutterException("Could not resolve the GAME module layer's class loader for module '"
                    + moduleName + "'", e);
        }
        Runnable activation = this.pendingActivation;
        if (activation != null) {
            activation.run();
        }
    }

    private static boolean exists(String className) {
        try {
            Class.forName(className, false, Ml9Platform.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
