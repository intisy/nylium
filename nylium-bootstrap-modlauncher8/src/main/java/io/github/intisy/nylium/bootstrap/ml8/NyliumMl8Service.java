package io.github.intisy.nylium.bootstrap.ml8;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;
import io.github.intisy.nylium.core.ModuleDescriptor;
import io.github.intisy.nylium.core.NyliumKernel;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * @implNote ModLauncher 8 and ModLauncher 9+ share this class's {@code ServiceLoader} file, and
 * {@code ServiceLoader} instantiates every entry regardless of which generation is actually
 * running, so this no-ops when {@code cpw.mods.jarhandling.SecureJar}, a class that exists only on
 * ModLauncher 9+, is present.
 */
public final class NyliumMl8Service implements ITransformationService {

    @Override
    public String name() {
        return "nylium-ml8";
    }

    @Override
    public void initialize(IEnvironment environment) {
    }

    @Override
    public void beginScanning(IEnvironment environment) {
    }

    @Override
    public void onLoad(IEnvironment env, Set<String> otherServices) throws IncompatibleEnvironmentException {
        if (isModLauncher9OrNewer()) {
            return;
        }
        ModuleDescriptor module = NyliumKernel.boot(
                new Ml8Platform(),
                NyliumMl8Service.class.getClassLoader(),
                Paths.get(".").resolve("nylium").resolve("cache"));
        System.out.println("[Nylium] booted " + module);
    }

    @Override
    public List<ITransformer> transformers() {
        return Collections.emptyList();
    }

    private static boolean isModLauncher9OrNewer() {
        try {
            Class.forName("cpw.mods.jarhandling.SecureJar", false,
                    NyliumMl8Service.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
