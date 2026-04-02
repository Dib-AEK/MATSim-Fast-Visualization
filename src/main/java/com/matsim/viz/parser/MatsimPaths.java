package com.matsim.viz.parser;

import java.nio.file.Path;

public record MatsimPaths(Path networkFile, Path eventsFile, Path tripsFile) {
}
