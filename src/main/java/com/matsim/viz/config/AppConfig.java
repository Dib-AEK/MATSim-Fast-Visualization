package com.matsim.viz.config;

import java.nio.file.Path;

public record AppConfig(
        Path matsimConfigFile,
        Path cacheDir,
        Path scenarioDir,
        String outputDirName,
        String explicitNetworkFile,
        String explicitEventsFile,
        String explicitTripsFile,
        int playbackStartSeconds,
        int playbackEndSeconds,
        int playbackSpeed
) {
    public boolean hasMatsimConfigFile() {
        return matsimConfigFile != null;
    }

    public Path outputDir() {
        return scenarioDir.resolve(outputDirName);
    }
}
