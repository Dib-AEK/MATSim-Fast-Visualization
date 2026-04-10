package com.matsim.viz.parser;

import com.matsim.viz.domain.PtStopPoint;

import java.util.Collections;
import java.util.Map;

public record TransitScheduleData(
        Map<String, String> vehicleToMode,
        Map<String, PtStopPoint> stopsById
) {
    public TransitScheduleData {
        vehicleToMode = vehicleToMode == null ? Map.of() : Collections.unmodifiableMap(vehicleToMode);
        stopsById = stopsById == null ? Map.of() : Collections.unmodifiableMap(stopsById);
    }
}
