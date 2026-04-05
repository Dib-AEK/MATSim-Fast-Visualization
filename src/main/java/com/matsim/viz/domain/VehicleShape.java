package com.matsim.viz.domain;

public enum VehicleShape {
    RECTANGLE("Rectangle"),
    ARROW("Arrow"),
    TRIANGLE("Triangle"),
    DIAMOND("Diamond"),
    CIRCLE("Circle"),
    OVAL("Oval");

    private final String label;

    VehicleShape(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
