package com.matsim.viz.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ConfigLoader {
    private ConfigLoader() {
    }

    public static AppConfig load(Path configPath) throws IOException {
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(configPath)) {
            properties.load(stream);
        }

        Path matsimConfigFile = readOptionalPath(properties, "matsim.config.file");

        Path scenarioDir = readOptionalPath(properties, "scenario.dir");
        if (scenarioDir == null) {
            scenarioDir = Path.of(".").toAbsolutePath().normalize();
        }

        String outputDirName = properties.getProperty("output.dir.name", "").trim();
        Path cacheDir = Path.of(properties.getProperty("cache.dir", "cache").trim());

        return new AppConfig(
                matsimConfigFile,
            cacheDir,
                scenarioDir,
                outputDirName,
                properties.getProperty("network.file", "").trim(),
                properties.getProperty("events.file", "").trim(),
                properties.getProperty("trips.file", "").trim(),
                parseInt(properties, "playback.start.seconds", 0),
                parseInt(properties, "playback.end.seconds", 86_400),
                parseInt(properties, "playback.speed", 60)
        );
    }

    private static Path readOptionalPath(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return null;
        }
        return Path.of(value.trim());
    }

    private static int parseInt(Properties properties, String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value.trim());
    }
}
