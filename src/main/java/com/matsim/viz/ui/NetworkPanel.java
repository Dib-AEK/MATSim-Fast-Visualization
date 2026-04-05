package com.matsim.viz.ui;

import com.matsim.viz.domain.ColorMode;
import com.matsim.viz.domain.LinkSegment;
import com.matsim.viz.domain.NetworkData;
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
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class NetworkPanel extends JPanel {
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

    private final SimulationModel model;
    private final PlaybackController playbackController;
    private final VehicleColorProvider colorProvider = new VehicleColorProvider();
    private final Set<String> selectedLinkModes = new HashSet<>();
    private final Set<String> selectedTripModes = new HashSet<>();
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
    private double minVehicleLengthPixels = 2.5;
    private double minVehicleWidthPixels = 1.2;

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

    private Point dragStart;

    public NetworkPanel(SimulationModel model, PlaybackController playbackController) {
        this.model = model;
        this.playbackController = playbackController;
        setBackground(mapBackground);
        setPreferredSize(new Dimension(1200, 800));
        selectedLinkModes.addAll(defaultTransportModes(model.availableLinkModes()));
        selectedTripModes.addAll(defaultTransportModes(model.availableTripModes()));

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
                    invalidateNetworkCache();
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

        renderNetworkLayerIfNeeded();
        if (cachedNetworkLayer != null) {
            g2.drawImage(cachedNetworkLayer, 0, 0, null);
        }

        drawVehicles(g2);
        if (showQueues && !suppressOverlays) {
            drawQueueLabels(g2);
        }
        if (!suppressOverlays) {
            drawClockOverlay(g2);
            drawLegendOverlay(g2);
        }

        g2.dispose();
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
        if (cachedNetworkLayer != null
                && cachedZoom == zoom
                && cachedPanX == panX
                && cachedPanY == panY
                && cachedWidth == getWidth()
                && cachedHeight == getHeight()) {
            return;
        }

        cachedWidth = getWidth();
        cachedHeight = getHeight();
        cachedZoom = zoom;
        cachedPanX = panX;
        cachedPanY = panY;

        cachedNetworkLayer = new BufferedImage(cachedWidth, cachedHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = cachedNetworkLayer.createGraphics();
        g2.setColor(mapBackground);
        g2.fillRect(0, 0, cachedWidth, cachedHeight);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        linkScreenGeometries.clear();
        double laneWidth = laneWidthPixels();
        Map<String, double[]> nodeMaxRadius = new HashMap<>();

        Set<String> renderedNodePairs = new HashSet<>();
        for (LinkSegment link : model.networkData().getLinks().values()) {
            if (shouldRenderLink(link)) {
                renderedNodePairs.add(link.fromNodeId() + ">" + link.toNodeId());
            }
        }

        g2.setColor(mapRoad);
        for (LinkSegment link : model.networkData().getLinks().values()) {
            if (!shouldRenderLink(link)) {
                continue;
            }

            Point2D.Double a = worldToScreen(link.fromX(), link.fromY());
            Point2D.Double b = worldToScreen(link.toX(), link.toY());
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double length = Math.hypot(dx, dy);
            if (length < 0.0001) {
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
                double shift = roadWidth * bidirectionalOffset;
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
        List<Integer> activeTraversalIndexes = new ArrayList<>(playbackController.getActiveTraversalIndexes());
        activeTraversalIndexes.sort(Integer::compareTo);

        Map<String, List<Integer>> activeByLink = new HashMap<>();

        for (int traversalIndex : activeTraversalIndexes) {
            String linkId = model.traversalLinkId(traversalIndex);
            LinkSegment link = model.networkData().getLinks().get(linkId);
            if (link == null) {
                continue;
            }

            if (!shouldRenderLink(link)) {
                continue;
            }

            String tripMode = model.vehicleToMode().get(model.traversalVehicleId(traversalIndex));
            if (!shouldRenderTripMode(tripMode)) {
                continue;
            }

            activeByLink.computeIfAbsent(linkId, key -> new ArrayList<>()).add(traversalIndex);
        }

        for (Map.Entry<String, List<Integer>> entry : activeByLink.entrySet()) {
            String linkId = entry.getKey();
            LinkSegment link = model.networkData().getLinks().get(linkId);
            if (link == null) {
                continue;
            }

            LinkScreenGeometry geometry = linkScreenGeometries.get(link.id());
            if (geometry == null) {
                continue;
            }

            List<Integer> linkTraversals = entry.getValue();

            boolean linkIsBottleneck = false;
            if (showBottleneck) {
                int queueCount = playbackController.getQueueCountForLink(linkId);
                double capacity = sampleSize * laneCount(link) * (link.length() / bottleneckDivisor);
                linkIsBottleneck = queueCount > capacity;
            }

                List<Integer> bikeTraversals = new ArrayList<>();
                List<Integer> truckTraversals = new ArrayList<>();
                List<Integer> busTraversals = new ArrayList<>();
                List<Integer> railTraversals = new ArrayList<>();
                List<Integer> carLikeTraversals = new ArrayList<>();
            for (int traversalIndex : linkTraversals) {
                String mode = model.vehicleToMode().get(model.traversalVehicleId(traversalIndex));
                if (isBikeMode(mode)) {
                    bikeTraversals.add(traversalIndex);
                } else if (isTruckMode(mode)) {
                    truckTraversals.add(traversalIndex);
                } else if (isBusMode(mode)) {
                    busTraversals.add(traversalIndex);
                } else if (isRailMode(mode)) {
                    railTraversals.add(traversalIndex);
                } else {
                    carLikeTraversals.add(traversalIndex);
                }
            }

                carLikeTraversals.sort(Comparator.comparingDouble((Integer idx) -> naturalProgress(idx, currentTime)).reversed());
                truckTraversals.sort(Comparator.comparingDouble((Integer idx) -> naturalProgress(idx, currentTime)).reversed());
            bikeTraversals.sort(Comparator.comparingDouble((Integer idx) -> naturalProgress(idx, currentTime)).reversed());
            busTraversals.sort(Comparator.comparingDouble((Integer idx) -> naturalProgress(idx, currentTime)).reversed());
            railTraversals.sort(Comparator.comparingDouble((Integer idx) -> naturalProgress(idx, currentTime)).reversed());

            drawModeGroup(
                    g2,
                    geometry,
                    link,
                    carLikeTraversals,
                    currentTime,
                    carLikeVehicleLengthMeters,
                    carLikeVehicleWidthRatio,
                    carShape,
                    0.12,
                    true,
                    linkIsBottleneck
            );

                drawModeGroup(
                    g2,
                    geometry,
                    link,
                    truckTraversals,
                    currentTime,
                    truckVehicleLengthMeters,
                    truckVehicleWidthRatio,
                    truckShape,
                    0.30,
                    true,
                    linkIsBottleneck
                );

            drawModeGroup(
                    g2,
                    geometry,
                    link,
                    busTraversals,
                    currentTime,
                    busVehicleLengthMeters,
                    busVehicleWidthRatio,
                    busShape,
                    0.15,
                    true,
                    linkIsBottleneck
            );

            drawModeGroup(
                    g2,
                    geometry,
                    link,
                    railTraversals,
                    currentTime,
                    railVehicleLengthMeters,
                    railVehicleWidthRatio,
                    railShape,
                    0.0,
                    true,
                    linkIsBottleneck
            );

            drawModeGroup(
                    g2,
                    geometry,
                    link,
                    bikeTraversals,
                    currentTime,
                    bikeVehicleLengthMeters,
                    bikeVehicleWidthRatio,
                    bikeShape,
                    -0.20,
                    false,
                    linkIsBottleneck
            );
        }
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

            String tripMode = model.vehicleToMode().get(model.traversalVehicleId(traversalIndex));
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

            drawVehicle(g2, sx, sy, geometry.angle(), vehicleLengthPx, vehicleWidthPx, shape);
        }
    }

    private double naturalProgress(int traversalIndex, double currentTime) {
        double enter = model.traversalEnterTime(traversalIndex);
        double leave = model.traversalLeaveTime(traversalIndex);
        double duration = Math.max(0.05, leave - enter);
        double progress = (currentTime - enter) / duration;
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
    }

    private boolean shouldRenderLink(LinkSegment link) {
        if (model.availableLinkModes().isEmpty()) {
            return true;
        }
        if (selectedLinkModes.isEmpty()) {
            return false;
        }
        for (String mode : selectedLinkModes) {
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
                int[] xs = {(int) Math.round(hl), (int) Math.round(-hl), (int) Math.round(-hl * 0.4),
                            (int) Math.round(-hl), (int) Math.round(-hl)};
                int[] ys = {0, (int) Math.round(-hw), 0,
                            (int) Math.round(hw), (int) Math.round(-hw)};
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
        List<LegendEntry> entries = legendEntriesToDraw();
        if (entries.isEmpty()) {
            return;
        }

        FontMetrics metrics = g2.getFontMetrics();
        int maxEntries = Math.min(11, entries.size());
        int rowHeight = 18;
        int maxLabelWidth = metrics.stringWidth("Legend");
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
        g2.drawString("Legend", x + 10, y + 18);

        for (int i = 0; i < maxEntries; i++) {
            LegendEntry entry = entries.get(i);
            int rowY = y + 30 + i * rowHeight;
            g2.setColor(entry.color());
            g2.fillRect(x + 10, rowY - 11, 12, 12);
            g2.setColor(darkTheme ? new Color(0xEFEFEF) : new Color(0x202020));
            g2.drawString(entry.label(), x + 28, rowY);
        }
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

    private static double clampWidthRatio(double value) {
        return Math.max(0.10, Math.min(2.0, value));
    }

    private static double clampVehiclePixelSize(double value) {
        return Math.max(0.5, Math.min(30.0, value));
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

}
