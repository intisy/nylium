package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.NyliumException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class ModuleAssembler {

    /**
     * @implNote 1980-01-01, the earliest instant a zip entry can carry. Entry times mean nothing
     *     here and the cache file name comes from the index digest rather than these bytes, so
     *     pinning one constant is cheaper than preserving per-entry times through the format.
     */
    private static final long FIXED_ENTRY_TIME = 315532800000L;

    public interface BlobSource {

        /** @return the blob's content, or {@code null} when the jar holds no such object. */
        InputStream open(String hash) throws IOException;
    }

    private ModuleAssembler() {
    }

    /**
     * @implNote Finishes the zip without closing {@code target}: the caller owns that stream and
     *     closes it, which is what lets {@code ModuleExtractor} land the result with its existing
     *     temporary-file-and-atomic-move handling.
     */
    public static void assemble(ModuleIndex index, BlobSource blobs, OutputStream target) {
        try {
            JarOutputStream jar = new JarOutputStream(target);
            for (ModuleIndex.Entry entry : index.entries()) {
                JarEntry written = new JarEntry(entry.name());
                written.setTime(FIXED_ENTRY_TIME);
                jar.putNextEntry(written);
                if (!entry.isDirectory()) {
                    copyBlob(entry, blobs, jar);
                }
                jar.closeEntry();
            }
            jar.finish();
        } catch (IOException e) {
            throw new NyliumException("Could not assemble a Nylium module from its index", e);
        }
    }

    private static void copyBlob(ModuleIndex.Entry entry, BlobSource blobs, OutputStream target)
            throws IOException {
        InputStream stream = blobs.open(entry.hash());
        if (stream == null) {
            throw new NyliumException("A Nylium module index names entry '" + entry.name()
                    + "' with content " + entry.hash() + ", but the jar holds no such object."
                    + " The index and the object store disagree, so the jar is corrupt.");
        }
        try {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) >= 0) {
                target.write(chunk, 0, read);
            }
        } finally {
            stream.close();
        }
    }
}
