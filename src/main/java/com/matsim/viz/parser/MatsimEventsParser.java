package com.matsim.viz.parser;

import com.matsim.viz.domain.VehicleTraversal;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MatsimEventsParser {
    public EventsParseResult parse(Path eventsFile) throws Exception {
        List<VehicleTraversal> traversals = new ArrayList<>(1_000_000);
        Map<String, String> vehicleToPerson = new HashMap<>();
        Map<String, ActiveLinkState> activeByVehicle = new HashMap<>(64_000);

        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);
        SAXParser parser = factory.newSAXParser();

        try (InputStream input = InputStreams.openMaybeGzip(eventsFile)) {
            parser.parse(input, new DefaultHandler() {
                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
                    if (!"event".equalsIgnoreCase(qName)) {
                        return;
                    }

                    double time = parseDouble(attributes.getValue("time"), 0.0);
                    String type = value(attributes, "type").toLowerCase(Locale.ROOT);
                    String linkId = firstNonBlank(value(attributes, "link"), value(attributes, "linkId"));
                    String vehicleId = firstNonBlank(value(attributes, "vehicle"), value(attributes, "vehicleId"));
                    String personId = firstNonBlank(value(attributes, "person"), value(attributes, "personId"), value(attributes, "driver"));

                    if (vehicleId != null && personId != null) {
                        vehicleToPerson.putIfAbsent(vehicleId, personId);
                    }

                    if (vehicleId == null || linkId == null) {
                        return;
                    }

                    if (isLinkEnter(type)) {
                        ActiveLinkState previous = activeByVehicle.put(vehicleId, new ActiveLinkState(linkId, time));
                        if (previous != null) {
                            appendTraversal(traversals, vehicleId, previous.linkId(), previous.enterTimeSeconds(), time);
                        }
                        return;
                    }

                    if (isLinkLeave(type)) {
                        ActiveLinkState entered = activeByVehicle.remove(vehicleId);
                        if (entered != null) {
                            appendTraversal(traversals, vehicleId, entered.linkId(), entered.enterTimeSeconds(), time);
                        }
                    }
                }
            });
        }

        return new EventsParseResult(traversals.toArray(new VehicleTraversal[0]), vehicleToPerson, Map.of());
    }

    private static void appendTraversal(List<VehicleTraversal> traversals, String vehicleId, String linkId, double enter, double leave) {
        double safeLeave = Math.max(leave, enter + 0.05);
        traversals.add(new VehicleTraversal(traversals.size(), vehicleId, linkId, enter, safeLeave));
    }

    private static boolean isLinkEnter(String type) {
        return type.equals("entered link") || type.equals("linkenter") || type.equals("link enter");
    }

    private static boolean isLinkLeave(String type) {
        return type.equals("left link") || type.equals("linkleave") || type.equals("link leave");
    }

    private static String value(Attributes attributes, String key) {
        String value = attributes.getValue(key);
        return value == null ? "" : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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

    private record ActiveLinkState(String linkId, double enterTimeSeconds) {
    }
}
