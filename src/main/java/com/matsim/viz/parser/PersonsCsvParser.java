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

public final class PersonsCsvParser {
    public Map<String, VehicleMetadata> parse(Path personsFile) throws Exception {
        Map<String, VehicleMetadata> metadataByPerson = new HashMap<>();

        try (InputStream input = InputStreams.openMaybeGzip(personsFile);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8), 256 * 1024)) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                return metadataByPerson;
            }

            List<String> headers = SimpleCsv.parseLine(headerLine);
            int personIndex = findColumn(headers, "person", "person_id", "agent", "agent_id", "id");
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
                String personId = valueAt(cols, personIndex);
                if (personId == null || personId.isBlank()) {
                    continue;
                }

                Integer age = parseAge(valueAt(cols, ageIndex));
                String sex = normalizeSex(valueAt(cols, sexIndex));

                VehicleMetadata existing = metadataByPerson.get(personId);
                if (existing == null) {
                    metadataByPerson.put(personId, new VehicleMetadata(personId, null, age, sex));
                } else {
                    Integer mergedAge = age != null ? age : existing.age();
                    String mergedSex = (sex != null && !sex.isBlank()) ? sex : existing.sex();
                    metadataByPerson.put(personId, new VehicleMetadata(personId, existing.tripPurpose(), mergedAge, mergedSex));
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
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Integer parseAge(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            try {
                return (int) Math.round(Double.parseDouble(raw));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private static String normalizeSex(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("0") || value.equals("0.0") || value.equals("m") || value.equals("male")) {
            return "male";
        }
        if (value.equals("1") || value.equals("1.0") || value.equals("f") || value.equals("female")) {
            return "female";
        }
        return value;
    }
}
