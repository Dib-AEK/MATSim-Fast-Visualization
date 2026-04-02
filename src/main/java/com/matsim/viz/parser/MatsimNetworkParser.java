package com.matsim.viz.parser;

import com.matsim.viz.domain.LinkSegment;
import com.matsim.viz.domain.NetworkData;
import com.matsim.viz.domain.NodePoint;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class MatsimNetworkParser {
    public NetworkData parse(Path networkFile) throws Exception {
        Map<String, NodePoint> nodes = new HashMap<>();
        Map<String, LinkSegment> links = new HashMap<>();

        double[] bounds = new double[]{
                Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY,
                Double.NEGATIVE_INFINITY
        };

        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        SAXParser parser = factory.newSAXParser();

        try (InputStream input = InputStreams.openMaybeGzip(networkFile)) {
            parser.parse(input, new DefaultHandler() {
                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
                    String element = qName.toLowerCase();
                    if ("node".equals(element)) {
                        String id = attributes.getValue("id");
                        String xRaw = attributes.getValue("x");
                        String yRaw = attributes.getValue("y");
                        if (id == null || xRaw == null || yRaw == null) {
                            return;
                        }
                        double x = parseDouble(xRaw, 0.0);
                        double y = parseDouble(yRaw, 0.0);
                        nodes.put(id, new NodePoint(id, x, y));
                        bounds[0] = Math.min(bounds[0], x);
                        bounds[1] = Math.min(bounds[1], y);
                        bounds[2] = Math.max(bounds[2], x);
                        bounds[3] = Math.max(bounds[3], y);
                    } else if ("link".equals(element)) {
                        String id = attributes.getValue("id");
                        String fromId = attributes.getValue("from");
                        String toId = attributes.getValue("to");
                        if (id == null || fromId == null || toId == null) {
                            return;
                        }
                        NodePoint from = nodes.get(fromId);
                        NodePoint to = nodes.get(toId);
                        if (from == null || to == null) {
                            return;
                        }
                        double length = parseDouble(attributes.getValue("length"), distance(from, to));
                        double freeSpeed = parseDouble(attributes.getValue("freespeed"), 0.0);
                        double lanes = parseDouble(attributes.getValue("permlanes"), 1.0);
                        Set<String> allowedModes = parseAllowedModes(attributes.getValue("modes"));
                        links.put(id, new LinkSegment(
                                id,
                                fromId,
                                toId,
                                from.x(),
                                from.y(),
                                to.x(),
                                to.y(),
                                length,
                                freeSpeed,
                                lanes,
                                allowedModes
                        ));
                    }
                }
            });
        }

        if (links.isEmpty()) {
            throw new IllegalStateException("No links parsed from network file: " + networkFile);
        }

        return new NetworkData(nodes, links, bounds[0], bounds[1], bounds[2], bounds[3]);
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double distance(NodePoint from, NodePoint to) {
        double dx = to.x() - from.x();
        double dy = to.y() - from.y();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static Set<String> parseAllowedModes(String modesRaw) {
        if (modesRaw == null || modesRaw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(modesRaw.split(","))
                .map(String::trim)
                .filter(mode -> !mode.isBlank())
                .map(mode -> mode.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }
}
