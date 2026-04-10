package com.matsim.viz.cache;

import com.matsim.viz.parser.MatsimEventsCollector;
import com.matsim.viz.parser.MatsimEventsProcessor;
import com.matsim.viz.parser.MatsimNetworkConverter;
import com.matsim.viz.parser.TransitScheduleParser;
import com.matsim.viz.parser.ResolvedSimulationInputs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

public final class SimulationFingerprint {
    private static final String CACHE_SCHEMA = "mviz-cache-v7";

    private static final List<Class<?>> PROCESSOR_CLASSES = List.of(
            SimulationCacheStore.class,
            MatsimNetworkConverter.class,
            MatsimEventsProcessor.class,
            MatsimEventsCollector.class,
            TransitScheduleParser.class
    );

    private SimulationFingerprint() {
    }

    public static String fromInputs(ResolvedSimulationInputs inputs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, CACHE_SCHEMA);

            List<Path> sourceFiles = new ArrayList<>();
            sourceFiles.add(inputs.matsimConfigFile());
            sourceFiles.add(inputs.networkFile());
            sourceFiles.add(inputs.populationFile());
            sourceFiles.add(inputs.eventsFile());
            if (inputs.tripsFile() != null) {
                sourceFiles.add(inputs.tripsFile());
            }
            if (inputs.outputPersonsFile() != null) {
                sourceFiles.add(inputs.outputPersonsFile());
            }
            if (inputs.outputPlansFile() != null) {
                sourceFiles.add(inputs.outputPlansFile());
            }
            if (inputs.transitScheduleFile() != null) {
                sourceFiles.add(inputs.transitScheduleFile());
            }
            sourceFiles.sort(Comparator.comparing(path -> path.toAbsolutePath().normalize().toString()));

            for (Path file : sourceFiles) {
                Path absolute = file.toAbsolutePath().normalize();
                update(digest, absolute.toString());
                if (Files.exists(absolute)) {
                    update(digest, Long.toString(Files.size(absolute)));
                    update(digest, Long.toString(Files.getLastModifiedTime(absolute).toMillis()));
                } else {
                    update(digest, "missing");
                }
            }

            updateProcessorCodeFingerprint(digest);

            String full = HexFormat.of().formatHex(digest.digest());
            return full.substring(0, 24);
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException("Failed to compute simulation cache fingerprint", e);
        }
    }

    private static void updateProcessorCodeFingerprint(MessageDigest digest) throws IOException {
        for (Class<?> clazz : PROCESSOR_CLASSES) {
            update(digest, clazz.getName());
            String resource = "/" + clazz.getName().replace('.', '/') + ".class";
            try (InputStream in = clazz.getResourceAsStream(resource)) {
                if (in == null) {
                    update(digest, "missing-bytecode");
                    continue;
                }

                byte[] bytes = in.readAllBytes();
                update(digest, Integer.toString(bytes.length));
                digest.update(bytes);
                digest.update((byte) 0);
            }
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
