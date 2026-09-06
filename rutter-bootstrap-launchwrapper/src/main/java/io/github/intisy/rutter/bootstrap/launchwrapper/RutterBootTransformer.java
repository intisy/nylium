package io.github.intisy.rutter.bootstrap.launchwrapper;

import io.github.intisy.rutter.api.Environment;
import io.github.intisy.rutter.core.ModuleDescriptor;
import io.github.intisy.rutter.core.RutterKernel;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import org.spongepowered.asm.launch.MixinBootstrap;

import java.io.File;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @implNote Forge's own deobfuscation tweaker is queued to run after any coremod-discovered
 * tweaker such as {@link RutterTweaker}, so a marker class like {@code net.minecraft.init.Blocks}
 * cannot yet be resolved by name from inside {@code RutterTweaker.injectIntoClassLoader}, and
 * bootstrapping Mixin that early was observed to make that later tweaker's own transformer
 * registration fail bytecode verification. Both are deferred here, to a transformer's
 * {@code transform} call, but firing on literally the first class transformed reintroduces the
 * same problem: FML itself loads several of its own classes (triggering every registered
 * transformer, reentrantly) as part of finishing its own tweaker's setup, before the game is
 * truly ready. Filtering for a vanilla client or server entry point name is the reliable signal
 * that every tweaker (deobfuscation included) has finished and Rutter is no longer racing FML's own
 * startup. Which of the two fired is also the side, which is why {@link LaunchWrapperPlatform}
 * takes the environment as a constructor argument rather than probing for the client class itself.
 * @implNote Unlike {@code injectIntoClassLoader}, which every tweaker always receives, this fires
 * only if one of {@link #LAUNCH_TARGETS} is actually loaded; {@link RutterTweaker} backstops that
 * with a shutdown hook that warns if {@link #BOOTED} was never set, so a variant that renames or
 * bypasses those classes fails loudly instead of silently.
 * @implNote if a module's own entrypoint, invoked from inside {@link RutterKernel#boot} below,
 * eagerly touches the very class this transformer is transforming, that happens from inside that
 * class's own {@code transform()} call, before {@code LaunchClassLoader} has finished defining it;
 * that reentrant path is unexercised territory.
 */
public final class RutterBootTransformer implements IClassTransformer {

    private static final Map<String, Environment> LAUNCH_TARGETS = new HashMap<String, Environment>();

    static {
        LAUNCH_TARGETS.put("net.minecraft.client.Minecraft", Environment.CLIENT);
        LAUNCH_TARGETS.put("net.minecraft.server.MinecraftServer", Environment.SERVER);
    }

    private static final AtomicBoolean BOOTED = new AtomicBoolean(false);

    static volatile File gameDirectory = new File(".");

    static boolean wasBooted() {
        return BOOTED.get();
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        Environment environment = LAUNCH_TARGETS.get(transformedName);
        if (environment != null && BOOTED.compareAndSet(false, true)) {
            MixinBootstrap.init();
            ModuleDescriptor module = RutterKernel.boot(
                    new LaunchWrapperPlatform(environment),
                    Launch.classLoader,
                    Paths.get(gameDirectory.getAbsolutePath()).resolve("rutter").resolve("cache"));
            System.out.println("[Rutter] booted " + module);
        }
        return basicClass;
    }
}
