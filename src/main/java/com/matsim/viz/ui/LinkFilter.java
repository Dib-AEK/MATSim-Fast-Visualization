package com.matsim.viz.ui;

import com.matsim.viz.domain.LinkSegment;

public enum LinkFilter {
    ALL_LINKS("All Links"),
    CAR_LINKS_ONLY("Car Links Only");

    private final String label;

    LinkFilter(String label) {
        this.label = label;
    }

    public boolean accepts(LinkSegment link) {
        return switch (this) {
            case ALL_LINKS -> true;
            case CAR_LINKS_ONLY -> link.allowsMode("car");
        };
    }

    @Override
    public String toString() {
        return label;
    }
}
