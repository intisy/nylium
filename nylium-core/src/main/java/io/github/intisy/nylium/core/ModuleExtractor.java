package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.NyliumException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class ModuleExtractor {

    private static final String INDEX_SUFFIX = ".index";

    private static final String OBJECTS_PREFIX = "nylium/objects/";

    private static final String NESTED_PREFIX = "META-INF/jars/";

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
        if (module.path().endsWith(INDEX_SUFFIX)) {
            landInPlace(target, assembling(ModuleIndex.parse(bytes), source));
        } else {
            landInPlace(target, copying(bytes));
        }
        return target;
    }

    private interface Payload {
        void writeTo(OutputStream target) throws IOException;
    }

    private static Payload copying(final byte[] bytes) {
        return new Payload() {
            @Override
            public void writeTo(OutputStream target) throws IOException {
                target.write(bytes);
            }
        };
    }

    /**
     * @implNote The cache file name comes from the index's digest rather than the rebuilt jar's, so
     *     a module is identified by what it declares rather than by how a given JVM happened to
     *     write the zip.
     */
    private static Payload assembling(final ModuleIndex index, final ClassLoader source) {
        return new Payload() {
            @Override
            public void writeTo(OutputStream target) {
                ModuleAssembler.assemble(index,
                        hash -> source.getResourceAsStream(OBJECTS_PREFIX + hash), target);
            }
        };
    }

    private static byte[] read(ClassLoader source, String path) {
        try (InputStream stream = source.getResourceAsStream(path)) {
            if (stream == null) {
                throw new NyliumException("The Nylium manifest names module '" + path
                        + "' but no such entry exists in the jar.");
            }
            return drain(stream);
        } catch (IOException e) {
            throw new NyliumException("Could not read module '" + path + "' from the jar", e);
        }
    }

    private static byte[] drain(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static String fileName(ModuleDescriptor module, byte[] bytes) {
        String path = module.path();
        int slashForward = path.lastIndexOf('/');
        int slashBackward = path.lastIndexOf('\\');
        int slash = Math.max(slashForward, slashBackward);
        String base = slash >= 0 ? path.substring(slash + 1) : path;
        if (base.endsWith(".jar")) {
            base = base.substring(0, base.length() - 4);
        } else if (base.endsWith(INDEX_SUFFIX)) {
            base = base.substring(0, base.length() - INDEX_SUFFIX.length());
        }
        if (isUnusableFileName(base)) {
            throw new NyliumException("The Nylium manifest names module '" + path
                    + "' with no usable filename (no name before extension or ends with separator).");
        }
        return base + "-" + Sha256.hex(bytes).substring(0, 16) + ".jar";
    }

    private static boolean isUnusableFileName(String base) {
        return base.isEmpty() || base.equals(".") || base.equals("..");
    }

    /**
     * Names the jars bundled inside an already-extracted module.
     *
     * @implNote Fabric's {@code include} nests a dependency at {@code META-INF/jars/}, and its
     *     loader unpacks those during mod discovery, which is over long before a module is
     *     dispatched. So a dispatched module's nested jars have to be extracted here or they are
     *     unreachable: a jar inside a jar is on no classpath.
     */
    public List<String> nestedJarEntries(Path moduleJar) {
        List<String> names = new ArrayList<>();
        try (JarFile jar = new JarFile(moduleJar.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith(NESTED_PREFIX) || !name.endsWith(".jar")) {
                    continue;
                }
                if (name.indexOf('/', NESTED_PREFIX.length()) >= 0) {
                    throw new NyliumException("Module " + moduleJar.getFileName() + " nests '" + name
                            + "' below " + NESTED_PREFIX + ", which is not a layout Fabric's include"
                            + " produces. Nylium extracts direct children only.");
                }
                names.add(name);
            }
        } catch (IOException e) {
            throw new NyliumException("Could not list the jars nested in " + moduleJar, e);
        }
        Collections.sort(names);
        return names;
    }

    public Path extractNested(Path moduleJar, String entry) {
        byte[] bytes = readNested(moduleJar, entry);
        Path target = cacheDirectory.resolve(nestedFileName(moduleJar, entry, bytes));
        if (Files.isRegularFile(target)) {
            return target;
        }
        landInPlace(target, copying(bytes));
        return target;
    }

    private static byte[] readNested(Path moduleJar, String entry) {
        try (JarFile jar = new JarFile(moduleJar.toFile())) {
            JarEntry nested = jar.getJarEntry(entry);
            if (nested == null) {
                throw new NyliumException("Module " + moduleJar.getFileName() + " was expected to"
                        + " nest '" + entry + "', but that entry is gone.");
            }
            try (InputStream stream = jar.getInputStream(nested)) {
                return drain(stream);
            }
        } catch (IOException e) {
            throw new NyliumException("Could not read '" + entry + "' nested in " + moduleJar, e);
        }
    }

    /**
     * @implNote A zip entry name can legally contain {@code ..}, and this one becomes a file name in
     *     the cache directory, so it is validated the same way a module path is.
     */
    private static String nestedFileName(Path moduleJar, String entry, byte[] bytes) {
        String base = entry.substring(NESTED_PREFIX.length(), entry.length() - 4);
        if (isUnusableFileName(base)) {
            throw new NyliumException("Module " + moduleJar.getFileName() + " nests '" + entry
                    + "', which has no usable filename.");
        }
        return base + "-" + Sha256.hex(bytes).substring(0, 16) + ".jar";
    }

    /**
     * @implNote On Windows, a lost atomic-move race (another thread winning the extract) surfaces as
     * AccessDeniedException rather than FileAlreadyExistsException; both are treated as race signals.
     */
    private void landInPlace(Path target, Payload payload) {
        try {
            Files.createDirectories(cacheDirectory);
            Path temporary = Files.createTempFile(cacheDirectory, "nylium-", ".jar.part");
            try {
                try (OutputStream out = Files.newOutputStream(temporary)) {
                    payload.writeTo(out);
                }
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
