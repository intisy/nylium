package io.github.intisy.nylium.conformance;

import io.github.intisy.nylium.conformance.identity.ModuleIdentity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

/**
 * @implNote {@code ModuleIdentity.id()}/{@code unique()} are called, not read as
 *     {@code public static final String} constants, because this class is compiled once, against
 *     the {@code identityStub}, and shipped to every module: a constant would be inlined here at
 *     that single compile time, baking the stub's value into every module's copy of this class.
 */
public final class ConformanceEntry {

    private ConformanceEntry() {
    }

    public static void nyliumInit() {
        String target = System.getProperty("nylium.smoke.report");
        if (target == null) {
            return;
        }
        String report = "module=" + ModuleIdentity.id() + "\n"
                + "entrypoint=invoked\n"
                + "sharedClass=" + SharedConstant.value() + "\n"
                + "uniqueClass=" + ModuleIdentity.unique() + "\n"
                + "loader=" + LoaderProbe.detect() + "\n"
                + "mcClass=" + McClassProbe.state() + "\n"
                + "bundledJar=" + BundledJarProbe.state() + "\n";
        ReportWriter.write(Paths.get(target), report.getBytes(StandardCharsets.UTF_8));
    }
}
