package io.github.intisy.rutter.gradle;

import org.gradle.api.InvalidUserDataException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The vocabulary Fabric Loader accepts for a mod's {@code environment}.
 *
 * @implNote Its own three values, unrelated to {@link io.github.intisy.rutter.api.Environment},
 *     which is what a Rutter <em>module</em> declares. Anything else is refused by the loader at
 *     launch with "Invalid environment type", so it is refused here instead.
 */
final class FabricEnvironments {

    private static final List<String> KNOWN = Arrays.asList("*", "client", "server");

    private FabricEnvironments() {
    }

    static String canonical(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "*";
        }
        String lowerCase = raw.trim().toLowerCase(Locale.ROOT);
        if (!KNOWN.contains(lowerCase)) {
            throw new InvalidUserDataException("The Rutter mod declares an unknown environment '"
                    + raw + "'. Known environments are " + KNOWN + ".");
        }
        return lowerCase;
    }

    static void requireKnown(String raw) {
        canonical(raw);
    }
}
