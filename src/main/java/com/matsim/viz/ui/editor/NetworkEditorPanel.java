package com.matsim.viz.ui.editor;

import com.matsim.viz.domain.LinkSegment;
import com.matsim.viz.domain.NetworkData;
import com.matsim.viz.domain.NodePoint;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NetworkEditorPanel extends JPanel {
    public enum CoordinateSystem {
        AUTO("Auto"),
        EPSG_2056("EPSG:2056 (CH1903+ / LV95)"),
        EPSG_4326("EPSG:4326 (WGS84 lon/lat)"),
        NONE("None (No OSM background)");

        private final String label;

        CoordinateSystem(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public record SaveSummary(int nodeCount, int linkCount, Path outputFile) {
    }

    public interface SelectionListener {
        void onSelectionChanged(NodePoint selectedNode, LinkSegment selectedLink);
    }

    private static final Color BACKGROUND = new Color(0x0F1115);
    private static final Color LINK_COLOR = new Color(0x808A9D);
    private static final Color NODE_COLOR_DEFAULT = new Color(0xC7CEDD);
    private static final Color SELECTED_LINK = new Color(0xF05D23);
    private static final Color SELECTED_NODE = new Color(0x1FA2FF);
    private static final int OSM_MAX_VIEWPORT_TILES = 512;
    private static final double VIEWPORT_MARGIN_PIXELS = 80.0;
    private static final double BIDIRECTIONAL_OFFSET_PIXELS = 3.2;

    private final Map<String, NodePoint> nodes = new LinkedHashMap<>();
    private final Map<String, LinkSegment> links = new LinkedHashMap<>();
    private final Set<String> directedConnectionKeys = new LinkedHashSet<>();
    private final Set<String> availableLinkModes = new LinkedHashSet<>();
    private final Set<String> visibleLinkModes = new LinkedHashSet<>();
    private final List<String> pendingLinkNodes = new ArrayList<>(2);
    private final List<PickableLink> renderedPickLinks = new ArrayList<>();
    private final OsmTileCache osmTileCache;
    private final CoordinateTransformation lv95ToWgs84Transform;
    private final CoordinateTransformation wgs84ToLv95Transform;
    private final ExecutorService mutationWorker;

    private SelectionListener selectionListener;

    private String selectedNodeId;
    private String selectedLinkId;

    private boolean createNodeArmed;
    private boolean createLinkArmed;

    private double minX;
    private double minY;
    private double maxX;
    private double maxY;
    private double baseScale = 1.0;
    private double zoom = 1.0;
    private double panX = 20.0;
    private double panY = 20.0;
    private boolean fitInitialized;
    private Point panDragStart;

    private CoordinateSystem coordinateSystem;
    private final Timer selectionAnimationTimer;
    private Color linkColor = LINK_COLOR;
    private Color nodeColor = NODE_COLOR_DEFAULT;

    public NetworkEditorPanel(NetworkData data, Path cacheDir) {
        this.osmTileCache = new OsmTileCache((cacheDir == null ? Path.of("cache") : cacheDir).toAbsolutePath().normalize());
        this.lv95ToWgs84Transform = tryCreateTransformation("EPSG:2056", "EPSG:4326");
        this.wgs84ToLv95Transform = tryCreateTransformation("EPSG:4326", "EPSG:2056");
        this.mutationWorker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "network-editor-worker");
            thread.setDaemon(true);
            return thread;
        });

        nodes.putAll(data.getNodes());
        links.putAll(data.getLinks());
        rebuildDirectionalIndex();
        rebuildAvailableModes();
        recomputeBounds();
        this.coordinateSystem = detectCoordinateSystem(minX, minY, maxX, maxY);

        setPreferredSize(new Dimension(1200, 850));
        setBackground(BACKGROUND);

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON2) {
                    panDragStart = e.getPoint();
                }
                if (e.isPopupTrigger()) {
                    showContextMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON2) {
                    panDragStart = null;
                }
                if (e.isPopupTrigger()) {
                    showContextMenu(e);
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (panDragStart != null) {
                    Point current = e.getPoint();
                    int dx = current.x - panDragStart.x;
                    int dy = current.y - panDragStart.y;
                    panX += dx;
                    panY -= dy;
                    panDragStart = current;
                    repaint();
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }

                ensureFitted();

                if (createNodeArmed) {
                    createNodeAt(screenToWorld(e.getX(), e.getY()));
                    createNodeArmed = false;
                    repaint();
                    return;
                }

                if (createLinkArmed) {
                    handleCreateLinkSelection(e.getPoint());
                    return;
                }

                selectAt(e.getPoint());
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                ensureFitted();
                Point2D.Double anchorWorld = screenToWorld(e.getX(), e.getY());
                double factor = e.getWheelRotation() < 0 ? 1.12 : 0.89;
                zoom = Math.max(0.2, Math.min(80.0, zoom * factor));

                Point2D.Double after = worldToScreen(anchorWorld.x, anchorWorld.y);
                panX += e.getX() - after.x;
                panY += after.y - e.getY();
                repaint();
            }
        };

        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);

        selectionAnimationTimer = new Timer(33, e -> {
            if (selectedLinkId != null) {
                repaint();
            }
        });
        selectionAnimationTimer.start();
    }

    public void setSelectionListener(SelectionListener selectionListener) {
        this.selectionListener = selectionListener;
        notifySelectionChanged();
    }

    public void armCreateNode() {
        createNodeArmed = true;
        createLinkArmed = false;
        pendingLinkNodes.clear();
        repaint();
    }

    public void armCreateLink() {
        createLinkArmed = true;
        createNodeArmed = false;
        pendingLinkNodes.clear();
        repaint();
    }

    public boolean hasGeoCoordinates() {
        return resolvedCoordinateSystem() != CoordinateSystem.NONE;
    }

    public CoordinateSystem getCoordinateSystem() {
        return coordinateSystem;
    }

    public void setCoordinateSystem(CoordinateSystem coordinateSystem) {
        this.coordinateSystem = coordinateSystem == null ? CoordinateSystem.AUTO : coordinateSystem;
        repaint();
    }

    public List<String> availableLinkModes() {
        return availableLinkModes.stream().sorted().toList();
    }

    public Set<String> visibleLinkModes() {
        return new LinkedHashSet<>(visibleLinkModes);
    }

    public void setVisibleLinkModes(Set<String> modes) {
        visibleLinkModes.clear();
        if (modes != null) {
            for (String mode : modes) {
                String normalized = normalizeMode(mode);
                if (availableLinkModes.contains(normalized)) {
                    visibleLinkModes.add(normalized);
                }
            }
        }
        if (visibleLinkModes.isEmpty()) {
            visibleLinkModes.addAll(availableLinkModes);
        }
        repaint();
    }

    public Color getLinkColor() {
        return linkColor;
    }

    public void setLinkColor(Color linkColor) {
        if (linkColor != null) {
            this.linkColor = linkColor;
            repaint();
        }
    }

    public Color getNodeColor() {
        return nodeColor;
    }

    public void setNodeColor(Color nodeColor) {
        if (nodeColor != null) {
            this.nodeColor = nodeColor;
            repaint();
        }
    }

    public SaveSummary saveNetwork(Path outputFile) {
        try {
            Files.createDirectories(outputFile.toAbsolutePath().normalize().getParent());
        } catch (Exception ignored) {
            // Parent may be null or already exists.
        }

        Network network = NetworkUtils.createNetwork();
        for (NodePoint node : nodes.values()) {
            Node matsimNode = NetworkUtils.createAndAddNode(
                    network,
                    Id.createNodeId(node.id()),
                    new Coord(node.x(), node.y())
            );
            matsimNode.getAttributes().putAttribute("source", "network-editor");
        }

        for (LinkSegment link : links.values()) {
            Node from = network.getNodes().get(Id.createNodeId(link.fromNodeId()));
            Node to = network.getNodes().get(Id.createNodeId(link.toNodeId()));
            if (from == null || to == null) {
                continue;
            }

            double capacity = parseDouble(link.attributes().get("capacity"), 900.0);
            Link matsimLink = NetworkUtils.createAndAddLink(
                    network,
                    Id.createLinkId(link.id()),
                    from,
                    to,
                    Math.max(0.01, link.length()),
                    Math.max(0.01, link.freeSpeed()),
                    Math.max(0.0, capacity),
                    Math.max(0.1, link.lanes())
            );
            matsimLink.setAllowedModes(new LinkedHashSet<>(link.allowedModes()));
            link.attributes().forEach((key, value) -> {
                if (key != null && value != null) {
                    matsimLink.getAttributes().putAttribute(key, value);
                }
            });
        }

        new NetworkWriter(network).write(outputFile.toAbsolutePath().normalize().toString());
        return new SaveSummary(nodes.size(), links.size(), outputFile.toAbsolutePath().normalize());
    }

    public boolean hasSelectedNode() {
        return selectedNodeId != null && nodes.containsKey(selectedNodeId);
    }

    public boolean hasSelectedLink() {
        return selectedLinkId != null && links.containsKey(selectedLinkId);
    }

    public boolean deleteSelectedLink() {
        if (!hasSelectedLink()) {
            return false;
        }

        links.remove(selectedLinkId);
        scheduleDerivedRebuild();
        selectedLinkId = null;
        notifySelectionChanged();
        repaint();
        return true;
    }

    public int deleteSelectedNodeAndConnectedLinks() {
        if (!hasSelectedNode()) {
            return -1;
        }

        String removedNodeId = selectedNodeId;
        selectedNodeId = null;
        nodes.remove(removedNodeId);

        int removedLinks = 0;
        List<String> toRemove = new ArrayList<>();
        for (LinkSegment link : links.values()) {
            if (removedNodeId.equals(link.fromNodeId()) || removedNodeId.equals(link.toNodeId())) {
                toRemove.add(link.id());
            }
        }
        for (String id : toRemove) {
            links.remove(id);
            removedLinks++;
        }
        scheduleDerivedRebuild();
        if (selectedLinkId != null && !links.containsKey(selectedLinkId)) {
            selectedLinkId = null;
        }

        recomputeBounds();
        notifySelectionChanged();
        repaint();
        return removedLinks;
    }

    public boolean editSelectedLink() {
        if (!hasSelectedLink()) {
            return false;
        }

        LinkSegment existing = links.get(selectedLinkId);
        String initialCapacity = existing.attributes().getOrDefault("capacity", "900.0");
        JTextField lengthField = new JTextField(String.format(Locale.ROOT, "%.2f", existing.length()));
        JTextField freeSpeedField = new JTextField(String.format(Locale.ROOT, "%.6f", existing.freeSpeed()));
        JTextField lanesField = new JTextField(String.format(Locale.ROOT, "%.3f", existing.lanes()));
        JTextField capacityField = new JTextField(initialCapacity);
        JTextField typeField = new JTextField(existing.attributes().getOrDefault("type", ""));
        JTextField onewayField = new JTextField(existing.attributes().getOrDefault("oneway", ""));
        JTextArea attrsPatchArea = new JTextArea(4, 28);
        attrsPatchArea.setText("# extra key=value lines to add/update");

        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(new JLabel("Link ID: " + existing.id()));
        panel.add(new JLabel("Direction: " + existing.fromNodeId() + " -> " + existing.toNodeId()));
        panel.add(new JLabel("Length (m)"));
        panel.add(lengthField);
        panel.add(new JLabel("Free speed (m/s)"));
        panel.add(freeSpeedField);
        panel.add(new JLabel("Lanes"));
        panel.add(lanesField);
        panel.add(new JLabel("Capacity (veh/h)"));
        panel.add(capacityField);
        panel.add(new JLabel("Type (optional)"));
        panel.add(typeField);
        panel.add(new JLabel("Oneway (optional)"));
        panel.add(onewayField);
        panel.add(new JLabel("Extra attributes patch (key=value, optional)"));
        panel.add(attrsPatchArea);

        int choice = JOptionPane.showConfirmDialog(this, panel, "Quick Edit Link", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return false;
        }

        double length;
        double freeSpeed;
        double lanes;
        double capacity;
        try {
            length = Math.max(0.01, Double.parseDouble(trimToEmpty(lengthField.getText())));
            freeSpeed = Math.max(0.01, Double.parseDouble(trimToEmpty(freeSpeedField.getText())));
            lanes = Math.max(0.1, Double.parseDouble(trimToEmpty(lanesField.getText())));
            capacity = Math.max(0.0, Double.parseDouble(trimToEmpty(capacityField.getText())));
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Length, free speed, lanes and capacity must be numeric.", "Invalid Link", JOptionPane.WARNING_MESSAGE);
            return false;
        }

        Map<String, String> attrs = new LinkedHashMap<>(existing.attributes());
        attrs.put("capacity", Double.toString(capacity));
        String type = trimToEmpty(typeField.getText());
        if (type.isEmpty()) {
            attrs.remove("type");
        } else {
            attrs.put("type", type);
        }

        String oneway = trimToEmpty(onewayField.getText());
        if (oneway.isEmpty()) {
            attrs.remove("oneway");
        } else {
            attrs.put("oneway", oneway);
        }

        Map<String, String> patched = parseAttributes(attrsPatchArea.getText());
        patched.forEach((key, value) -> {
            if (key.startsWith("#")) {
                return;
            }
            attrs.put(key, value);
        });

        LinkSegment edited = new LinkSegment(
                existing.id(),
                existing.fromNodeId(),
                existing.toNodeId(),
                existing.fromX(),
                existing.fromY(),
                existing.toX(),
                existing.toY(),
                length,
                freeSpeed,
                lanes,
                existing.allowedModes(),
                attrs
        );

        links.put(edited.id(), edited);
        selectedLinkId = edited.id();
        selectedNodeId = null;
        notifySelectionChanged();
        repaint();
        return true;
    }

    public void disposeResources() {
        selectionAnimationTimer.stop();
        osmTileCache.close();
        mutationWorker.shutdownNow();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        ensureFitted();

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(BACKGROUND);
        g2.fillRect(0, 0, getWidth(), getHeight());

        ViewportBounds viewportBounds = computeViewportBounds(VIEWPORT_MARGIN_PIXELS);
        Map<String, Double> nodeWidthCaps = new HashMap<>();

        if (resolvedCoordinateSystem() != CoordinateSystem.NONE) {
            drawOsmBackground(g2);
        }

        drawLinks(g2, viewportBounds, nodeWidthCaps);
        drawNodes(g2, viewportBounds, nodeWidthCaps);
        drawModeStatus(g2);

        g2.dispose();
    }

    private void drawOsmBackground(Graphics2D g2) {
        CoordinateSystem crs = resolvedCoordinateSystem();
        if (crs == CoordinateSystem.NONE) {
            return;
        }

        Point2D.Double topLeft = screenToWorld(0, 0);
        Point2D.Double bottomRight = screenToWorld(getWidth(), getHeight());

        Point2D.Double topLeftLonLat = worldToLonLat(topLeft.x, topLeft.y, crs);
        Point2D.Double bottomRightLonLat = worldToLonLat(bottomRight.x, bottomRight.y, crs);
        if (topLeftLonLat == null || bottomRightLonLat == null) {
            return;
        }

        double minLon = Math.min(topLeftLonLat.x, bottomRightLonLat.x);
        double maxLon = Math.max(topLeftLonLat.x, bottomRightLonLat.x);
        double minLat = Math.max(-85.0511, Math.min(topLeftLonLat.y, bottomRightLonLat.y));
        double maxLat = Math.min(85.0511, Math.max(topLeftLonLat.y, bottomRightLonLat.y));

        if (maxLon <= minLon || maxLat <= minLat) {
            return;
        }

        int zoomLevel = estimateTileZoom(minLon, maxLon);
        double tileXMin = lonToTileX(minLon, zoomLevel);
        double tileXMax = lonToTileX(maxLon, zoomLevel);
        double tileYMin = latToTileY(maxLat, zoomLevel);
        double tileYMax = latToTileY(minLat, zoomLevel);

        int xStart = (int) Math.floor(tileXMin);
        int xEnd = (int) Math.floor(tileXMax);
        int yStart = (int) Math.floor(tileYMin);
        int yEnd = (int) Math.floor(tileYMax);

        long tileCount = (long) (xEnd - xStart + 1) * (long) (yEnd - yStart + 1);
        if (tileCount <= 0 || tileCount > OSM_MAX_VIEWPORT_TILES) {
            return;
        }

        List<BufferedTile> readyTiles = new ArrayList<>((xEnd - xStart + 1) * (yEnd - yStart + 1));
        boolean allTilesReady = true;

        for (int tileX = xStart; tileX <= xEnd; tileX++) {
            for (int tileY = yStart; tileY <= yEnd; tileY++) {
                BufferedTile tile = new BufferedTile(tileX, tileY, osmTileCache.getTile(zoomLevel, tileX, tileY, this));
                if (tile.image == null) {
                    allTilesReady = false;
                    continue;
                }

                readyTiles.add(tile);
            }
        }

        if (!allTilesReady || readyTiles.isEmpty()) {
            return;
        }

        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        for (BufferedTile tile : readyTiles) {
                double lonLeft = tileXToLon(tile.tileX, zoomLevel);
                double lonRight = tileXToLon(tile.tileX + 1, zoomLevel);
                double latTop = tileYToLat(tile.tileY, zoomLevel);
                double latBottom = tileYToLat(tile.tileY + 1, zoomLevel);

                Point2D.Double worldTopLeft = lonLatToWorld(lonLeft, latTop, crs);
                Point2D.Double worldBottomRight = lonLatToWorld(lonRight, latBottom, crs);
                if (worldTopLeft == null || worldBottomRight == null) {
                    continue;
                }

                Point2D.Double a = worldToScreen(worldTopLeft.x, worldTopLeft.y);
                Point2D.Double b = worldToScreen(worldBottomRight.x, worldBottomRight.y);

                int x1 = (int) Math.floor(Math.min(a.x, b.x));
                int y1 = (int) Math.floor(Math.min(a.y, b.y));
                int x2 = (int) Math.ceil(Math.max(a.x, b.x));
                int y2 = (int) Math.ceil(Math.max(a.y, b.y));
                int w = Math.max(1, x2 - x1 + 1);
                int h = Math.max(1, y2 - y1 + 1);
                g2.drawImage(tile.image, x1, y1, w, h, null);
        }

        g2.setColor(new Color(0, 0, 0, 38));
        g2.fillRect(0, 0, getWidth(), getHeight());
    }

    private void drawLinks(Graphics2D g2, ViewportBounds viewportBounds, Map<String, Double> nodeWidthCaps) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        renderedPickLinks.clear();

        for (LinkSegment link : links.values()) {
            if (!intersectsViewport(link, viewportBounds)) {
                continue;
            }
            if (!isLinkModeVisible(link)) {
                continue;
            }

            Point2D.Double from = worldToScreen(link.fromX(), link.fromY());
            Point2D.Double to = worldToScreen(link.toX(), link.toY());
            double strokeWidth = linkStrokeWidth(link);
            double offsetSign = bidirectionalOffsetSign(link);
            Point2D.Double shiftedFrom = applyPerpendicularOffset(from, to, offsetSign * BIDIRECTIONAL_OFFSET_PIXELS);
            Point2D.Double shiftedTo = applyPerpendicularOffset(to, from, offsetSign * BIDIRECTIONAL_OFFSET_PIXELS);
            renderedPickLinks.add(new PickableLink(link.id(), shiftedFrom.x, shiftedFrom.y, shiftedTo.x, shiftedTo.y));

            nodeWidthCaps.merge(link.fromNodeId(), strokeWidth, Math::max);
            nodeWidthCaps.merge(link.toNodeId(), strokeWidth, Math::max);

            if (link.id().equals(selectedLinkId)) {
                g2.setColor(SELECTED_LINK);
                g2.setStroke(new BasicStroke((float) (strokeWidth + 1.3), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine((int) Math.round(shiftedFrom.x), (int) Math.round(shiftedFrom.y), (int) Math.round(shiftedTo.x), (int) Math.round(shiftedTo.y));
                drawSelectedLinkFlow(g2, shiftedFrom, shiftedTo);
            } else {
                g2.setColor(linkColor);
                g2.setStroke(new BasicStroke((float) strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine((int) Math.round(shiftedFrom.x), (int) Math.round(shiftedFrom.y), (int) Math.round(shiftedTo.x), (int) Math.round(shiftedTo.y));
            }
        }
    }

    private void drawSelectedLinkFlow(Graphics2D g2, Point2D.Double from, Point2D.Double to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double length = Math.hypot(dx, dy);
        if (length < 2.0) {
            return;
        }

        double ux = dx / length;
        double uy = dy / length;

        double spacing = 26.0;
        double pulseLength = 13.0;
        double head = (System.nanoTime() / 17_000_000.0) % spacing;

        g2.setStroke(new BasicStroke(2.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (double tip = head; tip <= length + pulseLength; tip += spacing) {
            double start = Math.max(0.0, tip - pulseLength);
            double end = Math.min(length, tip);
            if (end <= start) {
                continue;
            }

            double sx = from.x + ux * start;
            double sy = from.y + uy * start;
            double ex = from.x + ux * end;
            double ey = from.y + uy * end;

            double pulseRatio = (end - start) / pulseLength;
            int alpha = (int) Math.round(90 + 140 * Math.max(0.0, Math.min(1.0, pulseRatio)));
            g2.setColor(new Color(0x55, 0xE4, 0xFF, alpha));
            g2.drawLine((int) Math.round(sx), (int) Math.round(sy), (int) Math.round(ex), (int) Math.round(ey));
        }
    }

    private void drawNodes(Graphics2D g2, ViewportBounds viewportBounds, Map<String, Double> nodeWidthCaps) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        for (NodePoint node : nodes.values()) {
            if (!viewportBounds.contains(node.x(), node.y())) {
                continue;
            }

            Point2D.Double p = worldToScreen(node.x(), node.y());
            boolean selected = node.id().equals(selectedNodeId);
            boolean pending = pendingLinkNodes.contains(node.id());
            if (!selected && !pending && !nodeWidthCaps.containsKey(node.id())) {
                continue;
            }

            double linkWidthCap = Math.max(1.0, nodeWidthCaps.getOrDefault(node.id(), 2.2));
            double preferredRadius = selected ? linkWidthCap * 0.95 : (pending ? linkWidthCap * 0.75 : linkWidthCap * 0.55);
            double radius = Math.max(0.9, Math.min(linkWidthCap, preferredRadius));
            g2.setColor(selected ? SELECTED_NODE : (pending ? new Color(0xFFD166) : nodeColor));
            g2.fillOval(
                    (int) Math.round(p.x - radius),
                    (int) Math.round(p.y - radius),
                    (int) Math.round(radius * 2),
                    (int) Math.round(radius * 2)
            );

            if (selected || pending) {
                g2.setColor(new Color(0xE5ECF8));
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
                g2.drawString(node.id(), (int) Math.round(p.x + 6), (int) Math.round(p.y - 6));
            }
        }
    }

    private void drawModeStatus(Graphics2D g2) {
        if (!createNodeArmed && !createLinkArmed) {
            return;
        }

        String text;
        if (createNodeArmed) {
            text = "Create Node: click anywhere on the map.";
        } else {
            text = pendingLinkNodes.isEmpty()
                    ? "Create Link: click first node."
                    : "Create Link: click second node.";
        }

        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillRoundRect(12, 12, 330, 30, 10, 10);
        g2.setColor(new Color(0xECF2FF));
        g2.drawString(text, 22, 32);
    }

    private void showContextMenu(MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem addNode = new JMenuItem("Add Node Here");
        addNode.addActionListener(ignored -> createNodeAt(screenToWorld(e.getX(), e.getY())));
        menu.add(addNode);
        menu.show(this, e.getX(), e.getY());
    }

    private void handleCreateLinkSelection(Point click) {
        NodePoint node = findNearestNode(click, 10.0);
        if (node == null) {
            JOptionPane.showMessageDialog(this, "Click on a node to build the new link.", "Node Required", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        if (!pendingLinkNodes.isEmpty() && pendingLinkNodes.getLast().equals(node.id())) {
            return;
        }

        pendingLinkNodes.add(node.id());
        selectedNodeId = node.id();
        selectedLinkId = null;
        notifySelectionChanged();
        repaint();

        if (pendingLinkNodes.size() < 2) {
            return;
        }

        NodePoint from = nodes.get(pendingLinkNodes.get(0));
        NodePoint to = nodes.get(pendingLinkNodes.get(1));
        pendingLinkNodes.clear();
        createLinkArmed = false;
        if (from == null || to == null || from.id().equals(to.id())) {
            return;
        }

        createLinkBetween(from, to);
    }

    private void createNodeAt(Point2D.Double world) {
        JTextField idField = new JTextField();
        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(new JLabel("Node ID", SwingConstants.LEFT));
        panel.add(idField);

        int choice = JOptionPane.showConfirmDialog(this, panel, "Create Node", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }

        String nodeId = idField.getText() == null ? "" : idField.getText().trim();
        if (nodeId.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Node ID cannot be blank.", "Invalid Node ID", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (nodes.containsKey(nodeId)) {
            JOptionPane.showMessageDialog(this, "A node with this ID already exists.", "Duplicate Node ID", JOptionPane.WARNING_MESSAGE);
            return;
        }

        NodePoint node = new NodePoint(nodeId, world.x, world.y);
        nodes.put(node.id(), node);
        selectedNodeId = node.id();
        selectedLinkId = null;
        if (nodes.size() == 1) {
            minX = node.x();
            minY = node.y();
            maxX = node.x();
            maxY = node.y();
        } else {
            minX = Math.min(minX, node.x());
            minY = Math.min(minY, node.y());
            maxX = Math.max(maxX, node.x());
            maxY = Math.max(maxY, node.y());
        }
        fitInitialized = false;
        notifySelectionChanged();
        repaint();
    }

    private void createLinkBetween(NodePoint from, NodePoint to) {
        double defaultLength = distance(from.x(), from.y(), to.x(), to.y());

        JTextField idField = new JTextField();
        JTextField lengthField = new JTextField(String.format(Locale.ROOT, "%.2f", defaultLength));
        JTextField freeSpeedField = new JTextField("13.89");
        JTextField lanesField = new JTextField("1.0");
        JTextField modesField = new JTextField("car");
        JTextField capacityField = new JTextField("900.0");
        JTextField typeField = new JTextField("");
        JTextArea customAttrs = new JTextArea(5, 28);
        customAttrs.setText("name=\nallowed_turns=");

        JPanel panel = new JPanel(new GridLayout(0, 1, 4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(new JLabel("From: " + from.id() + "   To: " + to.id()));
        panel.add(new JLabel("Link ID"));
        panel.add(idField);
        panel.add(new JLabel("Length (m)"));
        panel.add(lengthField);
        panel.add(new JLabel("Free speed (m/s)"));
        panel.add(freeSpeedField);
        panel.add(new JLabel("Lanes"));
        panel.add(lanesField);
        panel.add(new JLabel("Modes (comma-separated)"));
        panel.add(modesField);
        panel.add(new JLabel("Capacity (veh/h)"));
        panel.add(capacityField);
        panel.add(new JLabel("Road type (optional)"));
        panel.add(typeField);
        panel.add(new JLabel("Custom attributes (key=value, one per line)"));
        panel.add(customAttrs);

        int choice = JOptionPane.showConfirmDialog(this, panel, "Create Link", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) {
            return;
        }

        String linkId = trimToEmpty(idField.getText());
        if (linkId.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Link ID cannot be blank.", "Invalid Link", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (links.containsKey(linkId)) {
            JOptionPane.showMessageDialog(this, "A link with this ID already exists.", "Duplicate Link ID", JOptionPane.WARNING_MESSAGE);
            return;
        }

        double length;
        double freeSpeed;
        double lanes;
        double capacity;
        try {
            length = Math.max(0.01, Double.parseDouble(trimToEmpty(lengthField.getText())));
            freeSpeed = Math.max(0.01, Double.parseDouble(trimToEmpty(freeSpeedField.getText())));
            lanes = Math.max(0.1, Double.parseDouble(trimToEmpty(lanesField.getText())));
            capacity = Math.max(0.0, Double.parseDouble(trimToEmpty(capacityField.getText())));
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Length, free speed, lanes and capacity must be numeric.", "Invalid Link", JOptionPane.WARNING_MESSAGE);
            return;
        }

        Set<String> modes = parseModes(trimToEmpty(modesField.getText()));
        Map<String, String> attrs = parseAttributes(customAttrs.getText());
        attrs.put("capacity", Double.toString(capacity));
        String type = trimToEmpty(typeField.getText());
        if (!type.isEmpty()) {
            attrs.put("type", type);
        }

        LinkSegment created = new LinkSegment(
                linkId,
                from.id(),
                to.id(),
                from.x(),
                from.y(),
                to.x(),
                to.y(),
                length,
                freeSpeed,
                lanes,
                modes,
                attrs
        );

        links.put(linkId, created);
        scheduleDerivedRebuild();
        selectedLinkId = linkId;
        selectedNodeId = null;
        notifySelectionChanged();
        repaint();
    }

    private void selectAt(Point click) {
        NodePoint nearestNode = findNearestNode(click, 10.0);
        if (nearestNode != null) {
            selectedNodeId = nearestNode.id();
            selectedLinkId = null;
            notifySelectionChanged();
            repaint();
            return;
        }

        LinkSegment nearestLink = findNearestLink(click, 8.0);
        if (nearestLink != null) {
            selectedLinkId = nearestLink.id();
            selectedNodeId = null;
        } else {
            selectedLinkId = null;
            selectedNodeId = null;
        }
        notifySelectionChanged();
        repaint();
    }

    private NodePoint findNearestNode(Point click, double thresholdPixels) {
        NodePoint best = null;
        double bestDistance = thresholdPixels;
        for (NodePoint node : nodes.values()) {
            Point2D.Double p = worldToScreen(node.x(), node.y());
            double d = distance(click.x, click.y, p.x, p.y);
            if (d <= bestDistance) {
                bestDistance = d;
                best = node;
            }
        }
        return best;
    }

    private LinkSegment findNearestLink(Point click, double thresholdPixels) {
        List<PickCandidate> candidates = new ArrayList<>();

        if (!renderedPickLinks.isEmpty()) {
            for (PickableLink pickable : renderedPickLinks) {
                double d = pointToSegmentDistance(click.x, click.y, pickable.ax(), pickable.ay(), pickable.bx(), pickable.by());
                if (d <= thresholdPixels) {
                    candidates.add(new PickCandidate(pickable.linkId(), d));
                }
            }
        } else {
            for (LinkSegment link : links.values()) {
                Point2D.Double a = worldToScreen(link.fromX(), link.fromY());
                Point2D.Double b = worldToScreen(link.toX(), link.toY());
                double d = pointToSegmentDistance(click.x, click.y, a.x, a.y, b.x, b.y);
                if (d <= thresholdPixels) {
                    candidates.add(new PickCandidate(link.id(), d));
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort((a, b) -> Double.compare(a.distance(), b.distance()));
        PickCandidate chosen = candidates.getFirst();

        if (selectedLinkId != null && selectedLinkId.equals(chosen.linkId()) && candidates.size() > 1) {
            for (int i = 1; i < candidates.size(); i++) {
                PickCandidate alternative = candidates.get(i);
                if (!selectedLinkId.equals(alternative.linkId()) && alternative.distance() <= chosen.distance() + 1.25) {
                    chosen = alternative;
                    break;
                }
            }
        }

        return links.get(chosen.linkId());
    }

    private void notifySelectionChanged() {
        if (selectionListener == null) {
            return;
        }
        selectionListener.onSelectionChanged(nodes.get(selectedNodeId), links.get(selectedLinkId));
    }

    private void ensureFitted() {
        if (fitInitialized) {
            return;
        }

        if (getWidth() <= 40 || getHeight() <= 40) {
            return;
        }

        double width = Math.max(1.0, maxX - minX);
        double height = Math.max(1.0, maxY - minY);
        double sx = (getWidth() - 40.0) / width;
        double sy = (getHeight() - 40.0) / height;
        baseScale = Math.max(0.000001, Math.min(sx, sy));

        zoom = 1.0;
        panX = 20.0;
        panY = 20.0;
        fitInitialized = true;
    }

    private Point2D.Double worldToScreen(double x, double y) {
        double sx = (x - minX) * baseScale * zoom + panX;
        double sy = getHeight() - ((y - minY) * baseScale * zoom + panY);
        return new Point2D.Double(sx, sy);
    }

    private Point2D.Double screenToWorld(double x, double y) {
        double worldX = ((x - panX) / (baseScale * zoom)) + minX;
        double worldY = ((getHeight() - y - panY) / (baseScale * zoom)) + minY;
        return new Point2D.Double(worldX, worldY);
    }

    private void recomputeBounds() {
        minX = Double.POSITIVE_INFINITY;
        minY = Double.POSITIVE_INFINITY;
        maxX = Double.NEGATIVE_INFINITY;
        maxY = Double.NEGATIVE_INFINITY;

        for (NodePoint node : nodes.values()) {
            minX = Math.min(minX, node.x());
            minY = Math.min(minY, node.y());
            maxX = Math.max(maxX, node.x());
            maxY = Math.max(maxY, node.y());
        }

        if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(maxX) || !Double.isFinite(maxY)) {
            minX = 0.0;
            minY = 0.0;
            maxX = 1.0;
            maxY = 1.0;
        }
        fitInitialized = false;
    }

    private static Set<String> parseModes(String raw) {
        Set<String> modes = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String mode = token.trim().toLowerCase(Locale.ROOT);
            if (!mode.isEmpty()) {
                modes.add(mode);
            }
        }
        if (modes.isEmpty()) {
            modes.add("car");
        }
        return modes;
    }

    private static Map<String, String> parseAttributes(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return map;
        }
        String[] lines = raw.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int idx = trimmed.indexOf('=');
            if (idx < 0) {
                map.put(trimmed, "");
            } else {
                String key = trimmed.substring(0, idx).trim();
                String value = trimmed.substring(idx + 1).trim();
                if (!key.isEmpty()) {
                    map.put(key, value);
                }
            }
        }
        return map;
    }

    private static String attributesToText(Map<String, String> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        attributes.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (!sb.isEmpty()) {
                        sb.append(System.lineSeparator());
                    }
                    sb.append(entry.getKey()).append('=').append(entry.getValue() == null ? "" : entry.getValue());
                });
        return sb.toString();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double pointToSegmentDistance(double px, double py, double ax, double ay, double bx, double by) {
        double vx = bx - ax;
        double vy = by - ay;
        double wx = px - ax;
        double wy = py - ay;

        double c1 = vx * wx + vy * wy;
        if (c1 <= 0) {
            return distance(px, py, ax, ay);
        }

        double c2 = vx * vx + vy * vy;
        if (c2 <= c1) {
            return distance(px, py, bx, by);
        }

        double b = c1 / c2;
        double projX = ax + b * vx;
        double projY = ay + b * vy;
        return distance(px, py, projX, projY);
    }

    private static double distance(double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return Math.hypot(dx, dy);
    }

    private static double linkStrokeWidth(LinkSegment link) {
        double lanes = Math.max(0.1, link.lanes());
        return 1.4 + Math.min(4.8, Math.sqrt(lanes) * 1.05);
    }

    private double bidirectionalOffsetSign(LinkSegment link) {
        if (!hasOppositeLink(link)) {
            return 0.0;
        }
        return link.fromNodeId().compareTo(link.toNodeId()) <= 0 ? 1.0 : -1.0;
    }

    private boolean hasOppositeLink(LinkSegment link) {
        return directedConnectionKeys.contains(directedEdgeKey(link.toNodeId(), link.fromNodeId()));
    }

    private void scheduleDerivedRebuild() {
        List<LinkSegment> snapshot = new ArrayList<>(links.values());
        mutationWorker.submit(() -> {
            Set<String> rebuiltDirected = new LinkedHashSet<>(Math.max(16, snapshot.size() * 2));
            Set<String> rebuiltAvailableModes = new LinkedHashSet<>();

            for (LinkSegment link : snapshot) {
                rebuiltDirected.add(directedEdgeKey(link.fromNodeId(), link.toNodeId()));
                for (String mode : link.allowedModes()) {
                    String normalized = normalizeMode(mode);
                    if (!normalized.isEmpty()) {
                        rebuiltAvailableModes.add(normalized);
                    }
                }
            }

            SwingUtilities.invokeLater(() -> {
                directedConnectionKeys.clear();
                directedConnectionKeys.addAll(rebuiltDirected);

                Set<String> previousVisible = new LinkedHashSet<>(visibleLinkModes);
                availableLinkModes.clear();
                availableLinkModes.addAll(rebuiltAvailableModes);

                visibleLinkModes.clear();
                if (previousVisible.isEmpty()) {
                    visibleLinkModes.addAll(availableLinkModes);
                } else {
                    for (String mode : previousVisible) {
                        if (availableLinkModes.contains(mode)) {
                            visibleLinkModes.add(mode);
                        }
                    }
                    if (visibleLinkModes.isEmpty()) {
                        visibleLinkModes.addAll(availableLinkModes);
                    }
                }
            });
        });
    }

    private void rebuildAvailableModes() {
        Set<String> previousVisible = new LinkedHashSet<>(visibleLinkModes);
        availableLinkModes.clear();
        for (LinkSegment link : links.values()) {
            for (String mode : link.allowedModes()) {
                String normalized = normalizeMode(mode);
                if (!normalized.isEmpty()) {
                    availableLinkModes.add(normalized);
                }
            }
        }

        visibleLinkModes.clear();
        if (previousVisible.isEmpty()) {
            visibleLinkModes.addAll(availableLinkModes);
            return;
        }

        for (String mode : previousVisible) {
            if (availableLinkModes.contains(mode)) {
                visibleLinkModes.add(mode);
            }
        }
        if (visibleLinkModes.isEmpty()) {
            visibleLinkModes.addAll(availableLinkModes);
        }
    }

    private boolean isLinkModeVisible(LinkSegment link) {
        if (availableLinkModes.isEmpty() || visibleLinkModes.isEmpty()) {
            return true;
        }
        if (link.allowedModes().isEmpty()) {
            return true;
        }

        for (String mode : link.allowedModes()) {
            if (visibleLinkModes.contains(normalizeMode(mode))) {
                return true;
            }
        }
        return false;
    }

    private void rebuildDirectionalIndex() {
        directedConnectionKeys.clear();
        for (LinkSegment link : links.values()) {
            directedConnectionKeys.add(directedEdgeKey(link.fromNodeId(), link.toNodeId()));
        }
    }

    private static String directedEdgeKey(String fromNodeId, String toNodeId) {
        return fromNodeId + "->" + toNodeId;
    }

    private static String normalizeMode(String mode) {
        return mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
    }

    private static Point2D.Double applyPerpendicularOffset(Point2D.Double from, Point2D.Double to, double offsetPixels) {
        if (offsetPixels == 0.0) {
            return from;
        }

        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double length = Math.hypot(dx, dy);
        if (length <= 1.0) {
            return from;
        }

        double nx = -dy / length;
        double ny = dx / length;
        return new Point2D.Double(from.x + nx * offsetPixels, from.y + ny * offsetPixels);
    }

    private ViewportBounds computeViewportBounds(double marginPixels) {
        Point2D.Double topLeft = screenToWorld(-marginPixels, -marginPixels);
        Point2D.Double bottomRight = screenToWorld(getWidth() + marginPixels, getHeight() + marginPixels);
        return new ViewportBounds(
                Math.min(topLeft.x, bottomRight.x),
                Math.min(topLeft.y, bottomRight.y),
                Math.max(topLeft.x, bottomRight.x),
                Math.max(topLeft.y, bottomRight.y)
        );
    }

    private static boolean intersectsViewport(LinkSegment link, ViewportBounds viewportBounds) {
        double minLinkX = Math.min(link.fromX(), link.toX());
        double maxLinkX = Math.max(link.fromX(), link.toX());
        double minLinkY = Math.min(link.fromY(), link.toY());
        double maxLinkY = Math.max(link.fromY(), link.toY());
        return maxLinkX >= viewportBounds.minX
                && minLinkX <= viewportBounds.maxX
                && maxLinkY >= viewportBounds.minY
                && minLinkY <= viewportBounds.maxY;
    }

    private static CoordinateSystem detectCoordinateSystem(double minX, double minY, double maxX, double maxY) {
        if (looksLikeLonLat(minX, minY, maxX, maxY)) {
            return CoordinateSystem.EPSG_4326;
        }
        if (looksLikeLv95(minX, minY, maxX, maxY)) {
            return CoordinateSystem.EPSG_2056;
        }
        return CoordinateSystem.NONE;
    }

    private CoordinateSystem resolvedCoordinateSystem() {
        if (coordinateSystem != null && coordinateSystem != CoordinateSystem.AUTO) {
            return coordinateSystem;
        }
        return detectCoordinateSystem(minX, minY, maxX, maxY);
    }

    private static boolean looksLikeLonLat(double minX, double minY, double maxX, double maxY) {
        return minX >= -180.0 && maxX <= 180.0 && minY >= -90.0 && maxY <= 90.0;
    }

    private static boolean looksLikeLv95(double minX, double minY, double maxX, double maxY) {
        return minX >= 2_200_000.0 && maxX <= 2_900_000.0
                && minY >= 1_000_000.0 && maxY <= 1_400_000.0;
    }

    private Point2D.Double worldToLonLat(double x, double y, CoordinateSystem crs) {
        return switch (crs) {
            case EPSG_4326 -> new Point2D.Double(x, y);
            case EPSG_2056 -> lv95ToWgs84(x, y);
            case AUTO, NONE -> null;
        };
    }

    private Point2D.Double lonLatToWorld(double lon, double lat, CoordinateSystem crs) {
        return switch (crs) {
            case EPSG_4326 -> new Point2D.Double(lon, lat);
            case EPSG_2056 -> wgs84ToLv95(lon, lat);
            case AUTO, NONE -> null;
        };
    }

    private Point2D.Double lv95ToWgs84(double easting, double northing) {
        if (lv95ToWgs84Transform != null) {
            Coord transformed = lv95ToWgs84Transform.transform(new Coord(easting, northing));
            return new Point2D.Double(transformed.getX(), transformed.getY());
        }
        return lv95ToWgs84Fallback(easting, northing);
    }

    private Point2D.Double wgs84ToLv95(double lon, double lat) {
        if (wgs84ToLv95Transform != null) {
            Coord transformed = wgs84ToLv95Transform.transform(new Coord(lon, lat));
            return new Point2D.Double(transformed.getX(), transformed.getY());
        }
        return wgs84ToLv95Fallback(lon, lat);
    }

    private static CoordinateTransformation tryCreateTransformation(String fromCrs, String toCrs) {
        try {
            return TransformationFactory.getCoordinateTransformation(fromCrs, toCrs);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Point2D.Double lv95ToWgs84Fallback(double easting, double northing) {
        double y = (easting - 2_600_000.0) / 1_000_000.0;
        double x = (northing - 1_200_000.0) / 1_000_000.0;

        double latitudeAux = 16.9023892
                + 3.238272 * x
                - 0.270978 * y * y
                - 0.002528 * x * x
                - 0.0447 * y * y * x
                - 0.0140 * x * x * x;

        double longitudeAux = 2.6779094
                + 4.728982 * y
                + 0.791484 * y * x
                + 0.1306 * y * x * x
                - 0.0436 * y * y * y;

        double lat = latitudeAux * 100.0 / 36.0;
        double lon = longitudeAux * 100.0 / 36.0;
        return new Point2D.Double(lon, lat);
    }

    private static Point2D.Double wgs84ToLv95Fallback(double lon, double lat) {
        double latSec = lat * 3600.0;
        double lonSec = lon * 3600.0;
        double latAux = (latSec - 169_028.66) / 10_000.0;
        double lonAux = (lonSec - 26_782.5) / 10_000.0;

        double e = 2_600_072.37
                + 211_455.93 * lonAux
                - 10_938.51 * lonAux * latAux
                - 0.36 * lonAux * latAux * latAux
                - 44.54 * lonAux * lonAux * lonAux;

        double n = 1_200_147.07
                + 308_807.95 * latAux
                + 3_745.25 * lonAux * lonAux
                + 76.63 * latAux * latAux
                - 194.56 * lonAux * lonAux * latAux
                + 119.79 * latAux * latAux * latAux;

        return new Point2D.Double(e, n);
    }

    private int estimateTileZoom(double minLon, double maxLon) {
        double lonSpan = Math.max(0.000001, maxLon - minLon);
        double raw = Math.log((getWidth() * 360.0) / (OsmTileCache.tileSize() * lonSpan)) / Math.log(2.0);
        int zoomLevel = (int) Math.round(raw + Math.log(zoom) / Math.log(2.0));
        return Math.max(3, Math.min(19, zoomLevel));
    }

    private static double lonToTileX(double lon, int zoomLevel) {
        return (lon + 180.0) / 360.0 * (1 << zoomLevel);
    }

    private static double latToTileY(double lat, int zoomLevel) {
        double latRad = Math.toRadians(lat);
        double n = Math.log(Math.tan(Math.PI / 4.0 + latRad / 2.0));
        return (1.0 - n / Math.PI) / 2.0 * (1 << zoomLevel);
    }

    private static double tileXToLon(double tileX, int zoomLevel) {
        return tileX / (1 << zoomLevel) * 360.0 - 180.0;
    }

    private static double tileYToLat(double tileY, int zoomLevel) {
        double n = Math.PI - 2.0 * Math.PI * tileY / (1 << zoomLevel);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    private record BufferedTile(int tileX, int tileY, java.awt.image.BufferedImage image) {
    }

    private record ViewportBounds(double minX, double minY, double maxX, double maxY) {
        private boolean contains(double x, double y) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }
    }

    private record PickableLink(String linkId, double ax, double ay, double bx, double by) {
    }

    private record PickCandidate(String linkId, double distance) {
    }
}
