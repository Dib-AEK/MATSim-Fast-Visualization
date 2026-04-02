package com.matsim.viz.parser;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Parses a MATSim transit schedule file (output_transitSchedule.xml.gz)
 * and extracts a mapping from departure vehicleRefId to transport mode
 * (bus, tram, rail, subway, ferry, etc.).
 */
public final class TransitScheduleParser {
    private TransitScheduleParser() {
    }

    public static Map<String, String> parseVehicleModes(Path transitScheduleFile) {
        Map<String, String> vehicleToMode = new HashMap<>();

        try (InputStream is = InputStreams.openMaybeGzip(transitScheduleFile)) {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            SAXParser parser = factory.newSAXParser();

            parser.parse(is, new DefaultHandler() {
                private String currentMode;
                private boolean inTransportMode;
                private final StringBuilder textBuffer = new StringBuilder();

                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes) {
                    if ("transitRoute".equals(qName)) {
                        currentMode = null;
                    } else if ("transportMode".equals(qName)) {
                        inTransportMode = true;
                        textBuffer.setLength(0);
                    } else if ("departure".equals(qName)) {
                        String vehicleRefId = attributes.getValue("vehicleRefId");
                        if (vehicleRefId != null && !vehicleRefId.isBlank() && currentMode != null) {
                            vehicleToMode.put(vehicleRefId, currentMode.toLowerCase(Locale.ROOT));
                        }
                    }
                }

                @Override
                public void characters(char[] ch, int start, int length) {
                    if (inTransportMode) {
                        textBuffer.append(ch, start, length);
                    }
                }

                @Override
                public void endElement(String uri, String localName, String qName) {
                    if ("transportMode".equals(qName)) {
                        inTransportMode = false;
                        String mode = textBuffer.toString().trim();
                        if (!mode.isEmpty()) {
                            currentMode = mode;
                        }
                    }
                }
            });
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse transit schedule: " + transitScheduleFile, ex);
        }

        return vehicleToMode;
    }
}
