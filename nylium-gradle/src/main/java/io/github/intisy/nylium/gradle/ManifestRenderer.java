package io.github.intisy.nylium.gradle;

import io.github.intisy.nylium.api.PlatformId;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class ManifestRenderer {

    private ManifestRenderer() {
    }

    /**
     * @implNote Field order matches the manifest the kernel's smoke matrix already proves, and the
     *     index is the module's position, so nothing in the wire format is hand maintained.
     */
    static String render(List<ResolvedModule> modules) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < modules.size(); index++) {
            ResolvedModule module = modules.get(index);
            String prefix = "module." + index + ".";
            append(out, prefix + "path", module.path());
            append(out, prefix + "platforms", join(module.platforms()));
            append(out, prefix + "minecraft", module.minecraft());
            if (module.environment() != null) {
                append(out, prefix + "environment", module.environment().name());
            }
            if (!module.mixins().isEmpty()) {
                append(out, prefix + "mixins", String.join(",", module.mixins()));
            }
            if (module.priority() != 0) {
                append(out, prefix + "priority", Integer.toString(module.priority()));
            }
            if (module.entrypoint() != null) {
                append(out, prefix + "entrypoint", module.entrypoint());
            }
        }
        return out.toString();
    }

    private static String join(Set<PlatformId> platforms) {
        List<String> names = new ArrayList<String>();
        for (PlatformId platform : platforms) {
            names.add(platform.name());
        }
        return String.join(",", names);
    }

    private static void append(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(value).append('\n');
    }
}
