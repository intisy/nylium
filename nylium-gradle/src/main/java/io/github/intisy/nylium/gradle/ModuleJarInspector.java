package io.github.intisy.nylium.gradle;

import org.gradle.api.InvalidUserDataException;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

final class ModuleJarInspector {

    private static final String API_PREFIX = "io/github/intisy/nylium/api/";

    private ModuleJarInspector() {
    }

    static void verify(String moduleName, File jar, List<String> declaredMixins) {
        Set<String> entries = entries(moduleName, jar);
        for (String mixin : declaredMixins) {
            if (!entries.contains(mixin)) {
                throw new InvalidUserDataException("Nylium module '" + moduleName + "' declares the"
                        + " mixin config '" + mixin + "', but " + jar.getName()
                        + " does not contain it. The config has to be inside the module jar, since"
                        + " that is where the platform looks for it.");
            }
        }
        if (entries.contains("nylium-modules.properties")) {
            throw new InvalidUserDataException("Nylium module '" + moduleName + "' contains its own"
                    + " nylium-modules.properties. Only the universal jar carries a manifest.");
        }
        for (String entry : entries) {
            if (entry.startsWith(API_PREFIX)) {
                throw new InvalidUserDataException("Nylium module '" + moduleName + "' bundles the"
                        + " nylium api (" + entry + "). The universal jar already provides it, so"
                        + " the module must not shade it.");
            }
        }
    }

    private static Set<String> entries(String moduleName, File jar) {
        Set<String> names = new LinkedHashSet<String>();
        try (JarFile file = new JarFile(jar)) {
            Enumeration<JarEntry> enumeration = file.entries();
            while (enumeration.hasMoreElements()) {
                names.add(enumeration.nextElement().getName());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Nylium module '" + moduleName + "' has an unreadable jar "
                    + jar + ". Make sure it is a real jar built before nyliumVerifyModules runs.", e);
        }
        return names;
    }
}
