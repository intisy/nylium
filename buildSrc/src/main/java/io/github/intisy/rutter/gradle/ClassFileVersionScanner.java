package io.github.intisy.rutter.gradle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ClassFileVersionScanner {

    public static final int JAVA_8 = 52;

    private static final int MAGIC = 0xCAFEBABE;
    private static final int HEADER_LENGTH = 8;

    private ClassFileVersionScanner() {
    }

    public static List<String> scan(Path jarFile) throws IOException {
        List<String> findings = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jarFile.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream stream = zip.getInputStream(entry)) {
                    int major = majorVersion(readFully(stream));
                    if (major != JAVA_8) {
                        findings.add(entry.getName() + " is class file major version " + major
                                + ", not " + JAVA_8);
                    }
                }
            }
        }
        return findings;
    }

    /**
     * @implNote Reads the two version bytes straight out of the header rather than through ASM's
     * {@code ClassReader}, which rejects a class file newer than the ASM build it was compiled
     * against; that would report an unsupported-version failure instead of naming the entry whose
     * version is wrong, which is the whole point of this check.
     */
    public static int majorVersion(byte[] classFile) {
        if (classFile.length < HEADER_LENGTH || readInt(classFile) != MAGIC) {
            throw new IllegalArgumentException("not a class file");
        }
        return ((classFile[6] & 0xFF) << 8) | (classFile[7] & 0xFF);
    }

    private static int readInt(byte[] classFile) {
        return ((classFile[0] & 0xFF) << 24) | ((classFile[1] & 0xFF) << 16)
                | ((classFile[2] & 0xFF) << 8) | (classFile[3] & 0xFF);
    }

    private static byte[] readFully(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = stream.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
