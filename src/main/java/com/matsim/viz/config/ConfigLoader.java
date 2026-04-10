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

        Path cacheDir = Path.of(properties.getProperty("cache.dir", "cache").trim());

        return new AppConfig(
                matsimConfigFile,
                cacheDir,
                parseInt(properties, "playback.start.seconds", 0),
                parseInt(properties, "playback.end.seconds", 86_400),
                parseInt(properties, "playback.speed", 60),
                properties.getProperty("render.backend", "auto").trim(),
                properties.getProperty("render.java2d.pipeline", "auto").trim(),
                parseBoolean(properties, "render.java2d.force.vram", false),
                parseBoolean(properties, "ui.theme.dark", true),
                properties.getProperty("ui.color.mode", "DEFAULT").trim(),
                parseBoolean(properties, "ui.show.queues", false),
                parseDouble(properties, "ui.bidirectional.offset", 0.45),
                parseBoolean(properties, "ui.show.bottleneck", false),
                parseDouble(properties, "ui.bottleneck.divisor", 6.0),
                parseBoolean(properties, "ui.keep.vehicles.visible.when.zoomed.out", true),
                parseDouble(properties, "ui.min.vehicle.length.px", 3.5),
                parseDouble(properties, "ui.min.vehicle.width.px", 1.7),
                parseDouble(properties, "ui.vehicle.length.car.m", 7.0),
                parseDouble(properties, "ui.vehicle.length.bike.m", 3.0),
                parseDouble(properties, "ui.vehicle.length.truck.m", 10.0),
                parseDouble(properties, "ui.vehicle.length.bus.m", 12.0),
                parseDouble(properties, "ui.vehicle.length.rail.m", 30.0),
                parseDouble(properties, "ui.vehicle.width.ratio.car", 0.70),
                parseDouble(properties, "ui.vehicle.width.ratio.bike", 0.30),
                parseDouble(properties, "ui.vehicle.width.ratio.truck", 0.95),
                parseDouble(properties, "ui.vehicle.width.ratio.bus", 0.85),
                parseDouble(properties, "ui.vehicle.width.ratio.rail", 0.90),
                properties.getProperty("ui.vehicle.shape.car", "RECTANGLE").trim(),
                properties.getProperty("ui.vehicle.shape.bike", "DIAMOND").trim(),
                properties.getProperty("ui.vehicle.shape.truck", "RECTANGLE").trim(),
                properties.getProperty("ui.vehicle.shape.bus", "OVAL").trim(),
                properties.getProperty("ui.vehicle.shape.rail", "ARROW").trim(),
                properties.getProperty("ui.visualization.mode", "VEHICLES").trim(),
                parseInt(properties, "ui.heatmap.time.bin.seconds", 600),
                properties.getProperty("ui.heatmap.flow.color.low", "#F7F7F7").trim(),
                properties.getProperty("ui.heatmap.flow.color.high", "#7A0014").trim(),
                properties.getProperty("ui.heatmap.speed.color.low", "#F7F7F7").trim(),
                properties.getProperty("ui.heatmap.speed.color.high", "#0C4A86").trim(),
                properties.getProperty("ui.heatmap.speed.ratio.color.low", "#F7F7F7").trim(),
                properties.getProperty("ui.heatmap.speed.ratio.color.high", "#0A5D2A").trim(),
                properties.getProperty("recording.default.quality", "VIEWPORT_SYNC").trim()
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

    private static boolean parseBoolean(Properties properties, String key, boolean defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static double parseDouble(Properties properties, String key, double defaultValue) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Double.parseDouble(value.trim());
    }
}
