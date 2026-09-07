package io.github.intisy.nylium.conformance;

import io.github.intisy.nylium.conformance.identity.ModuleIdentity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

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
                + "sharedClass=" + SharedConstant.VALUE + "\n"
                + "uniqueClass=" + ModuleIdentity.unique() + "\n"
                + "loader=" + LoaderProbe.detect() + "\n"
                + "mcClass=" + McClassProbe.state() + "\n";
        ReportWriter.write(Paths.get(target), report.getBytes(StandardCharsets.UTF_8));
    }
}
