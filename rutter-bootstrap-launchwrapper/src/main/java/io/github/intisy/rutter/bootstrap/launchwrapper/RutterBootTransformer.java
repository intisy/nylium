package io.github.intisy.rutter.bootstrap.launchwrapper;

import io.github.intisy.rutter.core.RutterKernel;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import org.spongepowered.asm.launch.MixinBootstrap;

import java.io.File;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
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
 * truly ready. Filtering for the vanilla client or server entry point name, the same one {@link
 * LaunchWrapperPlatform#environment()} already checks for, is the reliable signal that every
 * tweaker (deobfuscation included) has finished and Rutter is no longer racing FML's own startup.
 */
public final class RutterBootTransformer implements IClassTransformer {

    private static final Set<String> LAUNCH_TARGETS = new HashSet<String>();

    static {
        LAUNCH_TARGETS.add("net.minecraft.client.Minecraft");
        LAUNCH_TARGETS.add("net.minecraft.server.MinecraftServer");
    }

    private static final AtomicBoolean BOOTED = new AtomicBoolean(false);

    static volatile File gameDirectory = new File(".");

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (LAUNCH_TARGETS.contains(transformedName) && BOOTED.compareAndSet(false, true)) {
            MixinBootstrap.init();
            RutterKernel.boot(
                    new LaunchWrapperPlatform(),
                    Launch.classLoader,
                    Paths.get(gameDirectory.getAbsolutePath()).resolve("rutter").resolve("cache"));
        }
        return basicClass;
    }
}
