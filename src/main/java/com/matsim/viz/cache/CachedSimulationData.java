package com.matsim.viz.cache;

import com.matsim.viz.domain.NetworkData;
import com.matsim.viz.domain.PtStopInteraction;
import com.matsim.viz.domain.PtStopPoint;
import com.matsim.viz.domain.VehicleMetadata;
import com.matsim.viz.domain.VehicleTraversal;

import java.util.Map;

public record CachedSimulationData(
        NetworkData networkData,
        VehicleTraversal[] traversals,
        Map<String, String> vehicleToPerson,
        Map<String, String> vehicleToMode,
        Map<String, VehicleMetadata> metadataByPerson,
        Map<String, PtStopPoint> ptStopsById,
        PtStopInteraction[] ptStopInteractions
) {
}
