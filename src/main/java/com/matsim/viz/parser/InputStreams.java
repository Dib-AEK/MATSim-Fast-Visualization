package com.matsim.viz.parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

public final class InputStreams {
    private InputStreams() {
    }

    public static InputStream openMaybeGzip(Path file) throws IOException {
        InputStream inputStream = Files.newInputStream(file);
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".gz")) {
            return new GZIPInputStream(inputStream, 256 * 1024);
        }
        return inputStream;
    }
}
