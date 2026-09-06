package io.github.intisy.rutter.gradle;

import io.github.intisy.rutter.api.PlatformId;
import org.gradle.api.InvalidUserDataException;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class Platforms {

    private Platforms() {
    }

    static Set<PlatformId> parse(String moduleName, List<String> declared) {
        if (declared.isEmpty()) {
            throw new InvalidUserDataException("Rutter module '" + moduleName
                    + "' declares no platforms. Known platforms are "
                    + Arrays.toString(PlatformId.values()) + ".");
        }
        Set<PlatformId> platforms = new LinkedHashSet<PlatformId>();
        for (String each : declared) {
            platforms.add(single(moduleName, each));
        }
        return platforms;
    }

    private static PlatformId single(String moduleName, String declared) {
        String text = declared.trim().toUpperCase(Locale.ROOT);
        PlatformId platform;
        try {
            platform = PlatformId.valueOf(text);
        } catch (IllegalArgumentException e) {
            throw new InvalidUserDataException("Rutter module '" + moduleName
                    + "' declares an unknown platform '" + declared + "'. Known platforms are "
                    + Arrays.toString(PlatformId.values()) + ".", e);
        }
        if (platform == PlatformId.NEOFORGE) {
            throw new InvalidUserDataException("Rutter module '" + moduleName
                    + "' declares NEOFORGE, which has no Rutter bootstrap yet (SP-1b). A jar built"
                    + " with it would never dispatch on NeoForge, so it is rejected rather than"
                    + " shipped.");
        }
        return platform;
    }
}
