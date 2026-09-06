package io.github.intisy.nylium.bootstrap.fabric;

import io.github.intisy.nylium.core.ModuleDescriptor;
import io.github.intisy.nylium.core.NyliumKernel;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class NyliumPreLaunch implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        ModuleDescriptor module = NyliumKernel.boot(
                new FabricPlatform(),
                NyliumPreLaunch.class.getClassLoader(),
                FabricLoader.getInstance().getGameDir().resolve("nylium").resolve("cache"));
        System.out.println("[Nylium] booted " + module);
    }
}
