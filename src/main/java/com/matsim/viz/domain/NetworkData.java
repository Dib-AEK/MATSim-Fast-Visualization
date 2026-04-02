package com.matsim.viz.domain;

import java.util.Collections;
import java.util.Map;

public final class NetworkData {
    private final Map<String, NodePoint> nodes;
    private final Map<String, LinkSegment> links;
    private final double minX;
    private final double minY;
    private final double maxX;
    private final double maxY;

    public NetworkData(
            Map<String, NodePoint> nodes,
            Map<String, LinkSegment> links,
            double minX,
            double minY,
            double maxX,
            double maxY
    ) {
        this.nodes = Collections.unmodifiableMap(nodes);
        this.links = Collections.unmodifiableMap(links);
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
    }

    public Map<String, NodePoint> getNodes() {
        return nodes;
    }

    public Map<String, LinkSegment> getLinks() {
        return links;
    }

    public double getMinX() {
        return minX;
    }

    public double getMinY() {
        return minY;
    }

    public double getMaxX() {
        return maxX;
    }

    public double getMaxY() {
        return maxY;
    }
}
