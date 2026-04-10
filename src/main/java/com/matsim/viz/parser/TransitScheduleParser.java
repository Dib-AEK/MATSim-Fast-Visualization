package com.matsim.viz.parser;

import com.matsim.viz.domain.PtStopPoint;
import org.matsim.api.core.v01.Scenario;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.transitSchedule.api.Departure;
import org.matsim.pt.transitSchedule.api.TransitLine;
import org.matsim.pt.transitSchedule.api.TransitRoute;
import org.matsim.pt.transitSchedule.api.TransitRouteStop;
import org.matsim.pt.transitSchedule.api.TransitScheduleReader;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses a MATSim transit schedule file (output_transitSchedule.xml.gz)
 * and extracts a mapping from departure vehicleRefId to transport mode
 * (bus, tram, rail, subway, ferry, etc.).
 */
public final class TransitScheduleParser {
    private TransitScheduleParser() {
    }

    public static TransitScheduleData parse(Path transitScheduleFile) {
        Map<String, String> vehicleToMode = new HashMap<>();
        Map<String, StopBuilder> stopBuilders = new HashMap<>();

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

                    for (TransitRouteStop routeStop : route.getStops()) {
                        TransitStopFacility facility = routeStop.getStopFacility();
                        if (facility == null || facility.getId() == null || facility.getCoord() == null) {
                            continue;
                        }

                        String stopId = facility.getId().toString();
                        stopBuilders.computeIfAbsent(
                                stopId,
                                ignored -> new StopBuilder(stopId, facility.getCoord().getX(), facility.getCoord().getY())
                        ).modes().add(normalizedMode);
                    }

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

        Map<String, PtStopPoint> stopsById = new HashMap<>(stopBuilders.size());
        for (StopBuilder stop : stopBuilders.values()) {
            stopsById.put(stop.id(), new PtStopPoint(stop.id(), stop.x(), stop.y(), stop.modes()));
        }

        return new TransitScheduleData(vehicleToMode, stopsById);
    }

    public static Map<String, String> parseVehicleModes(Path transitScheduleFile) {
        return parse(transitScheduleFile).vehicleToMode();
    }

    private record StopBuilder(String id, double x, double y, Set<String> modes) {
        private StopBuilder(String id, double x, double y) {
            this(id, x, y, new HashSet<>());
        }
    }
}
