package com.matsim.viz.engine;

import com.matsim.viz.domain.NetworkData;
import com.matsim.viz.domain.TripPurposeWindow;
import com.matsim.viz.domain.VehicleMetadata;
import com.matsim.viz.domain.VehicleTraversal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SimulationModel {
    private final NetworkData networkData;
    private final Map<String, String> vehicleToPerson;
    private final Map<String, String> vehicleToMode;
    private final Map<String, VehicleMetadata> metadataByPerson;
    private final Map<String, List<TripPurposeWindow>> tripPurposeWindowsByPerson;

    private final String[] traversalVehicleIds;
    private final String[] traversalTripModes;
    private final String[] traversalLinkIds;
    private final double[] traversalEnterTimes;
    private final double[] traversalLeaveTimes;
    private final double[] traversalInverseDurations;

    private final double[] transitionTimes;
    private final int[] transitionTraversalIndexes;
    private final String[] transitionLinkIds;
    private final boolean[] transitionEnters;

    private final List<String> availableLinkModes;
    private final List<String> availableTripModes;
    private final List<String> availableTripPurposes;
    private final List<String> availableSexCategories;

    private final double minTime;
    private final double maxTime;

    public SimulationModel(
            NetworkData networkData,
            VehicleTraversal[] traversals,
            Map<String, String> vehicleToPerson,
            Map<String, String> vehicleToMode,
            Map<String, VehicleMetadata> metadataByPerson,
            Map<String, List<TripPurposeWindow>> tripPurposeWindowsByPerson
    ) {
        this.networkData = networkData;
        this.vehicleToPerson = Collections.unmodifiableMap(vehicleToPerson);
        this.vehicleToMode = Collections.unmodifiableMap(vehicleToMode);
        this.metadataByPerson = Collections.unmodifiableMap(metadataByPerson);
        this.tripPurposeWindowsByPerson = Collections.unmodifiableMap(copyTripPurposeWindows(tripPurposeWindowsByPerson));

        int count = traversals == null ? 0 : traversals.length;
        this.traversalVehicleIds = new String[count];
        this.traversalTripModes = new String[count];
        this.traversalLinkIds = new String[count];
        this.traversalEnterTimes = new double[count];
        this.traversalLeaveTimes = new double[count];
        this.traversalInverseDurations = new double[count];

        for (int i = 0; i < count; i++) {
            VehicleTraversal traversal = traversals[i];
            this.traversalVehicleIds[i] = traversal.vehicleId();
            this.traversalTripModes[i] = normalizeMode(vehicleToMode.get(this.traversalVehicleIds[i]));
            this.traversalLinkIds[i] = traversal.linkId();
            this.traversalEnterTimes[i] = traversal.enterTimeSeconds();
            this.traversalLeaveTimes[i] = traversal.leaveTimeSeconds();
            double duration = Math.max(0.05, this.traversalLeaveTimes[i] - this.traversalEnterTimes[i]);
            this.traversalInverseDurations[i] = 1.0 / duration;
        }

        TransitionArrays transitionArrays = buildTransitionArrays();
        this.transitionTimes = transitionArrays.times();
        this.transitionTraversalIndexes = transitionArrays.traversalIndexes();
        this.transitionLinkIds = transitionArrays.linkIds();
        this.transitionEnters = transitionArrays.enters();

        this.availableLinkModes = Collections.unmodifiableList(extractLinkModes(networkData));
        this.availableTripModes = Collections.unmodifiableList(extractTripModes(vehicleToMode));
        this.availableTripPurposes = Collections.unmodifiableList(
            extractTripPurposes(metadataByPerson, this.tripPurposeWindowsByPerson)
        );
        this.availableSexCategories = Collections.unmodifiableList(extractSexCategories(metadataByPerson));

        if (transitionTimes.length == 0) {
            this.minTime = 0.0;
            this.maxTime = 0.0;
        } else {
            this.minTime = transitionTimes[0];
            this.maxTime = transitionTimes[transitionTimes.length - 1];
        }
    }

    public NetworkData networkData() {
        return networkData;
    }

    public Map<String, String> vehicleToPerson() {
        return vehicleToPerson;
    }

    public Map<String, String> vehicleToMode() {
        return vehicleToMode;
    }

    public Map<String, VehicleMetadata> metadataByPerson() {
        return metadataByPerson;
    }

    public String tripPurposeForTraversal(int traversalIndex) {
        String personId = resolvePersonIdForVehicle(traversalVehicleId(traversalIndex));
        if (personId == null) {
            return null;
        }

        List<TripPurposeWindow> windows = tripPurposeWindowsByPerson.get(personId);
        if (windows == null || windows.isEmpty()) {
            return null;
        }

        double enter = traversalEnterTime(traversalIndex);
        double leave = traversalLeaveTime(traversalIndex);
        double midpoint = (enter + leave) * 0.5;

        for (TripPurposeWindow window : windows) {
            if (overlaps(enter, leave, window.departureTimeSeconds(), window.arrivalTimeSeconds())
                    || contains(window.departureTimeSeconds(), window.arrivalTimeSeconds(), midpoint)) {
                return window.purpose();
            }
        }

        return null;
    }

    public int traversalCount() {
        return traversalVehicleIds.length;
    }

    public String traversalVehicleId(int index) {
        return traversalVehicleIds[index];
    }

    public String traversalTripMode(int index) {
        return traversalTripModes[index];
    }

    public String traversalLinkId(int index) {
        return traversalLinkIds[index];
    }

    public double traversalEnterTime(int index) {
        return traversalEnterTimes[index];
    }

    public double traversalLeaveTime(int index) {
        return traversalLeaveTimes[index];
    }

    public double traversalInverseDuration(int index) {
        return traversalInverseDurations[index];
    }

    public int transitionCount() {
        return transitionTimes.length;
    }

    public double transitionTime(int index) {
        return transitionTimes[index];
    }

    public int transitionTraversalIndex(int index) {
        return transitionTraversalIndexes[index];
    }

    public String transitionLinkId(int index) {
        return transitionLinkIds[index];
    }

    public boolean transitionEnter(int index) {
        return transitionEnters[index];
    }

    public double minTime() {
        return minTime;
    }

    public double maxTime() {
        return maxTime;
    }

    public List<String> availableLinkModes() {
        return availableLinkModes;
    }

    public List<String> availableTripModes() {
        return availableTripModes;
    }

    public List<String> availableTripPurposes() {
        return availableTripPurposes;
    }

    public List<String> availableSexCategories() {
        return availableSexCategories;
    }

    private TransitionArrays buildTransitionArrays() {
        int traversalCount = traversalCount();
        int transitionCount = traversalCount * 2;

        double[] times = new double[transitionCount];
        int[] traversalIndexes = new int[transitionCount];
        String[] linkIds = new String[transitionCount];
        boolean[] enters = new boolean[transitionCount];

        int p = 0;
        for (int i = 0; i < traversalCount; i++) {
            times[p] = traversalEnterTimes[i];
            traversalIndexes[p] = i;
            linkIds[p] = traversalLinkIds[i];
            enters[p] = true;
            p++;

            times[p] = traversalLeaveTimes[i];
            traversalIndexes[p] = i;
            linkIds[p] = traversalLinkIds[i];
            enters[p] = false;
            p++;
        }

        quickSortTransitions(times, traversalIndexes, linkIds, enters, 0, transitionCount - 1);
        return new TransitionArrays(times, traversalIndexes, linkIds, enters);
    }

    private static void quickSortTransitions(
            double[] times,
            int[] traversalIndexes,
            String[] linkIds,
            boolean[] enters,
            int left,
            int right
    ) {
        int i = left;
        int j = right;
        double pivotTime = times[left + ((right - left) >> 1)];
        boolean pivotEnter = enters[left + ((right - left) >> 1)];

        while (i <= j) {
            while (compareTransition(times[i], enters[i], pivotTime, pivotEnter) < 0) {
                i++;
            }
            while (compareTransition(times[j], enters[j], pivotTime, pivotEnter) > 0) {
                j--;
            }
            if (i <= j) {
                swap(times, i, j);
                swap(traversalIndexes, i, j);
                swap(linkIds, i, j);
                swap(enters, i, j);
                i++;
                j--;
            }
        }

        if (left < j) {
            quickSortTransitions(times, traversalIndexes, linkIds, enters, left, j);
        }
        if (i < right) {
            quickSortTransitions(times, traversalIndexes, linkIds, enters, i, right);
        }
    }

    private static int compareTransition(double timeA, boolean enterA, double timeB, boolean enterB) {
        if (timeA < timeB) {
            return -1;
        }
        if (timeA > timeB) {
            return 1;
        }
        int enterValueA = enterA ? 1 : 0;
        int enterValueB = enterB ? 1 : 0;
        return Integer.compare(enterValueA, enterValueB);
    }

    private static void swap(double[] values, int i, int j) {
        double tmp = values[i];
        values[i] = values[j];
        values[j] = tmp;
    }

    private static void swap(int[] values, int i, int j) {
        int tmp = values[i];
        values[i] = values[j];
        values[j] = tmp;
    }

    private static void swap(String[] values, int i, int j) {
        String tmp = values[i];
        values[i] = values[j];
        values[j] = tmp;
    }

    private static void swap(boolean[] values, int i, int j) {
        boolean tmp = values[i];
        values[i] = values[j];
        values[j] = tmp;
    }

    private static List<String> extractLinkModes(NetworkData networkData) {
        Set<String> modes = new LinkedHashSet<>();
        networkData.getLinks().values().forEach(link -> link.allowedModes().forEach(mode -> {
            if (mode != null && !mode.isBlank()) {
                modes.add(mode.toLowerCase(Locale.ROOT));
            }
        }));
        List<String> sorted = new ArrayList<>(modes);
        Collections.sort(sorted);
        return sorted;
    }

    private static List<String> extractTripModes(Map<String, String> vehicleToMode) {
        Set<String> modes = new LinkedHashSet<>();
        vehicleToMode.values().forEach(mode -> {
            if (mode != null && !mode.isBlank()) {
                modes.add(mode.toLowerCase(Locale.ROOT));
            }
        });
        List<String> sorted = new ArrayList<>(modes);
        Collections.sort(sorted);
        return sorted;
    }

    private static List<String> extractTripPurposes(
            Map<String, VehicleMetadata> metadataByPerson,
            Map<String, List<TripPurposeWindow>> tripPurposeWindowsByPerson
    ) {
        Set<String> purposes = new LinkedHashSet<>();
        metadataByPerson.values().forEach(metadata -> {
            String purpose = metadata.tripPurpose();
            if (purpose != null && !purpose.isBlank()) {
                purposes.add(purpose);
            }
        });

        tripPurposeWindowsByPerson.values().forEach(list -> {
            for (TripPurposeWindow window : list) {
                if (window.purpose() != null && !window.purpose().isBlank()) {
                    purposes.add(window.purpose());
                }
            }
        });

        List<String> sorted = new ArrayList<>(purposes);
        Collections.sort(sorted);
        return sorted;
    }

    private String resolvePersonIdForVehicle(String vehicleId) {
        if (vehicleId == null || vehicleId.isBlank()) {
            return null;
        }

        String direct = vehicleToPerson.get(vehicleId);
        if (direct != null) {
            return direct;
        }

        if (metadataByPerson.containsKey(vehicleId) || tripPurposeWindowsByPerson.containsKey(vehicleId)) {
            return vehicleId;
        }

        for (String candidate : personCandidatesFromVehicleId(vehicleId)) {
            if (metadataByPerson.containsKey(candidate) || tripPurposeWindowsByPerson.containsKey(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private static Map<String, List<TripPurposeWindow>> copyTripPurposeWindows(Map<String, List<TripPurposeWindow>> input) {
        Map<String, List<TripPurposeWindow>> copy = new java.util.HashMap<>();
        if (input == null) {
            return copy;
        }
        for (Map.Entry<String, List<TripPurposeWindow>> entry : input.entrySet()) {
            List<TripPurposeWindow> value = entry.getValue() == null
                    ? List.of()
                    : Collections.unmodifiableList(new ArrayList<>(entry.getValue()));
            copy.put(entry.getKey(), value);
        }
        return copy;
    }

    private static boolean overlaps(double aStart, double aEnd, double bStart, double bEnd) {
        return aStart <= bEnd && bStart <= aEnd;
    }

    private static boolean contains(double start, double end, double value) {
        return value >= start && value <= end;
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

    private static List<String> extractSexCategories(Map<String, VehicleMetadata> metadataByPerson) {
        Set<String> categories = new LinkedHashSet<>();
        metadataByPerson.values().forEach(metadata -> {
            String sex = metadata.sex();
            if (sex != null && !sex.isBlank()) {
                categories.add(normalizeSex(sex));
            }
        });

        List<String> sorted = new ArrayList<>(categories);
        sorted.sort((a, b) -> Integer.compare(sexSortKey(a), sexSortKey(b)));
        return sorted;
    }

    private static String normalizeSex(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("m") || value.equals("male")) {
            return "male";
        }
        if (value.equals("f") || value.equals("female")) {
            return "female";
        }
        if (value.isEmpty()) {
            return "unknown";
        }
        return value;
    }

    private static String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT);
    }

    private static int sexSortKey(String value) {
        return switch (value) {
            case "male" -> 0;
            case "female" -> 1;
            case "other" -> 2;
            case "unknown" -> 3;
            default -> 10 + Math.abs(value.hashCode() % 1000);
        };
    }

    private record TransitionArrays(double[] times, int[] traversalIndexes, String[] linkIds, boolean[] enters) {
    }
}
