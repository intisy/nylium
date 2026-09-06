package io.github.intisy.rutter.core.probe;

import io.github.intisy.rutter.api.McVersion;
import io.github.intisy.rutter.api.RutterException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class ProbeChain {

    private final List<VersionProbe> probes;

    public ProbeChain(List<VersionProbe> probes) {
        if (probes == null || probes.isEmpty()) {
            throw new RutterException("A version probe chain needs at least one probe");
        }
        this.probes = Collections.unmodifiableList(new ArrayList<>(probes));
    }

    public McVersion detect() {
        StringBuilder attempts = new StringBuilder();
        for (VersionProbe probe : probes) {
            String outcome;
            try {
                Optional<String> detected = probe.detect();
                if (detected.isPresent()) {
                    try {
                        return McVersion.parse(detected.get());
                    } catch (RutterException e) {
                        outcome = "found '" + detected.get() + "', which Rutter cannot read: " + e.getMessage();
                    }
                } else {
                    outcome = "found nothing";
                }
            } catch (RuntimeException e) {
                outcome = "failed with " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            attempts.append("\n  - ").append(probe.name()).append(": ").append(outcome);
        }
        throw new RutterException(
                "Rutter could not determine the running Minecraft version. Probes tried:" + attempts);
    }
}
