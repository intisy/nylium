package io.github.intisy.rutter.core.probe;

import java.util.Optional;

public interface VersionProbe {

    String name();

    Optional<String> detect();
}
