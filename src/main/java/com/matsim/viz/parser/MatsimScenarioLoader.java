package com.matsim.viz.parser;

import com.matsim.viz.config.AppConfig;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.matsim.viz.domain.VehicleMetadata;

public final class MatsimScenarioLoader {
    public ResolvedSimulationInputs resolveInputs(AppConfig appConfig) {
        if (!appConfig.hasMatsimConfigFile()) {
            throw new IllegalArgumentException("Missing matsim.config.file in app.properties");
        }

        Path matsimConfigPath = appConfig.matsimConfigFile().toAbsolutePath().normalize();
        Config matsimConfig = ConfigUtils.loadConfig(matsimConfigPath.toString());
        Path networkFile = resolveConfigInputPath(matsimConfigPath, matsimConfig.network().getInputFile(), "network.inputFile");
        Path populationFile = resolveConfigInputPath(matsimConfigPath, matsimConfig.plans().getInputFile(), "plans.inputFile");
        Path eventsFile = resolveEventsFile(appConfig, matsimConfigPath, matsimConfig);
        Path tripsFile = resolveTripsFile(appConfig, matsimConfigPath, matsimConfig);
        Path outputPersonsFile = resolveOutputPersonsFile(matsimConfigPath, matsimConfig);
        Path outputPlansFile = resolveOutputPlansFile(matsimConfigPath, matsimConfig);

        return new ResolvedSimulationInputs(
                matsimConfigPath,
                networkFile,
                populationFile,
                eventsFile,
                tripsFile,
                outputPersonsFile,
                outputPlansFile,
                matsimConfig
        );
    }

    public MatsimScenarioBundle load(ResolvedSimulationInputs inputs) {
        Scenario scenario = ScenarioUtils.loadScenario(inputs.matsimConfig());
        Map<String, VehicleMetadata> metadata = new HashMap<>(
                PopulationMetadataExtractor.fromPopulation(scenario.getPopulation())
        );

        if (inputs.outputPersonsFile() != null && Files.exists(inputs.outputPersonsFile())) {
            try {
                Map<String, VehicleMetadata> fromPersons = new PersonsCsvParser().parse(inputs.outputPersonsFile());
                mergeMetadata(metadata, fromPersons);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to read persons metadata from " + inputs.outputPersonsFile(), ex);
            }
        }

        if (inputs.tripsFile() != null && Files.exists(inputs.tripsFile())) {
            try {
                Map<String, VehicleMetadata> fromTrips = new TripsCsvParser().parse(inputs.tripsFile());
                mergeMetadata(metadata, fromTrips);
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to read trips metadata from " + inputs.tripsFile(), ex);
            }
        }

        return new MatsimScenarioBundle(
                inputs.eventsFile(),
                MatsimNetworkConverter.fromMatsim(scenario.getNetwork()),
                metadata
        );
    }

    private static void mergeMetadata(Map<String, VehicleMetadata> base, Map<String, VehicleMetadata> updates) {
        for (Map.Entry<String, VehicleMetadata> entry : updates.entrySet()) {
            String personId = entry.getKey();
            VehicleMetadata update = entry.getValue();
            VehicleMetadata current = base.get(personId);
            if (current == null) {
                base.put(personId, update);
                continue;
            }

            String purpose = firstNonBlank(update.tripPurpose(), current.tripPurpose());
            Integer age = update.age() != null ? update.age() : current.age();
            String sex = firstNonBlank(update.sex(), current.sex());
            base.put(personId, new VehicleMetadata(personId, purpose, age, sex));
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static Path resolveEventsFile(AppConfig appConfig, Path matsimConfigPath, Config matsimConfig) {
        if (appConfig.explicitEventsFile() != null && !appConfig.explicitEventsFile().isBlank()) {
            Path explicit = Path.of(appConfig.explicitEventsFile());
            if (explicit.isAbsolute()) {
                if (Files.exists(explicit)) {
                    return explicit;
                }
            } else {
                Path outputDir = outputDirectory(matsimConfigPath, matsimConfig);
                Path combined = outputDir.resolve(explicit);
                if (Files.exists(combined)) {
                    return combined;
                }
            }
        }

        Path outputDir = outputDirectory(matsimConfigPath, matsimConfig);
        String runId = matsimConfig.controller().getRunId();

        List<Path> candidates = new ArrayList<>();
        candidates.add(outputDir.resolve("output_events.xml.gz"));
        candidates.add(outputDir.resolve("output_events.xml"));
        if (runId != null && !runId.isBlank()) {
            candidates.add(outputDir.resolve(runId + ".output_events.xml.gz"));
            candidates.add(outputDir.resolve(runId + ".output_events.xml"));
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        throw new IllegalArgumentException("Could not locate events file from MATSim config output directory. Tried: " + candidates);
    }

    private static Path resolveTripsFile(AppConfig appConfig, Path matsimConfigPath, Config matsimConfig) {
        Path outputDir = outputDirectory(matsimConfigPath, matsimConfig);
        String runId = matsimConfig.controller().getRunId();

        if (appConfig.explicitTripsFile() != null && !appConfig.explicitTripsFile().isBlank()) {
            Path explicit = Path.of(appConfig.explicitTripsFile());
            Path candidate = explicit.isAbsolute() ? explicit : outputDir.resolve(explicit);
            if (Files.exists(candidate)) {
                return candidate;
            }
            throw new IllegalArgumentException("Configured trips.file not found: " + candidate);
        }

        List<Path> candidates = new ArrayList<>();
        candidates.add(outputDir.resolve("output_trips.csv.gz"));
        candidates.add(outputDir.resolve("output_trips.csv"));
        if (runId != null && !runId.isBlank()) {
            candidates.add(outputDir.resolve(runId + ".output_trips.csv.gz"));
            candidates.add(outputDir.resolve(runId + ".output_trips.csv"));
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path resolveOutputPlansFile(Path matsimConfigPath, Config matsimConfig) {
        Path outputDir = outputDirectory(matsimConfigPath, matsimConfig);
        String runId = matsimConfig.controller().getRunId();

        List<Path> candidates = new ArrayList<>();
        candidates.add(outputDir.resolve("output_plans.xml.gz"));
        candidates.add(outputDir.resolve("output_plans.xml"));
        if (runId != null && !runId.isBlank()) {
            candidates.add(outputDir.resolve(runId + ".output_plans.xml.gz"));
            candidates.add(outputDir.resolve(runId + ".output_plans.xml"));
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path resolveOutputPersonsFile(Path matsimConfigPath, Config matsimConfig) {
        Path outputDir = outputDirectory(matsimConfigPath, matsimConfig);
        String runId = matsimConfig.controller().getRunId();

        List<Path> candidates = new ArrayList<>();
        candidates.add(outputDir.resolve("output_persons.csv.gz"));
        candidates.add(outputDir.resolve("output_persons.csv"));
        if (runId != null && !runId.isBlank()) {
            candidates.add(outputDir.resolve(runId + ".output_persons.csv.gz"));
            candidates.add(outputDir.resolve(runId + ".output_persons.csv"));
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path outputDirectory(Path matsimConfigPath, Config matsimConfig) {
        String output = matsimConfig.controller().getOutputDirectory();
        if (output == null || output.isBlank()) {
            throw new IllegalArgumentException("MATSim config has no controller.outputDirectory");
        }

        Path outputDir = Path.of(output);
        if (outputDir.isAbsolute()) {
            return outputDir;
        }

        Path configDir = matsimConfigPath.toAbsolutePath().getParent();
        if (configDir == null) {
            configDir = Path.of(".").toAbsolutePath().normalize();
        }
        return configDir.resolve(outputDir).normalize();
    }

    private static Path resolveConfigInputPath(Path matsimConfigPath, String configuredPath, String label) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalArgumentException("MATSim config has no " + label);
        }

        Path input = Path.of(configuredPath);
        if (input.isAbsolute()) {
            return input.normalize();
        }

        Path configDir = matsimConfigPath.getParent();
        if (configDir == null) {
            configDir = Path.of(".").toAbsolutePath().normalize();
        }
        return configDir.resolve(input).normalize();
    }
}
