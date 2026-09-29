package com.matsim.viz.ui;

import com.matsim.viz.domain.*;
import com.matsim.viz.engine.*;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Dependency-free regression check; run with java -Djava.awt.headless=true. */
public final class CarriagewayRenderingCheck {
    private static LinkSegment link(String id, double x1, double y1, double x2, double y2, int lanes) {
        return new LinkSegment(id, id + "-from", id + "-to", x1, y1, x2, y2,
                Math.hypot(x2 - x1, y2 - y1), 15, lanes, Set.of("car", "bus"), Map.of());
    }
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        List<LinkSegment> links = List.of(
                link("east", 10, 55, 170, 55, 3), link("west", 170, 55, 10, 55, 1),
                link("one-way", 10, 20, 170, 20, 2),
                link("diagonal", 15, 80, 160, 110, 1), link("diagonal-back", 160, 110, 15, 80, 1));
        Map<String, CarriagewayLayout.Slot> layout = CarriagewayLayout.build(links);
        check(layout.get("one-way").offset(5, 1) == 0, "One-way road should stay centered");
        for (double laneWidth : new double[]{0.9, 1.9, 5, 18}) {
            for (double gap : new double[]{0, 0.5, 4}) {
                double separation = layout.get("east").offset(laneWidth, gap) + layout.get("west").offset(laneWidth, gap);
                check(separation >= 2 * laneWidth, "Unequal opposing carriageways overlap");
            }
        }
        List<LinkSegment> duplicates = new ArrayList<>(links);
        duplicates.add(link("east-copy", 10, 55, 170, 55, 2));
        Map<String, CarriagewayLayout.Slot> duplicateLayout = CarriagewayLayout.build(duplicates);
        check(duplicateLayout.get("east-copy").offset(5, 1) - duplicateLayout.get("east").offset(5, 1) >= 12.5,
                "Same-direction duplicate overlaps");
        Collections.reverse(duplicates);
        check(duplicateLayout.equals(CarriagewayLayout.build(duplicates)), "Input order changes layout");
        Map<String, LinkSegment> networkLinks = new HashMap<>();
        links.forEach(link -> networkLinks.put(link.id(), link));
        List<VehicleTraversal> traversals = new ArrayList<>();
        Map<String, String> modes = new HashMap<>();
        for (LinkSegment link : links) {
            for (int i = 0; i < 7; i++) {
                String vehicle = link.id() + i;
                modes.put(vehicle, i == 3 ? "bus" : "car");
                traversals.add(new VehicleTraversal(traversals.size(), vehicle, link.id(), i * 9, i * 9 + 100));
            }
        }
        SimulationModel model = new SimulationModel(new NetworkData(Map.of(), networkLinks, 0, 0, 180, 120),
                traversals.toArray(VehicleTraversal[]::new), Map.of(), modes, Map.of(), Map.of(), Map.of(), null);
        PlaybackController playback = new PlaybackController(model, 0, 200, 1);
        playback.seek(85);
        SwingUtilities.invokeAndWait(() -> {
            try {
                NetworkPanel panel = new NetworkPanel(model, playback);
                panel.setSize(1100, 760);
                panel.setBusShape(VehicleShape.RECTANGLE);
                for (boolean dark : new boolean[]{true, false}) {
                    panel.setDarkTheme(dark);
                    BufferedImage image = new BufferedImage(1100, 760, BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics = image.createGraphics();
                    panel.paint(graphics);
                    graphics.dispose();
                    Path output = Path.of("target", "carriageways-" + (dark ? "dark" : "light") + ".png");
                    ImageIO.write(image, "png", output.toFile());
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });
        System.out.println("Carriageway geometry checks passed; dark/light previews written to target/.");
    }
}

