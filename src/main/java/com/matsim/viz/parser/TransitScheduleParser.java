package com.matsim.viz.parser;

import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parses a MATSim transit schedule file (output_transitSchedule.xml.gz)
 * and extracts a mapping from departure vehicleRefId to transport mode
 * (bus, tram, rail, subway, ferry, etc.).
 */
public final class TransitScheduleParser {
    private TransitScheduleParser() {
    }

    public static Map<String, String> parseVehicleModes(Path transitScheduleFile) {
        Map<String, String> vehicleToMode = new HashMap<>();

        try {
            Config config = ConfigUtils.createConfig();
            config.transit().setUseTransit(true);
            Scenario scenario = ScenarioUtils.createScenario(config);

            new TransitScheduleReader(scenario).readFile(transitScheduleFile.toString());

            for (TransitLine line : scenario.getTransitSchedule().getTransitLines().values()) {
                for (TransitRoute route : line.getRoutes().values()) {
                    String mode = route.getTransportMode();
                    if (mode == null || mode.isBlank()) {
                        continue;
                    }
                    String normalizedMode = mode.toLowerCase(Locale.ROOT);

                    for (Departure departure : route.getDepartures().values()) {
                        if (departure.getVehicleId() != null) {
                            vehicleToMode.put(departure.getVehicleId().toString(), normalizedMode);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse transit schedule: " + transitScheduleFile, ex);
        }

        return vehicleToMode;
    }
}
