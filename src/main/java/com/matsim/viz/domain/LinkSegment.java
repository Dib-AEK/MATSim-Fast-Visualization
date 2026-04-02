package com.matsim.viz.domain;

import java.util.Collections;
import java.util.Set;

public record LinkSegment(
        String id,
        String fromNodeId,
        String toNodeId,
    double fromX,
    double fromY,
    double toX,
    double toY,
    double length,
    double freeSpeed,
    double lanes,
        Set<String> allowedModes
) {
    public LinkSegment {
        allowedModes = allowedModes == null ? Set.of() : Collections.unmodifiableSet(allowedModes);
    }

    public boolean allowsMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return true;
        }
        return allowedModes.contains(mode.toLowerCase());
    }
}
