package com.matsim.viz.ui;

import com.matsim.viz.domain.*;
import com.matsim.viz.engine.*;
import com.matsim.viz.parser.DetailedNetworkGeometry;
import java.nio.file.Files;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Connected event handoffs must be continuous, regardless of vehicle type or seek order. */
public final class VehicleMotionCheck {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static double value(Object pose, String name) throws Exception {
        var method = pose.getClass().getDeclaredMethod(name); method.setAccessible(true);
        return (double) method.invoke(pose);
    }
    private static BufferedImage render(NetworkPanel panel) {
        var image = new BufferedImage(900, 600, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics(); panel.paintRecordingFrame(g); g.dispose(); return image;
    }
    public static void main(String[] args) throws Exception {
        Path csv = Path.of("target/motion_detailed_network.csv");
        Files.writeString(csv, "link_id,geometry\nin,\"LINESTRING(0 0,50 -5,100 0)\"\nout,\"LINESTRING(100 0,105 50,100 100)\"\n");
        SwingUtilities.invokeAndWait(() -> {
            try {
                for (String mode : List.of("car", "bike", "truck", "bus", "tram", "rail", "ferry")) {
                    var links = Map.of(
                        "in", new LinkSegment("in", "a", "b", 0, 0, 100, 0, 100, 15, 2, Set.of(mode), Map.of()),
                        "out", new LinkSegment("out", "b", "c", 100, 0, 100, 100, 100, 15, 3, Set.of(mode), Map.of()));
                    var traversals = new VehicleTraversal[]{
                        new VehicleTraversal(0, mode, "in", 0, 10), new VehicleTraversal(1, mode, "out", 10, 20),
                        new VehicleTraversal(2, "gap", "in", 0, 10), new VehicleTraversal(3, "gap", "out", 11, 20),
                        new VehicleTraversal(4, "teleport", "out", 0, 10), new VehicleTraversal(5, "teleport", "in", 10, 20)};
                    var model = new SimulationModel(new NetworkData(Map.of(), links, -10, -10, 110, 110),
                            traversals, Map.of(), Map.of(mode, mode), Map.of(), Map.of(), Map.of(), null);
                    check(model.nextTraversal(0)==1 && model.previousTraversal(1)==0, "Missing connected handoff");
                    check(model.nextTraversal(2)==-1 && model.nextTraversal(4)==-1, "Invented motion across gap/teleport");
                    var playback = new PlaybackController(model, 0, 20, 1);
                    var panel = new NetworkPanel(model, playback); panel.setSize(900, 600); panel.setSuppressOverlays(true);
                    for (boolean curved : new boolean[]{false, true}) for (boolean overview : new boolean[]{false, true}) {
                        panel.setDetailedGeometry(DetailedNetworkGeometry.load(csv, model.networkData()));
                        panel.setDetailedGeometryEnabled(curved);
                        panel.setZoomDetail(overview ? 100 : 0.01, overview ? 200 : 0.02, 0.75);
                        playback.seek(9.99999); render(panel);
                        Field field = NetworkPanel.class.getDeclaredField("linkScreenGeometries"); field.setAccessible(true);
                        Map<?,?> geometries = (Map<?,?>) field.get(panel);
                        Class<?> geometryClass = geometries.get("in").getClass();
                        var raw = NetworkPanel.class.getDeclaredMethod("linkVehiclePose", geometryClass, double.class, String.class);
                        raw.setAccessible(true);
                        Object a = raw.invoke(panel, geometries.get("in"), 0.95, mode);
                        Object b = raw.invoke(panel, geometries.get("out"), 0.05, mode);
                        var smooth = NetworkPanel.class.getDeclaredMethod("smoothVehiclePose", int.class, double.class, a.getClass());
                        smooth.setAccessible(true);
                        Object before = smooth.invoke(panel, 0, 9.99999, a);
                        Object after = smooth.invoke(panel, 1, 10.00001, b);
                        check(Math.hypot(value(before,"x")-value(after,"x"),value(before,"y")-value(after,"y")) < 0.01,
                                "Position jumps for " + mode);
                        double angle = value(before,"angle")-value(after,"angle");
                        check(Math.abs(Math.atan2(Math.sin(angle),Math.cos(angle))) < 0.001, "Heading jumps for " + mode);
                        check(smooth.invoke(panel, 0, 5.0, a)==a, "Changed motion away from junction");
                        playback.seek(15); render(panel); playback.seek(9.99999); render(panel);
                        Object replay = smooth.invoke(panel, 0, 9.99999, a);
                        check(before.equals(replay), "Handoff depends on playback history");
                        if (mode.equals("car") && !overview && !curved) {
                            var sheet = new BufferedImage(1800, 600, BufferedImage.TYPE_INT_RGB);
                            var g = sheet.createGraphics();
                            playback.seek(9.99); g.drawImage(render(panel),0,0,null);
                            playback.seek(10.01); g.drawImage(render(panel),900,0,null); g.dispose();
                            ImageIO.write(sheet,"png",Path.of("target/vehicle-handoff.png").toFile());
                        }
                    }
                }
            } catch (Exception ex) { throw new AssertionError(ex); }
        });
        System.out.println("PASS: continuous vehicle position/heading at connected handoffs, overview/detail and CSV curves, seek determinism, gap/teleport isolation.");
    }
}
