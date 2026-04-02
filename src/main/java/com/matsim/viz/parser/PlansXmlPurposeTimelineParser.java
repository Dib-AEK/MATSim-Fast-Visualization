package com.matsim.viz.parser;

import com.matsim.viz.domain.TripPurposeWindow;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PlansXmlPurposeTimelineParser {
    public Map<String, List<TripPurposeWindow>> parse(Path plansFile) throws Exception {
        Map<String, List<TripPurposeWindow>> byPerson = new HashMap<>();

        XMLInputFactory factory = XMLInputFactory.newFactory();
        String currentPersonId = null;
        boolean inSelectedPlan = false;
        LegWindow pendingLeg = null;

        try (InputStream input = InputStreams.openMaybeGzip(plansFile)) {
            XMLStreamReader reader = factory.createXMLStreamReader(input);
            while (reader.hasNext()) {
                int event = reader.next();

                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("person".equals(name)) {
                        currentPersonId = attr(reader, "id");
                        inSelectedPlan = false;
                        pendingLeg = null;
                    } else if ("plan".equals(name)) {
                        if (currentPersonId != null) {
                            String selected = attr(reader, "selected");
                            inSelectedPlan = selected == null || selected.equalsIgnoreCase("yes") || selected.equalsIgnoreCase("true");
                            pendingLeg = null;
                        }
                    } else if ("leg".equals(name) && inSelectedPlan && currentPersonId != null) {
                        Double dep = parseTimeSeconds(attr(reader, "dep_time"));
                        if (dep == null) {
                            dep = parseTimeSeconds(attr(reader, "departure_time"));
                        }
                        Double arr = parseTimeSeconds(attr(reader, "arr_time"));
                        if (arr == null) {
                            arr = parseTimeSeconds(attr(reader, "arrival_time"));
                        }
                        Double trav = parseTimeSeconds(attr(reader, "trav_time"));
                        if (trav == null) {
                            trav = parseTimeSeconds(attr(reader, "travel_time"));
                        }

                        if (dep != null && arr == null && trav != null) {
                            arr = dep + Math.max(0.0, trav);
                        }
                        if (dep != null) {
                            if (arr == null || arr < dep) {
                                arr = dep;
                            }
                            pendingLeg = new LegWindow(dep, arr, attr(reader, "mode"));
                        } else {
                            pendingLeg = null;
                        }
                    } else if ("act".equals(name) && inSelectedPlan && currentPersonId != null && pendingLeg != null) {
                        String purpose = attr(reader, "type");
                        if (purpose != null && !purpose.isBlank()) {
                            TripPurposeWindow window = new TripPurposeWindow(
                                    currentPersonId,
                                    pendingLeg.departureTimeSeconds(),
                                    pendingLeg.arrivalTimeSeconds(),
                                    purpose,
                                    pendingLeg.mode()
                            );
                            byPerson.computeIfAbsent(currentPersonId, key -> new ArrayList<>()).add(window);
                        }
                        pendingLeg = null;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("plan".equals(name)) {
                        inSelectedPlan = false;
                        pendingLeg = null;
                    } else if ("person".equals(name)) {
                        currentPersonId = null;
                        inSelectedPlan = false;
                        pendingLeg = null;
                    }
                }
            }
            reader.close();
        }

        byPerson.values().forEach(list -> list.sort((a, b) -> Double.compare(a.departureTimeSeconds(), b.departureTimeSeconds())));
        return byPerson;
    }

    private static String attr(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Double parseTimeSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String value = raw.trim();
        if (value.contains(":")) {
            String[] parts = value.split(":");
            if (parts.length == 3) {
                Integer h = parseInt(parts[0]);
                Integer m = parseInt(parts[1]);
                Double s = parseDouble(parts[2]);
                if (h != null && m != null && s != null) {
                    return h * 3600.0 + m * 60.0 + s;
                }
            }
        }
        return parseDouble(value);
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static Double parseDouble(String raw) {
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private record LegWindow(double departureTimeSeconds, double arrivalTimeSeconds, String mode) {
    }
}
