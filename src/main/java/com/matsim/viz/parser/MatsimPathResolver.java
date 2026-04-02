package com.matsim.viz.parser;

import com.matsim.viz.config.AppConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class MatsimPathResolver {
    private MatsimPathResolver() {
    }

    public static MatsimPaths resolve(AppConfig config) {
        Path outputDir = config.outputDir();
        Path scenarioDir = config.scenarioDir();

        Path network = pick(
                config.explicitNetworkFile(),
                List.of(
                        outputDir.resolve("output_network.xml.gz"),
                        outputDir.resolve("output_network.xml"),
                        outputDir.resolve("network.xml.gz"),
                        outputDir.resolve("network.xml"),
                        scenarioDir.resolve("network.xml.gz"),
                        scenarioDir.resolve("network.xml")
                ),
                "network file"
        );

        Path events = pick(
                config.explicitEventsFile(),
                List.of(
                        outputDir.resolve("output_events.xml.gz"),
                        outputDir.resolve("output_events.xml")
                ),
                "events file"
        );

        Path trips = pick(
                config.explicitTripsFile(),
                List.of(
                        outputDir.resolve("output_trips.csv.gz"),
                        outputDir.resolve("output_trips.csv")
                ),
                "trips file"
        );

        return new MatsimPaths(network, events, trips);
    }

    private static Path pick(String explicitName, List<Path> candidates, String label) {
        List<Path> allCandidates = new ArrayList<>();
        if (explicitName != null && !explicitName.isBlank()) {
            Path explicitPath = Path.of(explicitName);
            if (!explicitPath.isAbsolute()) {
                explicitPath = candidates.getFirst().getParent().resolve(explicitName);
            }
            allCandidates.add(explicitPath);
        }
        allCandidates.addAll(candidates);

        for (Path candidate : allCandidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        throw new IllegalArgumentException("Could not locate " + label + ". Tried: " + allCandidates);
    }
}
