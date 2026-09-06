package io.github.intisy.rutter.gradle;

import io.github.intisy.rutter.api.PlatformId;

import java.util.Set;

final class ServiceRenderer {

    static final String ML8_SERVICE = "io.github.intisy.rutter.bootstrap.ml8.RutterMl8Service";
    static final String ML9_SERVICE =
            "io.github.intisy.rutter.bootstrap.ml9.RutterMl9TransformationService";
    static final String ML9_LAUNCH_PLUGIN =
            "io.github.intisy.rutter.bootstrap.ml9.RutterMl9LaunchPlugin";

    private ServiceRenderer() {
    }

    /**
     * @implNote Both ModLauncher backends share this one service file, which is why its contents
     *     depend on the declared platform set rather than being a fixed resource.
     */
    static String transformationServices(Set<PlatformId> platforms) {
        StringBuilder out = new StringBuilder();
        if (platforms.contains(PlatformId.MODLAUNCHER_8)) {
            out.append(ML8_SERVICE).append('\n');
        }
        if (platforms.contains(PlatformId.MODLAUNCHER_9)) {
            out.append(ML9_SERVICE).append('\n');
        }
        return out.length() == 0 ? null : out.toString();
    }

    static String launchPlugins(Set<PlatformId> platforms) {
        return platforms.contains(PlatformId.MODLAUNCHER_9)
                ? ML9_LAUNCH_PLUGIN + "\n"
                : null;
    }
}
