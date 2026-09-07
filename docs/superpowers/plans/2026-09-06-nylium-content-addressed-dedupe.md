# Content-Addressed Dedupe Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop a Nylium universal jar from shipping N complete copies of a consumer's code for N Minecraft versions, and prove it with a mod that exercises every Nylium feature on five real Minecraft servers.

**Architecture:** Every entry of every module jar is stored once in the universal jar under `nylium/objects/<sha256>`, and each module becomes an index file listing entry name to blob hash. At boot the kernel reads the index and rebuilds exactly one jar in its cache, so the runtime shape stays one jar on one classpath entry and no bootstrap, `Platform`, Mixin or classloader behaviour changes.

**Tech Stack:** Java 8 source and bytecode, Gradle 8.14.4, JUnit 5, Gradle TestKit, Sponge Mixin 0.8.7.

**Spec:** `docs/superpowers/specs/2026-09-06-nylium-content-addressed-dedupe-design.md`

## Global Constraints

Every task's requirements implicitly include this section.

- **Java 8 bytecode in every subproject.** No `var`, no records, no `List.of`, no `Map.of`, no `Stream.toList`. Enforced by `checkClassFileVersion`. Diamond operators, lambdas and try-with-resources are fine.
- **`nylium-api` must expose no `net.minecraft` type.** Nothing in this plan touches `nylium-api`.
- **`nylium-api` and `nylium-core` have zero runtime dependencies and no logging.** Use `java.security.MessageDigest`, never a hashing library. No `System.out` in `nylium-core`.
- **No en dashes or em dashes** anywhere: code, comments, commit messages, docs. Use a plain hyphen, a comma, parentheses, or separate sentences.
- **Comments default to zero.** A comment may only carry non-obvious *why*. Prefer a Javadoc `@implNote` on the declaration it explains over a floating inline paragraph. Never restate what the code does.
- **Commit messages are Conventional Commits:** `type(scope): summary`, imperative, lowercase, no trailing period, no task or phase archaeology in parentheses. Commit as the repo's configured identity; never pass `-c user.email` or `-c user.name`.
- **Work happens on the `development` branch.** It is already checked out.
- **A root `./gradlew build` must never download a Minecraft server.** Smoke provisioning stays gated on the dependency edge, not just `onlyIf`.
- **`./gradlew build` does not run buildSrc's tests.** Nothing in this plan touches buildSrc, so that trap does not apply here, but do not use a green `build` as evidence for anything buildSrc owns.

## File Structure

**`nylium-core`, the kernel. New code is split three ways so each file has one job.**

- Create `nylium-core/src/main/java/io/github/intisy/nylium/core/Sha256.java` - hex SHA-256, shared by the kernel and the plugin so the two sides cannot disagree on a hash.
- Create `nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleIndex.java` - the wire format, parse and render, nothing else.
- Create `nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleAssembler.java` - rebuilds a jar from an index plus a blob source.
- Modify `nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleExtractor.java` - branches on the module path suffix and delegates.

**`nylium-gradle`, the plugin.**

- Create `nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/DedupeWriter.java` - Gradle-free logic producing blobs plus index text.
- Create `nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/NyliumDedupeTask.java` - the task wrapper.
- Modify `NyliumExtension.java` - the `dedupe` property and the module path suffix.
- Modify `NyliumPlugin.java` - registers and wires the task.
- Modify `DifferentialTest.java` - pins `dedupe = false`.

**`nylium-conformance`, a new standalone build (not a subproject of the root build).**

- Create `nylium-conformance/settings.gradle`, `nylium-conformance/build.gradle`, shared sources under `src/main/java`, and one `src/module<Id>/java` per module.

**`smoke`, the acceptance harness.**

- Modify `smoke/build.gradle` - clears `mods/` before install, gains conformance provisioning.
- Create `smoke/src/test/java/io/github/intisy/nylium/smoke/conformance/*` - the conformance assertions.

---

### Task 1: The shared hash helper and the index wire format

**Files:**
- Create: `nylium-core/src/main/java/io/github/intisy/nylium/core/Sha256.java`
- Create: `nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleIndex.java`
- Modify: `nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleExtractor.java`
- Test: `nylium-core/src/test/java/io/github/intisy/nylium/core/ModuleIndexTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `Sha256.hex(byte[]) -> String` (64 lowercase hex chars). `ModuleIndex.HEADER` (`String`), `ModuleIndex.parse(byte[]) -> ModuleIndex`, `ModuleIndex.render(List<ModuleIndex.Entry>) -> String`, `ModuleIndex.entries() -> List<Entry>`, `ModuleIndex.Entry.file(String name, String hash) -> Entry`, `ModuleIndex.Entry.directory(String name) -> Entry`, `Entry.name() -> String`, `Entry.hash() -> String` (null for a directory), `Entry.isDirectory() -> boolean`.

- [ ] **Step 1: Write the failing test**

Create `nylium-core/src/test/java/io/github/intisy/nylium/core/ModuleIndexTest.java`:

```java
package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.NyliumException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleIndexTest {

    private static final String HASH_A =
            "0000000000000000000000000000000000000000000000000000000000000001";
    private static final String HASH_B =
            "00000000000000000000000000000000000000000000000000000000000000ab";

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void rendersAndParsesBackToTheSameEntries() {
        List<ModuleIndex.Entry> entries = Arrays.asList(
                ModuleIndex.Entry.directory("pkg/"),
                ModuleIndex.Entry.file("pkg/A.class", HASH_A),
                ModuleIndex.Entry.file("a name with spaces.txt", HASH_B));

        List<ModuleIndex.Entry> parsed =
                ModuleIndex.parse(utf8(ModuleIndex.render(entries))).entries();

        assertEquals(3, parsed.size());
        assertTrue(parsed.get(0).isDirectory());
        assertEquals("pkg/", parsed.get(0).name());
        assertNull(parsed.get(0).hash());
        assertEquals("pkg/A.class", parsed.get(1).name());
        assertEquals(HASH_A, parsed.get(1).hash());
        assertEquals("a name with spaces.txt", parsed.get(2).name());
        assertEquals(HASH_B, parsed.get(2).hash());
    }

    @Test
    void rendersTheVersionHeaderFirst() {
        String rendered = ModuleIndex.render(new ArrayList<ModuleIndex.Entry>());

        assertEquals(ModuleIndex.HEADER + "\n", rendered);
    }

    @Test
    void refusesAnIndexWithoutTheHeader() {
        NyliumException thrown = assertThrows(NyliumException.class,
                () -> ModuleIndex.parse(utf8("f " + HASH_A + " pkg/A.class\n")));

        assertTrue(thrown.getMessage().contains(ModuleIndex.HEADER), thrown.getMessage());
    }

    @Test
    void refusesAnIndexFromAFutureFormat() {
        assertThrows(NyliumException.class,
                () -> ModuleIndex.parse(utf8("# nylium-index 2\n")));
    }

    @Test
    void refusesALineThatIsNeitherAFileNorADirectory() {
        NyliumException thrown = assertThrows(NyliumException.class, () -> ModuleIndex.parse(
                utf8(ModuleIndex.HEADER + "\nx " + HASH_A + " pkg/A.class\n")));

        assertTrue(thrown.getMessage().contains("2"), thrown.getMessage());
    }

    @Test
    void refusesAFileLineWhoseHashIsNotLowercaseHex() {
        assertThrows(NyliumException.class, () -> ModuleIndex.parse(utf8(ModuleIndex.HEADER
                + "\nf 00000000000000000000000000000000000000000000000000000000000000AB a.txt\n")));
    }

    @Test
    void refusesAnEntryWithNoName() {
        assertThrows(NyliumException.class,
                () -> ModuleIndex.parse(utf8(ModuleIndex.HEADER + "\nd \n")));
    }

    @Test
    void refusesToRenderAnEntryNameContainingALineBreak() {
        List<ModuleIndex.Entry> entries =
                Arrays.asList(ModuleIndex.Entry.file("bad\nname.txt", HASH_A));

        NyliumException thrown =
                assertThrows(NyliumException.class, () -> ModuleIndex.render(entries));

        assertTrue(thrown.getMessage().contains("line break"), thrown.getMessage());
    }

    @Test
    void hashesAreLowercaseHexOfTheRightLength() {
        String hash = Sha256.hex("hello".getBytes(StandardCharsets.UTF_8));

        assertEquals(64, hash.length());
        assertEquals(hash.toLowerCase(java.util.Locale.ROOT), hash);
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :nylium-core:test --tests '*ModuleIndexTest*'`
Expected: FAIL to compile, `cannot find symbol: class ModuleIndex` and `class Sha256`.

- [ ] **Step 3: Write `Sha256`**

Create `nylium-core/src/main/java/io/github/intisy/nylium/core/Sha256.java`:

```java
package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.NyliumException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class Sha256 {

    private Sha256() {
    }

    public static String hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new NyliumException("SHA-256 is unavailable on this JVM", e);
        }
    }
}
```

- [ ] **Step 4: Write `ModuleIndex`**

Create `nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleIndex.java`:

```java
package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.NyliumException;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModuleIndex {

    public static final String HEADER = "# nylium-index 1";

    private static final int HASH_LENGTH = 64;

    public static final class Entry {

        private final String name;
        private final String hash;

        private Entry(String name, String hash) {
            this.name = name;
            this.hash = hash;
        }

        public static Entry file(String name, String hash) {
            return new Entry(name, hash);
        }

        public static Entry directory(String name) {
            return new Entry(name, null);
        }

        public String name() {
            return name;
        }

        public String hash() {
            return hash;
        }

        public boolean isDirectory() {
            return hash == null;
        }
    }

    private final List<Entry> entries;

    private ModuleIndex(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    /**
     * @implNote The name is the whole remainder of its line rather than a delimited field, so an
     *     entry name containing spaces needs no quoting and no escaping anywhere in the format.
     *     That is why {@link #render} rejects a name containing a line break: it is the one
     *     character the format cannot represent.
     */
    public static String render(List<Entry> entries) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Entry entry : entries) {
            requireSingleLine(entry.name());
            if (entry.isDirectory()) {
                out.append("d ").append(entry.name()).append('\n');
            } else {
                out.append("f ").append(entry.hash()).append(' ').append(entry.name()).append('\n');
            }
        }
        return out.toString();
    }

    public static ModuleIndex parse(byte[] bytes) {
        List<Entry> parsed = new ArrayList<Entry>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
        try {
            String header = reader.readLine();
            if (!HEADER.equals(header)) {
                throw new NyliumException("A Nylium module index has to start with '" + HEADER
                        + "' but started with '" + header + "'. The jar was built by a newer"
                        + " Nylium than the one loading it.");
            }
            String line;
            int number = 1;
            while ((line = reader.readLine()) != null) {
                number++;
                if (!line.isEmpty()) {
                    parsed.add(entry(line, number));
                }
            }
        } catch (IOException e) {
            throw new NyliumException("Could not read a Nylium module index", e);
        }
        return new ModuleIndex(parsed);
    }

    private static Entry entry(String line, int number) {
        if (line.startsWith("d ")) {
            return Entry.directory(requireName(line.substring(2), line, number));
        }
        if (line.startsWith("f ") && line.length() > HASH_LENGTH + 3
                && line.charAt(HASH_LENGTH + 2) == ' ') {
            String hash = line.substring(2, HASH_LENGTH + 2);
            if (isLowercaseHex(hash)) {
                return Entry.file(requireName(line.substring(HASH_LENGTH + 3), line, number), hash);
            }
        }
        throw new NyliumException("Line " + number + " of a Nylium module index is not a"
                + " recognised entry: '" + line + "'.");
    }

    private static String requireName(String name, String line, int number) {
        if (name.isEmpty()) {
            throw new NyliumException("Line " + number + " of a Nylium module index names no"
                    + " entry: '" + line + "'.");
        }
        return name;
    }

    private static boolean isLowercaseHex(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean digit = character >= '0' && character <= '9';
            boolean letter = character >= 'a' && character <= 'f';
            if (!digit && !letter) {
                return false;
            }
        }
        return true;
    }

    private static void requireSingleLine(String name) {
        if (name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
            throw new NyliumException("A jar entry name containing a line break cannot be written"
                    + " to a Nylium module index: '" + name + "'.");
        }
    }
}
```

- [ ] **Step 5: Remove the duplicated hash from `ModuleExtractor`**

In `nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleExtractor.java`, delete the private `sha256(byte[])` method and its `MessageDigest`/`NoSuchAlgorithmException` imports, and change the one call site inside `fileName` from `sha256(bytes)` to `Sha256.hex(bytes)`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew :nylium-core:test`
Expected: PASS, including the pre-existing `ModuleExtractorTest`, which proves the hash move changed no cache file name.

- [ ] **Step 7: Commit**

```bash
git add nylium-core/src/main/java/io/github/intisy/nylium/core/Sha256.java nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleIndex.java nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleExtractor.java nylium-core/src/test/java/io/github/intisy/nylium/core/ModuleIndexTest.java
git commit -m "feat(core): add the module index format and a shared sha-256 helper"
```

---

### Task 2: Rebuilding a jar from an index

**Files:**
- Create: `nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleAssembler.java`
- Test: `nylium-core/src/test/java/io/github/intisy/nylium/core/ModuleAssemblerTest.java`

**Interfaces:**
- Consumes: `ModuleIndex`, `ModuleIndex.Entry`, `Sha256.hex` from Task 1.
- Produces: `ModuleAssembler.assemble(ModuleIndex index, ModuleAssembler.BlobSource blobs, OutputStream target)` returning `void`, and the functional interface `ModuleAssembler.BlobSource` with the single method `InputStream open(String hash) throws IOException`, which returns `null` when the blob is absent.

- [ ] **Step 1: Write the failing test**

Create `nylium-core/src/test/java/io/github/intisy/nylium/core/ModuleAssemblerTest.java`:

```java
package io.github.intisy.nylium.core;

import io.github.intisy.nylium.api.NyliumException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleAssemblerTest {

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static ModuleAssembler.BlobSource sourceOf(final Map<String, byte[]> blobs) {
        return new ModuleAssembler.BlobSource() {
            @Override
            public InputStream open(String hash) {
                byte[] content = blobs.get(hash);
                return content == null ? null : new ByteArrayInputStream(content);
            }
        };
    }

    @Test
    void rebuildsEveryEntryInIndexOrderWithItsOwnContent() throws Exception {
        byte[] alpha = utf8("alpha");
        byte[] beta = utf8("beta");
        Map<String, byte[]> blobs = new HashMap<String, byte[]>();
        blobs.put(Sha256.hex(alpha), alpha);
        blobs.put(Sha256.hex(beta), beta);
        ModuleIndex index = ModuleIndex.parse(utf8(ModuleIndex.render(Arrays.asList(
                ModuleIndex.Entry.directory("pkg/"),
                ModuleIndex.Entry.file("pkg/one.txt", Sha256.hex(alpha)),
                ModuleIndex.Entry.file("pkg/two.txt", Sha256.hex(beta))))));
        ByteArrayOutputStream target = new ByteArrayOutputStream();

        ModuleAssembler.assemble(index, sourceOf(blobs), target);

        List<String> names = new ArrayList<String>();
        Map<String, byte[]> contents = new HashMap<String, byte[]>();
        try (JarInputStream jar = new JarInputStream(
                new ByteArrayInputStream(target.toByteArray()))) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                names.add(entry.getName());
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[64];
                int read;
                while ((read = jar.read(chunk)) >= 0) {
                    buffer.write(chunk, 0, read);
                }
                contents.put(entry.getName(), buffer.toByteArray());
            }
        }
        assertEquals(Arrays.asList("pkg/", "pkg/one.txt", "pkg/two.txt"), names);
        assertArrayEquals(alpha, contents.get("pkg/one.txt"));
        assertArrayEquals(beta, contents.get("pkg/two.txt"));
        assertEquals(0, contents.get("pkg/").length);
    }

    @Test
    void reusesOneBlobForTwoEntriesWithTheSameContent() throws Exception {
        byte[] shared = utf8("shared");
        Map<String, byte[]> blobs = new HashMap<String, byte[]>();
        blobs.put(Sha256.hex(shared), shared);
        ModuleIndex index = ModuleIndex.parse(utf8(ModuleIndex.render(Arrays.asList(
                ModuleIndex.Entry.file("a.txt", Sha256.hex(shared)),
                ModuleIndex.Entry.file("b.txt", Sha256.hex(shared))))));
        ByteArrayOutputStream target = new ByteArrayOutputStream();

        ModuleAssembler.assemble(index, sourceOf(blobs), target);

        try (JarInputStream jar = new JarInputStream(
                new ByteArrayInputStream(target.toByteArray()))) {
            assertEquals("a.txt", jar.getNextJarEntry().getName());
            assertEquals("b.txt", jar.getNextJarEntry().getName());
        }
    }

    @Test
    void aMissingBlobNamesBothTheEntryAndTheHash() {
        String hash = Sha256.hex(utf8("absent"));
        ModuleIndex index = ModuleIndex.parse(utf8(ModuleIndex.render(
                Arrays.asList(ModuleIndex.Entry.file("gone.txt", hash)))));

        NyliumException thrown = assertThrows(NyliumException.class, () -> ModuleAssembler.assemble(
                index, sourceOf(new HashMap<String, byte[]>()), new ByteArrayOutputStream()));

        assertTrue(thrown.getMessage().contains("gone.txt"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains(hash), thrown.getMessage());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :nylium-core:test --tests '*ModuleAssemblerTest*'`
Expected: FAIL to compile, `cannot find symbol: class ModuleAssembler`.

- [ ] **Step 3: Write `ModuleAssembler`**

Create `nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleAssembler.java`:

```java
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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :nylium-core:test --tests '*ModuleAssemblerTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleAssembler.java nylium-core/src/test/java/io/github/intisy/nylium/core/ModuleAssemblerTest.java
git commit -m "feat(core): rebuild a module jar from its index and object store"
```

---

### Task 3: Teaching the extractor to read an index

**Files:**
- Modify: `nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleExtractor.java`
- Test: `nylium-core/src/test/java/io/github/intisy/nylium/core/ModuleExtractorTest.java`

**Interfaces:**
- Consumes: `ModuleIndex`, `ModuleAssembler`, `Sha256` from Tasks 1 and 2.
- Produces: no signature change. `ModuleExtractor.extract(ClassLoader, ModuleDescriptor) -> Path` keeps its contract and now also accepts a module whose `path()` ends in `.index`.

- [ ] **Step 1: Write the failing tests**

Append these to `nylium-core/src/test/java/io/github/intisy/nylium/core/ModuleExtractorTest.java`, and add the imports `java.util.jar.JarInputStream`, `java.util.HashMap`, `java.util.Map`, `java.util.ArrayList`, `java.util.List` at the top:

```java
    private static ClassLoader outerJarContaining(Path dir, Map<String, byte[]> entries)
            throws Exception {
        Files.createDirectories(dir);
        Path outer = dir.resolve("outer.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(outer))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return new URLClassLoader(new URL[]{outer.toUri().toURL()}, null);
    }

    private static Map<String, byte[]> indexedModuleJar() {
        byte[] alpha = "alpha".getBytes(StandardCharsets.UTF_8);
        byte[] beta = "beta".getBytes(StandardCharsets.UTF_8);
        String indexText = ModuleIndex.render(java.util.Arrays.asList(
                ModuleIndex.Entry.file("one.txt", Sha256.hex(alpha)),
                ModuleIndex.Entry.file("two.txt", Sha256.hex(beta))));
        Map<String, byte[]> outer = new HashMap<String, byte[]>();
        outer.put("modules/a.index", indexText.getBytes(StandardCharsets.UTF_8));
        outer.put("nylium/objects/" + Sha256.hex(alpha), alpha);
        outer.put("nylium/objects/" + Sha256.hex(beta), beta);
        return outer;
    }

    private static List<String> entryNames(Path jarFile) throws Exception {
        List<String> names = new ArrayList<String>();
        try (JarInputStream jar = new JarInputStream(Files.newInputStream(jarFile))) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    @Test
    void rebuildsAModuleDeclaredAsAnIndex(@TempDir Path dir) throws Exception {
        try (URLClassLoader loader = (URLClassLoader) outerJarContaining(dir, indexedModuleJar())) {
            ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));

            Path extracted = extractor.extract(loader, descriptor("modules/a.index"));

            assertTrue(Files.isRegularFile(extracted));
            assertTrue(extracted.getFileName().toString().startsWith("a-"),
                    extracted.getFileName().toString());
            assertTrue(extracted.getFileName().toString().endsWith(".jar"),
                    extracted.getFileName().toString());
            assertEquals(java.util.Arrays.asList("one.txt", "two.txt"), entryNames(extracted));
        }
    }

    @Test
    void reusesAnAlreadyRebuiltIndexedModule(@TempDir Path dir) throws Exception {
        try (URLClassLoader loader = (URLClassLoader) outerJarContaining(dir, indexedModuleJar())) {
            ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));
            ModuleDescriptor module = descriptor("modules/a.index");

            Path first = extractor.extract(loader, module);
            long stamp = Files.getLastModifiedTime(first).toMillis();
            Path second = extractor.extract(loader, module);

            assertEquals(first, second);
            assertEquals(stamp, Files.getLastModifiedTime(second).toMillis());
        }
    }

    @Test
    void anIndexNamingAnAbsentObjectFailsClearly(@TempDir Path dir) throws Exception {
        Map<String, byte[]> outer = indexedModuleJar();
        outer.remove("nylium/objects/"
                + Sha256.hex("alpha".getBytes(StandardCharsets.UTF_8)));
        try (URLClassLoader loader = (URLClassLoader) outerJarContaining(dir, outer)) {
            ModuleExtractor extractor = new ModuleExtractor(dir.resolve("cache"));

            NyliumException thrown = assertThrows(NyliumException.class,
                    () -> extractor.extract(loader, descriptor("modules/a.index")));

            assertTrue(thrown.getMessage().contains("one.txt"), thrown.getMessage());
        }
    }
```

Also add `import java.nio.charset.StandardCharsets;` if it is not already present.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :nylium-core:test --tests '*ModuleExtractorTest*'`
Expected: FAIL. `rebuildsAModuleDeclaredAsAnIndex` fails because the extractor copies the raw index bytes out as if they were a jar, so `entryNames` throws or returns empty.

- [ ] **Step 3: Rework `ModuleExtractor` to branch on the suffix**

Replace the body of `ModuleExtractor` with this. Keep the class's existing package, and keep the existing `read` and `fileName` helpers exactly as they are apart from the two edits called out below.

```java
    private static final String INDEX_SUFFIX = ".index";

    private static final String OBJECTS_PREFIX = "nylium/objects/";

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
```

Then rename the existing `write(byte[] bytes, Path target)` to `landInPlace(Path target, Payload payload)`, keeping its `@implNote` about the Windows race verbatim, and change only the line that wrote the bytes:

```java
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
```

In `fileName`, extend the extension stripping so an index does not leave `.index` in the cache file name:

```java
        if (base.endsWith(".jar")) {
            base = base.substring(0, base.length() - 4);
        } else if (base.endsWith(INDEX_SUFFIX)) {
            base = base.substring(0, base.length() - INDEX_SUFFIX.length());
        }
```

Add `import java.io.OutputStream;` to the imports.

- [ ] **Step 4: Run the whole kernel suite to verify it passes**

Run: `./gradlew :nylium-core:test`
Expected: PASS. Every pre-existing `ModuleExtractorTest` case must still pass unchanged, which is what proves the `write` to `landInPlace` rework preserved the atomic-move behaviour.

- [ ] **Step 5: Check the bytecode floor**

Run: `./gradlew :nylium-core:checkClassFileVersion`
Expected: PASS. If it fails, a Java 9-or-later API reached the new code; the usual culprit is a diamond on an anonymous class, which is a Java 9 feature.

- [ ] **Step 6: Commit**

```bash
git add nylium-core/src/main/java/io/github/intisy/nylium/core/ModuleExtractor.java nylium-core/src/test/java/io/github/intisy/nylium/core/ModuleExtractorTest.java
git commit -m "feat(core): extract an indexed module by rebuilding it from the object store"
```

---

### Task 4: The build-side dedupe writer

**Files:**
- Create: `nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/DedupeWriter.java`
- Test: `nylium-gradle/src/test/java/io/github/intisy/nylium/gradle/DedupeWriterTest.java`

**Interfaces:**
- Consumes: `ModuleIndex`, `ModuleAssembler`, `Sha256` from Tasks 1 and 2.
- Produces: `DedupeWriter.OBJECTS` and `DedupeWriter.INDEXES` (both `String`, the directory names `objects` and `indexes`), the value type `DedupeWriter.Source` with constructor `Source(String indexFileName, File jar)`, and `DedupeWriter.write(List<Source> sources, Path outputDirectory) -> void`.

- [ ] **Step 1: Write the failing test**

Create `nylium-gradle/src/test/java/io/github/intisy/nylium/gradle/DedupeWriterTest.java`:

```java
package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.core.ModuleAssembler;
import io.github.intisy.nylium.core.ModuleIndex;
import io.github.intisy.nylium.core.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DedupeWriterTest {

    private static Path jarOf(Path dir, String name, Map<String, byte[]> entries) throws Exception {
        Files.createDirectories(dir);
        Path jar = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                if (entry.getValue() != null) {
                    out.write(entry.getValue());
                }
                out.closeEntry();
            }
        }
        return jar;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> readJar(Path jar) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        try (JarInputStream in = new JarInputStream(Files.newInputStream(jar))) {
            JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[512];
                int read;
                while ((read = in.read(chunk)) >= 0) {
                    buffer.write(chunk, 0, read);
                }
                entries.put(entry.getName(), buffer.toByteArray());
            }
        }
        return entries;
    }

    private static Map<String, byte[]> moduleEntries(String unique) {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("pkg/", null);
        entries.put("pkg/Shared.class", utf8("shared bytes"));
        entries.put("pkg/Unique.class", utf8(unique));
        return entries;
    }

    @Test
    void storesAnEntrySharedByTwoModulesOnlyOnce(@TempDir Path dir) throws Exception {
        Path first = jarOf(dir.resolve("in"), "one.jar", moduleEntries("one"));
        Path second = jarOf(dir.resolve("in"), "two.jar", moduleEntries("two"));
        Path output = dir.resolve("out");

        DedupeWriter.write(Arrays.asList(
                new DedupeWriter.Source("one.index", first.toFile()),
                new DedupeWriter.Source("two.index", second.toFile())), output);

        List<Path> objects = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream =
                     Files.list(output.resolve(DedupeWriter.OBJECTS))) {
            stream.forEach(objects::add);
        }
        assertEquals(3, objects.size(), "expected shared plus two unique blobs, got " + objects);
        assertTrue(Files.isRegularFile(output.resolve(DedupeWriter.OBJECTS)
                .resolve(Sha256.hex(utf8("shared bytes")))));
    }

    @Test
    void everyIndexRebuildsItsModuleEntryForEntry(@TempDir Path dir) throws Exception {
        Path first = jarOf(dir.resolve("in"), "one.jar", moduleEntries("one"));
        Path second = jarOf(dir.resolve("in"), "two.jar", moduleEntries("two"));
        Path output = dir.resolve("out");
        DedupeWriter.write(Arrays.asList(
                new DedupeWriter.Source("one.index", first.toFile()),
                new DedupeWriter.Source("two.index", second.toFile())), output);

        assertRebuilds(output, "one.index", first);
        assertRebuilds(output, "two.index", second);
    }

    @Test
    void writesNoObjectForADirectoryEntry(@TempDir Path dir) throws Exception {
        Map<String, byte[]> onlyDirectories = new LinkedHashMap<String, byte[]>();
        onlyDirectories.put("pkg/", null);
        Path jar = jarOf(dir.resolve("in"), "one.jar", onlyDirectories);
        Path output = dir.resolve("out");

        DedupeWriter.write(Arrays.asList(
                new DedupeWriter.Source("one.index", jar.toFile())), output);

        try (java.util.stream.Stream<Path> stream =
                     Files.list(output.resolve(DedupeWriter.OBJECTS))) {
            assertEquals(0, stream.count());
        }
    }

    private static void assertRebuilds(Path output, String indexName, Path original)
            throws Exception {
        final Path objects = output.resolve(DedupeWriter.OBJECTS);
        ModuleIndex index = ModuleIndex.parse(Files.readAllBytes(
                output.resolve(DedupeWriter.INDEXES).resolve(indexName)));
        ByteArrayOutputStream rebuilt = new ByteArrayOutputStream();
        ModuleAssembler.assemble(index, new ModuleAssembler.BlobSource() {
            @Override
            public InputStream open(String hash) throws java.io.IOException {
                Path blob = objects.resolve(hash);
                return Files.isRegularFile(blob) ? new ByteArrayInputStream(
                        Files.readAllBytes(blob)) : null;
            }
        }, rebuilt);

        Map<String, byte[]> expected = readJar(original);
        Map<String, byte[]> actual = new LinkedHashMap<String, byte[]>();
        try (JarInputStream in = new JarInputStream(
                new ByteArrayInputStream(rebuilt.toByteArray()))) {
            JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[512];
                int read;
                while ((read = in.read(chunk)) >= 0) {
                    buffer.write(chunk, 0, read);
                }
                actual.put(entry.getName(), buffer.toByteArray());
            }
        }
        assertEquals(new ArrayList<String>(expected.keySet()), new ArrayList<String>(actual.keySet()),
                indexName + " rebuilt different entry names or a different order");
        for (String name : expected.keySet()) {
            assertArrayEquals(expected.get(name), actual.get(name),
                    indexName + " rebuilt " + name + " with different content");
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :nylium-gradle:test --tests '*DedupeWriterTest*'`
Expected: FAIL to compile, `cannot find symbol: class DedupeWriter`.

- [ ] **Step 3: Write `DedupeWriter`**

Create `nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/DedupeWriter.java`:

```java
package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.core.ModuleIndex;
import io.github.intisy.nylium.core.Sha256;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class DedupeWriter {

    static final String OBJECTS = "objects";

    static final String INDEXES = "indexes";

    static final class Source {

        private final String indexFileName;
        private final File jar;

        Source(String indexFileName, File jar) {
            this.indexFileName = indexFileName;
            this.jar = jar;
        }
    }

    private DedupeWriter() {
    }

    /**
     * @implNote Every hash written to an index is added to {@code written} in the same pass that
     *     writes its object, so an index can never name a blob the store does not hold. That
     *     invariant is structural rather than checked, which is why the kernel does not verify
     *     hashes at boot.
     */
    static void write(List<Source> sources, Path outputDirectory) {
        Path objects = outputDirectory.resolve(OBJECTS);
        Path indexes = outputDirectory.resolve(INDEXES);
        try {
            Files.createDirectories(objects);
            Files.createDirectories(indexes);
            Set<String> written = new HashSet<String>();
            for (Source source : sources) {
                String rendered = ModuleIndex.render(entriesOf(source, objects, written));
                Files.write(indexes.resolve(source.indexFileName),
                        rendered.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not write the Nylium dedupe output to " + outputDirectory, e);
        }
    }

    private static List<ModuleIndex.Entry> entriesOf(Source source, Path objects, Set<String> written)
            throws IOException {
        List<ModuleIndex.Entry> entries = new ArrayList<ModuleIndex.Entry>();
        try (JarFile jar = new JarFile(source.jar)) {
            Enumeration<JarEntry> enumeration = jar.entries();
            while (enumeration.hasMoreElements()) {
                JarEntry entry = enumeration.nextElement();
                if (entry.isDirectory()) {
                    entries.add(ModuleIndex.Entry.directory(entry.getName()));
                    continue;
                }
                byte[] content = read(jar, entry);
                String hash = Sha256.hex(content);
                if (written.add(hash)) {
                    Files.write(objects.resolve(hash), content);
                }
                entries.add(ModuleIndex.Entry.file(entry.getName(), hash));
            }
        }
        return entries;
    }

    private static byte[] read(JarFile jar, JarEntry entry) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream stream = jar.getInputStream(entry)) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
        }
        return buffer.toByteArray();
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :nylium-gradle:test --tests '*DedupeWriterTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/DedupeWriter.java nylium-gradle/src/test/java/io/github/intisy/nylium/gradle/DedupeWriterTest.java
git commit -m "feat(gradle): collapse byte-identical module entries into an object store"
```

---

### Task 5: Wiring dedupe into the plugin

**Files:**
- Create: `nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/NyliumDedupeTask.java`
- Modify: `nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/NyliumExtension.java`
- Modify: `nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/NyliumPlugin.java`
- Modify: `nylium-gradle/src/test/java/io/github/intisy/nylium/gradle/DifferentialTest.java`

**Interfaces:**
- Consumes: `DedupeWriter.OBJECTS`, `DedupeWriter.INDEXES`, `DedupeWriter.Source`, `DedupeWriter.write` from Task 4.
- Produces: the task type `NyliumDedupeTask` with `getModules() -> ListProperty<NyliumDedupeTask.ModuleToDedupe>` and `getOutputDirectory() -> DirectoryProperty`; the nested type `ModuleToDedupe` with `getIndexFileName() -> Property<String>` and `getJar() -> RegularFileProperty`; the task name `nyliumDedupe`; and on the extension, `getDedupe() -> Property<Boolean>` exposed to consumers as `nylium { dedupe = false }`.

- [ ] **Step 1: Write the failing test**

Create `nylium-gradle/src/test/java/io/github/intisy/nylium/gradle/DedupeFunctionalTest.java`. Copy the fixture-writing helpers from `UpToDateFunctionalTest` in the same package if the shapes differ from the ones below; the fixture must build two module jars whose classes overlap.

```java
package io.github.intisy.nylium.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DedupeFunctionalTest {

    @TempDir
    Path projectDir;

    private void moduleJar(String name, String unique) throws IOException {
        Path jar = projectDir.resolve(name);
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            write(out, "pkg/Shared.class", "shared bytes");
            write(out, "pkg/Unique.class", unique);
        }
    }

    private static void write(ZipOutputStream out, String entry, String content) throws IOException {
        out.putNextEntry(new ZipEntry(entry));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }

    private void fixture(String dedupeLine) throws IOException {
        moduleJar("modules/a.jar", "alpha");
        moduleJar("modules/b.jar", "beta");
        Files.write(projectDir.resolve("settings.gradle"),
                "rootProject.name = 'fixture'\n".getBytes(StandardCharsets.UTF_8));
        String script = "plugins { id 'base'; id 'io.github.intisy.nylium' }\n"
                + "repositories { maven { url = '"
                + System.getProperty("nylium.test.repo").replace('\\', '/') + "' }; mavenCentral() }\n"
                + "nylium {\n"
                + dedupeLine
                + "    mod { id = 'demo'; version = '1.0.0'; modulePrefix = 'demo' }\n"
                + "    module('1.21.11') {\n"
                + "        jar = file('modules/a.jar')\n"
                + "        platforms = ['FABRIC']\n"
                + "        minecraft = '1.21.11'\n"
                + "    }\n"
                + "    module('1.21.10') {\n"
                + "        jar = file('modules/b.jar')\n"
                + "        platforms = ['FABRIC']\n"
                + "        minecraft = '1.21.10'\n"
                + "    }\n"
                + "}\n";
        Files.write(projectDir.resolve("build.gradle"), script.getBytes(StandardCharsets.UTF_8));
    }

    private BuildResult build() {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments("nyliumUniversalJar", "--stacktrace")
                .build();
    }

    private Path producedJar() {
        return projectDir.resolve("build/distributions/fixture-universal.jar");
    }

    @Test
    void dedupesByDefaultWhenTwoModulesAreDeclared() throws Exception {
        fixture("");
        build();

        try (JarFile jar = new JarFile(producedJar().toFile())) {
            assertNotNull(jar.getJarEntry("modules/demo-1.21.11.index"), "index missing");
            assertNotNull(jar.getJarEntry("modules/demo-1.21.10.index"), "index missing");
            assertNull(jar.getJarEntry("modules/demo-1.21.11.jar"), "module jar still shipped");
            assertEquals(3, countObjects(jar),
                    "expected one shared blob plus two unique ones");
            Properties manifest = manifestOf(jar);
            assertEquals("modules/demo-1.21.11.index", manifest.getProperty("module.0.path"));
        }
    }

    @Test
    void producesTheUndedupedShapeWhenDedupeIsOff() throws Exception {
        fixture("    dedupe = false\n");
        build();

        try (JarFile jar = new JarFile(producedJar().toFile())) {
            assertNotNull(jar.getJarEntry("modules/demo-1.21.11.jar"), "module jar missing");
            assertEquals(0, countObjects(jar), "objects shipped with dedupe off");
            Properties manifest = manifestOf(jar);
            assertEquals("modules/demo-1.21.11.jar", manifest.getProperty("module.0.path"));
        }
    }

    @Test
    void leavesASingleModuleUndedupedByDefault() throws Exception {
        moduleJar("modules/a.jar", "alpha");
        Files.write(projectDir.resolve("settings.gradle"),
                "rootProject.name = 'fixture'\n".getBytes(StandardCharsets.UTF_8));
        String single = "plugins { id 'base'; id 'io.github.intisy.nylium' }\n"
                + "repositories { maven { url = '"
                + System.getProperty("nylium.test.repo").replace('\\', '/') + "' }; mavenCentral() }\n"
                + "nylium {\n"
                + "    mod { id = 'demo'; version = '1.0.0'; modulePrefix = 'demo' }\n"
                + "    module('1.21.11') {\n"
                + "        jar = file('modules/a.jar')\n"
                + "        platforms = ['FABRIC']\n"
                + "        minecraft = '1.21.11'\n"
                + "    }\n"
                + "}\n";
        Files.write(projectDir.resolve("build.gradle"), single.getBytes(StandardCharsets.UTF_8));

        build();

        try (JarFile jar = new JarFile(producedJar().toFile())) {
            assertNotNull(jar.getJarEntry("modules/demo-1.21.11.jar"), "module jar missing");
            assertNull(jar.getJarEntry("modules/demo-1.21.11.index"), "a lone module was deduped");
            assertEquals(0, countObjects(jar), "a lone module produced objects");
        }
    }

    @Test
    void theDedupedJarIsSmallerThanTheUndedupedOne() throws Exception {
        fixture("    dedupe = false\n");
        build();
        long undeduped = Files.size(producedJar());

        fixture("");
        build();
        long deduped = Files.size(producedJar());

        assertTrue(deduped < undeduped,
                "deduped " + deduped + " was not smaller than undeduped " + undeduped);
    }

    private static int countObjects(JarFile jar) {
        int count = 0;
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().startsWith("nylium/objects/")) {
                count++;
            }
        }
        return count;
    }

    private static Properties manifestOf(JarFile jar) throws IOException {
        Properties properties = new Properties();
        try (java.io.Reader reader = new java.io.InputStreamReader(
                jar.getInputStream(jar.getJarEntry("nylium-modules.properties")),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :nylium-gradle:test --tests '*DedupeFunctionalTest*'`
Expected: FAIL. `dedupesByDefaultWhenTwoModulesAreDeclared` fails because `modules/demo-1.21.11.index` is absent; `producesTheUndedupedShapeWhenDedupeIsOff` fails on the unknown `dedupe` property.

- [ ] **Step 3: Write `NyliumDedupeTask`**

Create `nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/NyliumDedupeTask.java`:

```java
package io.github.intisy.nylium.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public abstract class NyliumDedupeTask extends DefaultTask {

    public abstract static class ModuleToDedupe {

        @Input
        public abstract Property<String> getIndexFileName();

        @InputFile
        @PathSensitive(PathSensitivity.NAME_ONLY)
        public abstract RegularFileProperty getJar();
    }

    @Nested
    public abstract ListProperty<ModuleToDedupe> getModules();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    /**
     * @implNote The directory is emptied first because Gradle never removes the output belonging to
     *     a module a consumer dropped between builds, and a stale object or index would then be
     *     copied straight into the universal jar. This is the same hazard {@code GeneratedFile}
     *     records for the metadata writers.
     */
    @TaskAction
    public void dedupe() {
        Path output = getOutputDirectory().get().getAsFile().toPath();
        empty(output);
        List<DedupeWriter.Source> sources = new ArrayList<DedupeWriter.Source>();
        for (ModuleToDedupe module : getModules().get()) {
            sources.add(new DedupeWriter.Source(module.getIndexFileName().get(),
                    module.getJar().get().getAsFile()));
        }
        DedupeWriter.write(sources, output);
    }

    private static void empty(Path directory) {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path visited, IOException failure)
                        throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    if (!visited.equals(directory)) {
                        Files.delete(visited);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Could not empty " + directory, e);
        }
    }
}
```

- [ ] **Step 4: Add the `dedupe` property to `NyliumExtension`**

In `NyliumExtension.java`, add the field, initialise it in the constructor, expose it, and thread the decision into the module path.

```java
    private final Property<Boolean> dedupe;
```

In the constructor, after the existing assignments:

```java
        this.dedupe = objects.property(Boolean.class);
```

Add the accessor and the resolver, importing `org.gradle.api.provider.Property`:

```java
    /**
     * Whether byte-identical entries shared by the module jars are stored once and each module
     * shipped as an index. Defaults to on once a second module exists, and off for a single module,
     * where an index is pure indirection.
     */
    public Property<Boolean> getDedupe() {
        return dedupe;
    }

    boolean dedupeEnabled() {
        return dedupe.getOrElse(modules.size() >= 2);
    }
```

In `resolve()`, capture the decision once and pass it down:

```java
        boolean deduped = dedupeEnabled();
        List<ResolvedModule> resolved = new ArrayList<ResolvedModule>();
        for (String name : moduleOrder) {
            resolved.add(resolveOne(modules.getByName(name), prefix, deduped));
        }
```

Change `resolveOne`'s signature to `private ResolvedModule resolveOne(ModuleSpec spec, String prefix, boolean deduped)` and its `return` so the path carries the right suffix:

```java
        String suffix = deduped ? ".index" : ".jar";
        return new ResolvedModule(name, "modules/" + prefix + "-" + name + suffix, platforms,
                minecraft, environment(name, spec.getEnvironment().getOrNull()),
                spec.getMixins().get(), spec.getPriority().getOrElse(0),
                emptyToNull(spec.getEntrypoint().getOrNull()), spec.getJar());
```

- [ ] **Step 5: Wire the task into `NyliumPlugin`**

In `NyliumPlugin.apply`, register the task next to the others:

```java
        final TaskProvider<NyliumDedupeTask> dedupe = project.getTasks().register(
                "nyliumDedupe", NyliumDedupeTask.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Collapses byte-identical entries shared by the module jars.");
            task.getOutputDirectory().set(
                    project.getLayout().getBuildDirectory().dir("nylium/dedupe"));
        });
```

Add `failWhenInvokedWithNoModules(dedupe);` alongside the other three in the empty-modules branch.

In the `afterEvaluate` block, after `List<ResolvedModule> modules = nylium.resolve();`, add:

```java
            boolean deduped = nylium.dedupeEnabled();
            if (deduped) {
                dedupe.configure(task -> {
                    for (ResolvedModule module : modules) {
                        task.getModules().add(moduleToDedupe(evaluated, module));
                    }
                });
            }
```

and change the universal jar configuration line to pass both:

```java
            universalJar.configure(jar -> fillUniversalJar(jar, evaluated, embed, generated,
                    modules, platforms, deduped, dedupe));
```

Add the mapper next to `moduleToVerify`:

```java
    private static NyliumDedupeTask.ModuleToDedupe moduleToDedupe(Project project,
                                                                 ResolvedModule module) {
        NyliumDedupeTask.ModuleToDedupe entry = project.getObjects()
                .newInstance(NyliumDedupeTask.ModuleToDedupe.class);
        String path = module.path();
        entry.getIndexFileName().set(path.substring(path.lastIndexOf('/') + 1));
        entry.getJar().set(module.jar());
        return entry;
    }
```

Change `fillUniversalJar` to take the two new parameters and branch:

```java
    private void fillUniversalJar(Jar jar, Project project, Configuration embed,
                                  List<GeneratedFile> generated, List<ResolvedModule> modules,
                                  Set<PlatformId> platforms, boolean deduped,
                                  TaskProvider<NyliumDedupeTask> dedupe) {
        embedInto(jar, project, embed);
        for (GeneratedFile file : generated) {
            jar.from(file.destination(), copy -> copy.into(file.parentDirectory()));
        }
        if (deduped) {
            addDedupedModules(jar, dedupe);
        } else {
            for (ResolvedModule module : modules) {
                addModuleJar(jar, module);
            }
        }
        if (platforms.contains(PlatformId.LAUNCHWRAPPER)) {
            jar.getManifest().getAttributes().put("TweakClass",
                    "io.github.intisy.nylium.bootstrap.launchwrapper.NyliumTweaker");
        }
    }

    private static void addDedupedModules(Jar jar, TaskProvider<NyliumDedupeTask> dedupe) {
        jar.dependsOn(dedupe);
        jar.from(dedupe.flatMap(NyliumDedupeTask::getOutputDirectory)
                        .map(directory -> directory.dir(DedupeWriter.OBJECTS)),
                copy -> copy.into("nylium/objects"));
        jar.from(dedupe.flatMap(NyliumDedupeTask::getOutputDirectory)
                        .map(directory -> directory.dir(DedupeWriter.INDEXES)),
                copy -> copy.into("modules"));
    }
```

- [ ] **Step 6: Pin `dedupe = false` in the differential gate**

In `DifferentialTest.java`, find the fixture builder around line 265 and insert the pin as the first line inside the `nylium {` block, immediately before `    mod {`:

```java
                + "nylium {\n"
                + "    dedupe = false\n"
```

Add this Javadoc to the method that builds that script, so the pin is not read later as an oversight:

```java
    /**
     * @implNote Pins {@code dedupe = false}. This gate compares the plugin's output byte for byte
     *     against {@code nylium-testmod}'s hand-rolled reference, which ships whole module jars, so
     *     the two are only comparable with dedupe off. Turning it on here would not find a bug, it
     *     would compare two different jar layouts.
     */
```

- [ ] **Step 7: Run the plugin suite to verify it passes**

Run: `./gradlew :nylium-gradle:test`
Expected: PASS, including `DifferentialTest` and `DedupeFunctionalTest`. `DifferentialTest` staying green is the proof that dedupe changed nothing about the undeduped path.

- [ ] **Step 8: Commit**

```bash
git add nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/NyliumDedupeTask.java nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/NyliumExtension.java nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/NyliumPlugin.java nylium-gradle/src/test/java/io/github/intisy/nylium/gradle/DedupeFunctionalTest.java nylium-gradle/src/test/java/io/github/intisy/nylium/gradle/DifferentialTest.java
git commit -m "feat(gradle): ship deduped modules as indexes over a shared object store"
```

---

### Task 6: Up-to-date and configuration cache behaviour for the new task

**Files:**
- Modify: `nylium-gradle/src/test/java/io/github/intisy/nylium/gradle/DedupeFunctionalTest.java`

**Interfaces:**
- Consumes: everything from Task 5.
- Produces: no new production interface.

- [ ] **Step 1: Write the failing tests**

Append to `DedupeFunctionalTest`, adding the imports `org.gradle.testkit.runner.TaskOutcome` and `java.util.Arrays`:

```java
    private BuildResult buildWith(String... extraArguments) {
        java.util.List<String> arguments = new java.util.ArrayList<String>(
                Arrays.asList("nyliumUniversalJar", "--stacktrace"));
        arguments.addAll(Arrays.asList(extraArguments));
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withPluginClasspath()
                .withArguments(arguments)
                .build();
    }

    @Test
    void isUpToDateOnASecondBuildWithNoChanges() throws Exception {
        fixture("");
        build();

        BuildResult second = build();

        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":nyliumDedupe").getOutcome());
    }

    @Test
    void rerunsWhenAModuleJarChanges() throws Exception {
        fixture("");
        build();

        moduleJar("modules/b.jar", "beta changed");
        BuildResult second = build();

        assertEquals(TaskOutcome.SUCCESS, second.task(":nyliumDedupe").getOutcome());
    }

    /**
     * @implNote A dropped module's object would otherwise stay on disk and be copied into the jar,
     *     naming content no index references. The task empties its output directory to prevent it.
     */
    @Test
    void dropsTheObjectsOfAModuleThatWasRemoved() throws Exception {
        fixture("");
        build();

        String single = "plugins { id 'base'; id 'io.github.intisy.nylium' }\n"
                + "repositories { maven { url = '"
                + System.getProperty("nylium.test.repo").replace('\\', '/') + "' }; mavenCentral() }\n"
                + "nylium {\n"
                + "    dedupe = true\n"
                + "    mod { id = 'demo'; version = '1.0.0'; modulePrefix = 'demo' }\n"
                + "    module('1.21.11') {\n"
                + "        jar = file('modules/a.jar')\n"
                + "        platforms = ['FABRIC']\n"
                + "        minecraft = '1.21.11'\n"
                + "    }\n"
                + "}\n";
        Files.write(projectDir.resolve("build.gradle"), single.getBytes(StandardCharsets.UTF_8));
        build();

        try (JarFile jar = new JarFile(producedJar().toFile())) {
            assertEquals(2, countObjects(jar), "the removed module's unique object still shipped");
            assertNull(jar.getJarEntry("modules/demo-1.21.10.index"), "stale index still shipped");
        }
    }

    @Test
    void worksUnderTheConfigurationCache() throws Exception {
        fixture("");
        buildWith("--configuration-cache");

        BuildResult second = buildWith("--configuration-cache");

        assertTrue(second.getOutput().contains("Reusing configuration cache"), second.getOutput());
    }
```

- [ ] **Step 2: Run the tests to verify they fail or pass, and record which**

Run: `./gradlew :nylium-gradle:test --tests '*DedupeFunctionalTest*'`
Expected: `isUpToDateOnASecondBuildWithNoChanges`, `rerunsWhenAModuleJarChanges` and `dropsTheObjectsOfAModuleThatWasRemoved` should already PASS from Task 5's implementation. `worksUnderTheConfigurationCache` is the one at real risk.

If the configuration cache test fails, read the reported problem before changing anything. The likely cause is a `Project` reference captured in a task action. `NyliumDedupeTask` deliberately holds only `Property` values and no `Project`, so a failure means the capture is in the wiring, not the task. Fix by replacing whatever captured `Project` with a `Provider` obtained at configuration time.

- [ ] **Step 3: Confirm all four pass**

Run: `./gradlew :nylium-gradle:test --tests '*DedupeFunctionalTest*'`
Expected: PASS, 7 tests total in the class.

- [ ] **Step 4: Commit**

```bash
git add nylium-gradle/src/test/java/io/github/intisy/nylium/gradle/DedupeFunctionalTest.java
git commit -m "test(gradle): cover dedupe staleness and configuration cache reuse"
```

---

### Task 7: The conformance mod's sources and module jars

**Files:**
- Create: `nylium-conformance/settings.gradle`
- Create: `nylium-conformance/build.gradle`
- Create: `nylium-conformance/src/main/java/io/github/intisy/nylium/conformance/ConformanceEntry.java`
- Create: `nylium-conformance/src/main/java/io/github/intisy/nylium/conformance/ReportWriter.java`
- Create: `nylium-conformance/src/main/java/io/github/intisy/nylium/conformance/SharedConstant.java`
- Create: `nylium-conformance/src/main/java/io/github/intisy/nylium/conformance/LoaderProbe.java`
- Create: `nylium-conformance/src/main/java/io/github/intisy/nylium/conformance/McClassProbe.java`
- Create: `nylium-conformance/src/main/java/io/github/intisy/nylium/conformance/mixin/ConformanceMl9Mixin.java`
- Create: one `nylium-conformance/src/module<Id>/java/io/github/intisy/nylium/conformance/identity/ModuleIdentity.java` per module

**Interfaces:**
- Consumes: nothing from earlier tasks. This is a standalone build.
- Produces: a built module jar per id under `nylium-conformance/build/modules/conformance-<id>.jar`, and the entrypoint class name `io.github.intisy.nylium.conformance.ConformanceEntry` with `public static void nyliumInit()`.

**The eight modules and what each one proves.** Every one of these ids is used verbatim in Task 9's assertions, so do not rename them.

| Module id | Platforms | minecraft | environment | priority | What it proves |
| --- | --- | --- | --- | --- | --- |
| `fabric-1.21.11` | FABRIC | `1.21.11` | unset | unset | selected on the 1.21.11 server |
| `fabric-1.21.11-alt` | FABRIC | `1.21.11` | unset | `-1` | must lose to the above on priority alone |
| `fabric-1.21.11-client` | FABRIC | `1.21.11` | `CLIENT` | unset | must lose on a server despite scoring higher on specificity |
| `fabric-1.21.10` | FABRIC | `1.21.10` | unset | unset | selected on the 1.21.10 server |
| `fabric-broad` | FABRIC | `[1.14,1.22)` | unset | unset | must lose to an exact match on both Fabric servers |
| `launchwrapper` | LAUNCHWRAPPER | `[1.7,1.12.2]` | unset | unset | selected on Forge 1.7.10 |
| `modlauncher8` | MODLAUNCHER_8 | `[1.13,1.16.5]` | unset | unset | selected on Forge 1.16.5 |
| `modlauncher9` | MODLAUNCHER_9 | `1.21.11` | unset | unset | selected on Forge 1.21.11, and its mixin applies |

- [ ] **Step 1: Write the shared sources**

`nylium-conformance/src/main/java/io/github/intisy/nylium/conformance/SharedConstant.java`:

```java
package io.github.intisy.nylium.conformance;

public final class SharedConstant {

    public static final String VALUE = "shared-ok";

    private SharedConstant() {
    }
}
```

`nylium-conformance/src/main/java/io/github/intisy/nylium/conformance/LoaderProbe.java`:

```java
package io.github.intisy.nylium.conformance;

public final class LoaderProbe {

    private LoaderProbe() {
    }

    /**
     * @implNote A module has no way to ask the kernel which platform selected it: {@code Platform}
     *     is handed to the bootstrap, never to the module. Until SP-4's unified loader API exists,
     *     the loader is inferred from what is visible, which is also an independent check on the
     *     bootstrap rather than an echo of it.
     */
    public static String detect() {
        if (exists("net.fabricmc.loader.api.FabricLoader")) {
            return "fabric";
        }
        if (exists("cpw.mods.jarhandling.SecureJar")) {
            return "modlauncher9";
        }
        if (exists("cpw.mods.modlauncher.api.ITransformationService")) {
            return "modlauncher8";
        }
        if (exists("net.minecraft.launchwrapper.Launch")) {
            return "launchwrapper";
        }
        return "unknown";
    }

    static boolean exists(String className) {
        try {
            Class.forName(className, false, LoaderProbe.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
```

`nylium-conformance/src/main/java/io/github/intisy/nylium/conformance/McClassProbe.java`:

```java
package io.github.intisy.nylium.conformance;

public final class McClassProbe {

    private McClassProbe() {
    }

    /**
     * @implNote Answers {@code unavailable} on ModLauncher 8 by design, not by accident: that
     *     backend's service is discovered by a sibling of the class loader hosting the game, which
     *     is Nylium's known limitation 1. Asserting the current answer per backend means the day
     *     the limitation is fixed, the suite says so instead of staying green either way.
     */
    public static String state() {
        boolean reachable = LoaderProbe.exists("net.minecraft.server.MinecraftServer")
                || LoaderProbe.exists("net.minecraft.server.dedicated.DedicatedServer");
        return reachable ? "reachable" : "unavailable";
    }
}
```

`nylium-conformance/src/main/java/io/github/intisy/nylium/conformance/ReportWriter.java`: copy `nylium-testmod/src/main/java/io/github/intisy/nylium/testmod/MarkerWriter.java` verbatim, changing only the package to `io.github.intisy.nylium.conformance`, the class name to `ReportWriter`, and the temporary file prefix to `nylium-report-`. Keep its `@implNote` about atomicity; the reason is unchanged.

`nylium-conformance/src/main/java/io/github/intisy/nylium/conformance/ConformanceEntry.java`:

```java
package io.github.intisy.nylium.conformance;

import io.github.intisy.nylium.conformance.identity.ModuleIdentity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

public final class ConformanceEntry {

    private ConformanceEntry() {
    }

    public static void nyliumInit() {
        String target = System.getProperty("nylium.smoke.report");
        if (target == null) {
            return;
        }
        String report = "module=" + ModuleIdentity.ID + "\n"
                + "entrypoint=invoked\n"
                + "sharedClass=" + SharedConstant.VALUE + "\n"
                + "uniqueClass=" + ModuleIdentity.UNIQUE + "\n"
                + "loader=" + LoaderProbe.detect() + "\n"
                + "mcClass=" + McClassProbe.state() + "\n";
        ReportWriter.write(Paths.get(target), report.getBytes(StandardCharsets.UTF_8));
    }
}
```

`nylium-conformance/src/main/java/io/github/intisy/nylium/conformance/mixin/ConformanceMl9Mixin.java`: copy `nylium-testmod/.../mixin/NyliumMl9SmokeMixin.java` verbatim, changing the package to `io.github.intisy.nylium.conformance.mixin`, the class name to `ConformanceMl9Mixin`, the `MarkerWriter` import and call to `ReportWriter`, and the system property to `nylium.smoke.mixinReport`. Keep both `@implNote` blocks: the reason the target is a string and the reason this class lives in its own package are both still exactly true here.

- [ ] **Step 2: Write the eight per-module identity classes**

For each of the eight ids in the table, create `nylium-conformance/src/module<SafeId>/java/io/github/intisy/nylium/conformance/identity/ModuleIdentity.java`, where `<SafeId>` is the id with `.` and `-` removed. For `fabric-1.21.11` that is `src/modulefabric12111/java/...`.

Each file is this, with `<id>` replaced by the module id from the table:

```java
package io.github.intisy.nylium.conformance.identity;

public final class ModuleIdentity {

    public static final String ID = "<id>";

    public static final String UNIQUE = "unique-<id>";

    private ModuleIdentity() {
    }
}
```

- [ ] **Step 3: Write the standalone build**

`nylium-conformance/settings.gradle`:

```groovy
pluginManagement {
    repositories {
        maven { url = providers.gradleProperty('nyliumRepo').get() }
        gradlePluginPortal()
    }
    /**
     * @implNote The plugins {} block in build.gradle only accepts a literal version string, never
     *     an expression, so the nyliumVersion project property cannot be inlined there directly.
     *     Resolving the version here instead is the documented workaround for a plugin version that
     *     is only known as a Gradle property.
     */
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == 'io.github.intisy.nylium') {
                useVersion(providers.gradleProperty('nyliumVersion').get())
            }
        }
    }
}

rootProject.name = 'nylium-conformance'
```

The `resolutionStrategy` block is not optional. A `plugins { }` block accepts only a literal version, so `id 'io.github.intisy.nylium' version "${providers.gradleProperty('nyliumVersion').get()}"` fails to evaluate. The version has to be supplied here, and `build.gradle` then requests the plugin without one.

`nylium-conformance/build.gradle`:

```groovy
plugins {
    id 'java'
    id 'io.github.intisy.nylium'
}

repositories {
    maven { url = providers.gradleProperty('nyliumRepo').get() }
    mavenCentral()
    maven { name = 'sponge'; url = 'https://repo.spongepowered.org/repository/maven-public/' }
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

tasks.withType(JavaCompile).configureEach {
    options.encoding = 'UTF-8'
    options.release = 8
    options.compilerArgs << '-Xlint:-options'
}

dependencies {
    compileOnly "io.github.intisy.nylium:nylium-api:${providers.gradleProperty('nyliumVersion').get()}"
    compileOnly 'org.spongepowered:mixin:0.8.7'
}

/**
 * @implNote Mixin's annotation processor auto-activates from the dependency and crashes without
 * gson on the processor path; skipping it avoids pulling gson in for one smoke-test mixin. Same
 * reason as nylium-testmod.
 */
tasks.named('compileJava') {
    options.compilerArgs << '-proc:none'
}

def moduleIds = ['fabric-1.21.11', 'fabric-1.21.11-alt', 'fabric-1.21.11-client',
                 'fabric-1.21.10', 'fabric-broad', 'launchwrapper', 'modlauncher8', 'modlauncher9']

def safe = { String id -> id.replace('.', '').replace('-', '') }

moduleIds.each { id ->
    sourceSets.create("module${safe(id)}") {
        java {
            srcDirs = ["src/module${safe(id)}/java"]
        }
        compileClasspath += sourceSets.main.output + configurations.compileClasspath
    }
    tasks.register("moduleJar${safe(id)}", Jar) {
        archiveFileName = "conformance-${id}.jar"
        destinationDirectory = layout.buildDirectory.dir('modules')
        from sourceSets.main.output
        from sourceSets."module${safe(id)}".output
        if (id == 'modlauncher9') {
            from(resources.text.fromString('''
{
  "required": true,
  "minVersion": "0.8",
  "package": "io.github.intisy.nylium.conformance.mixin",
  "target": "DEFAULT",
  "mixins": ["ConformanceMl9Mixin"]
}
'''.trim())) {
                rename '.*', 'mixins.nylium-conformance.json'
            }
        }
    }
}
```

**`sourceSets.main` cannot compile against `ModuleIdentity`,** because that class only exists per module. `ConformanceEntry` references it, so `main` will not compile as written. Fix it by giving `main` a compile-only stub: create `nylium-conformance/src/identityStub/java/io/github/intisy/nylium/conformance/identity/ModuleIdentity.java` with the same shape and `ID = "stub"`, and add to `build.gradle`:

```groovy
sourceSets.main.compileClasspath += files(sourceSets.identityStub.output)
```

after declaring `sourceSets.create('identityStub')` with `srcDirs = ['src/identityStub/java']`. The stub is on the compile classpath only and is never packaged into a module jar, so each module jar carries exactly one real `ModuleIdentity`.

- [ ] **Step 4: Verify the module jars build**

Publish the runtime pieces and the plugin to the test repository first, then build the nested project directly:

```bash
./gradlew publishAllPublicationsToNyliumTestRepository
ls build/test-repo/io/github/intisy/nylium/
```

Expected: directories for `nylium-api`, `nylium-core`, the four bootstraps, and `nylium-gradle`. **If `nylium-gradle` or `io.github.intisy.nylium.gradle.plugin` is missing, stop and fix that first**: the nested build cannot resolve the plugin without both the `pluginMaven` publication and its marker. Add an explicit publication to `nylium-gradle/build.gradle` if the `java-gradle-plugin` defaults did not land in this repository.

Then:

```bash
cd nylium-conformance
../gradlew moduleJarfabric12111 -PnyliumRepo=<absolute path to build/test-repo> -PnyliumVersion=<root project version>
ls build/modules/
cd ..
```

Expected: `conformance-fabric-1.21.11.jar` exists.

- [ ] **Step 5: Commit**

```bash
git add nylium-conformance
git commit -m "feat(conformance): add a mod exercising every nylium feature"
```

---

### Task 8: Building the conformance universal jar with the plugin

**Files:**
- Modify: `nylium-conformance/build.gradle`
- Modify: `build.gradle` (root)

**Interfaces:**
- Consumes: the module jars from Task 7 and the `nylium { }` DSL including `dedupe` from Task 5.
- Produces: the root task `conformanceUniversalJar`, whose output is `nylium-conformance/build/distributions/nylium-conformance-universal.jar`, and the root property `-PnyliumConformanceDedupe=false` to build the undeduped control.

- [ ] **Step 1: Declare the modules in the conformance build**

Append to `nylium-conformance/build.gradle`:

```groovy
def moduleJarFor = { String id ->
    tasks.named("moduleJar${safe(id)}").flatMap { it.archiveFile }
}

nylium {
    dedupe = providers.gradleProperty('nyliumConformanceDedupe').getOrElse('true') == 'true'

    mod {
        id = 'nylium_conformance'
        name = 'Nylium Conformance'
        version = '0.1.0'
        environment = '*'
        fabricLoaderVersion = '>=0.14.0'
        modulePrefix = 'conformance'
    }

    module('fabric-1.21.11') {
        jar = moduleJarFor('fabric-1.21.11')
        platforms = ['FABRIC']
        minecraft = '1.21.11'
        entrypoint = 'io.github.intisy.nylium.conformance.ConformanceEntry'
    }
    module('fabric-1.21.11-alt') {
        jar = moduleJarFor('fabric-1.21.11-alt')
        platforms = ['FABRIC']
        minecraft = '1.21.11'
        priority = -1
        entrypoint = 'io.github.intisy.nylium.conformance.ConformanceEntry'
    }
    module('fabric-1.21.11-client') {
        jar = moduleJarFor('fabric-1.21.11-client')
        platforms = ['FABRIC']
        minecraft = '1.21.11'
        environment = 'CLIENT'
        entrypoint = 'io.github.intisy.nylium.conformance.ConformanceEntry'
    }
    module('fabric-1.21.10') {
        jar = moduleJarFor('fabric-1.21.10')
        platforms = ['FABRIC']
        minecraft = '1.21.10'
        entrypoint = 'io.github.intisy.nylium.conformance.ConformanceEntry'
    }
    module('fabric-broad') {
        jar = moduleJarFor('fabric-broad')
        platforms = ['FABRIC']
        minecraft = '[1.14,1.22)'
        entrypoint = 'io.github.intisy.nylium.conformance.ConformanceEntry'
    }
    module('launchwrapper') {
        jar = moduleJarFor('launchwrapper')
        platforms = ['LAUNCHWRAPPER']
        minecraft = '[1.7,1.12.2]'
        entrypoint = 'io.github.intisy.nylium.conformance.ConformanceEntry'
    }
    module('modlauncher8') {
        jar = moduleJarFor('modlauncher8')
        platforms = ['MODLAUNCHER_8']
        minecraft = '[1.13,1.16.5]'
        entrypoint = 'io.github.intisy.nylium.conformance.ConformanceEntry'
    }
    module('modlauncher9') {
        jar = moduleJarFor('modlauncher9')
        platforms = ['MODLAUNCHER_9']
        minecraft = '1.21.11'
        mixins = ['mixins.nylium-conformance.json']
        entrypoint = 'io.github.intisy.nylium.conformance.ConformanceEntry'
    }
}

/**
 * @implNote Both the name and the directory are pinned rather than left to a convention. Applying
 * the java plugin can move a registered Jar task's default output between build/libs and
 * build/distributions, and smoke/build.gradle reads this exact path.
 */
tasks.named('nyliumUniversalJar') {
    archiveFileName = 'nylium-conformance-universal.jar'
    destinationDirectory = layout.buildDirectory.dir('distributions')
}
```

`ModuleSpec.getJar()` is a `RegularFileProperty`, so assigning the `Provider<RegularFile>` that `moduleJarFor` returns both type-checks and carries the producing task as a dependency. No explicit `dependsOn` is needed, and adding one would hide a broken wiring rather than fix it.

- [ ] **Step 2: Add the root task that drives the nested build**

Append to the root `build.gradle`, outside the `subprojects` block:

```groovy
def conformanceRepo = layout.buildDirectory.dir('test-repo')

/**
 * @implNote nylium-conformance is a separate build rather than a subproject because a Gradle plugin
 * that lives in this build cannot be applied to a sibling subproject of it. Running it against the
 * published plugin is the stronger arrangement anyway: it proves the artifact a consumer would
 * actually resolve, not an in-build shortcut.
 */
tasks.register('conformanceUniversalJar', GradleBuild) {
    group = 'nylium'
    description = 'Builds the conformance universal jar with the published Nylium plugin.'
    dependsOn ext.embeddedArtifactPaths.collect { "${it}:publishAllPublicationsToNyliumTestRepository" }
    dependsOn ':nylium-gradle:publishAllPublicationsToNyliumTestRepository'
    dir = file('nylium-conformance')
    tasks = ['nyliumUniversalJar']
    startParameter.projectProperties = [
            nyliumRepo   : conformanceRepo.get().asFile.toURI().toString(),
            nyliumVersion: project.version.toString(),
            nyliumConformanceDedupe:
                    providers.gradleProperty('nyliumConformanceDedupe').getOrElse('true')
    ]
}
```

- [ ] **Step 3: Build the deduped jar and inspect it**

```bash
./gradlew conformanceUniversalJar
unzip -l nylium-conformance/build/distributions/nylium-conformance-universal.jar | grep -c 'nylium/objects/'
unzip -p nylium-conformance/build/distributions/nylium-conformance-universal.jar nylium-modules.properties
```

Expected: a non-zero object count, and every `module.N.path` ending in `.index`. There should be eight modules, indices 0 through 7, in the declaration order of the table in Task 7.

- [ ] **Step 4: Build the undeduped control and compare sizes**

```bash
./gradlew conformanceUniversalJar -PnyliumConformanceDedupe=false
ls -l nylium-conformance/build/distributions/nylium-conformance-universal.jar
./gradlew conformanceUniversalJar
ls -l nylium-conformance/build/distributions/nylium-conformance-universal.jar
```

Expected: the deduped jar is substantially smaller. Eight modules built from one shared source set should collapse to close to one copy plus eight small identity classes. Record both byte counts; they go into the handoff in Task 10.

- [ ] **Step 5: Commit**

```bash
git add build.gradle nylium-conformance/build.gradle
git commit -m "feat(conformance): build the universal jar with the published plugin"
```

---

### Task 9: The conformance smoke matrix

**Files:**
- Modify: `smoke/build.gradle`
- Create: `smoke/src/test/java/io/github/intisy/nylium/smoke/ConformanceReport.java`
- Create: `smoke/src/test/java/io/github/intisy/nylium/smoke/ConformanceSmokeTest.java`

**Interfaces:**
- Consumes: `nylium-conformance/build/distributions/nylium-conformance-universal.jar` from Task 8; `ServerSmokeHarness.run(Path, List<String>, Path, Duration) -> String` and `ServerSmokeHarness.log(Path) -> String`, both unchanged.
- Produces: the Gradle property `-PnyliumSmokeMod=conformance` selecting which mod the matrix installs and which tests run.

- [ ] **Step 1: Fix the stale-jar trap in provisioning**

In `smoke/build.gradle`, every one of the four provisioning tasks copies the universal jar into a directory. Before each `copy { ... }`, delete any previously installed Nylium jar so two mods can never sit in one `mods/` directory. Add this helper near `resolveUniversalJar`:

```groovy
/**
 * @implNote The harness installs by file name, so a differently named jar does not replace an
 * earlier one: the rename to Nylium once left two universal jars plus a stale extraction cache in
 * every server directory. ModLauncher 8 failed hard on it, because ServiceLoader instantiates every
 * entry in the shared services file, so the dead service booted and killed the JVM. The other three
 * raced past because the harness force-kills as soon as a marker appears.
 */
def clearInstalledMods = { File installDirectory, File serverDirectory ->
    installDirectory.listFiles()?.each { File file ->
        if (file.name.startsWith('nylium-') && file.name.endsWith('.jar')) {
            file.delete()
        }
    }
    new File(serverDirectory, 'nylium').deleteDir()
}
```

The two directories are separate on purpose. The jar is installed into `mods/` for Fabric, 1.7.10 and 1.16.5 but into the server directory itself for 1.21.11, whereas the kernel's extraction cache is always `<serverDirectory>/nylium`. Passing one directory for both would leave that cache in place on three of the four loaders, which is exactly the failure the handoff records as looking like a kernel regression.

Call `clearInstalledMods(modsDir, dir)` immediately before each `copy` in `provisionFabricServers`, `provisionForge1710` and `provisionForge1165`, and `clearInstalledMods(dir, dir)` before the copy in `provisionForge12111`.

- [ ] **Step 2: Make the installed jar and its name selectable**

Replace `resolveUniversalJar` and add a name resolver:

```groovy
def conformanceJar = file('../nylium-conformance/build/distributions/nylium-conformance-universal.jar')

def smokeMod = providers.gradleProperty('nyliumSmokeMod').getOrElse('testmod')

/**
 * @implNote An unknown tag makes includeTags match nothing, so the matrix runs zero tests and
 * reports BUILD SUCCESSFUL. Rejecting the value turns a silently green acceptance run into a named
 * error.
 */
if (!(smokeMod in ['testmod', 'conformance'])) {
    throw new GradleException("-PnyliumSmokeMod must be 'testmod' or 'conformance', not '${smokeMod}'")
}

def resolveUniversalJar = {
    if (project.hasProperty('nyliumSmokeJar')) {
        return new File(project.property('nyliumSmokeJar') as String)
    }
    return smokeMod == 'conformance' ? conformanceJar : defaultUniversalJar.get().asFile
}

def installedJarName = smokeMod == 'conformance'
        ? 'nylium-conformance-universal.jar'
        : 'nylium-testmod-universal.jar'
```

Change every `rename { 'nylium-testmod-universal.jar' }` to `rename { installedJarName }`, and in `gateProvisioningIntoSmokeTest` make the provisioning tasks depend on `':conformanceUniversalJar'` instead of `':nylium-testmod:universalJar'` when `smokeMod == 'conformance'`.

Expose the name and the mod to the tests in the `test` block:

```groovy
    systemProperty 'nylium.smoke.mod', smokeMod
    systemProperty 'nylium.smoke.jarName', installedJarName
```

and select which tests run:

```groovy
    useJUnitPlatform {
        includeTags smokeMod
    }
```

Tag the four existing smoke test classes with `@Tag("testmod")` and the new one with `@Tag("conformance")`, importing `org.junit.jupiter.api.Tag`.

`ModLauncher9SmokeTest` hardcodes `"nylium-testmod-universal.jar"` in its classpath string. Change it to read `System.getProperty("nylium.smoke.jarName")`.

- [ ] **Step 3: Write the report reader**

Create `smoke/src/test/java/io/github/intisy/nylium/smoke/ConformanceReport.java`:

```java
package io.github.intisy.nylium.smoke;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Properties;

final class ConformanceReport {

    private final Properties values = new Properties();

    ConformanceReport(String text) throws IOException {
        try (Reader reader = new StringReader(text)) {
            values.load(reader);
        }
    }

    String get(String key) {
        return values.getProperty(key);
    }
}
```

- [ ] **Step 4: Write the conformance smoke test**

Create `smoke/src/test/java/io/github/intisy/nylium/smoke/ConformanceSmokeTest.java`:

```java
package io.github.intisy.nylium.smoke;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("conformance")
class ConformanceSmokeTest {

    private static final String FORGE_1211_VERSION = "1.21.11-61.1.5";

    private static Path server(String name) {
        return Paths.get(System.getProperty("nylium.smoke.servers")).resolve(name);
    }

    private static String javaExecutable(int version) {
        String path = System.getProperty("nylium.smoke.java." + version);
        if (path == null) {
            throw new IllegalStateException("System property nylium.smoke.java." + version
                    + " is not set; smoke/build.gradle must expose a Java " + version
                    + " toolchain launcher.");
        }
        return path;
    }

    private static ConformanceReport bootAndRead(Path directory, List<String> command,
                                                 Duration timeout) throws Exception {
        Path report = directory.resolve("nylium-conformance-report.properties");
        return new ConformanceReport(ServerSmokeHarness.run(directory, command, report, timeout));
    }

    private static void assertCommonKeys(ConformanceReport report, String module, String loader,
                                         String mcClass) {
        assertEquals(module, report.get("module"));
        assertEquals("invoked", report.get("entrypoint"));
        assertEquals("shared-ok", report.get("sharedClass"));
        assertEquals("unique-" + module, report.get("uniqueClass"));
        assertEquals(loader, report.get("loader"));
        assertEquals(mcClass, report.get("mcClass"));
    }

    private ConformanceReport bootFabric(String minecraftVersion) throws Exception {
        Path directory = server("fabric-" + minecraftVersion);
        Path report = directory.resolve("nylium-conformance-report.properties");
        return bootAndRead(directory, Arrays.asList(
                javaExecutable(21),
                "-Dnylium.smoke.report=" + report.toAbsolutePath(),
                "-jar", "fabric-server-launch.jar",
                "nogui"), Duration.ofMinutes(3));
    }

    @Test
    void picksTheExactModuleOverTheBroadTheAlternateAndTheClientOne() throws Exception {
        assertCommonKeys(bootFabric("1.21.11"), "fabric-1.21.11", "fabric", "reachable");
    }

    @Test
    void picksTheOtherExactModuleOnTheAdjacentVersion() throws Exception {
        assertCommonKeys(bootFabric("1.21.10"), "fabric-1.21.10", "fabric", "reachable");
    }

    @Test
    void dispatchesOnLaunchWrapper() throws Exception {
        Path directory = server("forge-1.7.10");
        Path report = directory.resolve("nylium-conformance-report.properties");
        assertCommonKeys(bootAndRead(directory, Arrays.asList(
                javaExecutable(8),
                "-Dnylium.smoke.report=" + report.toAbsolutePath(),
                "-jar", "forge-server.jar",
                "nogui"), Duration.ofMinutes(3)), "launchwrapper", "launchwrapper", "reachable");
    }

    /**
     * @implNote Asserts {@code mcClass=unavailable}. That is Nylium's known limitation 1, not a
     *     defect in this mod: on ModLauncher 8 the transformation service is discovered by a
     *     sibling of the class loader hosting the game. When the limitation is fixed this test
     *     fails, which is the point of asserting the current answer rather than skipping it.
     */
    @Test
    void dispatchesOnModLauncher8WithoutReachingGameClasses() throws Exception {
        Path directory = server("forge-1.16.5");
        Path report = directory.resolve("nylium-conformance-report.properties");
        assertCommonKeys(bootAndRead(directory, Arrays.asList(
                javaExecutable(8),
                "-Dnylium.smoke.report=" + report.toAbsolutePath(),
                "-jar", "forge-server.jar",
                "nogui"), Duration.ofMinutes(4)), "modlauncher8", "modlauncher8", "unavailable");
    }

    @Test
    void dispatchesOnModLauncher9AndAppliesItsMixin() throws Exception {
        Path directory = server("forge-1.21.11");
        Path report = directory.resolve("nylium-conformance-report.properties");
        Path mixinReport = directory.resolve("nylium-conformance-mixin.properties");
        Files.deleteIfExists(report);

        String classpath = System.getProperty("nylium.smoke.jarName") + File.pathSeparator
                + "forge-" + FORGE_1211_VERSION + "-shim.jar";

        String mixinResult = ServerSmokeHarness.run(directory, Arrays.asList(
                javaExecutable(21),
                "-Dnylium.smoke.report=" + report.toAbsolutePath(),
                "-Dnylium.smoke.mixinReport=" + mixinReport.toAbsolutePath(),
                "-Djava.net.preferIPv6Addresses=system",
                "-cp", classpath,
                "net.minecraftforge.bootstrap.shim.Main",
                "nogui"), mixinReport, Duration.ofMinutes(4));

        assertEquals("mixin=applied", mixinResult.trim());
        if (!Files.isRegularFile(report)) {
            throw new AssertionError("The mixin report was written but " + report + " never was, so"
                    + " the module's entrypoint did not run. Log:\n"
                    + ServerSmokeHarness.log(directory));
        }
        assertCommonKeys(new ConformanceReport(new String(Files.readAllBytes(report),
                StandardCharsets.UTF_8)), "modlauncher9", "modlauncher9", "reachable");
    }
}
```

- [ ] **Step 5: Run the Phase 0 control, with dedupe off**

This is the control the spec requires. It proves the conformance mod itself before dedupe is involved.

```bash
rm -rf smoke/build/servers/*/mods smoke/build/servers/forge-1.21.11/nylium-*.jar
./gradlew conformanceUniversalJar -PnyliumConformanceDedupe=false
./gradlew :smoke:test -PnyliumSmoke -PnyliumSmokeMod=conformance --rerun-tasks
```

Expected: 5 tests, 0 failures. **If this fails, the fault is in the conformance mod or the harness, never in dedupe**, because dedupe is off. Do not proceed to Step 6 until it is green.

- [ ] **Step 6: Run the Phase 3 acceptance, with dedupe on**

```bash
rm -rf smoke/build/servers/*/mods smoke/build/servers/forge-1.21.11/nylium-*.jar
./gradlew conformanceUniversalJar
./gradlew :smoke:test -PnyliumSmoke -PnyliumSmokeMod=conformance --rerun-tasks
```

Expected: 5 tests, 0 failures. `sharedClass` and `uniqueClass` passing together is the end-to-end proof that the rebuilt jar is complete and correct.

Confirm the run actually launched servers rather than reporting `UP-TO-DATE`:

```bash
ls -l smoke/build/servers/*/nylium-conformance-report.properties
```

Expected: five files, all with a timestamp from this run.

- [ ] **Step 7: Measure the two costs the spec refuses to assume**

The spec lists first-launch rebuild cost and ModLauncher 9 blob-lookup volume as risks to be measured. Both are readable from the Step 6 run without instrumenting anything shipped.

For rebuild cost, time the cache miss against the cache hit on one Fabric server:

```bash
rm -rf smoke/build/servers/fabric-1.21.11/nylium/cache
./gradlew :smoke:test -PnyliumSmoke -PnyliumSmokeMod=conformance --rerun-tasks --tests '*picksTheExactModule*'
grep -n 'Done (\|Nylium' smoke/build/servers/fabric-1.21.11/smoke.log | head
```

Run it twice, once with the cache cleared and once warm, and record the wall-clock difference between the two `:smoke:test` runs. Anything under a second is the expected result.

For ModLauncher 9, the module carries roughly as many blob lookups as it has entries, on the service-layer loader. Confirm the run is not measurably slower than the same server booting the testmod jar, whose module is a single read: compare the Step 6 ModLauncher 9 test duration against the Step 7 testmod one in the JUnit XML under `smoke/build/test-results/test/`.

Record both numbers; they go into the handoff in Task 10. If the rebuild takes longer than a second, say so rather than rounding it away: the spec committed to measuring this, and a slow answer is a finding, not a failure.

- [ ] **Step 8: Confirm the testmod matrix still passes**

```bash
rm -rf smoke/build/servers/*/mods smoke/build/servers/forge-1.21.11/nylium-*.jar
./gradlew :smoke:test -PnyliumSmoke --rerun-tasks
```

Expected: 5 tests, 0 failures. This proves the tagging and the `mods/` clearing did not break the existing evidence.

- [ ] **Step 9: Commit**

```bash
git add smoke
git commit -m "test(smoke): run the conformance mod across all four loader families"
```

---

### Task 10: Measure the real ratio and update the documentation

**Files:**
- Modify: `docs/superpowers/specs/2026-09-06-nylium-content-addressed-dedupe-design.md`
- Modify: `docs/superpowers/HANDOFF.md`
- Modify: `CONTENT.md`
- Modify (other repo): `F:\Documents\GitHub\intisy\vendor\baritone\docs\superpowers\HANDOFF.md`

**Interfaces:**
- Consumes: everything above.
- Produces: no code.

- [ ] **Step 1: Measure dedupe on real Baritone bytecode**

Both Baritone common nodes build today. Build them and run the dedupe writer over the two jars:

```bash
cd F:/Documents/GitHub/intisy/vendor/baritone
./gradlew :common:1.21.10:build :common:1.21.11:build
find . -path '*/1.21.1*/build/libs/*.jar' -not -name '*sources*'
```

Then add this throwaway test at `nylium-gradle/src/test/java/io/github/intisy/nylium/gradle/DedupeMeasurementTest.java`, run it, record what it prints, and **delete the file before committing**. It is a measurement instrument, not a gate: it depends on another repository and would fail on any machine without it.

```java
package io.github.intisy.nylium.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

class DedupeMeasurementTest {

    private static final String NODE_10 = "<absolute path to the 1.21.10 common jar>";
    private static final String NODE_11 = "<absolute path to the 1.21.11 common jar>";

    @Test
    void measuresBaritoneTwoNodeDedupe(@TempDir Path output) throws Exception {
        File first = Paths.get(NODE_10).toFile();
        File second = Paths.get(NODE_11).toFile();

        DedupeWriter.write(Arrays.asList(
                new DedupeWriter.Source("a.index", first),
                new DedupeWriter.Source("b.index", second)), output);

        long objects = 0L;
        try (java.util.stream.Stream<Path> stream =
                     Files.list(output.resolve(DedupeWriter.OBJECTS))) {
            for (Path blob : stream.toArray(Path[]::new)) {
                objects += Files.size(blob);
            }
        }
        System.out.println("jars on disk:    " + (first.length() + second.length()));
        System.out.println("object store:    " + objects);
    }
}
```

Replace both path constants with the jars `find` reported. The object-store total against the two jars' combined size is the number that goes into the spec; note that the jars are compressed while the blobs are not, so also report the blob count against the summed entry count for an apples-to-apples ratio.

- [ ] **Step 2: Record the measurement in the spec**

Add a "Measured result" section to the design doc with four numbers: the conformance jar deduped and undeduped from Task 8 Step 4, and the Baritone object-store size against the sum of the two node jars. State the caveat the handoff already carries: 1.21.10 and 1.21.11 are adjacent, so the ratio is unusually favourable and a distant pair would be lower.

- [ ] **Step 3: Update the Nylium handoff**

In `docs/superpowers/HANDOFF.md`:
- Rewrite "The duplication gap, and what it measures" to say candidate 1 is implemented, name the spec and this plan, and keep candidate 2 as the still-open deeper fix.
- Add `nylium-conformance` to "Where things are", including that it is a separate build driven by `./gradlew conformanceUniversalJar` and why.
- Add to "Traps that will bite a fresh session": the smoke matrix now takes `-PnyliumSmokeMod=conformance`, the tests are tagged, and running the wrong tag silently runs zero tests rather than failing.
- Under "What comes next", record SP-2b as done and restate that SP-3 remains blocked on the Stonecutter loader-node work.

- [ ] **Step 4: Update `CONTENT.md`**

`CONTENT.md` is the README source and states the three known limitations. Add the dedupe behaviour to the feature description and note that `nylium { dedupe = false }` restores whole module jars. Do not edit `README.md`; it is generated.

- [ ] **Step 5: Update the Baritone handoff**

In `F:\Documents\GitHub\intisy\vendor\baritone\docs\superpowers\HANDOFF.md`, add to the section describing Nylium that per-version duplication is now solved by content-addressed dedupe, with the measured ratio from Step 1, and that SP-3's remaining blocker is unchanged: making each loader a Stonecutter-versioned project.

- [ ] **Step 6: Run the full build one last time**

```bash
./gradlew build --offline
./gradlew :buildSrc:test
```

Expected: `BUILD SUCCESSFUL` with `:smoke:test SKIPPED`, and buildSrc's 21 tests passing. Run buildSrc's tests explicitly, because a root `build` skips them entirely and stale result XMLs look exactly like fresh passes.

- [ ] **Step 7: Commit**

```bash
git add docs CONTENT.md
git commit -m "docs: record the dedupe measurements and the conformance matrix"
cd F:/Documents/GitHub/intisy/vendor/baritone
git add docs/superpowers/HANDOFF.md
git commit -m "docs(handoff): record that nylium now dedupes per-version modules"
```

---

### Task 11: Split `NyliumPlugin`, optional

The handoff records that `NyliumPlugin` carries five concerns and that the natural split is a `NyliumEmbed` class and a universal-jar factory, "worth doing when the file is next touched, not as a gate". Task 5 touched it and added a sixth concern. This task pays that down. **It is behaviour-preserving and droppable**: if the schedule is tight, skip it and leave the note in the handoff.

**Files:**
- Create: `nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/NyliumEmbed.java`
- Create: `nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/UniversalJarFactory.java`
- Modify: `nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/NyliumPlugin.java`

**Interfaces:**
- Consumes: everything from Task 5.
- Produces: `NyliumEmbed.configuration(Project) -> Configuration`, `NyliumEmbed.addDependencies(Project, Configuration, Set<PlatformId>) -> void`, and `UniversalJarFactory.fill(Jar, Project, Configuration, List<GeneratedFile>, List<ResolvedModule>, Set<PlatformId>, boolean, TaskProvider<NyliumDedupeTask>, ArchiveOperations) -> void`. No public plugin behaviour changes.

- [ ] **Step 1: Confirm the gate is green before moving anything**

Run: `./gradlew :nylium-gradle:test`
Expected: PASS. `DifferentialTest` comparing byte for byte against the hand-rolled reference is the safety net for this whole task; a move that changes output fails it.

- [ ] **Step 2: Move the embed concern**

Move `embedConfiguration`, `addEmbedDependencies`, `addEmbed`, `BOOTSTRAPS`, `bootstraps()` and `pluginVersion()` from `NyliumPlugin` into a new package-private final class `NyliumEmbed`, renaming `embedConfiguration` to `configuration` and `addEmbedDependencies` to `addDependencies`. Keep every existing comment with the code it explains. Update the two call sites in `NyliumPlugin`.

- [ ] **Step 3: Move the jar-filling concern**

Move `fillUniversalJar`, `addModuleJar`, `addDedupedModules` and `embedInto` into a new package-private final class `UniversalJarFactory`, passing `ArchiveOperations` in as a parameter rather than holding it as a field. Update the call site in `NyliumPlugin`.

- [ ] **Step 4: Run the tests to verify nothing changed**

Run: `./gradlew :nylium-gradle:test`
Expected: PASS, with `DifferentialTest` green. If it fails, the move changed the jar's content or entry order; revert and redo the move one method at a time.

- [ ] **Step 5: Commit**

```bash
git add nylium-gradle/src/main/java/io/github/intisy/nylium/gradle/
git commit -m "refactor(gradle): split embedding and jar assembly out of the plugin class"
```
