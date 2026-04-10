package com.matsim.viz.ui;

import com.matsim.viz.domain.ColorMode;
import com.matsim.viz.domain.LinkSegment;
import com.matsim.viz.domain.NetworkData;
import com.matsim.viz.domain.PtStopPoint;
import com.matsim.viz.domain.VehicleShape;
import com.matsim.viz.engine.PlaybackController;
import com.matsim.viz.engine.SimulationModel;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.GraphicsConfiguration;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public final class NetworkPanel extends JPanel {
    public enum VisualizationMode {
        VEHICLES("Vehicle Animation"),
        FLOW_HEATMAP("Flow Heatmap"),
        PT_FLOW_HEATMAP("PT Volume Heatmap"),
        PT_STOP_BUBBLES("PT Stop Bubbles"),
        SPEED_HEATMAP("Speed Heatmap"),
        SPEED_RATIO_HEATMAP("Speed Ratio Heatmap");

        private final String label;

        VisualizationMode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final int PARALLEL_MIN_VISIBLE_LINKS = 200;
    private static final int PARALLEL_MIN_ACTIVE_TRAVERSALS = 8_000;
    private static final int MAX_SORTED_TRAVERSALS_PER_GROUP = 512;
    private static final long PAN_CACHE_REFRESH_NANOS = 120_000_000L;
    private static final double PAN_CACHE_MAX_DRIFT_RATIO = 0.35;
    private static final int VIEWPORT_WORLD_PADDING_PX = 48;

    private static final double DEFAULT_CAR_LIKE_LENGTH_METERS = 7.0;
    private static final double DEFAULT_BIKE_LENGTH_METERS = 3.0;
    private static final double DEFAULT_TRUCK_LENGTH_METERS = 10.0;
    private static final double DEFAULT_BUS_LENGTH_METERS = 12.0;
    private static final double DEFAULT_RAIL_LENGTH_METERS = 30.0;
    private static final double DEFAULT_CAR_WIDTH_RATIO = 0.70;
    private static final double DEFAULT_BIKE_WIDTH_RATIO = 0.30;
    private static final double DEFAULT_TRUCK_WIDTH_RATIO = 0.95;
    private static final double DEFAULT_BUS_WIDTH_RATIO = 0.85;
    private static final double DEFAULT_RAIL_WIDTH_RATIO = 0.90;
    private static final double MIN_VEHICLE_LENGTH_METERS = 0.8;

    private static final Color DEFAULT_BACKGROUND = new Color(0x060606);
    private static final Color DEFAULT_ROAD = new Color(0x333333);
    private static final Color LIGHT_BACKGROUND = new Color(0xECEFF4);
    private static final Color LIGHT_ROAD = new Color(0xB0B8C8);
    private static final Color QUEUE_LABEL = new Color(0xFF3D3D);
    private static final Color BOTTLENECK_NORMAL = new Color(0x2E86FF);
    private static final Color BOTTLENECK_CONGESTED = new Color(0xE03030);
    private static final Color DEFAULT_HEATMAP_LOW = new Color(0xF7F7F7);
    private static final Color DEFAULT_FLOW_HEATMAP_HIGH = new Color(0x7A0014);
    private static final Color DEFAULT_SPEED_HEATMAP_HIGH = new Color(0x0C4A86);
    private static final HeatmapSnapshot EMPTY_HEATMAP_SNAPSHOT = new HeatmapSnapshot(Map.of(), 0.0, 0.0);

    private final SimulationModel model;
    private final PlaybackController playbackController;
    private final VehicleColorProvider colorProvider = new VehicleColorProvider();
    private final SpatialGrid spatialGrid;
    private final Set<String> selectedLinkModes = new HashSet<>();
    private final Set<String> selectedTripModes = new HashSet<>();
    private final Set<String> selectedHeatmapTripModes = new HashSet<>();
    private final Set<String> selectedPtStopModes = new HashSet<>();
    private final Set<String> visibleLinkIds = new HashSet<>();
    private final Map<String, LinkScreenGeometry> linkScreenGeometries = new HashMap<>();

    private ColorMode colorMode = ColorMode.DEFAULT;
    private boolean darkTheme = true;
    private Color mapBackground = DEFAULT_BACKGROUND;
    private Color mapRoad = DEFAULT_ROAD;
    private boolean showQueues = false;
    private boolean suppressOverlays = false;
    private boolean showBottleneck;
    private double bottleneckDivisor = 6.0;
    private double bidirectionalOffset = 0.45;
    private double sampleSize = 1.0;
    private double carLikeVehicleLengthMeters = DEFAULT_CAR_LIKE_LENGTH_METERS;
    private double bikeVehicleLengthMeters = DEFAULT_BIKE_LENGTH_METERS;
    private double truckVehicleLengthMeters = DEFAULT_TRUCK_LENGTH_METERS;
    private double busVehicleLengthMeters = DEFAULT_BUS_LENGTH_METERS;
    private double railVehicleLengthMeters = DEFAULT_RAIL_LENGTH_METERS;
    private double carLikeVehicleWidthRatio = DEFAULT_CAR_WIDTH_RATIO;
    private double bikeVehicleWidthRatio = DEFAULT_BIKE_WIDTH_RATIO;
    private double truckVehicleWidthRatio = DEFAULT_TRUCK_WIDTH_RATIO;
    private double busVehicleWidthRatio = DEFAULT_BUS_WIDTH_RATIO;
    private double railVehicleWidthRatio = DEFAULT_RAIL_WIDTH_RATIO;
    private VehicleShape carShape = VehicleShape.RECTANGLE;
    private VehicleShape bikeShape = VehicleShape.DIAMOND;
    private VehicleShape truckShape = VehicleShape.RECTANGLE;
    private VehicleShape busShape = VehicleShape.OVAL;
    private VehicleShape railShape = VehicleShape.ARROW;
    private boolean keepVehiclesVisibleWhenZoomedOut = true;
    private double minVehicleLengthPixels = 3.5;
    private double minVehicleWidthPixels = 1.7;
    private VisualizationMode visualizationMode = VisualizationMode.VEHICLES;
    private int heatmapTimeBinSeconds = 600;
    private Color flowHeatmapLowColor = DEFAULT_HEATMAP_LOW;
    private Color flowHeatmapHighColor = DEFAULT_FLOW_HEATMAP_HIGH;
    private Color speedHeatmapLowColor = DEFAULT_HEATMAP_LOW;
    private Color speedHeatmapHighColor = DEFAULT_SPEED_HEATMAP_HIGH;
    private Color speedRatioHeatmapLowColor = DEFAULT_HEATMAP_LOW;
    private Color speedRatioHeatmapHighColor = new Color(0x0A5D2A);
    private double ptStopBubbleMinRadiusPixels = 3.5;
    private double ptStopBubbleMaxRadiusPixels = 24.0;
    private boolean useSeparateHeatmapNetworkModes;

    private HeatmapCacheKey cachedHeatmapKey;
    private HeatmapSnapshot cachedHeatmapSnapshot;
    private final Map<HeatmapCacheKey, HeatmapSnapshot> preprocessedHeatmapSnapshots = new HashMap<>();
    private HeatmapPreparedSignature preparedHeatmapSignature;
    private double preparedHeatmapDailyMax;
    private volatile boolean heatmapPreprocessing;
    private volatile double heatmapPreprocessProgress;
    private volatile boolean renderingSuspended;

    private double zoom = 1.0;
    private double panX = 20.0;
    private double panY = 20.0;
    private double baseScale = 1.0;
    private boolean fitInitialized;

    private BufferedImage cachedNetworkLayer;
    private double cachedZoom = -1.0;
    private double cachedPanX = Double.NaN;
    private double cachedPanY = Double.NaN;
    private int cachedWidth = -1;
    private int cachedHeight = -1;
    private long lastNetworkRenderNanos;

    private Point dragStart;

    public NetworkPanel(SimulationModel model, PlaybackController playbackController) {
        this.model = model;
        this.playbackController = playbackController;
        this.spatialGrid = SpatialGrid.build(model.networkData());
        setBackground(mapBackground);
        setPreferredSize(new Dimension(1200, 800));
        selectedLinkModes.addAll(defaultTransportModes(model.availableLinkModes()));
        selectedTripModes.addAll(defaultTransportModes(model.availableTripModes()));
        selectedHeatmapTripModes.addAll(defaultTransportModes(model.availableTripModes()));
        selectedPtStopModes.addAll(defaultTransportModes(model.availablePtStopModes()));

        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragStart = e.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStart != null) {
                    Point current = e.getPoint();
                    int dx = current.x - dragStart.x;
                    int dy = current.y - dragStart.y;
                    panX += dx;
                    panY -= dy;
                    dragStart = current;
                    repaint();
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                handleZoom(e);
            }
        };

        addMouseListener(mouseAdapter);
        addMouseMotionListener(mouseAdapter);
        addMouseWheelListener(mouseAdapter);
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                invalidateNetworkCache();
            }
        });
    }

    public void setColorMode(ColorMode colorMode) {
        this.colorMode = colorMode;
        repaint();
    }

    public void setSelectedLinkModes(Set<String> modes) {
        selectedLinkModes.clear();
        if (modes != null) {
            modes.forEach(mode -> selectedLinkModes.add(normalizeMode(mode)));
        }
        invalidateNetworkCache();
        repaint();
    }

    public void setSelectedTripModes(Set<String> modes) {
        selectedTripModes.clear();
        if (modes != null) {
            modes.forEach(mode -> selectedTripModes.add(normalizeMode(mode)));
        }
        repaint();
    }

    public VisualizationMode getVisualizationMode() {
        return visualizationMode;
    }

    public void setVisualizationMode(VisualizationMode mode) {
        this.visualizationMode = mode == null ? VisualizationMode.VEHICLES : mode;
        invalidateNetworkCache();
        invalidateHeatmapCache();
        repaint();
    }

    public void setSelectedHeatmapTripModes(Set<String> modes) {
        selectedHeatmapTripModes.clear();
        if (modes != null) {
            modes.forEach(mode -> selectedHeatmapTripModes.add(normalizeMode(mode)));
        }
        invalidateNetworkCache();
        invalidateHeatmapCache();
        repaint();
    }

    public void setSelectedPtStopModes(Set<String> modes) {
        selectedPtStopModes.clear();
        if (modes != null) {
            modes.forEach(mode -> selectedPtStopModes.add(normalizeMode(mode)));
        }
        invalidateNetworkCache();
        invalidateHeatmapCache();
        repaint();
    }

    public List<String> availablePtStopModes() {
        return model.availablePtStopModes();
    }

    public int getHeatmapTimeBinSeconds() {
        return heatmapTimeBinSeconds;
    }

    public void setHeatmapTimeBinSeconds(int seconds) {
        this.heatmapTimeBinSeconds = Math.max(30, seconds);
        invalidateHeatmapCache();
        repaint();
    }

    public Color getFlowHeatmapLowColor() {
        return flowHeatmapLowColor;
    }

    public void setFlowHeatmapLowColor(Color color) {
        this.flowHeatmapLowColor = color == null ? DEFAULT_HEATMAP_LOW : color;
        repaint();
    }

    public Color getFlowHeatmapHighColor() {
        return flowHeatmapHighColor;
    }

    public void setFlowHeatmapHighColor(Color color) {
        this.flowHeatmapHighColor = color == null ? DEFAULT_FLOW_HEATMAP_HIGH : color;
        repaint();
    }

    public Color getSpeedHeatmapLowColor() {
        return speedHeatmapLowColor;
    }

    public void setSpeedHeatmapLowColor(Color color) {
        this.speedHeatmapLowColor = color == null ? DEFAULT_HEATMAP_LOW : color;
        repaint();
    }

    public Color getSpeedHeatmapHighColor() {
        return speedHeatmapHighColor;
    }

    public void setSpeedHeatmapHighColor(Color color) {
        this.speedHeatmapHighColor = color == null ? DEFAULT_SPEED_HEATMAP_HIGH : color;
        repaint();
    }

    public Color getSpeedRatioHeatmapLowColor() {
        return speedRatioHeatmapLowColor;
    }

    public void setSpeedRatioHeatmapLowColor(Color color) {
        this.speedRatioHeatmapLowColor = color == null ? DEFAULT_HEATMAP_LOW : color;
        repaint();
    }

    public Color getSpeedRatioHeatmapHighColor() {
        return speedRatioHeatmapHighColor;
    }

    public void setSpeedRatioHeatmapHighColor(Color color) {
        this.speedRatioHeatmapHighColor = color == null ? new Color(0x0A5D2A) : color;
        repaint();
    }

    public double getPtStopBubbleMinRadiusPixels() {
        return ptStopBubbleMinRadiusPixels;
    }

    public void setPtStopBubbleMinRadiusPixels(double value) {
        double clamped = clampPtStopBubbleRadius(value);
        this.ptStopBubbleMinRadiusPixels = clamped;
        if (ptStopBubbleMaxRadiusPixels < clamped) {
            ptStopBubbleMaxRadiusPixels = clamped;
        }
        repaint();
    }

    public double getPtStopBubbleMaxRadiusPixels() {
        return ptStopBubbleMaxRadiusPixels;
    }

    public void setPtStopBubbleMaxRadiusPixels(double value) {
        double clamped = clampPtStopBubbleRadius(value);
        this.ptStopBubbleMaxRadiusPixels = Math.max(clamped, ptStopBubbleMinRadiusPixels);
        repaint();
    }

    public boolean isUseSeparateHeatmapNetworkModes() {
        return useSeparateHeatmapNetworkModes;
    }

    public void setUseSeparateHeatmapNetworkModes(boolean enabled) {
        this.useSeparateHeatmapNetworkModes = enabled;
        invalidateNetworkCache();
        repaint();
    }

    public boolean isHeatmapPreprocessing() {
        return heatmapPreprocessing;
    }

    public double heatmapPreprocessProgress() {
        return heatmapPreprocessProgress;
    }

    public void markHeatmapPreprocessingStarted() {
        heatmapPreprocessing = true;
        heatmapPreprocessProgress = 0.0;
        repaint();
    }

    public void markHeatmapPreprocessingFinished() {
        heatmapPreprocessProgress = 1.0;
        heatmapPreprocessing = false;
        repaint();
    }

    public boolean isHeatmapPreparedForCurrentSettings() {
        HeatmapPreparedSignature signature = currentHeatmapSignature();
        if (signature == null) {
            return true;
        }
        synchronized (this) {
            return signature.equals(preparedHeatmapSignature);
        }
    }

    public void preprocessHeatmapsForCurrentSettings() {
        HeatmapPreparedSignature signature = currentHeatmapSignature();
        if (signature == null) {
            return;
        }

        int binSize = signature.binSizeSeconds();
        int firstBinStart = (int) Math.floor(playbackController.getStartTime() / binSize) * binSize;
        int lastBinStart = (int) Math.floor(playbackController.getEndTime() / binSize) * binSize;
        int totalBins = Math.max(1, ((lastBinStart - firstBinStart) / binSize) + 1);

        Map<Integer, Map<String, Double>> flowCountByBin = new HashMap<>();
        Map<Integer, Map<String, Double>> speedSumByBin = new HashMap<>();
        Map<Integer, Map<String, Integer>> speedCountByBin = new HashMap<>();

        if (signature.mode() == VisualizationMode.PT_STOP_BUBBLES) {
            int interactionCount = model.ptStopInteractionCount();
            for (int i = 0; i < interactionCount; i++) {
                if ((i & 8191) == 0) {
                    heatmapPreprocessProgress = Math.min(0.80, 0.80 * (i / (double) Math.max(1, interactionCount)));
                }

                String stopMode = model.ptStopInteractionMode(i);
                if (!shouldAggregateTripModeForSignature(stopMode, signature)) {
                    continue;
                }

                double time = model.ptStopInteractionTime(i);
                if (time < firstBinStart || time >= (lastBinStart + binSize)) {
                    continue;
                }

                int binStart = (int) Math.floor(time / binSize) * binSize;
                String stopId = model.ptStopInteractionStopId(i);
                flowCountByBin
                        .computeIfAbsent(binStart, ignored -> new HashMap<>())
                        .merge(stopId, 1.0, Double::sum);
            }
        } else {
            int traversalCount = model.traversalCount();
            for (int i = 0; i < traversalCount; i++) {
                if ((i & 8191) == 0) {
                    heatmapPreprocessProgress = Math.min(0.80, 0.80 * (i / (double) Math.max(1, traversalCount)));
                }

                String tripMode = model.traversalTripMode(i);
                if (!shouldAggregateTripModeForSignature(tripMode, signature)) {
                    continue;
                }
                if (signature.mode() == VisualizationMode.PT_FLOW_HEATMAP && !isPtMode(tripMode)) {
                    continue;
                }

                double enter = model.traversalEnterTime(i);
                if (enter < firstBinStart || enter >= (lastBinStart + binSize)) {
                    continue;
                }

                int binStart = (int) Math.floor(enter / binSize) * binSize;
                String linkId = model.traversalLinkId(i);

                if (signature.mode() == VisualizationMode.FLOW_HEATMAP
                        || signature.mode() == VisualizationMode.PT_FLOW_HEATMAP) {
                    flowCountByBin
                            .computeIfAbsent(binStart, ignored -> new HashMap<>())
                            .merge(linkId, 1.0, Double::sum);
                    continue;
                }

                LinkSegment link = model.networkData().getLinks().get(linkId);
                if (link == null || link.length() <= 0.0) {
                    continue;
                }

                double duration = Math.max(0.05, model.traversalLeaveTime(i) - enter);
                double speedMetersPerSecond = link.length() / duration;
                double metricValue = signature.mode() == VisualizationMode.SPEED_HEATMAP
                        ? speedMetersPerSecond * 3.6
                        : speedMetersPerSecond / Math.max(0.1, link.freeSpeed());

                speedSumByBin
                        .computeIfAbsent(binStart, ignored -> new HashMap<>())
                        .merge(linkId, metricValue, Double::sum);
                speedCountByBin
                        .computeIfAbsent(binStart, ignored -> new HashMap<>())
                        .merge(linkId, 1, Integer::sum);
            }
        }

        Map<HeatmapCacheKey, HeatmapSnapshot> computed = new HashMap<>();
        double dailyMax = 0.0;
        for (int binIndex = 0, binStart = firstBinStart; binStart <= lastBinStart; binStart += binSize, binIndex++) {
            Map<String, Double> values = new HashMap<>();

            if (signature.mode() == VisualizationMode.FLOW_HEATMAP
                    || signature.mode() == VisualizationMode.PT_FLOW_HEATMAP
                    || signature.mode() == VisualizationMode.PT_STOP_BUBBLES) {
                Map<String, Double> counts = flowCountByBin.get(binStart);
                if (counts != null) {
                    double factor = 3600.0 / binSize;
                    for (Map.Entry<String, Double> entry : counts.entrySet()) {
                        values.put(entry.getKey(), entry.getValue() * factor);
                    }
                }
            } else {
                Map<String, Double> sums = speedSumByBin.get(binStart);
                Map<String, Integer> counts = speedCountByBin.get(binStart);
                if (sums != null && counts != null) {
                    for (Map.Entry<String, Double> entry : sums.entrySet()) {
                        int count = counts.getOrDefault(entry.getKey(), 0);
                        if (count > 0) {
                            values.put(entry.getKey(), entry.getValue() / count);
                        }
                    }
                }
            }

            double minValue = Double.POSITIVE_INFINITY;
            double maxValue = 0.0;
            for (double value : values.values()) {
                minValue = Math.min(minValue, value);
                maxValue = Math.max(maxValue, value);
            }
            dailyMax = Math.max(dailyMax, maxValue);
            if (!Double.isFinite(minValue)) {
                minValue = 0.0;
            }

            HeatmapCacheKey key = new HeatmapCacheKey(
                    signature.mode(),
                    binStart,
                    signature.binSizeSeconds(),
                    signature.selectedModesKey()
            );
            computed.put(key, new HeatmapSnapshot(values, minValue, maxValue));

            heatmapPreprocessProgress = 0.80 + 0.20 * ((binIndex + 1) / (double) totalBins);
        }

        synchronized (this) {
            preprocessedHeatmapSnapshots.clear();
            preprocessedHeatmapSnapshots.putAll(computed);
            preparedHeatmapSignature = signature;
            preparedHeatmapDailyMax = dailyMax;
            cachedHeatmapKey = null;
            cachedHeatmapSnapshot = null;
        }
        repaint();
    }

    public void setModeColor(String mode, Color color) {
        colorProvider.setModeColor(mode, color);
        repaint();
    }

    public Color getModeColor(String mode) {
        return colorProvider.modeColor(mode);
    }

    public void setTripPurposeColor(String purpose, Color color) {
        colorProvider.setTripPurposeColor(purpose, color);
        repaint();
    }

    public Color getTripPurposeColor(String purpose) {
        return colorProvider.tripPurposeColor(purpose);
    }

    public void setSexColor(String sexCategory, Color color) {
        colorProvider.setSexColor(sexCategory, color);
        repaint();
    }

    public Color getSexColor(String sexCategory) {
        return colorProvider.sexColor(sexCategory);
    }

    public List<String> getSexCategories() {
        List<String> categories = new ArrayList<>();
        for (String category : model.availableSexCategories()) {
            if (category != null && !category.isBlank()) {
                categories.add(category);
            }
        }
        if (!categories.contains("unknown")) {
            categories.add("unknown");
        }
        if (!categories.contains("other")) {
            categories.add("other");
        }
        return categories;
    }

    public int[] getAgeBinUpperBounds() {
        return colorProvider.ageBinUpperBounds();
    }

    public void setAgeBinUpperBounds(int[] bounds) {
        colorProvider.setAgeBinUpperBounds(bounds);
        repaint();
    }

    public int getAgeGroupCount() {
        return colorProvider.ageGroupCount();
    }

    public String getAgeGroupLabel(int index) {
        return colorProvider.ageGroupLabel(index);
    }

    public Color getAgeGroupColor(int index) {
        return colorProvider.ageGroupColor(index);
    }

    public void setAgeGroupColor(int index, Color color) {
        colorProvider.setAgeGroupColor(index, color);
        repaint();
    }

    public void setShowQueues(boolean showQueues) {
        this.showQueues = showQueues;
        repaint();
    }

    public void setSuppressOverlays(boolean suppress) {
        this.suppressOverlays = suppress;
    }

    public boolean isDarkTheme() {
        return darkTheme;
    }

    public void setDarkTheme(boolean dark) {
        this.darkTheme = dark;
        this.mapBackground = dark ? DEFAULT_BACKGROUND : LIGHT_BACKGROUND;
        this.mapRoad = dark ? DEFAULT_ROAD : LIGHT_ROAD;
        setBackground(mapBackground);
        invalidateNetworkCache();
        repaint();
    }

    public Color getMapBackground() {
        return mapBackground;
    }

    public void setMapBackground(Color color) {
        this.mapBackground = color;
        setBackground(mapBackground);
        invalidateNetworkCache();
        repaint();
    }

    public Color getMapRoad() {
        return mapRoad;
    }

    public void setMapRoad(Color color) {
        this.mapRoad = color;
        invalidateNetworkCache();
        repaint();
    }

    public VehicleShape getCarShape() { return carShape; }
    public void setCarShape(VehicleShape s) { this.carShape = s; repaint(); }
    public VehicleShape getBikeShape() { return bikeShape; }
    public void setBikeShape(VehicleShape s) { this.bikeShape = s; repaint(); }
    public VehicleShape getTruckShape() { return truckShape; }
    public void setTruckShape(VehicleShape s) { this.truckShape = s; repaint(); }
    public VehicleShape getBusShape() { return busShape; }
    public void setBusShape(VehicleShape s) { this.busShape = s; repaint(); }
    public VehicleShape getRailShape() { return railShape; }
    public void setRailShape(VehicleShape s) { this.railShape = s; repaint(); }

    public void setBidirectionalOffset(double offset) {
        this.bidirectionalOffset = Math.max(0.0, Math.min(1.0, offset));
        invalidateNetworkCache();
        repaint();
    }

    public double getBidirectionalOffset() {
        return bidirectionalOffset;
    }

    public void setShowBottleneck(boolean show) {
        this.showBottleneck = show;
        repaint();
    }

    public boolean isShowBottleneck() {
        return showBottleneck;
    }

    public void setBottleneckDivisor(double divisor) {
        this.bottleneckDivisor = Math.max(1.0, divisor);
        repaint();
    }

    public double getBottleneckDivisor() {
        return bottleneckDivisor;
    }

    public void setSampleSize(double sampleSize) {
        this.sampleSize = Math.max(0.0001, sampleSize);
    }

    public double getSampleSize() {
        return sampleSize;
    }

    public void setCarLikeVehicleLengthMeters(double value) {
        this.carLikeVehicleLengthMeters = Math.max(0.5, value);
        repaint();
    }

    public double getCarLikeVehicleLengthMeters() {
        return carLikeVehicleLengthMeters;
    }

    public void setBikeVehicleLengthMeters(double value) {
        this.bikeVehicleLengthMeters = Math.max(0.5, value);
        repaint();
    }

    public double getBikeVehicleLengthMeters() {
        return bikeVehicleLengthMeters;
    }

    public void setTruckVehicleLengthMeters(double value) {
        this.truckVehicleLengthMeters = Math.max(1.0, value);
        repaint();
    }

    public double getTruckVehicleLengthMeters() {
        return truckVehicleLengthMeters;
    }

    public void setCarLikeVehicleWidthRatio(double ratio) {
        this.carLikeVehicleWidthRatio = clampWidthRatio(ratio);
        repaint();
    }

    public double getCarLikeVehicleWidthRatio() {
        return carLikeVehicleWidthRatio;
    }

    public void setBikeVehicleWidthRatio(double ratio) {
        this.bikeVehicleWidthRatio = clampWidthRatio(ratio);
        repaint();
    }

    public double getBikeVehicleWidthRatio() {
        return bikeVehicleWidthRatio;
    }

    public void setTruckVehicleWidthRatio(double ratio) {
        this.truckVehicleWidthRatio = clampWidthRatio(ratio);
        repaint();
    }

    public double getTruckVehicleWidthRatio() {
        return truckVehicleWidthRatio;
    }

    public void setBusVehicleLengthMeters(double value) {
        this.busVehicleLengthMeters = Math.max(2.0, value);
        repaint();
    }

    public double getBusVehicleLengthMeters() {
        return busVehicleLengthMeters;
    }

    public void setBusVehicleWidthRatio(double ratio) {
        this.busVehicleWidthRatio = clampWidthRatio(ratio);
        repaint();
    }

    public double getBusVehicleWidthRatio() {
        return busVehicleWidthRatio;
    }

    public void setRailVehicleLengthMeters(double value) {
        this.railVehicleLengthMeters = Math.max(5.0, value);
        repaint();
    }

    public double getRailVehicleLengthMeters() {
        return railVehicleLengthMeters;
    }

    public void setRailVehicleWidthRatio(double ratio) {
        this.railVehicleWidthRatio = clampWidthRatio(ratio);
        repaint();
    }

    public double getRailVehicleWidthRatio() {
        return railVehicleWidthRatio;
    }

    public void setKeepVehiclesVisibleWhenZoomedOut(boolean enabled) {
        this.keepVehiclesVisibleWhenZoomedOut = enabled;
        repaint();
    }

    public boolean isKeepVehiclesVisibleWhenZoomedOut() {
        return keepVehiclesVisibleWhenZoomedOut;
    }

    public void setMinVehicleLengthPixels(double value) {
        this.minVehicleLengthPixels = clampVehiclePixelSize(value);
        repaint();
    }

    public double getMinVehicleLengthPixels() {
        return minVehicleLengthPixels;
    }

    public void setMinVehicleWidthPixels(double value) {
        this.minVehicleWidthPixels = clampVehiclePixelSize(value);
        repaint();
    }

    public void setRenderingSuspended(boolean renderingSuspended) {
        this.renderingSuspended = renderingSuspended;
        if (!renderingSuspended) {
            invalidateNetworkCache();
        }
        repaint();
    }

    public boolean isRenderingSuspended() {
        return renderingSuspended;
    }

    public double getMinVehicleWidthPixels() {
        return minVehicleWidthPixels;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        ensureFitted();

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

        renderScene(g2, !suppressOverlays, showQueues && !suppressOverlays);
        g2.dispose();
    }

    public void paintRecordingFrame(Graphics2D g2) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        ensureFitted();

        Graphics2D captureGraphics = (Graphics2D) g2.create();
        captureGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        captureGraphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        renderScene(captureGraphics, false, false);
        captureGraphics.dispose();
    }

    private void renderScene(Graphics2D g2, boolean drawOverlays, boolean drawQueueText) {
        if (renderingSuspended) {
            g2.setColor(mapBackground);
            g2.fillRect(0, 0, getWidth(), getHeight());
            return;
        }

        renderNetworkLayerIfNeeded();
        boolean vehicleMode = visualizationMode == VisualizationMode.VEHICLES;
        if (cachedNetworkLayer != null) {
            int panShiftX = (int) Math.round(panX - cachedPanX);
            int panShiftY = (int) Math.round(cachedPanY - panY);

            Graphics2D worldGraphics = (Graphics2D) g2.create();
            worldGraphics.translate(panShiftX, panShiftY);
            worldGraphics.drawImage(cachedNetworkLayer, 0, 0, null);
            if (vehicleMode) {
                drawVehicles(worldGraphics);
            } else {
                drawHeatmap(worldGraphics);
            }
            if (vehicleMode && drawQueueText) {
                drawQueueLabels(worldGraphics);
            }
            worldGraphics.dispose();
        } else {
            if (vehicleMode) {
                drawVehicles(g2);
            } else {
                drawHeatmap(g2);
            }
            if (vehicleMode && drawQueueText) {
                drawQueueLabels(g2);
            }
        }

        if (drawOverlays) {
            drawClockOverlay(g2);
            drawLegendOverlay(g2);
        }

        if (heatmapPreprocessing) {
            drawPreprocessingOverlay(g2);
        }
    }

    private void drawPreprocessingOverlay(Graphics2D g2) {
        g2.setColor(new Color(0, 0, 0, 110));
        g2.fillRect(0, 0, getWidth(), getHeight());

        int boxWidth = 280;
        int boxHeight = 108;
        int x = (getWidth() - boxWidth) / 2;
        int y = (getHeight() - boxHeight) / 2;

        g2.setColor(darkTheme ? new Color(0x121212) : new Color(0xF2F2F2));
        g2.fillRoundRect(x, y, boxWidth, boxHeight, 14, 14);
        g2.setColor(darkTheme ? new Color(0x6A6A6A) : new Color(0xA0A0A0));
        g2.drawRoundRect(x, y, boxWidth, boxHeight, 14, 14);

        int spinnerSize = 26;
        int spinnerX = x + 18;
        int spinnerY = y + 22;
        int angle = (int) ((System.nanoTime() / 7_000_000L) % 360);

        g2.setStroke(new BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(darkTheme ? new Color(0xB5B5B5) : new Color(0x777777));
        g2.drawOval(spinnerX, spinnerY, spinnerSize, spinnerSize);
        g2.setColor(darkTheme ? new Color(0xFFFFFF) : new Color(0x2A2A2A));
        g2.drawArc(spinnerX, spinnerY, spinnerSize, spinnerSize, angle, 110);

        g2.setColor(darkTheme ? new Color(0xECECEC) : new Color(0x1A1A1A));
        g2.drawString("Preprocessing heatmaps...", x + 56, y + 40);

        int progressBarX = x + 18;
        int progressBarY = y + 62;
        int progressBarW = boxWidth - 36;
        int progressBarH = 14;
        g2.setColor(darkTheme ? new Color(0x2F2F2F) : new Color(0xD6D6D6));
        g2.fillRoundRect(progressBarX, progressBarY, progressBarW, progressBarH, 8, 8);

        int filled = (int) Math.round(progressBarW * Math.max(0.0, Math.min(1.0, heatmapPreprocessProgress)));
        g2.setColor(darkTheme ? new Color(0x5DA9FF) : new Color(0x306FBA));
        g2.fillRoundRect(progressBarX, progressBarY, filled, progressBarH, 8, 8);

        g2.setColor(darkTheme ? new Color(0xDFDFDF) : new Color(0x262626));
        g2.drawString((int) Math.round(heatmapPreprocessProgress * 100) + "%", x + boxWidth - 44, y + 92);
    }

    private void drawPreprocessRequiredOverlay(Graphics2D g2) {
        g2.setColor(new Color(0, 0, 0, 90));
        g2.fillRect(0, 0, getWidth(), getHeight());

        int boxWidth = 310;
        int boxHeight = 70;
        int x = (getWidth() - boxWidth) / 2;
        int y = (getHeight() - boxHeight) / 2;

        g2.setColor(darkTheme ? new Color(0x151515) : new Color(0xF3F3F3));
        g2.fillRoundRect(x, y, boxWidth, boxHeight, 14, 14);
        g2.setColor(darkTheme ? new Color(0x6A6A6A) : new Color(0xA0A0A0));
        g2.drawRoundRect(x, y, boxWidth, boxHeight, 14, 14);

        g2.setColor(darkTheme ? new Color(0xEFEFEF) : new Color(0x1A1A1A));
        g2.drawString("Heatmap settings changed.", x + 18, y + 30);
        g2.drawString("Click Apply Bin + Preprocess to update.", x + 18, y + 50);
    }

    private void ensureFitted() {
        if (fitInitialized) {
            return;
        }

        NetworkData network = model.networkData();
        double width = Math.max(1.0, network.getMaxX() - network.getMinX());
        double height = Math.max(1.0, network.getMaxY() - network.getMinY());

        double sx = (getWidth() - 40.0) / width;
        double sy = (getHeight() - 40.0) / height;
        baseScale = Math.max(0.000001, Math.min(sx, sy));

        zoom = 1.0;
        panX = 20.0;
        panY = 20.0;
        fitInitialized = true;
        invalidateNetworkCache();
    }

    private void renderNetworkLayerIfNeeded() {
        long now = System.nanoTime();
        if (cachedNetworkLayer != null
                && cachedZoom == zoom
                && cachedWidth == getWidth()
                && cachedHeight == getHeight()) {
            double panDriftX = Math.abs(panX - cachedPanX);
            double panDriftY = Math.abs(panY - cachedPanY);
            double maxPanDrift = Math.max(cachedWidth, cachedHeight) * PAN_CACHE_MAX_DRIFT_RATIO;
            boolean driftTooLarge = panDriftX > maxPanDrift || panDriftY > maxPanDrift;
            boolean refreshForPanning = (panDriftX > 0.5 || panDriftY > 0.5)
                    && (now - lastNetworkRenderNanos) >= PAN_CACHE_REFRESH_NANOS;
            if (!driftTooLarge && !refreshForPanning) {
                return;
            }
        }

        cachedWidth = getWidth();
        cachedHeight = getHeight();
        cachedZoom = zoom;
        cachedPanX = panX;
        cachedPanY = panY;
        lastNetworkRenderNanos = now;

        cachedNetworkLayer = createCompatibleImage(cachedWidth, cachedHeight);
        Graphics2D g2 = cachedNetworkLayer.createGraphics();
        g2.setColor(mapBackground);
        g2.fillRect(0, 0, cachedWidth, cachedHeight);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);

        linkScreenGeometries.clear();
        visibleLinkIds.clear();
        queryVisibleLinks(visibleLinkIds);

        double laneWidth = laneWidthPixels();
        Map<String, double[]> nodeMaxRadius = new HashMap<>();
        double minScreenLength = zoom < 0.3 ? 1.5 : 0.5;

        Set<String> renderedNodePairs = new HashSet<>();
        for (String linkId : visibleLinkIds) {
            LinkSegment link = model.networkData().getLinks().get(linkId);
            if (link == null) {
                continue;
            }
            if (shouldRenderLink(link)) {
                renderedNodePairs.add(link.fromNodeId() + ">" + link.toNodeId());
            }
        }

        g2.setColor(mapRoad);
        for (String linkId : visibleLinkIds) {
            LinkSegment link = model.networkData().getLinks().get(linkId);
            if (link == null) {
                continue;
            }
            if (!shouldRenderLink(link)) {
                continue;
            }

            Point2D.Double a = worldToScreen(link.fromX(), link.fromY());
            Point2D.Double b = worldToScreen(link.toX(), link.toY());
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double length = Math.hypot(dx, dy);
            if (length < minScreenLength) {
                continue;
            }

            int laneCount = laneCount(link);
            double nx = -dy / length;
            double ny = dx / length;
            double angle = Math.atan2(dy, dx);
            float roadWidth = (float) Math.max(0.35, Math.min(32.0, laneWidth * laneCount));

            boolean hasReverse = renderedNodePairs.contains(link.toNodeId() + ">" + link.fromNodeId());
            double offsetX = 0;
            double offsetY = 0;
            if (hasReverse) {
                double shift = laneWidth * laneCount * bidirectionalOffset;
                offsetX = nx * shift;
                offsetY = ny * shift;
                a.x += offsetX;
                a.y += offsetY;
                b.x += offsetX;
                b.y += offsetY;
            }

            g2.setStroke(new BasicStroke(roadWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine((int) Math.round(a.x), (int) Math.round(a.y), (int) Math.round(b.x), (int) Math.round(b.y));

            double halfWidth = roadWidth / 2.0;
            nodeMaxRadius.merge(link.fromNodeId(), new double[]{a.x, a.y, halfWidth},
                    (old, nw) -> { old[2] = Math.max(old[2], nw[2]); return old; });
            nodeMaxRadius.merge(link.toNodeId(), new double[]{b.x, b.y, halfWidth},
                    (old, nw) -> { old[2] = Math.max(old[2], nw[2]); return old; });

            linkScreenGeometries.put(link.id(), new LinkScreenGeometry(a.x, a.y, dx, dy, length, nx, ny, laneWidth, laneCount, angle));
        }

        for (double[] nodeInfo : nodeMaxRadius.values()) {
            double r = nodeInfo[2];
            if (r > 0.3) {
                int cx = (int) Math.round(nodeInfo[0] - r);
                int cy = (int) Math.round(nodeInfo[1] - r);
                int d = (int) Math.round(r * 2);
                g2.fillOval(cx, cy, d, d);
            }
        }

        g2.dispose();
    }

    private void drawVehicles(Graphics2D g2) {
        double currentTime = playbackController.getCurrentTime();
        Map<String, PlaybackController.LinkFrameSnapshot> linkState =
                playbackController.snapshotLinkState(linkScreenGeometries.keySet());
        if (linkState.isEmpty()) {
            return;
        }

        int totalTraversals = 0;
        for (PlaybackController.LinkFrameSnapshot state : linkState.values()) {
            totalTraversals += state.traversalIndexes().length;
        }

        boolean canParallelize = Runtime.getRuntime().availableProcessors() > 1
                && linkState.size() >= PARALLEL_MIN_VISIBLE_LINKS
                && totalTraversals >= PARALLEL_MIN_ACTIVE_TRAVERSALS;

        Stream<Map.Entry<String, PlaybackController.LinkFrameSnapshot>> stateStream = linkState.entrySet().stream();
        if (canParallelize) {
            stateStream = stateStream.parallel();
        }

        List<PreparedLinkVehicles> preparedLinks = stateStream
                .map(entry -> prepareLinkVehicles(entry.getKey(), entry.getValue(), currentTime))
                .filter(Objects::nonNull)
                .toList();

        for (PreparedLinkVehicles prepared : preparedLinks) {
            drawModeGroup(
                    g2,
                    prepared.geometry(),
                    prepared.link(),
                    prepared.carTraversals(),
                    currentTime,
                    carLikeVehicleLengthMeters,
                    carLikeVehicleWidthRatio,
                    carShape,
                    0.12,
                    true,
                    prepared.linkIsBottleneck()
            );

            drawModeGroup(
                    g2,
                    prepared.geometry(),
                    prepared.link(),
                    prepared.truckTraversals(),
                    currentTime,
                    truckVehicleLengthMeters,
                    truckVehicleWidthRatio,
                    truckShape,
                    0.30,
                    true,
                    prepared.linkIsBottleneck()
            );

            drawModeGroup(
                    g2,
                    prepared.geometry(),
                    prepared.link(),
                    prepared.busTraversals(),
                    currentTime,
                    busVehicleLengthMeters,
                    busVehicleWidthRatio,
                    busShape,
                    0.15,
                    true,
                    prepared.linkIsBottleneck()
            );

            drawModeGroup(
                    g2,
                    prepared.geometry(),
                    prepared.link(),
                    prepared.railTraversals(),
                    currentTime,
                    railVehicleLengthMeters,
                    railVehicleWidthRatio,
                    railShape,
                    0.0,
                    true,
                    prepared.linkIsBottleneck()
            );

            drawModeGroup(
                    g2,
                    prepared.geometry(),
                    prepared.link(),
                    prepared.bikeTraversals(),
                    currentTime,
                    bikeVehicleLengthMeters,
                    bikeVehicleWidthRatio,
                    bikeShape,
                    -0.20,
                    false,
                    prepared.linkIsBottleneck()
            );
        }
    }

    private void drawHeatmap(Graphics2D g2) {
        if (visualizationMode == VisualizationMode.VEHICLES) {
            return;
        }

        if (visualizationMode == VisualizationMode.PT_STOP_BUBBLES) {
            drawPtStopBubbles(g2);
            return;
        }

        if (linkScreenGeometries.isEmpty()) {
            return;
        }

        HeatmapInterpolation interpolation = resolveHeatmapInterpolation();
        if (!interpolation.prepared()) {
            drawPreprocessRequiredOverlay(g2);
            return;
        }

        Color lowColor = heatmapLowColor();
        Color highColor = heatmapHighColor();
        double maxValue = interpolation.maxValueForScale();

        for (Map.Entry<String, LinkScreenGeometry> entry : linkScreenGeometries.entrySet()) {
            String linkId = entry.getKey();
            LinkScreenGeometry geometry = entry.getValue();
            LinkSegment link = model.networkData().getLinks().get(linkId);
            if (link == null || !shouldRenderLink(link)) {
                continue;
            }

            double valueA = interpolation.currentBinSnapshot().values().getOrDefault(linkId, 0.0);
            double valueB = interpolation.nextBinSnapshot().values().getOrDefault(linkId, valueA);
            double value = valueA + (valueB - valueA) * interpolation.alpha();
            double normalized = heatmapLogNormalized(value, maxValue);

            float roadWidth = (float) Math.max(0.35, Math.min(32.0, geometry.laneWidth() * geometry.laneCount()));
            g2.setStroke(new BasicStroke(roadWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(interpolateColor(lowColor, highColor, normalized));
            g2.drawLine(
                    (int) Math.round(geometry.fromX()),
                    (int) Math.round(geometry.fromY()),
                    (int) Math.round(geometry.fromX() + geometry.dx()),
                    (int) Math.round(geometry.fromY() + geometry.dy())
            );
        }
    }

    private void drawPtStopBubbles(Graphics2D g2) {
        HeatmapInterpolation interpolation = resolveHeatmapInterpolation();
        if (!interpolation.prepared()) {
            drawPreprocessRequiredOverlay(g2);
            return;
        }

        double maxValue = interpolation.maxValueForScale();
        double safeMaxValue = maxValue <= 0.0 ? 1.0 : maxValue;

        Color lowColor = heatmapLowColor();
        Color highColor = heatmapHighColor();
        double minRadius = Math.min(ptStopBubbleMinRadiusPixels, ptStopBubbleMaxRadiusPixels);
        double maxRadius = Math.max(ptStopBubbleMinRadiusPixels, ptStopBubbleMaxRadiusPixels);

        for (PtStopPoint stop : model.ptStopsById().values()) {
            double valueA = interpolation.currentBinSnapshot().values().getOrDefault(stop.id(), 0.0);
            double valueB = interpolation.nextBinSnapshot().values().getOrDefault(stop.id(), valueA);
            double value = valueA + (valueB - valueA) * interpolation.alpha();

            Point2D.Double screen = worldToScreen(stop.x(), stop.y());
            if (screen.x < -48 || screen.y < -48 || screen.x > getWidth() + 48 || screen.y > getHeight() + 48) {
                continue;
            }

            double normalized = heatmapLogNormalized(Math.max(0.0, value), safeMaxValue);
            double eased = smoothstep(normalized);
            double radius = minRadius + (maxRadius - minRadius) * eased;
            Color color = interpolateColor(lowColor, highColor, eased);
            int fillAlpha = (int) Math.round(50 + 135 * eased);
            int strokeAlpha = (int) Math.round(95 + 125 * eased);
            Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), fillAlpha);
            Color stroke = new Color(
                    Math.max(0, color.getRed() - 20),
                    Math.max(0, color.getGreen() - 20),
                    Math.max(0, color.getBlue() - 20),
                    strokeAlpha
            );

            g2.setColor(fill);
            g2.fillOval(
                    (int) Math.round(screen.x - radius),
                    (int) Math.round(screen.y - radius),
                    (int) Math.round(radius * 2.0),
                    (int) Math.round(radius * 2.0)
            );
            g2.setColor(stroke);
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawOval(
                    (int) Math.round(screen.x - radius),
                    (int) Math.round(screen.y - radius),
                    (int) Math.round(radius * 2.0),
                    (int) Math.round(radius * 2.0)
            );
        }
    }

    private HeatmapInterpolation resolveHeatmapInterpolation() {
        double currentTime = playbackController.getCurrentTime();
        int binSize = Math.max(30, heatmapTimeBinSeconds);
        int binStart = (int) Math.floor(currentTime / binSize) * binSize;

        HeatmapPreparedSignature signature = currentHeatmapSignature();
        if (signature == null) {
            return new HeatmapInterpolation(EMPTY_HEATMAP_SNAPSHOT, EMPTY_HEATMAP_SNAPSHOT, 0.0, 0.0, true);
        }

        HeatmapCacheKey currentKey = new HeatmapCacheKey(
                signature.mode(),
                binStart,
                signature.binSizeSeconds(),
                signature.selectedModesKey()
        );
        HeatmapCacheKey nextKey = new HeatmapCacheKey(
                signature.mode(),
                binStart + binSize,
                signature.binSizeSeconds(),
                signature.selectedModesKey()
        );

        HeatmapSnapshot currentSnapshot;
        HeatmapSnapshot nextSnapshot;
        double fixedDailyMax;
        synchronized (this) {
            if (!signature.equals(preparedHeatmapSignature)) {
                return new HeatmapInterpolation(EMPTY_HEATMAP_SNAPSHOT, EMPTY_HEATMAP_SNAPSHOT, 0.0, 0.0, false);
            }

            currentSnapshot = preprocessedHeatmapSnapshots.get(currentKey);
            nextSnapshot = preprocessedHeatmapSnapshots.get(nextKey);
            fixedDailyMax = preparedHeatmapDailyMax;
        }

        if (currentSnapshot == null) {
            return new HeatmapInterpolation(EMPTY_HEATMAP_SNAPSHOT, EMPTY_HEATMAP_SNAPSHOT, 0.0, 0.0, false);
        }
        if (nextSnapshot == null) {
            nextSnapshot = currentSnapshot;
        }

        double alpha = (currentTime - binStart) / Math.max(1.0, binSize);
        alpha = Math.max(0.0, Math.min(1.0, alpha));
        double maxValueForScale = fixedDailyMax;
        if (maxValueForScale < 0.0) {
            maxValueForScale = 0.0;
        }

        return new HeatmapInterpolation(currentSnapshot, nextSnapshot, alpha, maxValueForScale, true);
    }

    private HeatmapPreparedSignature currentHeatmapSignature() {
        if (visualizationMode == VisualizationMode.VEHICLES) {
            return null;
        }
        Set<String> selectedModes = visualizationMode == VisualizationMode.PT_STOP_BUBBLES
            ? Set.copyOf(selectedPtStopModes)
            : Set.copyOf(selectedHeatmapTripModes);
        String selectedModesKey = String.join("|", selectedModes.stream().sorted().toList());
        return new HeatmapPreparedSignature(
                visualizationMode,
                Math.max(30, heatmapTimeBinSeconds),
                selectedModesKey,
            selectedModes
        );
    }

    private static boolean shouldAggregateTripModeForSignature(
            String tripMode,
            HeatmapPreparedSignature signature
    ) {
        if (signature.selectedModes().isEmpty()) {
            return false;
        }
        if (tripMode == null || tripMode.isBlank()) {
            return false;
        }
        return signature.selectedModes().contains(normalizeMode(tripMode));
    }

    private HeatmapSnapshot computeHeatmapSnapshot(int binStartSeconds, int binEndSeconds) {
        Map<String, Double> values = new HashMap<>();
        Map<String, Double> speedSums = new HashMap<>();
        Map<String, Integer> speedCounts = new HashMap<>();

        int binSizeSeconds = Math.max(1, binEndSeconds - binStartSeconds);
        boolean flowMode = visualizationMode == VisualizationMode.FLOW_HEATMAP
                || visualizationMode == VisualizationMode.PT_FLOW_HEATMAP
                || visualizationMode == VisualizationMode.PT_STOP_BUBBLES;
        boolean speedMode = visualizationMode == VisualizationMode.SPEED_HEATMAP;
        boolean speedRatioMode = visualizationMode == VisualizationMode.SPEED_RATIO_HEATMAP;

        if (visualizationMode == VisualizationMode.PT_STOP_BUBBLES) {
            int interactionCount = model.ptStopInteractionCount();
            for (int i = 0; i < interactionCount; i++) {
                String mode = model.ptStopInteractionMode(i);
                if (!shouldAggregateTripMode(mode)) {
                    continue;
                }
                double time = model.ptStopInteractionTime(i);
                if (time < binStartSeconds || time >= binEndSeconds) {
                    continue;
                }
                values.merge(model.ptStopInteractionStopId(i), 1.0, Double::sum);
            }
        } else {
            int traversalCount = model.traversalCount();
            for (int i = 0; i < traversalCount; i++) {
                String tripMode = model.traversalTripMode(i);
                if (!shouldAggregateTripMode(tripMode)) {
                    continue;
                }
                if (visualizationMode == VisualizationMode.PT_FLOW_HEATMAP && !isPtMode(tripMode)) {
                    continue;
                }

                double enter = model.traversalEnterTime(i);
                if (enter < binStartSeconds || enter >= binEndSeconds) {
                    continue;
                }

                String linkId = model.traversalLinkId(i);
                if (flowMode) {
                    values.merge(linkId, 1.0, Double::sum);
                    continue;
                }

                LinkSegment link = model.networkData().getLinks().get(linkId);
                if (link == null || link.length() <= 0.0) {
                    continue;
                }

                double duration = Math.max(0.05, model.traversalLeaveTime(i) - enter);
                double speedMetersPerSecond = link.length() / duration;
                if (speedMode) {
                    speedSums.merge(linkId, speedMetersPerSecond * 3.6, Double::sum);
                } else if (speedRatioMode) {
                    double freeSpeed = Math.max(0.1, link.freeSpeed());
                    speedSums.merge(linkId, speedMetersPerSecond / freeSpeed, Double::sum);
                }
                speedCounts.merge(linkId, 1, Integer::sum);
            }
        }

        if (flowMode) {
            double factor = 3600.0 / binSizeSeconds;
            for (Map.Entry<String, Double> entry : values.entrySet()) {
                entry.setValue(entry.getValue() * factor);
            }
        }

        if (speedMode || speedRatioMode) {
            for (Map.Entry<String, Double> entry : speedSums.entrySet()) {
                String linkId = entry.getKey();
                int count = speedCounts.getOrDefault(linkId, 0);
                if (count > 0) {
                    values.put(linkId, entry.getValue() / count);
                }
            }
        }

        double minValue = Double.POSITIVE_INFINITY;
        double maxValue = 0.0;
        for (double value : values.values()) {
            minValue = Math.min(minValue, value);
            maxValue = Math.max(maxValue, value);
        }
        if (!Double.isFinite(minValue)) {
            minValue = 0.0;
        }

        return new HeatmapSnapshot(values, minValue, maxValue);
    }

    private boolean shouldAggregateTripMode(String tripMode) {
        List<String> availableModes = visualizationMode == VisualizationMode.PT_STOP_BUBBLES
                ? model.availablePtStopModes()
                : model.availableTripModes();
        Set<String> selectedModes = visualizationMode == VisualizationMode.PT_STOP_BUBBLES
                ? selectedPtStopModes
                : selectedHeatmapTripModes;

        if (availableModes.isEmpty()) {
            return true;
        }
        if (selectedModes.isEmpty()) {
            return false;
        }
        if (tripMode == null || tripMode.isBlank()) {
            return false;
        }
        return selectedModes.contains(normalizeMode(tripMode));
    }

    private Set<String> effectiveHeatmapNetworkModes() {
        if (visualizationMode == VisualizationMode.PT_STOP_BUBBLES) {
            return selectedPtStopModes;
        }
        if (visualizationMode == VisualizationMode.PT_FLOW_HEATMAP) {
            Set<String> ptModes = new HashSet<>();
            for (String mode : selectedHeatmapTripModes) {
                if (isPtMode(mode)) {
                    ptModes.add(mode);
                }
            }
            return ptModes;
        }
        return selectedHeatmapTripModes;
    }

    private Color heatmapLowColor() {
        return switch (visualizationMode) {
            case FLOW_HEATMAP, PT_FLOW_HEATMAP, PT_STOP_BUBBLES -> flowHeatmapLowColor;
            case SPEED_HEATMAP -> speedHeatmapLowColor;
            case SPEED_RATIO_HEATMAP -> speedRatioHeatmapLowColor;
            case VEHICLES -> DEFAULT_HEATMAP_LOW;
        };
    }

    private Color heatmapHighColor() {
        return switch (visualizationMode) {
            case FLOW_HEATMAP, PT_FLOW_HEATMAP, PT_STOP_BUBBLES -> flowHeatmapHighColor;
            case SPEED_HEATMAP -> speedHeatmapHighColor;
            case SPEED_RATIO_HEATMAP -> speedRatioHeatmapHighColor;
            case VEHICLES -> DEFAULT_FLOW_HEATMAP_HIGH;
        };
    }

    private static double heatmapLogNormalized(double value, double maxValue) {
        if (value <= 0.0 || maxValue <= 0.0) {
            return 0.0;
        }
        return Math.log1p(value) / Math.log1p(maxValue);
    }

    private static double smoothstep(double value) {
        double t = Math.max(0.0, Math.min(1.0, value));
        return t * t * (3.0 - 2.0 * t);
    }

    private static Color interpolateColor(Color low, Color high, double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        int red = (int) Math.round(low.getRed() + (high.getRed() - low.getRed()) * clamped);
        int green = (int) Math.round(low.getGreen() + (high.getGreen() - low.getGreen()) * clamped);
        int blue = (int) Math.round(low.getBlue() + (high.getBlue() - low.getBlue()) * clamped);
        return new Color(red, green, blue);
    }

    private void drawModeGroup(
            Graphics2D g2,
            LinkScreenGeometry geometry,
            LinkSegment link,
            List<Integer> traversals,
            double currentTime,
            double baseLengthMeters,
            double widthRatio,
            VehicleShape shape,
            double modeOffsetFactor,
            boolean queueConstrained,
            boolean isBottleneck
    ) {
        if (traversals.isEmpty()) {
            return;
        }

        int laneCount = geometry.laneCount();
        double linkLengthMeters = Math.max(0.1, link.length());

        double vehicleLengthMeters = baseLengthMeters;
        if (queueConstrained) {
            vehicleLengthMeters = Math.min(baseLengthMeters, linkLengthMeters / Math.max(1, traversals.size()));
        }
        vehicleLengthMeters = Math.max(MIN_VEHICLE_LENGTH_METERS, vehicleLengthMeters);

        double minCenterMeters = vehicleLengthMeters * 0.5;
        double maxCenterMeters = Math.max(minCenterMeters, linkLengthMeters - vehicleLengthMeters * 0.5);
        double previousCenterMeters = Double.POSITIVE_INFINITY;

        for (int i = 0; i < traversals.size(); i++) {
            int traversalIndex = traversals.get(i);

            double naturalCenterMeters = Math.max(
                    minCenterMeters,
                    Math.min(maxCenterMeters, naturalProgress(traversalIndex, currentTime) * linkLengthMeters)
            );

            double centerMeters;
            if (!queueConstrained || i == 0) {
                centerMeters = naturalCenterMeters;
            } else {
                centerMeters = Math.min(naturalCenterMeters, previousCenterMeters - vehicleLengthMeters);
                centerMeters = Math.max(minCenterMeters, centerMeters);
            }
            previousCenterMeters = centerMeters;

            double progress = centerMeters / linkLengthMeters;
            int laneIndex = i % laneCount;

            double sx = geometry.fromX() + geometry.dx() * progress;
            double sy = geometry.fromY() + geometry.dy() * progress;

            double laneCenterOffset = ((laneIndex + 0.5) - laneCount / 2.0) * geometry.laneWidth();
        double modeOffset = geometry.laneWidth() * modeOffsetFactor;
            double laneOffset = laneCenterOffset + modeOffset;
            sx += geometry.nx() * laneOffset;
            sy += geometry.ny() * laneOffset;

            String tripMode = model.traversalTripMode(traversalIndex);
            Color drawColor;
            if (showBottleneck) {
                drawColor = isBottleneck ? BOTTLENECK_CONGESTED : BOTTLENECK_NORMAL;
            } else {
                drawColor = colorMode == ColorMode.DEFAULT
                        ? colorProvider.colorForTripMode(tripMode)
                        : colorProvider.colorFor(traversalIndex, model, colorMode);
            }

            g2.setColor(drawColor);
            double vehicleLengthPx = Math.max(1.2, (vehicleLengthMeters / linkLengthMeters) * geometry.length());
            double vehicleWidthPx = Math.max(0.8, geometry.laneWidth() * widthRatio);

            if (keepVehiclesVisibleWhenZoomedOut) {
                vehicleLengthPx = Math.max(minVehicleLengthPixels, vehicleLengthPx);
                vehicleWidthPx = Math.max(minVehicleWidthPixels, vehicleWidthPx);
            }

            if (vehicleLengthPx < 3.0 && vehicleWidthPx < 3.0) {
                g2.fillRect((int) Math.round(sx), (int) Math.round(sy), 1, 1);
            } else {
                drawVehicle(g2, sx, sy, geometry.angle(), vehicleLengthPx, vehicleWidthPx, shape);
            }
        }
    }

    private double naturalProgress(int traversalIndex, double currentTime) {
        double enter = model.traversalEnterTime(traversalIndex);
        double progress = (currentTime - enter) * model.traversalInverseDuration(traversalIndex);
        return Math.max(0.0, Math.min(1.0, progress));
    }

    private void drawQueueLabels(Graphics2D g2) {
        if (zoom < 1.6) {
            return;
        }

        g2.setColor(QUEUE_LABEL);
        for (Map.Entry<String, Integer> queue : playbackController.getLinkQueueCountsView().entrySet()) {
            if (queue.getValue() <= 1) {
                continue;
            }

            LinkSegment link = model.networkData().getLinks().get(queue.getKey());
            if (link == null) {
                continue;
            }

            if (!shouldRenderLink(link)) {
                continue;
            }

            LinkScreenGeometry geometry = linkScreenGeometries.get(link.id());
            if (geometry == null) {
                continue;
            }

            double midX = geometry.fromX() + geometry.dx() * 0.5;
            double midY = geometry.fromY() + geometry.dy() * 0.5;
            g2.drawString(Integer.toString(queue.getValue()), (int) midX + 2, (int) midY - 2);
        }
    }

    private void handleZoom(MouseWheelEvent e) {
        ensureFitted();

        Point2D.Double anchorWorld = screenToWorld(e.getX(), e.getY());
        double factor = e.getWheelRotation() < 0 ? 1.12 : 0.89;
        zoom = Math.max(0.2, Math.min(80.0, zoom * factor));

        Point2D.Double anchorScreenAfterZoom = worldToScreen(anchorWorld.x, anchorWorld.y);
        panX += e.getX() - anchorScreenAfterZoom.x;
        panY += anchorScreenAfterZoom.y - e.getY();

        invalidateNetworkCache();
        repaint();
    }

    private Point2D.Double worldToScreen(double x, double y) {
        NetworkData network = model.networkData();
        double sx = (x - network.getMinX()) * baseScale * zoom + panX;
        double sy = getHeight() - ((y - network.getMinY()) * baseScale * zoom + panY);
        return new Point2D.Double(sx, sy);
    }

    private Point2D.Double screenToWorld(double x, double y) {
        NetworkData network = model.networkData();
        double worldX = ((x - panX) / (baseScale * zoom)) + network.getMinX();
        double worldY = ((getHeight() - y - panY) / (baseScale * zoom)) + network.getMinY();
        return new Point2D.Double(worldX, worldY);
    }

    private void invalidateNetworkCache() {
        cachedNetworkLayer = null;
        lastNetworkRenderNanos = 0L;
    }

    private void invalidateHeatmapCache() {
        cachedHeatmapKey = null;
        cachedHeatmapSnapshot = null;
    }

    private PreparedLinkVehicles prepareLinkVehicles(
            String linkId,
            PlaybackController.LinkFrameSnapshot linkState,
            double currentTime
    ) {
        LinkScreenGeometry geometry = linkScreenGeometries.get(linkId);
        LinkSegment link = model.networkData().getLinks().get(linkId);
        if (geometry == null || link == null) {
            return null;
        }

        int[] traversalIndexes = linkState.traversalIndexes();
        if (traversalIndexes.length == 0) {
            return null;
        }

        List<Integer> bikeTraversals = null;
        List<Integer> truckTraversals = null;
        List<Integer> busTraversals = null;
        List<Integer> railTraversals = null;
        List<Integer> carTraversals = null;

        for (int traversalIndex : traversalIndexes) {
            String mode = model.traversalTripMode(traversalIndex);
            if (!shouldRenderTripMode(mode)) {
                continue;
            }

            if (isBikeMode(mode)) {
                if (bikeTraversals == null) {
                    bikeTraversals = new ArrayList<>();
                }
                bikeTraversals.add(traversalIndex);
            } else if (isTruckMode(mode)) {
                if (truckTraversals == null) {
                    truckTraversals = new ArrayList<>();
                }
                truckTraversals.add(traversalIndex);
            } else if (isBusMode(mode)) {
                if (busTraversals == null) {
                    busTraversals = new ArrayList<>();
                }
                busTraversals.add(traversalIndex);
            } else if (isRailMode(mode)) {
                if (railTraversals == null) {
                    railTraversals = new ArrayList<>();
                }
                railTraversals.add(traversalIndex);
            } else {
                if (carTraversals == null) {
                    carTraversals = new ArrayList<>();
                }
                carTraversals.add(traversalIndex);
            }
        }

        if (bikeTraversals == null
                && truckTraversals == null
                && busTraversals == null
                && railTraversals == null
                && carTraversals == null) {
            return null;
        }

        sortByProgressIfReasonable(carTraversals, currentTime);
        sortByProgressIfReasonable(truckTraversals, currentTime);
        sortByProgressIfReasonable(busTraversals, currentTime);
        sortByProgressIfReasonable(railTraversals, currentTime);
        sortByProgressIfReasonable(bikeTraversals, currentTime);

        boolean linkIsBottleneck = false;
        if (showBottleneck) {
            double capacity = sampleSize * laneCount(link) * (link.length() / bottleneckDivisor);
            linkIsBottleneck = linkState.queueCount() > capacity;
        }

        return new PreparedLinkVehicles(
                link,
                geometry,
                linkIsBottleneck,
                emptyIfNull(carTraversals),
                emptyIfNull(truckTraversals),
                emptyIfNull(busTraversals),
                emptyIfNull(railTraversals),
                emptyIfNull(bikeTraversals)
        );
    }

    private void sortByProgressIfReasonable(List<Integer> traversals, double currentTime) {
        if (traversals == null || traversals.size() <= 1 || traversals.size() > MAX_SORTED_TRAVERSALS_PER_GROUP) {
            return;
        }
        traversals.sort((left, right) -> Double.compare(
                naturalProgress(right, currentTime),
                naturalProgress(left, currentTime)
        ));
    }

    private static List<Integer> emptyIfNull(List<Integer> traversals) {
        return traversals == null ? List.of() : traversals;
    }

    private void queryVisibleLinks(Set<String> output) {
        Point2D.Double topLeft = screenToWorld(-VIEWPORT_WORLD_PADDING_PX, -VIEWPORT_WORLD_PADDING_PX);
        Point2D.Double bottomRight = screenToWorld(
                getWidth() + VIEWPORT_WORLD_PADDING_PX,
                getHeight() + VIEWPORT_WORLD_PADDING_PX
        );

        double minWorldX = Math.min(topLeft.x, bottomRight.x);
        double maxWorldX = Math.max(topLeft.x, bottomRight.x);
        double minWorldY = Math.min(topLeft.y, bottomRight.y);
        double maxWorldY = Math.max(topLeft.y, bottomRight.y);

        spatialGrid.query(minWorldX, minWorldY, maxWorldX, maxWorldY, output);
    }

    private BufferedImage createCompatibleImage(int width, int height) {
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration == null) {
            return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        }
        return configuration.createCompatibleImage(width, height);
    }

    private boolean shouldRenderLink(LinkSegment link) {
        if (model.availableLinkModes().isEmpty()) {
            return true;
        }

        Set<String> activeModes = selectedLinkModes;
        if (visualizationMode != VisualizationMode.VEHICLES && !useSeparateHeatmapNetworkModes) {
            activeModes = effectiveHeatmapNetworkModes();
        }

        if (activeModes.isEmpty()) {
            return false;
        }
        for (String mode : activeModes) {
            if (link.allowedModes().contains(mode)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldRenderTripMode(String tripMode) {
        if (model.availableTripModes().isEmpty()) {
            return true;
        }
        if (selectedTripModes.isEmpty()) {
            return false;
        }
        if (tripMode == null || tripMode.isBlank()) {
            return false;
        }
        return selectedTripModes.contains(normalizeMode(tripMode));
    }

    private double laneWidthPixels() {
        // Start thin at city-scale view; widen smoothly while zooming in.
        return Math.max(0.28, Math.min(8.5, 0.09 + 0.20 * Math.pow(zoom, 0.84)));
    }

    private int laneCount(LinkSegment link) {
        return Math.max(1, (int) Math.ceil(link.lanes()));
    }

    private void drawVehicle(Graphics2D g2, double sx, double sy, double angle,
                             double length, double width, VehicleShape shape) {
        AffineTransform original = g2.getTransform();
        g2.translate(sx, sy);
        g2.rotate(angle);

        double hl = length / 2.0;
        double hw = width / 2.0;

        switch (shape) {
            case RECTANGLE -> {
                g2.fill(new Rectangle2D.Double(-hl, -hw, length, width));
            }
            case ARROW -> {
                // Arrow: tip at front, notched tail
                int[] axs = {(int) Math.round(hl), (int) Math.round(-hl), (int) Math.round(-hl * 0.5),
                             (int) Math.round(-hl)};
                int[] ays = {0, (int) Math.round(-hw), 0, (int) Math.round(hw)};
                g2.fillPolygon(axs, ays, 4);
            }
            case TRIANGLE -> {
                int[] txs = {(int) Math.round(hl), (int) Math.round(-hl), (int) Math.round(-hl)};
                int[] tys = {0, (int) Math.round(-hw), (int) Math.round(hw)};
                g2.fillPolygon(txs, tys, 3);
            }
            case DIAMOND -> {
                int[] dxs = {(int) Math.round(hl), 0, (int) Math.round(-hl), 0};
                int[] dys = {0, (int) Math.round(-hw), 0, (int) Math.round(hw)};
                g2.fillPolygon(dxs, dys, 4);
            }
            case CIRCLE -> {
                double r = Math.min(hl, hw);
                g2.fillOval((int) Math.round(-r), (int) Math.round(-r),
                        (int) Math.round(r * 2), (int) Math.round(r * 2));
            }
            case OVAL -> {
                g2.fillOval((int) Math.round(-hl), (int) Math.round(-hw),
                        (int) Math.round(length), (int) Math.round(width));
            }
        }

        g2.setTransform(original);
    }

    private void drawClockOverlay(Graphics2D g2) {
        String time = TimeFormat.hhmmss(playbackController.getCurrentTime());

        int width = 190;
        int height = 52;
        int x = getWidth() - width - 12;
        int y = 12;

        g2.setColor(darkTheme ? new Color(0x101010) : new Color(0xF0F0F0));
        g2.fillRoundRect(x, y, width, height, 10, 10);
        g2.setColor(darkTheme ? new Color(0x5A5A5A) : new Color(0xB0B0B0));
        g2.drawRoundRect(x, y, width, height, 10, 10);

        int iconX = x + 14;
        int iconY = y + 16;
        g2.setColor(darkTheme ? new Color(0xF0F0F0) : new Color(0x202020));
        g2.drawOval(iconX, iconY, 16, 16);
        g2.drawLine(iconX + 8, iconY + 8, iconX + 8, iconY + 3);
        g2.drawLine(iconX + 8, iconY + 8, iconX + 12, iconY + 8);

        Font original = g2.getFont();
        Font timeFont = original.deriveFont(Font.BOLD, 20f);
        g2.setFont(timeFont);
        g2.drawString(time, x + 40, y + 35);
        g2.setFont(original);
    }

    private void drawLegendOverlay(Graphics2D g2) {
        if (visualizationMode != VisualizationMode.VEHICLES) {
            drawHeatmapLegendOverlay(g2);
            return;
        }

        List<LegendEntry> entries = legendEntriesToDraw();
        if (entries.isEmpty()) {
            return;
        }

        String legendTitle = legendTitle();

        FontMetrics metrics = g2.getFontMetrics();
        int maxEntries = Math.min(11, entries.size());
        int rowHeight = 18;
        int maxLabelWidth = metrics.stringWidth(legendTitle);
        for (int i = 0; i < maxEntries; i++) {
            maxLabelWidth = Math.max(maxLabelWidth, metrics.stringWidth(entries.get(i).label()));
        }
        int width = Math.min(getWidth() - 24, Math.max(220, 44 + maxLabelWidth));
        int height = 30 + maxEntries * rowHeight;
        int x = getWidth() - width - 12;
        int y = 72;

        g2.setColor(darkTheme ? new Color(0x101010) : new Color(0xF0F0F0));
        g2.fillRoundRect(x, y, width, height, 10, 10);
        g2.setColor(darkTheme ? new Color(0x5A5A5A) : new Color(0xB0B0B0));
        g2.drawRoundRect(x, y, width, height, 10, 10);

        g2.setColor(darkTheme ? new Color(0xEFEFEF) : new Color(0x202020));
        g2.drawString(legendTitle, x + 10, y + 18);

        for (int i = 0; i < maxEntries; i++) {
            LegendEntry entry = entries.get(i);
            int rowY = y + 34 + i * rowHeight;
            g2.setColor(entry.color());
            g2.fillRect(x + 10, rowY - 11, 12, 12);
            g2.setColor(darkTheme ? new Color(0xEFEFEF) : new Color(0x202020));
            g2.drawString(entry.label(), x + 28, rowY);
        }
    }

    private void drawHeatmapLegendOverlay(Graphics2D g2) {
        HeatmapInterpolation interpolation = resolveHeatmapInterpolation();
        if (!interpolation.prepared()) {
            return;
        }

        double maxValue = interpolation.maxValueForScale();
        String legendTitle = legendTitle();
        String legendMin = formatHeatmapLegendValue(0.0);
        String legendMid = formatHeatmapLegendValue(maxValue <= 0.0 ? 0.0 : Math.expm1(Math.log1p(maxValue) * 0.5));
        String legendMax = formatHeatmapLegendValue(maxValue);

        int width = Math.min(getWidth() - 24, 290);
        int height = 92;
        int x = getWidth() - width - 12;
        int y = 72;

        g2.setColor(darkTheme ? new Color(0x101010) : new Color(0xF0F0F0));
        g2.fillRoundRect(x, y, width, height, 10, 10);
        g2.setColor(darkTheme ? new Color(0x5A5A5A) : new Color(0xB0B0B0));
        g2.drawRoundRect(x, y, width, height, 10, 10);

        g2.setColor(darkTheme ? new Color(0xEFEFEF) : new Color(0x202020));
        g2.drawString(legendTitle, x + 10, y + 18);
        g2.drawString("Log scale", x + width - 68, y + 18);

        int barX = x + 12;
        int barY = y + 36;
        int barWidth = width - 24;
        int barHeight = 16;
        Color low = heatmapLowColor();
        Color high = heatmapHighColor();
        for (int i = 0; i < barWidth; i++) {
            double t = barWidth <= 1 ? 0.0 : (double) i / (barWidth - 1);
            g2.setColor(interpolateColor(low, high, t));
            g2.drawLine(barX + i, barY, barX + i, barY + barHeight - 1);
        }
        g2.setColor(darkTheme ? new Color(0x777777) : new Color(0x909090));
        g2.drawRect(barX, barY, barWidth, barHeight);

        g2.setColor(darkTheme ? new Color(0xEFEFEF) : new Color(0x202020));
        FontMetrics metrics = g2.getFontMetrics();
        int labelsY = barY + barHeight + 16;
        g2.drawString(legendMin, barX, labelsY);
        g2.drawString(legendMid, barX + (barWidth - metrics.stringWidth(legendMid)) / 2, labelsY);
        g2.drawString(legendMax, barX + barWidth - metrics.stringWidth(legendMax), labelsY);
    }

    private String legendTitle() {
        return switch (visualizationMode) {
            case FLOW_HEATMAP -> "Flow (veh/h)";
            case PT_FLOW_HEATMAP -> "PT Volume (veh/h)";
            case PT_STOP_BUBBLES -> "PT Stop Volume (pax/h)";
            case SPEED_HEATMAP -> "Speed (km/h)";
            case SPEED_RATIO_HEATMAP -> "Speed Ratio (v/freeSpeed)";
            case VEHICLES -> "Legend";
        };
    }

    private List<LegendEntry> legendEntriesToDraw() {
        if (showBottleneck) {
            return List.of(
                    new LegendEntry("Normal", BOTTLENECK_NORMAL),
                    new LegendEntry("Bottleneck", BOTTLENECK_CONGESTED)
            );
        }

        if (colorMode == ColorMode.DEFAULT && !selectedTripModes.isEmpty()) {
            List<LegendEntry> modeEntries = new ArrayList<>();
            for (String mode : model.availableTripModes()) {
                if (selectedTripModes.contains(mode)) {
                    modeEntries.add(new LegendEntry(mode, colorProvider.colorForTripMode(mode)));
                }
            }
            return modeEntries;
        }

        if (colorMode == ColorMode.TRIP_PURPOSE) {
            List<LegendEntry> purposeEntries = new ArrayList<>();
            for (String purpose : model.availableTripPurposes()) {
                purposeEntries.add(new LegendEntry(purpose, colorProvider.tripPurposeColor(purpose)));
                if (purposeEntries.size() >= 11) {
                    break;
                }
            }
            return purposeEntries;
        }

        if (colorMode == ColorMode.AGE_GROUP) {
            List<LegendEntry> ageEntries = new ArrayList<>();
            for (int i = 0; i < colorProvider.ageGroupCount(); i++) {
                ageEntries.add(new LegendEntry(colorProvider.ageGroupLabel(i), colorProvider.ageGroupColor(i)));
            }
            return ageEntries;
        }

        if (colorMode == ColorMode.SEX) {
            List<LegendEntry> sexEntries = new ArrayList<>();
            List<String> categories = getSexCategories();
            for (String category : categories) {
                sexEntries.add(new LegendEntry(category, colorProvider.sexColor(category)));
            }
            return sexEntries;
        }

        return List.of();
    }

    private String formatHeatmapLegendValue(double value) {
        if (visualizationMode == VisualizationMode.FLOW_HEATMAP
                || visualizationMode == VisualizationMode.PT_FLOW_HEATMAP
                || visualizationMode == VisualizationMode.PT_STOP_BUBBLES) {
            return String.format(Locale.ROOT, "%.1f", value);
        }
        if (visualizationMode == VisualizationMode.SPEED_RATIO_HEATMAP) {
            return String.format(Locale.ROOT, "%.3f", value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static boolean isBikeMode(String mode) {
        if (mode == null) {
            return false;
        }
        String normalized = normalizeMode(mode);
        return normalized.equals("bike") || normalized.equals("bicycle");
    }

    private static boolean isTruckMode(String mode) {
        if (mode == null) {
            return false;
        }
        String normalized = normalizeMode(mode);
        return normalized.equals("truck")
                || normalized.equals("freight")
                || normalized.equals("hdv")
                || normalized.contains("truck")
                || normalized.contains("freight");
    }

    private static boolean isBusMode(String mode) {
        if (mode == null) {
            return false;
        }
        String normalized = normalizeMode(mode);
        return normalized.equals("bus");
    }

    private static boolean isRailMode(String mode) {
        if (mode == null) {
            return false;
        }
        String normalized = normalizeMode(mode);
        return normalized.equals("tram")
                || normalized.equals("train")
                || normalized.equals("rail")
                || normalized.equals("subway")
                || normalized.equals("metro")
                || normalized.equals("ferry")
                || normalized.equals("funicular");
    }

    private static boolean isPtMode(String mode) {
        return isBusMode(mode) || isRailMode(mode);
    }

    private static double clampWidthRatio(double value) {
        return Math.max(0.10, Math.min(2.0, value));
    }

    private static double clampVehiclePixelSize(double value) {
        return Math.max(0.5, Math.min(30.0, value));
    }

    private static double clampPtStopBubbleRadius(double value) {
        return Math.max(1.0, Math.min(64.0, value));
    }

    private static String normalizeMode(String mode) {
        return mode == null ? "" : mode.toLowerCase(Locale.ROOT);
    }

    private static Set<String> defaultTransportModes(List<String> availableModes) {
        Set<String> selected = new HashSet<>();
        Set<String> defaults = Set.of("car", "bike", "truck", "bus", "tram");
        for (String mode : availableModes) {
            String normalized = normalizeMode(mode);
            if (defaults.contains(normalized)) {
                selected.add(normalized);
            }
        }
        if (selected.isEmpty()) {
            for (String mode : availableModes) {
                selected.add(normalizeMode(mode));
            }
        }
        return selected;
    }

    private record PreparedLinkVehicles(
            LinkSegment link,
            LinkScreenGeometry geometry,
            boolean linkIsBottleneck,
            List<Integer> carTraversals,
            List<Integer> truckTraversals,
            List<Integer> busTraversals,
            List<Integer> railTraversals,
            List<Integer> bikeTraversals
    ) {
    }

    private record LinkScreenGeometry(
            double fromX,
            double fromY,
            double dx,
            double dy,
            double length,
            double nx,
            double ny,
            double laneWidth,
            int laneCount,
            double angle
    ) {
    }

    private record LegendEntry(String label, Color color) {
    }

    private record HeatmapCacheKey(
            VisualizationMode mode,
            int binStartSeconds,
            int binSizeSeconds,
            String selectedModesKey
    ) {
    }

    private record HeatmapSnapshot(Map<String, Double> values, double minValue, double maxValue) {
    }

        private record HeatmapInterpolation(
            HeatmapSnapshot currentBinSnapshot,
            HeatmapSnapshot nextBinSnapshot,
            double alpha,
            double maxValueForScale,
            boolean prepared
        ) {
        }

    private record HeatmapPreparedSignature(
            VisualizationMode mode,
            int binSizeSeconds,
            String selectedModesKey,
            Set<String> selectedModes
    ) {
    }

}
