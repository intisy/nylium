package io.github.intisy.nylium.bootstrap.ml9;

import cpw.mods.modlauncher.api.NamedPath;
import cpw.mods.modlauncher.serviceapi.ILaunchPluginService;
import org.objectweb.asm.Type;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @implNote Shares a jar with {@link NyliumMl9TransformationService} and both are listed in the same
 * generation-agnostic services files, so this runs the same {@code SecureJar}-presence check that
 * service uses, rather than only relying on {@link Ml9Bridge#platform} staying {@code null} because
 * the sibling service's own check skipped {@code NyliumKernel.boot}. That coupling is empirically
 * safe but not independently auditable, and this class is the only place a stray copy of it ends up
 * on a ModLauncher 8 boot layer with nothing else to catch it.
 * @implNote This is the earliest point at which the module injected in {@code beginScanning}
 * becomes loadable; see the spike doc, Finding 4.
 */
public final class NyliumMl9LaunchPlugin implements ILaunchPluginService {

    private final AtomicBoolean activated = new AtomicBoolean();

    @Override
    public String name() {
        return "nylium-ml9-plugin";
    }

    @Override
    public EnumSet<Phase> handlesClass(Type classType, boolean isEmpty) {
        return EnumSet.noneOf(Phase.class);
    }

    /**
     * @implNote ModLauncher 10.2.4 added the one-argument {@code initializeLaunch} and deprecated
     * this two-argument form for removal, but the spike observed 10.2.4 still invoking both when
     * both are overridden; ModLauncher 10.0.9 (Forge 1.17 to 1.20.x) declares only this form.
     * Overriding it alone therefore covers the whole ModLauncher 9+ range without needing to guess
     * which arity a given Forge version will call; {@link #activated} makes a duplicate call from a
     * newer ModLauncher harmless. See the spike doc's coverage table.
     */
    @Override
    public void initializeLaunch(ITransformerLoader transformerLoader, NamedPath[] namedPaths) {
        activate();
    }

    private void activate() {
        if (!isModLauncher9OrNewer() || !activated.compareAndSet(false, true)) {
            return;
        }
        Ml9Platform platform = Ml9Bridge.platform;
        if (platform == null) {
            System.err.println("[Nylium] WARNING: ModLauncher reached initializeLaunch without "
                    + "NyliumMl9TransformationService ever booting the kernel, so no module was "
                    + "loaded. That service is listed in the same jar's "
                    + "META-INF/services/cpw.mods.modlauncher.api.ITransformationService; if it is "
                    + "missing or was never discovered, nothing else will report it.");
            return;
        }
        platform.activate(Ml9Bridge.moduleLayerManager);
        Ml9Bridge.activated = true;
        System.out.println("[Nylium] booted " + Ml9Bridge.module);
    }

    private static boolean isModLauncher9OrNewer() {
        try {
            Class.forName("cpw.mods.jarhandling.SecureJar", false,
                    NyliumMl9LaunchPlugin.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
