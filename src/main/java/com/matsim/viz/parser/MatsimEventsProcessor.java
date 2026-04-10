package com.matsim.viz.parser;

import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;

import java.nio.file.Path;
import java.util.Map;

public final class MatsimEventsProcessor {
    public EventsParseResult readTraversals(Path eventsFile) {
        return readTraversals(eventsFile, Map.of());
    }

    public EventsParseResult readTraversals(Path eventsFile, Map<String, String> knownVehicleModes) {
        MatsimEventsCollector collector = new MatsimEventsCollector(knownVehicleModes);
        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(collector);

        MatsimEventsReader reader = new MatsimEventsReader(eventsManager);
        reader.readFile(eventsFile.toString());

        return collector.snapshotResult();
    }
}
