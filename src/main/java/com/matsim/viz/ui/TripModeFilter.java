package com.matsim.viz.ui;

import java.util.Locale;

public enum TripModeFilter {
    ALL("All Trips"),
    CAR("Car Trips"),
    BIKE("Bike Trips"),
    CAR_AND_BIKE("Car + Bike");

    private final String label;

    TripModeFilter(String label) {
        this.label = label;
    }

    public boolean accepts(String mode) {
        if (this == ALL) {
            return true;
        }
        if (mode == null || mode.isBlank()) {
            return false;
        }

        String normalized = mode.toLowerCase(Locale.ROOT);
        return switch (this) {
            case CAR -> normalized.equals("car");
            case BIKE -> normalized.equals("bike");
            case CAR_AND_BIKE -> normalized.equals("car") || normalized.equals("bike");
            case ALL -> true;
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
