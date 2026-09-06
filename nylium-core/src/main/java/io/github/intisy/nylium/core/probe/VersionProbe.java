package io.github.intisy.nylium.core.probe;

import java.util.Optional;

public interface VersionProbe {

    String name();

    Optional<String> detect();
}
