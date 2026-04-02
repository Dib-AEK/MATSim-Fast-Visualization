package com.matsim.viz.domain;

public record VehicleTraversal(
        int index,
        String vehicleId,
        String linkId,
        double enterTimeSeconds,
        double leaveTimeSeconds
) {
}
