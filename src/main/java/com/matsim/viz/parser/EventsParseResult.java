package com.matsim.viz.parser;

import com.matsim.viz.domain.PtStopInteraction;
import com.matsim.viz.domain.VehicleTraversal;

import java.util.Collections;
import java.util.Map;

public record EventsParseResult(
        VehicleTraversal[] traversals,
        Map<String, String> vehicleToPerson,
        Map<String, String> vehicleToMode,
        PtStopInteraction[] ptStopInteractions
) {
    public EventsParseResult {
        traversals = traversals == null ? new VehicleTraversal[0] : traversals;
        vehicleToPerson = Collections.unmodifiableMap(vehicleToPerson);
        vehicleToMode = Collections.unmodifiableMap(vehicleToMode);
        ptStopInteractions = ptStopInteractions == null ? new PtStopInteraction[0] : ptStopInteractions;
    }
}
