package com.matsim.viz.parser;

import org.matsim.core.config.Config;

import java.nio.file.Path;

public record ResolvedSimulationInputs(
        Path matsimConfigFile,
        Path networkFile,
        Path populationFile,
        Path eventsFile,
        Path tripsFile,
        Path outputPersonsFile,
        Path outputPlansFile,
        Config matsimConfig
) {
}
