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
        Path networkFile = resolveNetworkFile(matsimConfigPath, matsimConfig);
        Path populationFile = resolvePopulationFile(matsimConfigPath, matsimConfig);
        Path eventsFile = resolveEventsFile(matsimConfigPath, matsimConfig);
        Path tripsFile = resolveTripsFile(matsimConfigPath, matsimConfig);
        Path outputPersonsFile = resolveOutputPersonsFile(matsimConfigPath, matsimConfig);
        Path outputPlansFile = resolveOutputPlansFile(matsimConfigPath, matsimConfig);
        Path transitScheduleFile = resolveTransitScheduleFile(matsimConfigPath, matsimConfig);

        // Enforce the resolved paths in the config so ScenarioUtils loads the intended scenario data.
        matsimConfig.network().setInputFile(networkFile.toString());
        matsimConfig.plans().setInputFile(populationFile.toString());
        if (transitScheduleFile != null) {
            matsimConfig.transit().setUseTransit(true);
            matsimConfig.transit().setTransitScheduleFile(transitScheduleFile.toString());
        }

        return new ResolvedSimulationInputs(
                matsimConfigPath,
                networkFile,
                populationFile,
                eventsFile,
                tripsFile,
                outputPersonsFile,
                outputPlansFile,
                transitScheduleFile,
                matsimConfig
        );
    }

    /** Best-effort discovery for the launcher: missing files remain editable instead of aborting the scan. */
    public record InputDiscovery(Map<String, Path> files, List<String> warnings) { }

    public InputDiscovery discoverInputs(Path configFile) {
        Path path = configFile.toAbsolutePath().normalize();
        Config config = ConfigUtils.loadConfig(path.toString());
        Map<String, Path> files = new java.util.LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        java.util.function.BiConsumer<String, java.util.function.Supplier<Path>> find = (key, resolver) -> {
            try { Path value = resolver.get(); if (value != null) files.put(key, value); }
            catch (IllegalArgumentException ex) { warnings.add(ex.getMessage()); }
        };
        find.accept("network", () -> resolveNetworkFile(path, config));
        find.accept("population", () -> resolvePopulationFile(path, config));
        find.accept("events", () -> resolveEventsFile(path, config));
        find.accept("trips", () -> resolveTripsFile(path, config));
        find.accept("persons", () -> resolveOutputPersonsFile(path, config));
        find.accept("plans", () -> resolveOutputPlansFile(path, config));
        find.accept("schedule", () -> resolveTransitScheduleFile(path, config));
        find.accept("vehicles", () -> resolveTransitVehiclesFile(new ResolvedSimulationInputs(path,
                files.get("network"), files.get("population"), files.get("events"), files.get("trips"),
                files.get("persons"), files.get("plans"), files.get("schedule"), config)));
        return new InputDiscovery(files, warnings);
    }

    public MatsimScenarioBundle load(ResolvedSimulationInputs inputs) {
        // Only load the chosen visualization inputs, not unrelated scenario modules' file references.
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new org.matsim.core.network.io.MatsimNetworkReader(scenario.getNetwork()).readFile(inputs.networkFile().toString());
        if (inputs.populationFile() != null)
            new org.matsim.core.population.io.PopulationReader(scenario).readFile(inputs.populationFile().toString());
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

    private static Path resolveNetworkFile(Path matsimConfigPath, Config matsimConfig) {
        Path configured = resolveConfigInputPath(matsimConfigPath, matsimConfig.network().getInputFile(), "network.inputFile");
        Path outputDir = outputDirectory(matsimConfigPath, matsimConfig);
        String runId = matsimConfig.controller().getRunId();

        List<Path> candidates = new ArrayList<>();
        candidates.add(outputDir.resolve("output_network.xml.gz"));
        candidates.add(outputDir.resolve("output_network.xml"));
        if (runId != null && !runId.isBlank()) {
            candidates.add(outputDir.resolve(runId + ".output_network.xml.gz"));
            candidates.add(outputDir.resolve(runId + ".output_network.xml"));
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        if (Files.exists(configured)) {
            return configured;
        }

        throw new IllegalArgumentException("Could not locate network file. Tried: " + candidates + " and configured path: " + configured);
    }

    private static Path resolvePopulationFile(Path matsimConfigPath, Config matsimConfig) {
        Path configured = resolveConfigInputPath(matsimConfigPath, matsimConfig.plans().getInputFile(), "plans.inputFile");
        Path outputPlans = resolveOutputPlansFile(matsimConfigPath, matsimConfig);
        if (outputPlans != null) {
            return outputPlans;
        }
        if (Files.exists(configured)) {
            return configured;
        }

        throw new IllegalArgumentException("Could not locate population/plans file. Configured path: " + configured);
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

    private static Path resolveEventsFile(Path matsimConfigPath, Config matsimConfig) {
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

    private static Path resolveTripsFile(Path matsimConfigPath, Config matsimConfig) {
        Path outputDir = outputDirectory(matsimConfigPath, matsimConfig);
        String runId = matsimConfig.controller().getRunId();

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

    public static Path resolveTransitVehiclesFile(ResolvedSimulationInputs inputs) {
        Path output=outputDirectory(inputs.matsimConfigFile(),inputs.matsimConfig());
        String run=inputs.matsimConfig().controller().getRunId();
        List<Path> candidates=new ArrayList<>();
        for(String suffix:List.of("output_transitVehicles.xml.gz","output_transitVehicles.xml")) {
            if(run!=null&&!run.isBlank())candidates.add(output.resolve(run+"."+suffix));
            candidates.add(output.resolve(suffix));
        }
        String configured=inputs.matsimConfig().transit().getVehiclesFile();
        if(configured!=null&&!configured.isBlank()) {
            Path file=Path.of(configured);candidates.add(file.isAbsolute()?file:inputs.matsimConfigFile().getParent().resolve(file).normalize());
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    private static Path resolveTransitScheduleFile(Path matsimConfigPath, Config matsimConfig) {
        Path outputDir = outputDirectory(matsimConfigPath, matsimConfig);
        String runId = matsimConfig.controller().getRunId();

        List<Path> candidates = new ArrayList<>();
        candidates.add(outputDir.resolve("output_transitSchedule.xml.gz"));
        candidates.add(outputDir.resolve("output_transitSchedule.xml"));
        if (runId != null && !runId.isBlank()) {
            candidates.add(outputDir.resolve(runId + ".output_transitSchedule.xml.gz"));
            candidates.add(outputDir.resolve(runId + ".output_transitSchedule.xml"));
        }

        // Also check the input transit schedule from config
        String configSchedule = matsimConfig.transit().getTransitScheduleFile();
        if (configSchedule != null && !configSchedule.isBlank()) {
            Path configSchedulePath = Path.of(configSchedule);
            if (configSchedulePath.isAbsolute()) {
                candidates.add(configSchedulePath);
            } else {
                Path configDir = matsimConfigPath.getParent();
                if (configDir != null) {
                    candidates.add(configDir.resolve(configSchedulePath).normalize());
                }
            }
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
