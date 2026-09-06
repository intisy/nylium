package io.github.intisy.rutter.bootstrap.ml8;

import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.IncompatibleEnvironmentException;
import io.github.intisy.rutter.core.ModuleDescriptor;
import io.github.intisy.rutter.core.RutterKernel;

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
public final class RutterMl8Service implements ITransformationService {

    @Override
    public String name() {
        return "rutter-ml8";
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
        ModuleDescriptor module = RutterKernel.boot(
                new Ml8Platform(),
                RutterMl8Service.class.getClassLoader(),
                Paths.get(".").resolve("rutter").resolve("cache"));
        System.out.println("[Rutter] booted " + module);
    }

    @Override
    public List<ITransformer> transformers() {
        return Collections.emptyList();
    }

    private static boolean isModLauncher9OrNewer() {
        try {
            Class.forName("cpw.mods.jarhandling.SecureJar", false,
                    RutterMl8Service.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
