package com.matsim.viz.parser;

import com.matsim.viz.domain.VehicleMetadata;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class PopulationMetadataExtractor {
    private PopulationMetadataExtractor() {
    }

    public static Map<String, VehicleMetadata> fromPopulation(Population population) {
        Map<String, VehicleMetadata> result = new HashMap<>();
        for (Person person : population.getPersons().values()) {
            String personId = person.getId().toString();
            Integer age = extractAge(person);
            String purpose = extractTripPurpose(person);
            String sex = extractSex(person);
            result.put(personId, new VehicleMetadata(personId, purpose, age, sex));
        }
        return result;
    }

    private static Integer extractAge(Person person) {
        Object value = person.getAttributes().getAttribute("age");
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                try {
                    return (int) Math.round(Double.parseDouble(value.toString()));
                } catch (NumberFormatException ignoredAgain) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String extractTripPurpose(Person person) {
        Plan selectedPlan = person.getSelectedPlan();
        if (selectedPlan == null) {
            return null;
        }

        String firstNonHome = null;
        String lastMeaningfulActivity = null;
        for (PlanElement element : selectedPlan.getPlanElements()) {
            if (element instanceof Activity activity) {
                String type = activity.getType();
                if (type == null) {
                    continue;
                }
                String normalized = type.trim();
                if (normalized.isEmpty() || normalized.equalsIgnoreCase("pt interaction")) {
                    continue;
                }
                if (firstNonHome == null && !isHomeActivity(normalized)) {
                    firstNonHome = normalized;
                }
                lastMeaningfulActivity = normalized;
            }
        }
        return firstNonHome != null ? firstNonHome : lastMeaningfulActivity;
    }

    private static boolean isHomeActivity(String activityType) {
        String value = activityType.toLowerCase(Locale.ROOT);
        return value.equals("home") || value.startsWith("home_") || value.startsWith("home-") || value.startsWith("h_");
    }

    private static String extractSex(Person person) {
        Object value = person.getAttributes().getAttribute("sex");
        if (value == null) {
            value = person.getAttributes().getAttribute("gender");
        }
        if (value == null) {
            value = person.getAttributes().getAttribute("gender_identity");
        }
        if (value == null) {
            return null;
        }
        String raw = value.toString().trim().toLowerCase(Locale.ROOT);
        if (raw.equals("0") || raw.equals("0.0") || raw.equals("m") || raw.equals("male")) {
            return "male";
        }
        if (raw.equals("1") || raw.equals("1.0") || raw.equals("f") || raw.equals("female")) {
            return "female";
        }
        return raw;
    }
}
