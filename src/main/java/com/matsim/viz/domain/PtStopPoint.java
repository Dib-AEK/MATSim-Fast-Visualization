package com.matsim.viz.domain;

import java.util.Collections;
import java.util.Set;

public record PtStopPoint(
        String id,
        double x,
        double y,
        Set<String> modes
) {
    public PtStopPoint {
        modes = modes == null ? Set.of() : Collections.unmodifiableSet(modes);
    }
}
