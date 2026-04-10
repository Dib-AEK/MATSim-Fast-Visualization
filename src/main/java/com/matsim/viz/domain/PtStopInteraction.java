package com.matsim.viz.domain;

public record PtStopInteraction(
        String stopId,
        String mode,
        double timeSeconds,
        boolean boarding
) {
}
