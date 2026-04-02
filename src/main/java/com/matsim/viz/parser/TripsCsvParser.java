package com.matsim.viz.parser;

import com.matsim.viz.domain.VehicleMetadata;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class TripsCsvParser {
    public Map<String, VehicleMetadata> parse(Path tripsFile) throws Exception {
        Map<String, VehicleMetadata> metadataByPerson = new HashMap<>();

        try (InputStream input = InputStreams.openMaybeGzip(tripsFile);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8), 256 * 1024)) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                return metadataByPerson;
            }

            List<String> headers = SimpleCsv.parseLine(headerLine);
            int personIndex = findColumn(headers, "person", "person_id", "agent", "agent_id");
            int purposeIndex = findColumn(headers, "purpose", "trip_purpose", "main_purpose", "end_activity_type");
            int ageIndex = findColumn(headers, "age", "person_age");
            int sexIndex = findColumn(headers, "sex", "gender", "person_sex", "person_gender");

            if (personIndex < 0) {
                return metadataByPerson;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                List<String> cols = SimpleCsv.parseLine(line);
                if (personIndex >= cols.size()) {
                    continue;
                }

                String personId = cols.get(personIndex).trim();
                if (personId.isEmpty()) {
                    continue;
                }

                String purpose = valueAt(cols, purposeIndex);
                Integer age = parseInteger(valueAt(cols, ageIndex));
                String sex = valueAt(cols, sexIndex);

                VehicleMetadata existing = metadataByPerson.get(personId);
                if (existing == null) {
                    metadataByPerson.put(personId, new VehicleMetadata(personId, purpose, age, sex));
                } else {
                    String mergedPurpose = (existing.tripPurpose() == null || existing.tripPurpose().isBlank()) ? purpose : existing.tripPurpose();
                    Integer mergedAge = existing.age() == null ? age : existing.age();
                    String mergedSex = (existing.sex() == null || existing.sex().isBlank()) ? sex : existing.sex();
                    metadataByPerson.put(personId, new VehicleMetadata(personId, mergedPurpose, mergedAge, mergedSex));
                }
            }
        }

        return metadataByPerson;
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
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer parseInteger(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
