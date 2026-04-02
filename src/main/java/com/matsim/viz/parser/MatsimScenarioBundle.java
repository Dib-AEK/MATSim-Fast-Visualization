package com.matsim.viz.parser;

import com.matsim.viz.domain.NetworkData;
import com.matsim.viz.domain.VehicleMetadata;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

public record MatsimScenarioBundle(
        Path eventsFile,
        NetworkData networkData,
        Map<String, VehicleMetadata> metadataByPerson
) {
    public MatsimScenarioBundle {
        metadataByPerson = Collections.unmodifiableMap(metadataByPerson);
    }
}
