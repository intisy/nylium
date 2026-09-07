package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.NyliumException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ModuleExtractor {

    private final Path cacheDirectory;

    public ModuleExtractor(Path cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    public Path extract(ClassLoader source, ModuleDescriptor module) {
        byte[] bytes = read(source, module.path());
        Path target = cacheDirectory.resolve(fileName(module, bytes));
        if (Files.isRegularFile(target)) {
            return target;
        }
        write(bytes, target);
        return target;
    }

    private static byte[] read(ClassLoader source, String path) {
        try (InputStream stream = source.getResourceAsStream(path)) {
            if (stream == null) {
                throw new NyliumException("The Nylium manifest names module '" + path
                        + "' but no such entry exists in the jar.");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new NyliumException("Could not read module '" + path + "' from the jar", e);
        }
    }

    private static String fileName(ModuleDescriptor module, byte[] bytes) {
        String path = module.path();
        int slashForward = path.lastIndexOf('/');
        int slashBackward = path.lastIndexOf('\\');
        int slash = Math.max(slashForward, slashBackward);
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        if (base.endsWith(".jar")) {
            base = base.substring(0, base.length() - 4);
        }
        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            throw new NyliumException("The Nylium manifest names module '" + path
                    + "' with no usable filename (no name before extension or ends with separator).");
        }
        return base + "-" + Sha256.hex(bytes).substring(0, 16) + ".jar";
    }

    /**
     * @implNote On Windows, a lost atomic-move race (another thread winning the extract) surfaces as
     * AccessDeniedException rather than FileAlreadyExistsException; both are treated as race signals.
     */
    private void write(byte[] bytes, Path target) {
        try {
            Files.createDirectories(cacheDirectory);
            Path temporary = Files.createTempFile(cacheDirectory, "nylium-", ".jar.part");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException | AccessDeniedException e) {
                    if (Files.isRegularFile(target)) {
                        Files.deleteIfExists(temporary);
                    } else {
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } finally {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            throw new NyliumException("Could not write module to the Nylium cache at " + target, e);
        }
    }
}
