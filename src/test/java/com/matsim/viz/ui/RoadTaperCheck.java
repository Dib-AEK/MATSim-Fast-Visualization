package com.matsim.viz.ui;

import com.matsim.viz.domain.*;
import com.matsim.viz.engine.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Topology, boundary continuity, short-link safety and renderer integration. */
public final class RoadTaperCheck {
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
    private static LinkSegment link(String id, String from, String to, double x1, double y1,
                                    double x2, double y2, int lanes) {
        return new LinkSegment(id, from, to, x1, y1, x2, y2,
                Math.hypot(x2-x1, y2-y1), 15, lanes, Set.of("car"), Map.of());
    }
    public static void main(String[] args) throws Exception {
        List<LinkSegment> links = new ArrayList<>(List.of(
                link("ab", "a", "b", 0, 0, 100, 0, 2),
                link("bc", "b", "c", 100, 0, 200, 0, 3),
                link("cd", "c", "d", 200, 0, 300, 25, 2)));
        for (LinkSegment l : List.copyOf(links)) {
            links.add(link(l.id()+"-reverse", l.toNodeId(), l.fromNodeId(),
                    l.toX(), l.toY(), l.fromX(), l.fromY(), (int) l.lanes()));
        }
        check(RoadTaper.joins(links).size() == 4, "Both directions need two transitions");
        List<LinkSegment> junction = new ArrayList<>(links);
        junction.add(link("branch", "b", "extra", 100, 0, 100, 80, 1));
        check(RoadTaper.joins(junction).size() == 2, "Must not taper through an intersection");
        junction = new ArrayList<>(links);
        junction.add(link("duplicate", "b", "c", 100, 0, 200, 0, 1));
        check(RoadTaper.joins(junction).size() == 2, "Ambiguous parallel links must not be joined");
        for (double length : new double[]{2, 20, 200}) {
            RoadTaper wider = new RoadTaper(0, 15, length, 0, 0, 1, 30, 80);
            RoadTaper.Section left = new RoadTaper.Section(0, 10, 0, 20);
            RoadTaper.Section right = new RoadTaper.Section(length, 10, 0, 20);
            wider.start(left);
            wider.end(right);
            check(wider.section(0).equals(left) && wider.section(1).equals(right), "Boundary not continuous");
            check(wider.section(0.5).acrossY() == 30, "Short-link transitions overlap");
            for (int i=0; i<=100; i++) {
                var section = wider.section(i / 100.0);
                check(section.acrossY() >= 20 && section.acrossY() <= 30, "Taper overshoots lane widths");
                check(Math.abs(section.y() - section.acrossY()/2) < 1e-9, "Median moved into opposing traffic");
            }
        }
        Map<String, LinkSegment> network = new LinkedHashMap<>();
        links.forEach(l -> network.put(l.id(), l));
        SimulationModel model = new SimulationModel(new NetworkData(Map.of(), network, -20, -60, 320, 70),
                new VehicleTraversal[0], Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null);
        PlaybackController playback = new PlaybackController(model, 0, 100, 1);
        SwingUtilities.invokeAndWait(() -> {
            try {
                NetworkPanel panel = new NetworkPanel(model, playback);
                panel.setSize(1400, 600);
                for (boolean dark : new boolean[]{true, false}) {
                    panel.setDarkTheme(dark);
                    BufferedImage image = new BufferedImage(1400, 600, BufferedImage.TYPE_INT_RGB);
                    var graphics = image.createGraphics();
                    panel.paint(graphics);
                    graphics.dispose();
                    var taperField = NetworkPanel.class.getDeclaredField("roadTapers");
                    taperField.setAccessible(true);
                    check(((Map<?, ?>) taperField.get(panel)).size() == 6, "Renderer did not apply all road transitions");
                    ImageIO.write(image, "png", Path.of("target/road-taper-"+(dark ? "dark" : "light")+".png").toFile());
                }
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
        System.out.println("PASS: road tapers, bidirectional continuity, short links and junction exclusions.");
    }
}
