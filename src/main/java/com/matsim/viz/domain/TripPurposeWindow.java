package com.matsim.viz.domain;

public record TripPurposeWindow(
        String personId,
        double departureTimeSeconds,
        double arrivalTimeSeconds,
        String purpose,
        String mode
) {
}
