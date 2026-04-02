package com.matsim.viz.parser;

import com.matsim.viz.domain.VehicleTraversal;

import java.util.Collections;
import java.util.Map;

public record EventsParseResult(
        VehicleTraversal[] traversals,
        Map<String, String> vehicleToPerson,
        Map<String, String> vehicleToMode
) {
    public EventsParseResult {
        traversals = traversals == null ? new VehicleTraversal[0] : traversals;
        vehicleToPerson = Collections.unmodifiableMap(vehicleToPerson);
        vehicleToMode = Collections.unmodifiableMap(vehicleToMode);
    }
}
