package com.matsim.viz.config;

import java.nio.file.Path;

public record AppConfig(
        Path matsimConfigFile,
        Path cacheDir,
        int playbackStartSeconds,
        int playbackEndSeconds,
        int playbackSpeed,
        String java2dPipeline,
        boolean java2dForceVram,
        boolean uiDarkTheme,
        String uiColorMode,
        boolean uiShowQueues,
        double uiBidirectionalOffset,
        boolean uiShowBottleneck,
        double uiBottleneckDivisor,
        boolean uiKeepVehiclesVisibleWhenZoomedOut,
        double uiMinVehicleLengthPixels,
        double uiMinVehicleWidthPixels,
        double uiVehicleLengthCarMeters,
        double uiVehicleLengthBikeMeters,
        double uiVehicleLengthTruckMeters,
        double uiVehicleLengthBusMeters,
        double uiVehicleLengthRailMeters,
        double uiVehicleWidthRatioCar,
        double uiVehicleWidthRatioBike,
        double uiVehicleWidthRatioTruck,
        double uiVehicleWidthRatioBus,
        double uiVehicleWidthRatioRail,
        String uiVehicleShapeCar,
        String uiVehicleShapeBike,
        String uiVehicleShapeTruck,
        String uiVehicleShapeBus,
        String uiVehicleShapeRail,
        String uiVisualizationMode,
        int uiHeatmapTimeBinSeconds,
        String uiFlowColorLow,
        String uiFlowColorHigh,
        String uiSpeedColorLow,
        String uiSpeedColorHigh,
        String uiSpeedRatioColorLow,
        String uiSpeedRatioColorHigh,
        String recordingDefaultQuality
) {
    public boolean hasMatsimConfigFile() {
        return matsimConfigFile != null;
    }
}
