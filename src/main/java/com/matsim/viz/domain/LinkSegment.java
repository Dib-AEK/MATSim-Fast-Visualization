package com.matsim.viz.domain;

import java.util.Collections;
import java.util.Map;
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
        Set<String> allowedModes,
        Map<String, String> attributes
) {
    public LinkSegment {
        allowedModes = allowedModes == null ? Set.of() : Collections.unmodifiableSet(allowedModes);
        attributes = attributes == null ? Map.of() : Collections.unmodifiableMap(attributes);
    }

    public boolean allowsMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return true;
        }
        return allowedModes.contains(mode.toLowerCase());
    }

    public String attribute(String key) {
        return attributes.get(key);
    }
}
