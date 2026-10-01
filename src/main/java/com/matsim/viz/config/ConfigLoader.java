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

        Path cacheDir = Path.of(properties.getProperty("cache.dir", AppDefaults.Paths.CACHE_DIR).trim());

        return new AppConfig(
                matsimConfigFile,
                cacheDir,
                parseInt(properties, "playback.start.seconds", AppDefaults.Playback.START_SECONDS),
                parseInt(properties, "playback.end.seconds", AppDefaults.Playback.END_SECONDS),
                parseInt(properties, "playback.speed", AppDefaults.Playback.SPEED),
                properties.getProperty("render.backend", AppDefaults.Rendering.BACKEND).trim(),
                properties.getProperty("render.java2d.pipeline", AppDefaults.Rendering.JAVA2D_PIPELINE).trim(),
                parseBoolean(properties, "render.java2d.force.vram", AppDefaults.Rendering.JAVA2D_FORCE_VRAM),
                parseBoolean(properties, "ui.theme.dark", AppDefaults.Display.THEME_DARK),
                properties.getProperty("ui.color.mode", AppDefaults.Display.COLOR_MODE).trim(),
                parseBoolean(properties, "ui.show.queues", AppDefaults.Display.SHOW_QUEUES),
                parseDouble(properties, "ui.bidirectional.offset", AppDefaults.Display.BIDIRECTIONAL_OFFSET),
                parseDouble(properties, "ui.lane.width.m", AppDefaults.Display.LANE_WIDTH_M),
                parseBoolean(properties, "ui.show.bottleneck", AppDefaults.Display.SHOW_BOTTLENECK),
                parseDouble(properties, "ui.bottleneck.divisor", AppDefaults.Display.BOTTLENECK_DIVISOR),
                parseBoolean(properties, "ui.keep.vehicles.visible.when.zoomed.out", AppDefaults.Display.KEEP_VEHICLES_VISIBLE_WHEN_ZOOMED_OUT),
                parseDouble(properties, "ui.min.vehicle.length.px", AppDefaults.Display.MIN_VEHICLE_LENGTH_PX),
                parseDouble(properties, "ui.min.vehicle.width.px", AppDefaults.Display.MIN_VEHICLE_WIDTH_PX),
                parseDouble(properties, "ui.vehicle.length.car.m", AppDefaults.Vehicles.LENGTH_CAR_M),
                parseDouble(properties, "ui.vehicle.length.bike.m", AppDefaults.Vehicles.LENGTH_BIKE_M),
                parseDouble(properties, "ui.vehicle.length.truck.m", AppDefaults.Vehicles.LENGTH_TRUCK_M),
                parseDouble(properties, "ui.vehicle.length.bus.m", AppDefaults.Vehicles.LENGTH_BUS_M),
                parseDouble(properties, "ui.vehicle.length.rail.m", AppDefaults.Vehicles.LENGTH_RAIL_M),
                parseDouble(properties, "ui.vehicle.width.ratio.car", AppDefaults.Vehicles.WIDTH_RATIO_CAR),
                parseDouble(properties, "ui.vehicle.width.ratio.bike", AppDefaults.Vehicles.WIDTH_RATIO_BIKE),
                parseDouble(properties, "ui.vehicle.width.ratio.truck", AppDefaults.Vehicles.WIDTH_RATIO_TRUCK),
                parseDouble(properties, "ui.vehicle.width.ratio.bus", AppDefaults.Vehicles.WIDTH_RATIO_BUS),
                parseDouble(properties, "ui.vehicle.width.ratio.rail", AppDefaults.Vehicles.WIDTH_RATIO_RAIL),
                properties.getProperty("ui.vehicle.shape.car", AppDefaults.Vehicles.SHAPE_CAR).trim(),
                properties.getProperty("ui.vehicle.shape.bike", AppDefaults.Vehicles.SHAPE_BIKE).trim(),
                properties.getProperty("ui.vehicle.shape.truck", AppDefaults.Vehicles.SHAPE_TRUCK).trim(),
                properties.getProperty("ui.vehicle.shape.bus", AppDefaults.Vehicles.SHAPE_BUS).trim(),
                properties.getProperty("ui.vehicle.shape.rail", AppDefaults.Vehicles.SHAPE_RAIL).trim(),
                properties.getProperty("ui.visualization.mode", AppDefaults.Display.VISUALIZATION_MODE).trim(),
                parseInt(properties, "ui.heatmap.time.bin.seconds", AppDefaults.Heatmap.TIME_BIN_SECONDS),
                properties.getProperty("ui.heatmap.flow.color.low", AppDefaults.Heatmap.FLOW_COLOR_LOW).trim(),
                properties.getProperty("ui.heatmap.flow.color.high", AppDefaults.Heatmap.FLOW_COLOR_HIGH).trim(),
                properties.getProperty("ui.heatmap.speed.color.low", AppDefaults.Heatmap.SPEED_COLOR_LOW).trim(),
                properties.getProperty("ui.heatmap.speed.color.high", AppDefaults.Heatmap.SPEED_COLOR_HIGH).trim(),
                properties.getProperty("ui.heatmap.speed.ratio.color.low", AppDefaults.Heatmap.SPEED_RATIO_COLOR_LOW).trim(),
                properties.getProperty("ui.heatmap.speed.ratio.color.high", AppDefaults.Heatmap.SPEED_RATIO_COLOR_HIGH).trim(),
                properties.getProperty("recording.default.quality", AppDefaults.Recording.DEFAULT_QUALITY).trim(),
                parseBoolean(properties, "ui.map.background", AppDefaults.Maps.BACKGROUND),
                properties.getProperty("ui.map.crs", AppDefaults.Maps.CRS).trim(),
                parseDouble(properties, "ui.zoom.detail.start.lane.px", AppDefaults.Display.ZOOM_DETAIL_START_LANE_PX),
                parseDouble(properties, "ui.zoom.detail.full.lane.px", AppDefaults.Display.ZOOM_DETAIL_FULL_LANE_PX),
                parseDouble(properties, "ui.overview.vehicle.coverage", AppDefaults.Display.OVERVIEW_VEHICLE_COVERAGE),
                parseDouble(properties, "ui.vehicle.length.ferry.m", AppDefaults.Vehicles.LENGTH_FERRY_M),
                parseDouble(properties, "ui.vehicle.width.ratio.ferry", AppDefaults.Vehicles.WIDTH_RATIO_FERRY),
                properties.getProperty("ui.vehicle.shape.ferry", AppDefaults.Vehicles.SHAPE_FERRY).trim()
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
