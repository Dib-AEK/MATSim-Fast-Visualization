package com.matsim.viz;

import com.matsim.viz.cache.CachedSimulationData;
import com.matsim.viz.cache.SimulationCacheStore;
import com.matsim.viz.cache.SimulationFingerprint;
import com.matsim.viz.config.AppConfig;
import com.matsim.viz.config.ConfigLoader;
import com.matsim.viz.domain.TripPurposeWindow;
import com.matsim.viz.engine.PlaybackController;
import com.matsim.viz.engine.SimulationModel;
import com.matsim.viz.parser.EventsParseResult;
import com.matsim.viz.parser.MatsimEventsProcessor;
import com.matsim.viz.parser.MatsimScenarioBundle;
import com.matsim.viz.parser.MatsimScenarioLoader;
import com.matsim.viz.parser.PlansXmlPurposeTimelineParser;
import com.matsim.viz.parser.ResolvedSimulationInputs;
import com.matsim.viz.parser.TransitScheduleParser;
import com.matsim.viz.parser.TripsPurposeTimelineParser;
import com.matsim.viz.ui.fx.FxVisualizerApp;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        long startNanos = System.nanoTime();
        RunOptions options = RunOptions.parse(args);

        Path configPath = Path.of("config", "app.properties").toAbsolutePath();
        AppConfig config = ConfigLoader.load(configPath);
        MatsimScenarioLoader scenarioLoader = new MatsimScenarioLoader();
        ResolvedSimulationInputs inputs = scenarioLoader.resolveInputs(config);
        String cacheKey = SimulationFingerprint.fromInputs(inputs);
        SimulationCacheStore cacheStore = new SimulationCacheStore(config.cacheDir());

        if (options.guiOnly && options.overwriteCache) {
            throw new IllegalArgumentException("Cannot combine --gui-only with --overwrite-cache");
        }

        if (options.overwriteCache) {
            cacheStore.delete(cacheKey);
            System.out.println("Deleted cache entry for key: " + cacheKey);
        }

        System.out.println("Using config: " + configPath);
        System.out.println("MATSim config: " + config.matsimConfigFile());
        System.out.println("Events      : " + inputs.eventsFile());
        System.out.println("Trips       : " + (inputs.tripsFile() == null ? "(not found)" : inputs.tripsFile()));
        System.out.println("Persons     : " + (inputs.outputPersonsFile() == null ? "(not found)" : inputs.outputPersonsFile()));
        System.out.println("Output plans: " + (inputs.outputPlansFile() == null ? "(not found)" : inputs.outputPlansFile()));
        System.out.println("Transit sched: " + (inputs.transitScheduleFile() == null ? "(not found)" : inputs.transitScheduleFile()));
        System.out.println("Cache key   : " + cacheKey);

        CachedSimulationData cached;
        boolean loadedFromCache = false;

        if (cacheStore.exists(cacheKey) && !options.overwriteCache) {
            long cacheLoadStart = System.nanoTime();
            cached = cacheStore.load(cacheKey);
            loadedFromCache = true;
            System.out.printf("Loaded cache in %.2fs%n", seconds(System.nanoTime() - cacheLoadStart));
        } else {
            if (options.guiOnly) {
                throw new IllegalStateException("No cache found for this simulation. Run once without --gui-only to build cache.");
            }

            long t1 = System.nanoTime();
            MatsimScenarioBundle scenarioBundle = scenarioLoader.load(inputs);
            long t2 = System.nanoTime();
            EventsParseResult events = new MatsimEventsProcessor().readTraversals(inputs.eventsFile());
            long t3 = System.nanoTime();

            Map<String, String> mergedVehicleToMode = new HashMap<>(events.vehicleToMode());
            if (inputs.transitScheduleFile() != null) {
                long ptStart = System.nanoTime();
                Map<String, String> ptModes = TransitScheduleParser.parseVehicleModes(inputs.transitScheduleFile());
                mergedVehicleToMode.putAll(ptModes);
                System.out.printf("Parsed transit schedule in %.2fs (%d PT vehicles)%n",
                        seconds(System.nanoTime() - ptStart), ptModes.size());
            }

            cached = new CachedSimulationData(
                    scenarioBundle.networkData(),
                    events.traversals(),
                    events.vehicleToPerson(),
                    mergedVehicleToMode,
                    scenarioBundle.metadataByPerson()
            );

            long saveStart = System.nanoTime();
            cacheStore.save(cacheKey, cached);
            long saveEnd = System.nanoTime();

            System.out.printf("Parsed network in %.2fs%n", seconds(t2 - t1));
            System.out.printf("Processed events in %.2fs (%d traversals)%n", seconds(t3 - t2), events.traversals().length);
            System.out.printf("Loaded population metadata (%d persons)%n", scenarioBundle.metadataByPerson().size());
            System.out.printf("Saved cache in %.2fs%n", seconds(saveEnd - saveStart));
        }

        if (options.buildCacheOnly) {
            if (loadedFromCache) {
                System.out.println("Cache is already available. Build-cache mode finished.");
            } else {
                System.out.println("Cache created. Build-cache mode finished.");
            }
            System.out.printf("Startup total  in %.2fs%n", seconds(System.nanoTime() - startNanos));
            return;
        }

        Map<String, List<TripPurposeWindow>> tripPurposeWindowsByPerson = Collections.emptyMap();
        if (inputs.tripsFile() != null) {
            try {
                tripPurposeWindowsByPerson = new TripsPurposeTimelineParser().parse(inputs.tripsFile());
                System.out.printf("Loaded trips timeline metadata (%d persons)%n", tripPurposeWindowsByPerson.size());
            } catch (Exception ex) {
                System.out.println("Warning: failed to parse trips timeline metadata: " + ex.getMessage());
            }
        }

        if (tripPurposeWindowsByPerson.isEmpty() && inputs.outputPlansFile() != null) {
            try {
                tripPurposeWindowsByPerson = new PlansXmlPurposeTimelineParser().parse(inputs.outputPlansFile());
                System.out.printf("Loaded output-plans timeline metadata (%d persons)%n", tripPurposeWindowsByPerson.size());
            } catch (Exception ex) {
                System.out.println("Warning: failed to parse output plans timeline metadata: " + ex.getMessage());
            }
        }

        SimulationModel model = new SimulationModel(
                cached.networkData(),
                cached.traversals(),
                cached.vehicleToPerson(),
                cached.vehicleToMode(),
                cached.metadataByPerson(),
                tripPurposeWindowsByPerson
        );

        double startTime = Math.max(config.playbackStartSeconds(), model.minTime());
        double endTime = Math.min(config.playbackEndSeconds(), Math.max(model.maxTime(), startTime));

        PlaybackController playbackController = new PlaybackController(
                model,
                startTime,
                endTime,
                config.playbackSpeed()
        );

        if (loadedFromCache) {
            System.out.printf("Loaded cached traversals (%d)%n", cached.traversals().length);
            System.out.printf("Loaded cached metadata (%d persons)%n", cached.metadataByPerson().size());
        }
        System.out.printf("Startup total  in %.2fs%n", seconds(System.nanoTime() - startNanos));

        cached = null;

        double sampleSize = 1.0;
        try {
            var eqasimModule = inputs.matsimConfig().getModules().get("eqasim");
            if (eqasimModule != null) {
                String sampleSizeStr = eqasimModule.getParams().get("sampleSize");
                if (sampleSizeStr != null && !sampleSizeStr.isBlank()) {
                    sampleSize = Double.parseDouble(sampleSizeStr.trim());
                }
            }
        } catch (Exception ex) {
            System.out.println("Warning: Could not read eqasim sampleSize: " + ex.getMessage());
        }
        System.out.printf("Sample size: %.4f%n", sampleSize);

        FxVisualizerApp.launchVisualizer(model, playbackController, sampleSize, config.cacheDir());
    }

    private static double seconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    private record RunOptions(boolean overwriteCache, boolean buildCacheOnly, boolean guiOnly) {
        static RunOptions parse(String[] args) {
            boolean overwrite = false;
            boolean buildOnly = false;
            boolean guiOnly = false;

            for (String arg : args) {
                if ("--overwrite-cache".equalsIgnoreCase(arg) || "--overwrite".equalsIgnoreCase(arg)) {
                    overwrite = true;
                } else if ("--build-cache".equalsIgnoreCase(arg) || "--cache-only".equalsIgnoreCase(arg)) {
                    buildOnly = true;
                } else if ("--gui-only".equalsIgnoreCase(arg)) {
                    guiOnly = true;
                }
            }

            return new RunOptions(overwrite, buildOnly, guiOnly);
        }
    }
}
