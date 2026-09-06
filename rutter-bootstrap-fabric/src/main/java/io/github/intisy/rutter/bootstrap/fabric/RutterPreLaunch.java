package io.github.intisy.rutter.bootstrap.fabric;

import io.github.intisy.rutter.core.RutterKernel;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public final class RutterPreLaunch implements PreLaunchEntrypoint {

    @Override
    public void onPreLaunch() {
        RutterKernel.boot(
                new FabricPlatform(),
                RutterPreLaunch.class.getClassLoader(),
                FabricLoader.getInstance().getGameDir().resolve("rutter").resolve("cache"));
    }
}
