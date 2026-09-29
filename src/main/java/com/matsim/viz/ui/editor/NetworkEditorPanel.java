package com.matsim.viz.ui.editor;

import com.matsim.viz.domain.LinkSegment;
import com.matsim.viz.ui.SpatialGrid;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import com.matsim.viz.ui.map.OsmBackground;
import com.matsim.viz.domain.NetworkData;
import com.matsim.viz.domain.NodePoint;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;

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
    private static final double VIEWPORT_MARGIN_PIXELS = 80.0;
    private static final double BIDIRECTIONAL_OFFSET_PIXELS = 3.2;

    private final Map<String, NodePoint> nodes;
    private final Map<String, LinkSegment> links;
    private final Set<String> directedConnectionKeys;
    private final Set<String> availableLinkModes;
    private final Set<String> visibleLinkModes = new LinkedHashSet<>();
    private final List<String> pendingLinkNodes = new ArrayList<>(2);
    private final List<PickableLink> renderedPickLinks = new ArrayList<>();
    private final Path mapCacheDir;
    private OsmBackground osmBackground;
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
    private Color linkColor = LINK_COLOR;
    private Color nodeColor = NODE_COLOR_DEFAULT;

    public record PreparedNetwork(NetworkData data, Map<String, NodePoint> nodes,
            Map<String, LinkSegment> links, Set<String> directions, Set<String> modes,
            Set<String> orphanNodes, SpatialGrid spatialIndex) { }

    /** Prepare off the UI thread, sharing immutable domain objects and the viewer's spatial index. */
    public static PreparedNetwork prepare(NetworkData data, SpatialGrid index) {
        Map<String, NodePoint> nodes = new EditableNetworkMap<>(data.getNodes());
        Map<String, LinkSegment> links = new EditableNetworkMap<>(data.getLinks());
        Set<String> directions = new LinkedHashSet<>(), modes = new LinkedHashSet<>();
        Set<String> orphans = new LinkedHashSet<>(nodes.keySet());
        for (LinkSegment link : links.values()) {
            directions.add(directedEdgeKey(link.fromNodeId(), link.toNodeId()));
            modes.addAll(link.allowedModes());
            orphans.remove(link.fromNodeId()); orphans.remove(link.toNodeId());
        }
        return new PreparedNetwork(data, nodes, links, directions, modes, orphans,
                index == null ? SpatialGrid.build(data) : index);
    }

    private final SpatialGrid spatialIndex;
    private final Set<String> changedLinks = new LinkedHashSet<>();
    private final Set<String> extraNodes;
    private final Set<String> viewportLinks = new LinkedHashSet<>();
    private final Set<String> viewportNodes = new LinkedHashSet<>();
    private final ArrayDeque<Edit> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private record Edit(Runnable undo, Runnable redo) { }
    private boolean unsavedChanges, disposed, mapEnabled, editingLocked;
    public void setEditingLocked(boolean locked) { editingLocked = locked; }
    private long mapGeneration;
    private BufferedImage drawingCache;
    private String drawingKey;
    private static final int CACHE_MARGIN = 256;
    private double cachePanX, cachePanY, cacheZoom;
    private Point pressPoint;
    private boolean dragMoved, zoomSettling;
    private final javax.swing.Timer zoomTimer = new javax.swing.Timer(120, e -> {
        zoomSettling = false; repaint();
    });

    public NetworkEditorPanel(NetworkData data, Path cacheDir) {
        this(prepare(data, null), cacheDir);
    }

    public NetworkEditorPanel(PreparedNetwork prepared, Path cacheDir) {

        this.mapCacheDir = (cacheDir == null ? Path.of("cache") : cacheDir).toAbsolutePath().normalize();
        this.mutationWorker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "network-editor-worker");
            thread.setDaemon(true);
            return thread;
        });

        nodes = prepared.nodes(); links = prepared.links();
        directedConnectionKeys = prepared.directions(); availableLinkModes = prepared.modes();
        visibleLinkModes.addAll(availableLinkModes);
        spatialIndex = prepared.spatialIndex(); extraNodes = prepared.orphanNodes();
        minX = prepared.data().getMinX(); minY = prepared.data().getMinY();
        maxX = prepared.data().getMaxX(); maxY = prepared.data().getMaxY();
        this.coordinateSystem = CoordinateSystem.EPSG_2056;

        zoomTimer.setRepeats(false);
        setPreferredSize(new Dimension(1200, 850));
        setBackground(BACKGROUND);

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (editingLocked) return;
                pressPoint = e.getPoint(); dragMoved = false;
                if (e.getButton() == MouseEvent.BUTTON2 || e.getButton() == MouseEvent.BUTTON1) {
                    panDragStart = e.getPoint();
                }
                if (e.isPopupTrigger()) {
                    showContextMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (editingLocked) return;
                panDragStart = null;
                repaint();
                if (e.isPopupTrigger()) {
                    showContextMenu(e);
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (editingLocked) return;
                if (panDragStart != null) {
                    if (!dragMoved && pressPoint != null && pressPoint.distance(e.getPoint()) < 4) return;
                    dragMoved = true;
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
                if (editingLocked) return;
                if (e.getButton() != MouseEvent.BUTTON1 || e.isShiftDown() || dragMoved) {
                    return;
                }

                ensureFitted();

                if (locationPicker != null) {
                    var picker=locationPicker;locationPicker=null;
                    picker.accept(screenToWorld(e.getX(),e.getY()));return;
                }
                if (!createNodeArmed && !createLinkArmed && selectTransitAt(e.getPoint())) return;
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
                if (editingLocked) return;
                ensureFitted();
                zoomSettling = true; zoomTimer.restart();
                Point2D.Double anchorWorld = screenToWorld(e.getX(), e.getY());
                double factor = Math.pow(1.15, -e.getPreciseWheelRotation());
                zoom = Math.max(0.05, Math.min(Math.max(2048, 24 / baseScale), zoom * factor));

                Point2D.Double after = worldToScreen(anchorWorld.x, anchorWorld.y);
                panX += e.getX() - after.x;
                panY += after.y - e.getY();
                repaint();
            }
        };

        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);


    }

    public record TransitStopMarker(String id, String name, double x, double y) { }
    public record TransitPath(String line, String route, List<String> links, List<TransitStopMarker> stops) { }
    private List<TransitPath> transitPaths = List.of();
    private String highlightedLine, highlightedRoute;
    private boolean showTransit;
    private java.util.function.BiConsumer<String,String> transitSelection;
    private java.util.function.Consumer<Point2D.Double> locationPicker;

    public void setTransitOverlay(List<TransitPath> paths, String line, String route, boolean visible,
            java.util.function.BiConsumer<String,String> onSelect) {
        transitPaths = List.copyOf(paths); highlightedLine=line; highlightedRoute=route;
        showTransit=visible; transitSelection=onSelect; drawingCache=null; repaint();
    }
    public void pickLocation(java.util.function.Consumer<Point2D.Double> callback) {
        cancelTool(); locationPicker=callback;
    }
    public String selectedLinkId() { return selectedLinkId; }
    public LinkSegment linkById(String id) { return links.get(id); }
    public Map<String,LinkSegment> linksById(java.util.Collection<String> ids) {
        Map<String,LinkSegment> found=new HashMap<>();for(String id:ids){var link=links.get(id);if(link!=null)found.put(id,link);}return found;
    }
    public void centreOn(double x, double y) {
        ensureFitted(); panX=getWidth()/2.0-(x-minX)*baseScale*zoom;
        panY=getHeight()/2.0-(y-minY)*baseScale*zoom; repaint();
    }
    private void drawTransitOverlay(Graphics2D g) {
        if (!showTransit) return;
        for (TransitPath path:transitPaths) {
            boolean chosen=path.line().equals(highlightedLine);
            g.setColor(chosen ? new Color(0xFFBC42) : new Color(80,180,240,110));
            g.setStroke(new BasicStroke(chosen?4f:1.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
            for (String id:path.links()) {
                LinkSegment link=links.get(id); if(link==null)continue;
                var a=worldToScreen(link.fromX(),link.fromY()); var b=worldToScreen(link.toX(),link.toY());
                if (g.getClipBounds()==null || g.getClipBounds().intersectsLine(a.x,a.y,b.x,b.y))
                    g.draw(new java.awt.geom.Line2D.Double(a,b));
            }
            if(chosen && (highlightedRoute==null || path.route().equals(highlightedRoute))) {
                int index=0;
                for(TransitStopMarker stop:path.stops()) {
                    var p=worldToScreen(stop.x(),stop.y());index++;
                    if(p.x < -CACHE_MARGIN || p.y < -CACHE_MARGIN || p.x > getWidth()+CACHE_MARGIN || p.y > getHeight()+CACHE_MARGIN)continue;
                    g.setColor(Color.WHITE);g.fillOval((int)p.x-5,(int)p.y-5,10,10);
                    g.setColor(new Color(0xEF7B26));g.drawOval((int)p.x-5,(int)p.y-5,10,10);
                    g.drawString(index+" "+(stop.name()==null?stop.id():stop.name()),(int)p.x+8,(int)p.y-8);
                }
            }
        }
    }
    private boolean selectTransitAt(Point click) {
        if(!showTransit || transitSelection==null)return false;
        TransitPath best=null;double distance=7;
        for(TransitPath path:transitPaths) for(String id:path.links()) {
            LinkSegment link=links.get(id);if(link==null)continue;
            var a=worldToScreen(link.fromX(),link.fromY());var b=worldToScreen(link.toX(),link.toY());
            double d=pointToSegmentDistance(click.x,click.y,a.x,a.y,b.x,b.y);
            if(d<distance){distance=d;best=path;}
        }
        if(best==null)return false;
        transitSelection.accept(best.line(),best.route());return true;
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
        return mapEnabled && resolvedCoordinateSystem() != CoordinateSystem.NONE;
    }

    public CoordinateSystem getCoordinateSystem() {
        return coordinateSystem;
    }

    public void setCoordinateSystem(CoordinateSystem coordinateSystem) {
        this.coordinateSystem = coordinateSystem == null ? CoordinateSystem.EPSG_2056 : coordinateSystem;
        prepareMap();
        drawingCache = null;
        repaint();
    }

    public void setMapBackgroundEnabled(boolean enabled) {
        mapEnabled = enabled;
        prepareMap();
    }

    private void prepareMap() {
        long generation = ++mapGeneration;
        if (osmBackground != null) osmBackground.close();
        osmBackground = null; drawingCache = null;
        CoordinateSystem crs = resolvedCoordinateSystem();
        if (!mapEnabled || crs == CoordinateSystem.NONE || disposed) { repaint(); return; }
        mutationWorker.submit(() -> {
            try {
                OsmBackground map = new OsmBackground(crs == CoordinateSystem.EPSG_2056 ? "EPSG:2056" : "EPSG:4326", mapCacheDir);
                SwingUtilities.invokeLater(() -> {
                    if (disposed || generation != mapGeneration) { map.close(); return; }
                    osmBackground = map; drawingCache = null; repaint();
                });
            } catch (RuntimeException ex) {
                SwingUtilities.invokeLater(() -> {
                    if (!disposed && generation == mapGeneration)
                        JOptionPane.showMessageDialog(this, ex.getMessage(), "Map unavailable", JOptionPane.WARNING_MESSAGE);
                });
            }
        });
    }

    public void setActive(boolean active) {
        drawingCache = null;
        renderedPickLinks.clear(); viewportLinks.clear(); viewportNodes.clear();
        if (!active) {
            ++mapGeneration;
            if (osmBackground != null) osmBackground.close();
            osmBackground = null;
        } else if (mapEnabled) prepareMap();
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

    public SaveSummary saveNetwork(Path outputFile) { return snapshotForSave().write(outputFile); }

    private static SaveSummary writeNetwork(Map<String, NodePoint> nodes, Map<String, LinkSegment> links, Path outputFile) {
        try {
            Files.createDirectories(outputFile.toAbsolutePath().normalize().getParent());
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Cannot create output directory", ex);
        }

        for (NodePoint node : nodes.values()) {
            if (!Double.isFinite(node.x()) || !Double.isFinite(node.y()))
                throw new IllegalArgumentException("Invalid coordinates at node " + node.id());
        }
        for (LinkSegment link : links.values()) {
            validateNumbers(link.length(), link.freeSpeed(), link.lanes(), Double.parseDouble(link.attributes().getOrDefault("capacity", "900")));
            if (!nodes.containsKey(link.fromNodeId()) || !nodes.containsKey(link.toNodeId()))
                throw new IllegalArgumentException("Link " + link.id() + " refers to a missing node");
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

        // Write beside the destination, then replace it only after the native writer succeeds.
        Path target = outputFile.toAbsolutePath().normalize();
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(), ".network-editor-", target.toString().endsWith(".gz") ? ".xml.gz" : ".xml");
            new NetworkWriter(network).write(temporary.toString());
            try {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Could not save network: " + ex.getMessage(), ex);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (java.io.IOException ignored) { }
        }
        return new SaveSummary(nodes.size(), links.size(), outputFile.toAbsolutePath().normalize());
    }

    public boolean hasUnsavedChanges() { return unsavedChanges; }
    public boolean canUndo() { return !undo.isEmpty(); }
    public boolean canRedo() { return !redo.isEmpty(); }
    public void markSaved() { unsavedChanges = false; notifySelectionChanged(); }

    private void remember(Runnable backwards, Runnable forwards) {
        undo.addLast(new Edit(backwards, forwards));
        if (undo.size() > 50) undo.removeFirst();
        redo.clear(); unsavedChanges = true; drawingCache = null;
    }

    public void undo() {
        if (undo.isEmpty()) return;
        Edit edit = undo.removeLast(); edit.undo().run(); redo.addLast(edit); afterHistoryChange();
    }

    public void redo() {
        if (redo.isEmpty()) return;
        Edit edit = redo.removeLast(); edit.redo().run(); undo.addLast(edit); afterHistoryChange();
    }

    private void afterHistoryChange() {
        unsavedChanges = true;
        selectedLinkId = null; selectedNodeId = null;
        scheduleDerivedRebuild(); notifySelectionChanged(); repaint();
    }

    public void fitNetwork() { recomputeBounds(); drawingCache = null; repaint(); }
    public void cancelTool() {
        locationPicker = null;
        createNodeArmed = false; createLinkArmed = false; pendingLinkNodes.clear();
        drawingCache = null; repaint();
    }

    /** Exact IDs avoid ambiguous matches; the chosen transport filters are expanded if necessary. */
    public boolean findById(String id, boolean node) {
        ensureFitted();
        double x, y;
        if (node) {
            NodePoint selected = nodes.get(id); if (selected == null) return false;
            selectedNodeId = id; selectedLinkId = null; extraNodes.add(id);
            x = selected.x(); y = selected.y();
        } else {
            LinkSegment selected = links.get(id); if (selected == null) return false;
            selectedLinkId = id; selectedNodeId = null;
            visibleLinkModes.addAll(selected.allowedModes());
            x = (selected.fromX()+selected.toX())/2; y = (selected.fromY()+selected.toY())/2;
        }
        zoom = Math.max(zoom, Math.min(2048, 2 / baseScale));
        panX = getWidth()/2.0 - (x-minX)*baseScale*zoom;
        panY = getHeight()/2.0 - (y-minY)*baseScale*zoom;
        notifySelectionChanged(); repaint(); return true;
    }

    public void updateLink(String id, double length, double speedKmh, double lanes, double capacity, Set<String> modes) {
        LinkSegment before = links.get(id);
        if (before == null) throw new IllegalArgumentException("Link does not exist: " + id);
        validateNumbers(length, speedKmh, lanes, capacity);
        if (modes == null || modes.isEmpty() || modes.stream().anyMatch(m -> m == null || m.isBlank()))
            throw new IllegalArgumentException("Choose at least one allowed mode");
        Map<String,String> attrs = new LinkedHashMap<>(before.attributes());
        attrs.put("capacity", Double.toString(capacity));
        LinkSegment after = new LinkSegment(id,before.fromNodeId(),before.toNodeId(),before.fromX(),before.fromY(),
                before.toX(),before.toY(),length,speedKmh/3.6,lanes,Set.copyOf(modes),attrs);
        replaceLink(before, after);
    }

    public void createReverseLink(String id) {
        if (!hasSelectedLink()) throw new IllegalArgumentException("Select the road direction to copy first");
        if (id == null || id.isBlank() || links.containsKey(id.trim()))
            throw new IllegalArgumentException("Enter a new, unique link ID");
        LinkSegment source = links.get(selectedLinkId);
        LinkSegment reverse = new LinkSegment(id.trim(),source.toNodeId(),source.fromNodeId(),
                source.toX(),source.toY(),source.fromX(),source.fromY(),source.length(),source.freeSpeed(),
                source.lanes(),source.allowedModes(),source.attributes());
        links.put(reverse.id(),reverse); changedLinks.add(reverse.id());
        remember(() -> links.remove(reverse.id()), () -> links.put(reverse.id(),reverse));
        selectedLinkId = reverse.id(); selectedNodeId = null;
        scheduleDerivedRebuild(); notifySelectionChanged(); repaint();
    }

    private void replaceLink(LinkSegment before, LinkSegment after) {
        links.put(after.id(), after); changedLinks.add(after.id());
        remember(() -> links.put(before.id(), before), () -> links.put(after.id(), after));
        visibleLinkModes.addAll(after.allowedModes());
        scheduleDerivedRebuild(); notifySelectionChanged(); repaint();
    }

    private static void validateNumbers(double length, double speed, double lanes, double capacity) {
        if (!Double.isFinite(length) || length <= 0 || !Double.isFinite(speed) || speed <= 0
                || !Double.isFinite(lanes) || lanes <= 0 || !Double.isFinite(capacity) || capacity < 0)
            throw new IllegalArgumentException("Length, speed and lanes must be positive and finite; capacity must be nonnegative.");
    }

    public record SaveSnapshot(Map<String, NodePoint> nodes, Map<String, LinkSegment> links) {
        public SaveSummary write(Path output) {
            return writeNetwork(nodes, links, output);
        }
    }
    /** Call on EDT, then write the immutable snapshot on a worker. */
    public SaveSnapshot snapshotForSave() {
        return new SaveSnapshot(new LinkedHashMap<>(nodes), new LinkedHashMap<>(links));
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

        LinkSegment removed = links.remove(selectedLinkId);
        changedLinks.add(removed.id()); extraNodes.add(removed.fromNodeId()); extraNodes.add(removed.toNodeId());
        remember(() -> links.put(removed.id(), removed), () -> links.remove(removed.id()));
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
        NodePoint removedNode = nodes.remove(removedNodeId);

        int removedLinks = 0;
        List<String> toRemove = new ArrayList<>();
        for (LinkSegment link : links.values()) {
            if (removedNodeId.equals(link.fromNodeId()) || removedNodeId.equals(link.toNodeId())) {
                toRemove.add(link.id());
            }
        }
        Map<String, LinkSegment> removed = new LinkedHashMap<>();
        for (String id : toRemove) {
            LinkSegment link = links.remove(id);
            removed.put(id, link); changedLinks.add(id);
            extraNodes.add(link.fromNodeId()); extraNodes.add(link.toNodeId());
            removedLinks++;
        }
        extraNodes.add(removedNodeId);
        remember(() -> { nodes.put(removedNodeId, removedNode); links.putAll(removed); },
                () -> { nodes.remove(removedNodeId); removed.keySet().forEach(links::remove); });
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
        JTextField freeSpeedField = new JTextField(String.format(Locale.ROOT, "%.6f", existing.freeSpeed() * 3.6));
        JTextField lanesField = new JTextField(String.format(Locale.ROOT, "%.3f", existing.lanes()));
        JTextField modesField = new JTextField(String.join(",", existing.allowedModes()));
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
        panel.add(new JLabel("Free speed (km/h)"));
        panel.add(freeSpeedField);
        panel.add(new JLabel("Lanes"));
        panel.add(lanesField);
        panel.add(new JLabel("Allowed modes (comma-separated)"));
        panel.add(modesField);
        panel.add(new JLabel("Capacity (veh/h)"));
        panel.add(capacityField);
        panel.add(new JLabel("Type (optional)"));
        panel.add(typeField);
        panel.add(new JLabel("Oneway tag (metadata only; reverse links are edited separately)"));
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
            length = Double.parseDouble(trimToEmpty(lengthField.getText()));
            freeSpeed = Double.parseDouble(trimToEmpty(freeSpeedField.getText())) / 3.6;
            lanes = Double.parseDouble(trimToEmpty(lanesField.getText()));
            capacity = Double.parseDouble(trimToEmpty(capacityField.getText()));
            validateNumbers(length, freeSpeed, lanes, capacity);
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Invalid Link", JOptionPane.WARNING_MESSAGE);
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
        attrs.put("capacity", Double.toString(capacity));

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
                parseModes(modesField.getText()),
                attrs
        );

        replaceLink(existing, edited);
        selectedLinkId = edited.id();
        selectedNodeId = null;
        notifySelectionChanged();
        repaint();
        return true;
    }

    public void disposeResources() {
        disposed = true;
        zoomTimer.stop();
        ++mapGeneration;
        drawingCache = null;
        transitPaths = List.of(); transitSelection=null; locationPicker=null;
        undo.clear(); redo.clear();
        if (osmBackground != null) osmBackground.close();
        mutationWorker.shutdownNow();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }

        ensureFitted();

        String key = getWidth()+":"+getHeight()+":"+baseScale
                +":"+selectedLinkId+":"+selectedNodeId+":"+visibleLinkModes.hashCode()+":"+linkColor+":"+nodeColor;
        if (drawingCache != null && key.equals(drawingKey)
                && ((cacheZoom == zoom && Math.abs(panX-cachePanX) <= CACHE_MARGIN && Math.abs(panY-cachePanY) <= CACHE_MARGIN)
                    || zoomSettling)) {
            drawCachedView((Graphics2D) g);
            drawModeStatus((Graphics2D) g);
            return;
        }
        drawingKey = key;
        cachePanX = panX; cachePanY = panY; cacheZoom = zoom;
        drawingCache = new BufferedImage(getWidth()+2*CACHE_MARGIN, getHeight()+2*CACHE_MARGIN, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = drawingCache.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(BACKGROUND);
        g2.fillRect(0, 0, drawingCache.getWidth(), drawingCache.getHeight());
        g2.translate(CACHE_MARGIN, CACHE_MARGIN);

        ViewportBounds viewportBounds = computeViewportBounds(VIEWPORT_MARGIN_PIXELS + CACHE_MARGIN);
        Map<String, Double> nodeWidthCaps = new HashMap<>();

        if (resolvedCoordinateSystem() != CoordinateSystem.NONE) {
            drawOsmBackground(g2);
        }

        viewportLinks.clear(); viewportNodes.clear();
        spatialIndex.query(viewportBounds.minX(), viewportBounds.minY(), viewportBounds.maxX(), viewportBounds.maxY(), viewportLinks);
        viewportLinks.addAll(changedLinks);
        viewportNodes.addAll(extraNodes);
        drawLinks(g2, viewportBounds, nodeWidthCaps);
        drawNodes(g2, viewportBounds, nodeWidthCaps);
        drawTransitOverlay(g2);


        g2.dispose();
        drawCachedView((Graphics2D) g);
        drawModeStatus((Graphics2D) g);
    }

    private void drawCachedView(Graphics2D graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        double ratio = zoom/cacheZoom;
        g.translate(panX-ratio*cachePanX, getHeight()*(1-ratio)+ratio*cachePanY-panY);
        g.scale(ratio,ratio);
        g.drawImage(drawingCache,-CACHE_MARGIN,-CACHE_MARGIN,null);
        g.dispose();
        if (osmBackground != null) osmBackground.drawAttribution(graphics,getWidth(),getHeight());
    }

    private void drawOsmBackground(Graphics2D g2) {
        if (mapEnabled && osmBackground != null) {
            Graphics2D mapGraphics = (Graphics2D) g2.create();
            mapGraphics.translate(-CACHE_MARGIN,-CACHE_MARGIN);
            osmBackground.draw(mapGraphics, getWidth()+2*CACHE_MARGIN, getHeight()+2*CACHE_MARGIN,
                    (x,y)->screenToWorld(x-CACHE_MARGIN,y-CACHE_MARGIN),
                    (x,y)-> { var point=worldToScreen(x,y); return new Point2D.Double(point.x+CACHE_MARGIN,point.y+CACHE_MARGIN); },
                    () -> { drawingCache = null; repaint(); });
            mapGraphics.dispose();
        }
    }

    private void drawLinks(Graphics2D g2, ViewportBounds viewportBounds, Map<String, Double> nodeWidthCaps) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        renderedPickLinks.clear();

        for (String id : viewportLinks) {
            LinkSegment link = links.get(id);
            if (link == null) continue;
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
            Point2D.Double shiftedFrom = applyPerpendicularOffset(from, to, offsetSign * Math.min(BIDIRECTIONAL_OFFSET_PIXELS, baseScale * zoom * 3.5));
            Point2D.Double shiftedTo = applyPerpendicularOffset(to, from, -offsetSign * Math.min(BIDIRECTIONAL_OFFSET_PIXELS, baseScale * zoom * 3.5));
            renderedPickLinks.add(new PickableLink(link.id(), shiftedFrom.x, shiftedFrom.y, shiftedTo.x, shiftedTo.y));

            viewportNodes.add(link.fromNodeId()); viewportNodes.add(link.toNodeId());
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
        double dx = to.x-from.x, dy = to.y-from.y, length = Math.hypot(dx, dy);
        if (length < 8) return;
        double t = Math.max(0.1, Math.min(0.9,
                ((getWidth()/2.0-from.x)*dx+(getHeight()/2.0-from.y)*dy)/(length*length)));
        double x=from.x+t*dx, y=from.y+t*dy, ux=dx/length, uy=dy/length;
        g2.setColor(new Color(0x55E4FF)); g2.setStroke(new BasicStroke(2));
        for (int side : new int[]{-1,1})
            g2.draw(new java.awt.geom.Line2D.Double(x-ux*10-uy*5*side, y-uy*10+ux*5*side, x, y));
    }

    private void drawNodes(Graphics2D g2, ViewportBounds viewportBounds, Map<String, Double> nodeWidthCaps) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        for (String id : viewportNodes) {
            NodePoint node = nodes.get(id);
            if (node == null) continue;
            if (!viewportBounds.contains(node.x(), node.y())) {
                continue;
            }

            Point2D.Double p = worldToScreen(node.x(), node.y());
            boolean selected = node.id().equals(selectedNodeId);
            boolean pending = pendingLinkNodes.contains(node.id());
            if (!selected && !pending && !nodeWidthCaps.containsKey(node.id()) && !extraNodes.contains(node.id())) {
                continue;
            }

            if (!selected && !pending && !createLinkArmed && baseScale * zoom < 0.4) continue;
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
        nodes.put(node.id(), node); extraNodes.add(node.id());
        remember(() -> nodes.remove(node.id()), () -> nodes.put(node.id(), node));
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
        JTextField freeSpeedField = new JTextField("50.0");
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
        panel.add(new JLabel("Free speed (km/h)"));
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
            length = Double.parseDouble(trimToEmpty(lengthField.getText()));
            freeSpeed = Double.parseDouble(trimToEmpty(freeSpeedField.getText())) / 3.6;
            lanes = Double.parseDouble(trimToEmpty(lanesField.getText()));
            capacity = Double.parseDouble(trimToEmpty(capacityField.getText()));
            validateNumbers(length, freeSpeed, lanes, capacity);
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Invalid Link", JOptionPane.WARNING_MESSAGE);
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

        links.put(linkId, created); changedLinks.add(linkId);
        remember(() -> links.remove(linkId), () -> links.put(linkId, created));
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
        for (String id : viewportNodes) {
            NodePoint node = nodes.get(id);
            if (node == null) continue;
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
        double ratio = zoom/cacheZoom;
        double pickX = (click.x-panX+ratio*cachePanX)/ratio;
        double pickY = (click.y-getHeight()*(1-ratio)-ratio*cachePanY+panY)/ratio;

        if (!renderedPickLinks.isEmpty()) {
            for (PickableLink pickable : renderedPickLinks) {
                double d = pointToSegmentDistance(pickX, pickY, pickable.ax(), pickable.ay(), pickable.bx(), pickable.by()) * ratio;
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
        drawingCache = null;
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

    private double linkStrokeWidth(LinkSegment link) {
        double lanes = Math.max(0.1, link.lanes());
        return Math.max(0.65, Math.min(6.2, 3.5 * baseScale * zoom * lanes));
    }

    private double bidirectionalOffsetSign(LinkSegment link) {
        if (!hasOppositeLink(link)) {
            return 0.0;
        }
        return 1.0; // Right side of travel; the reverse link has the reverse normal.
    }

    private boolean hasOppositeLink(LinkSegment link) {
        return directedConnectionKeys.contains(directedEdgeKey(link.toNodeId(), link.fromNodeId()));
    }

    private void scheduleDerivedRebuild() {
        drawingCache = null;
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
                if (disposed) return;
                drawingCache = null;
                repaint();
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
