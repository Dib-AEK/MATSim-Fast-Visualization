package com.matsim.viz.ui;

import com.matsim.viz.config.AppDefaults;
import com.matsim.viz.domain.ColorMode;
import com.matsim.viz.domain.VehicleMetadata;
import com.matsim.viz.engine.SimulationModel;

import java.awt.Color;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class VehicleColorProvider {
private static final Color[] PALETTE = AppDefaults.Colors.PALETTE.toArray(Color[]::new);

    private final Map<String, Color> modeColors = new HashMap<>();
    private final Map<String, Color> tripPurposeColors = new HashMap<>();
    private final Map<String, Color> sexColors = new HashMap<>();

    private int[] ageBinUpperBounds = AppDefaults.Colors.AGE_BIN_UPPER_BOUNDS.stream().mapToInt(Integer::intValue).toArray();
    private Color[] ageGroupColors = AppDefaults.Colors.AGE_GROUP_COLORS.toArray(Color[]::new);

    public VehicleColorProvider() {
        modeColors.put("car", AppDefaults.Colors.DEFAULT_CAR_MODE_COLOR);
        modeColors.put("bike", AppDefaults.Colors.DEFAULT_BIKE_MODE_COLOR);
        modeColors.put("bus", AppDefaults.Colors.MODE_BUS);
        modeColors.put("tram", AppDefaults.Colors.MODE_TRAM);
        modeColors.put("rail", AppDefaults.Colors.MODE_RAIL);
        modeColors.put("train", AppDefaults.Colors.MODE_TRAIN);
        modeColors.put("subway", AppDefaults.Colors.MODE_SUBWAY);
        modeColors.put("metro", AppDefaults.Colors.MODE_METRO);
        modeColors.put("ferry", AppDefaults.Colors.MODE_FERRY);
        modeColors.put("funicular", AppDefaults.Colors.MODE_FUNICULAR);
        sexColors.put("male", AppDefaults.Colors.SEX_MALE);
        sexColors.put("female", AppDefaults.Colors.SEX_FEMALE);
        sexColors.put("other", AppDefaults.Colors.SEX_OTHER);
        sexColors.put("unknown", AppDefaults.Colors.SEX_UNKNOWN);
    }

    public Color colorFor(int traversalIndex, SimulationModel model, ColorMode mode) {
        return switch (mode) {
            case DEFAULT -> colorForTripMode(model.vehicleToMode().get(model.traversalVehicleId(traversalIndex)));
            case SEX -> colorBySex(traversalIndex, model);
            case TRIP_PURPOSE -> colorByTripPurpose(traversalIndex, model);
            case AGE_GROUP -> colorByAgeGroup(traversalIndex, model);
        };
    }

    public Color colorForTripMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return AppDefaults.Colors.SEX_UNKNOWN;
        }
        return modeColor(mode);
    }

    private Color colorByTripPurpose(int traversalIndex, SimulationModel model) {
        String purpose = model.tripPurposeForTraversal(traversalIndex);
        if (purpose == null || purpose.isBlank()) {
            VehicleMetadata metadata = metadataForTraversal(traversalIndex, model);
            purpose = metadata == null ? null : metadata.tripPurpose();
        }

        if (purpose == null || purpose.isBlank()) {
            return AppDefaults.Colors.MISSING_MODE;
        }

        String purposeKey = normalize(purpose);
        Color configured = tripPurposeColors.get(purposeKey);
        if (configured != null) {
            return configured;
        }

        Color generated = colorFromString(purpose);
        tripPurposeColors.putIfAbsent(purposeKey, generated);
        return generated;
    }

    private Color colorByAgeGroup(int traversalIndex, SimulationModel model) {
        VehicleMetadata metadata = metadataForTraversal(traversalIndex, model);
        if (metadata == null || metadata.age() == null) {
            return AppDefaults.Colors.MISSING_MODE;
        }

        int groupIndex = ageGroupIndex(metadata.age());
        if (groupIndex < 0 || groupIndex >= ageGroupColors.length) {
            return AppDefaults.Colors.MISSING_METADATA;
        }
        return ageGroupColors[groupIndex];
    }

    private Color colorBySex(int traversalIndex, SimulationModel model) {
        VehicleMetadata metadata = metadataForTraversal(traversalIndex, model);
        if (metadata == null) {
            return sexColor("unknown");
        }
        return sexColor(metadata.sex());
    }

    private VehicleMetadata metadataForTraversal(int traversalIndex, SimulationModel model) {
        String vehicleId = model.traversalVehicleId(traversalIndex);
        if (vehicleId == null || vehicleId.isBlank()) {
            return null;
        }

        String personId = model.vehicleToPerson().get(vehicleId);
        if (personId != null) {
            VehicleMetadata direct = model.metadataByPerson().get(personId);
            if (direct != null) {
                return direct;
            }
        }

        VehicleMetadata fromVehicleId = model.metadataByPerson().get(vehicleId);
        if (fromVehicleId != null) {
            return fromVehicleId;
        }

        for (String candidate : personCandidatesFromVehicleId(vehicleId)) {
            VehicleMetadata candidateMetadata = model.metadataByPerson().get(candidate);
            if (candidateMetadata != null) {
                return candidateMetadata;
            }
        }
        return null;
    }

    private static String[] personCandidatesFromVehicleId(String vehicleId) {
        String normalized = vehicleId.trim();
        if (normalized.isEmpty()) {
            return new String[0];
        }

        return new String[]{
                stripKnownSuffix(normalized, "_veh"),
                stripKnownSuffix(normalized, "_car"),
                stripKnownSuffix(normalized, ":veh"),
                firstToken(normalized, '_'),
                firstToken(normalized, ':'),
                lastToken(normalized, '_'),
                lastToken(normalized, ':')
        };
    }

    private static String stripKnownSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    private static String firstToken(String value, char separator) {
        int index = value.indexOf(separator);
        return index > 0 ? value.substring(0, index) : value;
    }

    private static String lastToken(String value, char separator) {
        int index = value.lastIndexOf(separator);
        return index >= 0 && index + 1 < value.length() ? value.substring(index + 1) : value;
    }

    private Color colorFromString(String key) {
        int index = Math.floorMod(key.toLowerCase().hashCode(), PALETTE.length);
        return PALETTE[index];
    }

    public void setModeColor(String mode, Color color) {
        if (mode == null || mode.isBlank() || color == null) {
            return;
        }
        modeColors.put(normalize(mode), color);
    }

    public Color modeColor(String mode) {
        if (mode == null || mode.isBlank()) {
            return AppDefaults.Colors.SEX_UNKNOWN;
        }
        String key = normalize(mode);
        Color configured = modeColors.get(key);
        if (configured != null) {
            return configured;
        }
        Color generated = colorFromString("mode:" + key);
        modeColors.put(key, generated);
        return generated;
    }

    public void setTripPurposeColor(String tripPurpose, Color color) {
        if (tripPurpose == null || tripPurpose.isBlank() || color == null) {
            return;
        }
        tripPurposeColors.put(normalize(tripPurpose), color);
    }

    public Color tripPurposeColor(String tripPurpose) {
        if (tripPurpose == null || tripPurpose.isBlank()) {
            return AppDefaults.Colors.MISSING_METADATA;
        }

        String key = normalize(tripPurpose);
        Color configured = tripPurposeColors.get(key);
        if (configured != null) {
            return configured;
        }

        Color generated = colorFromString(tripPurpose);
        tripPurposeColors.put(key, generated);
        return generated;
    }

    public void setSexColor(String sexCategory, Color color) {
        if (sexCategory == null || sexCategory.isBlank() || color == null) {
            return;
        }
        sexColors.put(normalizeSex(sexCategory), color);
    }

    public Color sexColor(String sexCategory) {
        String key = normalizeSex(sexCategory);
        Color configured = sexColors.get(key);
        if (configured != null) {
            return configured;
        }
        Color generated = colorFromString("sex:" + key);
        sexColors.put(key, generated);
        return generated;
    }

    public int[] ageBinUpperBounds() {
        return Arrays.copyOf(ageBinUpperBounds, ageBinUpperBounds.length);
    }

    public void setAgeBinUpperBounds(int[] newBounds) {
        int[] sanitized = sanitizeAgeBounds(newBounds);
        if (Arrays.equals(ageBinUpperBounds, sanitized)) {
            return;
        }

        Color[] previous = ageGroupColors;
        ageBinUpperBounds = sanitized;
        ageGroupColors = new Color[sanitized.length + 1];
        for (int i = 0; i < ageGroupColors.length; i++) {
            ageGroupColors[i] = i < previous.length ? previous[i] : colorFromString("age-group-" + i);
        }
    }

    public int ageGroupCount() {
        return ageGroupColors.length;
    }

    public String ageGroupLabel(int index) {
        if (index < 0 || index >= ageGroupColors.length) {
            return "group" + index;
        }
        if (index == 0) {
            return "0-" + ageBinUpperBounds[0];
        }
        if (index < ageBinUpperBounds.length) {
            return (ageBinUpperBounds[index - 1] + 1) + "-" + ageBinUpperBounds[index];
        }
        return (ageBinUpperBounds[ageBinUpperBounds.length - 1] + 1) + "+";
    }

    public Color ageGroupColor(int index) {
        if (index < 0 || index >= ageGroupColors.length) {
            return AppDefaults.Colors.MISSING_METADATA;
        }
        return ageGroupColors[index];
    }

    public void setAgeGroupColor(int index, Color color) {
        if (index < 0 || index >= ageGroupColors.length || color == null) {
            return;
        }
        ageGroupColors[index] = color;
    }

    private int ageGroupIndex(int age) {
        for (int i = 0; i < ageBinUpperBounds.length; i++) {
            if (age <= ageBinUpperBounds[i]) {
                return i;
            }
        }
        return ageBinUpperBounds.length;
    }

    private static int[] sanitizeAgeBounds(int[] bounds) {
        if (bounds == null || bounds.length == 0) {
            return new int[]{17, 35, 59};
        }
        int[] copy = Arrays.copyOf(bounds, bounds.length);
        Arrays.sort(copy);

        int write = 0;
        int prev = Integer.MIN_VALUE;
        for (int value : copy) {
            if (value <= 0 || value == prev) {
                continue;
            }
            copy[write++] = value;
            prev = value;
        }
        if (write == 0) {
            return new int[]{17, 35, 59};
        }
        return Arrays.copyOf(copy, write);
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeSex(String sex) {
        if (sex == null || sex.isBlank()) {
            return "unknown";
        }
        String value = normalize(sex);
        if (value.equals("m") || value.equals("male")) {
            return "male";
        }
        if (value.equals("f") || value.equals("female")) {
            return "female";
        }
        if (value.equals("0") || value.equals("0.0")) {
            return "male";
        }
        if (value.equals("1") || value.equals("1.0")) {
            return "female";
        }
        return value;
    }
}
