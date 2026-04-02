package com.matsim.viz.parser;

import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;

import java.nio.file.Path;

public final class MatsimEventsProcessor {
    public EventsParseResult readTraversals(Path eventsFile) {
        MatsimEventsCollector collector = new MatsimEventsCollector();
        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler(collector);

        MatsimEventsReader reader = new MatsimEventsReader(eventsManager);
        reader.readFile(eventsFile.toString());

        return collector.snapshotResult();
    }
}
