package com.matsim.viz.ui;

import com.matsim.viz.config.*;
import com.matsim.viz.domain.*;
import com.matsim.viz.engine.*;
import java.awt.Color;
import java.nio.file.*;
import java.util.Map;
import javax.swing.SwingUtilities;

/** Configuration precedence and consistency with standalone renderer initialization. */
public final class DefaultsCheck {
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile(Path.of("target"), "defaults-", ".properties");
        try {
            AppConfig config = ConfigLoader.load(file);
            check(!config.hasMatsimConfigFile(), "Empty config invented a scenario");
            check(config.playbackStartSeconds() == AppDefaults.Playback.START_SECONDS
                    && config.playbackEndSeconds() == AppDefaults.Playback.END_SECONDS
                    && config.playbackSpeed() == AppDefaults.Playback.SPEED, "Playback defaults diverged");
            check(config.uiMapCrs().equals(AppDefaults.Maps.CRS), "Map CRS diverged");
            check(config.recordingDefaultQuality().equals(AppDefaults.Recording.DEFAULT_QUALITY), "Recording default diverged");
            SwingUtilities.invokeAndWait(() -> {
                var network = new NetworkData(Map.of(), Map.of(), 0, 0, 100, 100);
                var model = new SimulationModel(network, null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null);
                var panel = new NetworkPanel(model, new PlaybackController(model,
                        config.playbackStartSeconds(), config.playbackEndSeconds(), config.playbackSpeed()));
                check(panel.isDarkTheme() == config.uiDarkTheme(), "Theme differs");
                check(panel.getLaneWidthMeters() == config.uiLaneWidthMeters(), "Lane width differs");
                check(panel.getBidirectionalOffset() == config.uiBidirectionalOffset(), "Carriageway spacing differs");
                check(panel.getHeatmapTimeBinSeconds() == config.uiHeatmapTimeBinSeconds(), "Heatmap bin differs");
                check(panel.getVisualizationMode().name().equals(config.uiVisualizationMode()), "Visualization mode differs");
                check(panel.getCarLikeVehicleLengthMeters() == config.uiVehicleLengthCarMeters(), "Car length differs");
                check(panel.getBusVehicleLengthMeters() == config.uiVehicleLengthBusMeters(), "Bus length differs");
                check(panel.getBikeVehicleWidthRatio() == config.uiVehicleWidthRatioBike(), "Bike width differs");
                check(panel.getCarShape().name().equals(config.uiVehicleShapeCar()), "Car shape differs");
                check(panel.getBusShape().name().equals(config.uiVehicleShapeBus()), "Bus shape differs");
                check(panel.getSpeedHeatmapLowColor().equals(Color.decode(config.uiSpeedColorLow())), "Speed palette differs");
                check(panel.getSpeedRatioHeatmapHighColor().equals(Color.decode(config.uiSpeedRatioColorHigh())), "Ratio palette differs");
                panel.setSpeedHeatmapLowColor(Color.PINK);
                panel.setSpeedHeatmapLowColor(null);
                check(panel.getSpeedHeatmapLowColor().equals(Color.decode(config.uiSpeedColorLow())), "Null reset bypasses defaults");
                var first = new VehicleColorProvider();
                var second = new VehicleColorProvider();
                first.setModeColor("bus", Color.BLACK);
                first.setAgeGroupColor(0, Color.BLACK);
                check(!second.colorForTripMode("bus").equals(Color.BLACK)
                        && !second.ageGroupColor(0).equals(Color.BLACK), "Defaults share mutable palettes");
                check(PanelVideoRecorder.Quality.PRESENTATION_4K.width() == AppDefaults.Recording.PRESENTATION_4K.width(),
                        "Video preset diverged");
            });
            Files.writeString(file, "playback.speed=17\nui.theme.dark=false\nui.lane.width.m=4.2\n"
                    + "ui.map.crs=EPSG:4326\nrecording.default.quality=HIGH\nui.heatmap.time.bin.seconds=\n");
            AppConfig overrides = ConfigLoader.load(file);
            check(overrides.playbackSpeed() == 17 && !overrides.uiDarkTheme() && overrides.uiLaneWidthMeters() == 4.2,
                    "Explicit overrides lost to defaults");
            check(overrides.uiMapCrs().equals("EPSG:4326") && overrides.recordingDefaultQuality().equals("HIGH"),
                    "String overrides lost to defaults");
            check(overrides.uiHeatmapTimeBinSeconds() == config.uiHeatmapTimeBinSeconds(), "Blank numeric fallback diverged");
        } finally {
            Files.deleteIfExists(file);
        }
        System.out.println("PASS: centralized defaults, config overrides, renderer initialization, palette isolation and resets.");
    }
}
