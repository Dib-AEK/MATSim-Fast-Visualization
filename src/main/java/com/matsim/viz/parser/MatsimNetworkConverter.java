package com.matsim.viz.parser;

import com.matsim.viz.domain.LinkSegment;
import com.matsim.viz.domain.NetworkData;
import com.matsim.viz.domain.NodePoint;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class MatsimNetworkConverter {
    private MatsimNetworkConverter() {
    }

    public static NetworkData fromMatsim(Network network) {
        Map<String, NodePoint> nodes = new HashMap<>(network.getNodes().size());
        Map<String, LinkSegment> links = new HashMap<>(network.getLinks().size());

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;

        for (Node node : network.getNodes().values()) {
            String nodeId = node.getId().toString();
            double x = node.getCoord().getX();
            double y = node.getCoord().getY();
            nodes.put(nodeId, new NodePoint(nodeId, x, y));

            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }

        for (Link link : network.getLinks().values()) {
            String linkId = link.getId().toString();
            String fromNodeId = link.getFromNode().getId().toString();
            String toNodeId = link.getToNode().getId().toString();
            Set<String> allowedModes = link.getAllowedModes().stream()
                    .map(mode -> mode.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            Map<String, String> attributes = new HashMap<>();
            attributes.put("capacity", Double.toString(link.getCapacity()));
            link.getAttributes().getAsMap().forEach((key, value) -> {
                if (key != null && value != null) {
                    attributes.put(key, String.valueOf(value));
                }
            });

            links.put(linkId, new LinkSegment(
                    linkId,
                    fromNodeId,
                    toNodeId,
                    link.getFromNode().getCoord().getX(),
                    link.getFromNode().getCoord().getY(),
                    link.getToNode().getCoord().getX(),
                    link.getToNode().getCoord().getY(),
                    link.getLength(),
                    link.getFreespeed(),
                    link.getNumberOfLanes(),
                    allowedModes,
                    attributes
            ));
        }

        if (links.isEmpty()) {
            throw new IllegalStateException("No links were found in the MATSim network loaded from config.");
        }

        return new NetworkData(nodes, links, minX, minY, maxX, maxY);
    }
}
