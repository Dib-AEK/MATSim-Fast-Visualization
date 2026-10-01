package com.matsim.viz.ui;

import com.matsim.viz.domain.*;
import com.matsim.viz.engine.*;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Overview geometry, congestion visibility, detail progression and cursor-anchored zoom. */
public final class ZoomDetailCheck {
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
    private static NetworkPanel panel(int lanes, boolean reverse) {
        Map<String, LinkSegment> links = new HashMap<>();
        links.put("road", new LinkSegment("road", "a", "b", 2000, 10000, 18000, 10000,
                16000, 15, lanes, Set.of("car"), Map.of()));
        if (reverse) links.put("back", new LinkSegment("back", "b", "a", 18000, 10000, 2000, 10000,
                16000, 15, lanes, Set.of("car"), Map.of()));
        var model = new SimulationModel(new NetworkData(Map.of(), links, 0, 0, 20000, 20000),
                new VehicleTraversal[]{new VehicleTraversal(0, "car", "road", 0, 100)},
                Map.of(), Map.of("car", "car"), Map.of(), Map.of(), Map.of(), null);
        var playback = new PlaybackController(model, 0, 100, 1);
        playback.seek(50);
        var panel = new NetworkPanel(model, playback);
        panel.setSize(900, 600);
        return panel;
    }
    private static BufferedImage render(NetworkPanel panel) {
        var image = new BufferedImage(900, 600, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        panel.paintRecordingFrame(g);
        g.dispose();
        return image;
    }
    private static void wheel(NetworkPanel panel, double rotation) {
        panel.dispatchEvent(new MouseWheelEvent(panel, MouseEvent.MOUSE_WHEEL, 0, 0,
                300, 300, 300, 300, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, (int) rotation, rotation));
    }
    private static Object call(NetworkPanel panel, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = NetworkPanel.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(panel, args);
    }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (int count : new int[]{0, 1, 10, 1000}) {
                    double[] progress = new double[count];
                    Arrays.fill(progress, 0.95); // Worst case: every vehicle has nearly the same position.
                    for (double length : new double[]{0.5, 3, 10, 1000}) {
                        for (double coverage : new double[]{0.2, 0.75, 0.9}) {
                            double sum = 0, previousEnd = -1;
                            for (var mark : OverviewVehicleLayout.arrange(progress, length, coverage)) {
                                check(mark.start() >= 0 && mark.end() <= length + 1e-8, "Vehicle extends past link");
                                check(mark.start() > previousEnd, "Vehicle marks have no gap");
                                sum += mark.end() - mark.start();
                                previousEnd = mark.end();
                            }
                            check(sum <= coverage * length + 1e-8, "Vehicle coverage exceeds budget");
                        }
                    }
                }
                var narrow = panel(1, false);
                var wide = panel(6, true);
                var first = render(narrow);
                var overview = render(wide);
                check(Arrays.equals(first.getRGB(0, 0, 900, 600, null, 0, 900),
                        overview.getRGB(0, 0, 900, 600, null, 0, 900)),
                        "Overview thickness/position depends on lane count or opposing direction");
                check((double) call(wide, "networkDetail", new Class<?>[0]) == 0, "Regional view has local detail");
                wide.setSampleSize(0.0001);
                wide.setBottleneckDivisor(12);
                wide.setShowBottleneck(true); // Existing queue threshold now classifies the active link as congested.
                var congested = render(wide);
                int redPixels = 0;
                for (int y = 0; y < 600; y++) for (int x = 0; x < 900; x++) {
                    int rgb = congested.getRGB(x, y);
                    if (((rgb >> 16) & 255) > 120 && ((rgb >> 8) & 255) < 90) redPixels++;
                }
                double markerArea = (wide.getMinVehicleLengthPixels() + 2) * (wide.getMinVehicleWidthPixels() + 2);
                check(redPixels > 1 && redPixels <= markerArea, "Overview vehicle is missing or paints beyond its marker size");
                ImageIO.write(congested, "png", Path.of("target/zoom-overview.png").toFile());
                Point2D.Double anchor = (Point2D.Double) call(wide, "screenToWorld",
                        new Class<?>[]{double.class, double.class}, 300.0, 300.0);
                for (int i = 0; i < 12; i++) wheel(wide, -1);
                check((double) call(wide, "networkDetail", new Class<?>[0]) > 0, "Earlier default transition did not start");
                wide.setZoomDetail(1, 4, 0.75);
                check((double) call(wide, "networkDetail", new Class<?>[0]) == 0, "Transition setting not applied");
                wide.setZoomDetail(0.3, 1.5, 0.75);
                double previous = 0;
                for (int i = 0; i < 40; i++) {
                    wheel(wide, -1);
                    double detail = (double) call(wide, "networkDetail", new Class<?>[0]);
                    check(detail >= previous && detail <= 1, "Detail progression is not monotonic");
                    previous = detail;
                }
                check(previous == 1, "Street detail did not return");
                var zoom = NetworkPanel.class.getDeclaredField("zoom"); zoom.setAccessible(true);
                check(zoom.getDouble(wide) > 80, "Zoom remains limited to the old maximum");
                Point2D.Double after = (Point2D.Double) call(wide, "screenToWorld",
                        new Class<?>[]{double.class, double.class}, 300.0, 300.0);
                check(anchor.distance(after) < 1e-6, "Zoom moved the point under the cursor");
                double beforeFraction = zoom.getDouble(wide);
                wheel(wide, 0.5); wheel(wide, -0.5);
                check(Math.abs(zoom.getDouble(wide) - beforeFraction) < 1e-8, "Fractional wheel zoom is not reversible");
                ImageIO.write(render(wide), "png", Path.of("target/zoom-detail.png").toFile());
                for (int i = 0; i < 80; i++) wheel(wide, 1);
                check((double) call(wide, "networkDetail", new Class<?>[0]) == 0, "Overview did not return after zooming out");
                wide.setVisualizationMode(NetworkPanel.VisualizationMode.SPEED_HEATMAP);
                check("Speed (km/h)".equals(call(wide, "legendTitle", new Class<?>[0])), "Overview hid heatmap units");
                var lanePanel=panel(2,true);render(lanePanel);wheel(lanePanel,-34);render(lanePanel);
                double laneBefore=(double)call(lanePanel,"laneWidthPixels",new Class<?>[0]);
                lanePanel.setLaneWidthMeters(7);render(lanePanel);
                double laneAfter=(double)call(lanePanel,"laneWidthPixels",new Class<?>[0]);
                check(Math.abs(laneAfter-2*laneBefore)<1e-6,"Lane width control did not scale geometry");
                boolean invalidWidth=false;try{lanePanel.setLaneWidthMeters(Double.NaN);}catch(IllegalArgumentException expected){invalidWidth=true;}
                check(invalidWidth,"Invalid lane width accepted");
                System.out.println("PASS: thin centerlines, visible congestion, progressive detail, zoom >80x and cursor anchoring.");
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
    }
}
