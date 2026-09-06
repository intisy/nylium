package io.github.intisy.rutter.bootstrap.ml8;

import io.github.intisy.rutter.api.Environment;
import io.github.intisy.rutter.api.Platform;
import io.github.intisy.rutter.api.PlatformId;
import io.github.intisy.rutter.api.RutterException;
import org.spongepowered.asm.mixin.Mixins;

import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Optional;

final class Ml8Platform implements Platform {

    @Override
    public PlatformId id() {
        return PlatformId.MODLAUNCHER_8;
    }

    @Override
    public Environment environment() {
        return exists("net.minecraft.client.Minecraft") ? Environment.CLIENT : Environment.SERVER;
    }

    /**
     * @implNote ModLauncher 8.1.3's {@code ITransformationService} has no method that hands a jar
     * back to the loader: {@code ITransformerDiscoveryService} only locates more transformation
     * services, and {@code addTransformationPath} lives on {@code ITransformingClassLoaderBuilder},
     * reachable only from the registered {@code ILaunchHandlerService}, not from an arbitrary
     * transformation service. The class loader ModLauncher builds to discover and load this very
     * service is, verified against the real 8.1.3 jar, a plain {@code java.net.URLClassLoader}
     * subclass, so its protected {@code addURL} is reached reflectively here, the same technique
     * {@code LaunchWrapperPlatform} uses on the pre-ModLauncher era.
     */
    @Override
    public void addToClasspath(Path jar) {
        ClassLoader loader = Ml8Platform.class.getClassLoader();
        if (!(loader instanceof URLClassLoader)) {
            throw new RutterException("Rutter's ModLauncher 8 service was not loaded by a "
                    + "URLClassLoader, so " + jar + " cannot be added to its class path.");
        }
        try {
            Method addUrl = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            addUrl.setAccessible(true);
            addUrl.invoke(loader, jar.toUri().toURL());
        } catch (ReflectiveOperationException | MalformedURLException e) {
            throw new RutterException("Could not add " + jar + " to the ModLauncher 8 service class loader", e);
        }
    }

    @Override
    public void registerMixinConfig(String name) {
        Mixins.addConfiguration(name);
    }

    /**
     * @implNote {@code net.minecraftforge.versions.mcp.MCPVersion} lives inside the Forge universal
     * jar, which ModLauncher only adds to the game class loader once the registered launch handler
     * configures it, a step that runs after every transformation service's {@code onLoad}. This
     * probe therefore reliably finds nothing at boot time and the chain falls through to
     * {@code VersionJsonProbe}, which reads {@code version.json} off the vanilla server jar that
     * is already on this class loader via the Forge server jar's manifest {@code Class-Path}.
     */
    @Override
    public Optional<String> nativeVersionProbe() {
        try {
            Class<?> mcp = Class.forName("net.minecraftforge.versions.mcp.MCPVersion", false,
                    Ml8Platform.class.getClassLoader());
            return Optional.ofNullable((String) mcp.getMethod("getMCVersion").invoke(null));
        } catch (ReflectiveOperationException | LinkageError e) {
            return Optional.empty();
        }
    }

    private static boolean exists(String className) {
        try {
            Class.forName(className, false, Ml8Platform.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
