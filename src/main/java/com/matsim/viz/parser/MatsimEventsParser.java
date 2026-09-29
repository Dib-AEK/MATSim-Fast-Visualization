package com.matsim.viz.parser;

import java.nio.file.Path;

/** Compatibility entry point; all events go through MATSim's reader and typed handlers. */
public final class MatsimEventsParser {
    public EventsParseResult parse(Path eventsFile) {
        return new MatsimEventsProcessor().readTraversals(eventsFile);
    }
}
