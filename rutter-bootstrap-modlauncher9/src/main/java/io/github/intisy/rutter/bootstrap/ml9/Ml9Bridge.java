package io.github.intisy.rutter.bootstrap.ml9;

import cpw.mods.modlauncher.api.IModuleLayerManager;
import io.github.intisy.rutter.core.ModuleDescriptor;

/**
 * @implNote {@link RutterMl9TransformationService} and {@link RutterMl9LaunchPlugin} are separate
 * {@code ServiceLoader}-instantiated objects with no reference to each other, so the module handed
 * off in {@code beginScanning} and the {@code IModuleLayerManager} captured in {@code completeScan}
 * are bridged here for the plugin service's {@code initializeLaunch} to pick up.
 */
final class Ml9Bridge {

    private Ml9Bridge() {
    }

    static volatile Ml9Platform platform;
    static volatile IModuleLayerManager moduleLayerManager;
    static volatile ModuleDescriptor module;
}
