package io.github.intisy.nylium.testmod.mixin;

import io.github.intisy.nylium.testmod.MarkerWriter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

/**
 * Demonstrates that a mixin registered from a ModLauncher 9+ injected module actually applies to a
 * real game class, rather than only sharing the transforming class loader's identity.
 *
 * @implNote Targets {@code net.minecraft.server.Main} by string, not by class literal, because this
 * module compiles at release 8 with no Minecraft dependency on its own compile classpath. That class
 * is Forge's real dedicated server entry point, verified against the installed 1.21.11-61.1.5 server.
 * @implNote Lives in its own package, separate from {@code io.github.intisy.nylium.testmod}: Mixin
 * treats a config's declared {@code package} as entirely mixin-owned and throws
 * {@code IllegalClassLoadError} on any other class in that package being loaded directly, which
 * broke {@code TestModEntry} when both shared a package. {@link MarkerWriter} stays public in the
 * parent package for exactly that reason: this class cannot reach a package-private sibling in
 * {@code io.github.intisy.nylium.testmod}.
 */
@Mixin(targets = "net.minecraft.server.Main")
public final class NyliumMl9SmokeMixin {

    @Inject(method = "main", at = @At("HEAD"))
    private static void nyliumMixinApplied(String[] args, CallbackInfo ci) {
        String target = System.getProperty("nylium.smoke.mixinMarker");
        if (target == null) {
            return;
        }
        MarkerWriter.write(Paths.get(target), "mixin=applied\n".getBytes(StandardCharsets.UTF_8));
    }
}
