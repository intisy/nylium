package io.github.intisy.rutter.testmod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Demonstrates that a mixin registered from a ModLauncher 9+ injected module actually applies to a
 * real game class, rather than only sharing the transforming class loader's identity.
 *
 * @implNote Targets {@code net.minecraft.server.Main} by string, not by class literal, because this
 * module compiles at release 8 with no Minecraft dependency on its own compile classpath. That class
 * is Forge's real dedicated server entry point, verified against the installed 1.21.11-61.1.5 server.
 * @implNote Lives in its own package, separate from {@code io.github.intisy.rutter.testmod}: Mixin
 * treats a config's declared {@code package} as entirely mixin-owned and throws
 * {@code IllegalClassLoadError} on any other class in that package being loaded directly, which
 * broke {@code TestModEntry} when both shared a package.
 */
@Mixin(targets = "net.minecraft.server.Main")
public final class RutterMl9SmokeMixin {

    @Inject(method = "main", at = @At("HEAD"))
    private static void rutterMixinApplied(String[] args, CallbackInfo ci) {
        String target = System.getProperty("rutter.smoke.mixinMarker");
        if (target == null) {
            return;
        }
        writeAtomically(Paths.get(target), "mixin=applied\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeAtomically(Path marker, byte[] bytes) {
        try {
            Files.createDirectories(marker.getParent());
            Path temporary = Files.createTempFile(marker.getParent(), "rutter-mixin-marker-", ".part");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException | AccessDeniedException e) {
                    Files.move(temporary, marker, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
