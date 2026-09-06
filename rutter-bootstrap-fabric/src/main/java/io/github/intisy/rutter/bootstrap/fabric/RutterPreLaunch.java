package io.github.intisy.rutter.bootstrap.fabric;

import io.github.intisy.rutter.core.ModuleDescriptor;
import io.github.intisy.rutter.core.RutterKernel;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class RutterPreLaunch implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        ModuleDescriptor module = RutterKernel.boot(
                new FabricPlatform(),
                RutterPreLaunch.class.getClassLoader(),
                FabricLoader.getInstance().getGameDir().resolve("rutter").resolve("cache"));
        System.out.println("[Rutter] booted " + module);
    }
}
