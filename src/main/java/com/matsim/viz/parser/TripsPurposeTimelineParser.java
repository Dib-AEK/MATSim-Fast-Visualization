package com.matsim.viz.parser;

import com.matsim.viz.domain.TripPurposeWindow;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TripsPurposeTimelineParser {
    public Map<String, List<TripPurposeWindow>> parse(Path tripsFile) throws Exception {
        Map<String, List<TripPurposeWindow>> byPerson = new HashMap<>();

        try (InputStream input = InputStreams.openMaybeGzip(tripsFile);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8), 256 * 1024)) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                return byPerson;
            }

            List<String> headers = SimpleCsv.parseLine(headerLine);
            int personIndex = findColumn(headers, "person", "person_id", "agent", "agent_id");
            int purposeIndex = findColumn(headers, "purpose", "trip_purpose", "main_purpose", "end_activity_type", "destination_activity_type");
            int modeIndex = findColumn(headers, "main_mode", "longest_distance_mode", "mode", "leg_mode");
            int departureIndex = findColumn(headers, "dep_time", "departure_time", "start_time", "departure");
            int arrivalIndex = findColumn(headers, "arr_time", "arrival_time", "end_time", "arrival");
            int travelTimeIndex = findColumn(headers, "trav_time", "travel_time", "duration", "trip_duration");

            if (personIndex < 0 || purposeIndex < 0 || departureIndex < 0) {
                return byPerson;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                List<String> cols = SimpleCsv.parseLine(line);
                String personId = valueAt(cols, personIndex);
                String purpose = valueAt(cols, purposeIndex);
                if (personId == null || purpose == null) {
                    continue;
                }

                Double departure = parseTimeSeconds(valueAt(cols, departureIndex));
                if (departure == null) {
                    continue;
                }

                Double arrival = parseTimeSeconds(valueAt(cols, arrivalIndex));
                if (arrival == null) {
                    Double travelTime = parseTimeSeconds(valueAt(cols, travelTimeIndex));
                    if (travelTime != null) {
                        arrival = departure + Math.max(0.0, travelTime);
                    }
                }
                if (arrival == null) {
                    arrival = departure;
                }
                if (arrival < departure) {
                    arrival = departure;
                }

                String mode = valueAt(cols, modeIndex);
                TripPurposeWindow window = new TripPurposeWindow(personId, departure, arrival, purpose, mode);
                byPerson.computeIfAbsent(personId, key -> new ArrayList<>()).add(window);
            }
        }

        byPerson.values().forEach(list -> list.sort((a, b) -> Double.compare(a.departureTimeSeconds(), b.departureTimeSeconds())));
        return byPerson;
    }

    private static int findColumn(List<String> headers, String... candidates) {
        for (int i = 0; i < headers.size(); i++) {
            String normalized = normalize(headers.get(i));
            for (String candidate : candidates) {
                if (normalized.equals(normalize(candidate))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
    }

    private static String valueAt(List<String> values, int index) {
        if (index < 0 || index >= values.size()) {
            return null;
        }
        String value = values.get(index);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Double parseTimeSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String value = raw.trim();
        if (value.contains(":")) {
            String[] parts = value.split(":");
            if (parts.length == 3) {
                Integer h = parseInt(parts[0]);
                Integer m = parseInt(parts[1]);
                Double s = parseDouble(parts[2]);
                if (h != null && m != null && s != null) {
                    return h * 3600.0 + m * 60.0 + s;
                }
            }
        }

        return parseDouble(value);
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static Double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ex) {
            return null;
        }
    }
}
