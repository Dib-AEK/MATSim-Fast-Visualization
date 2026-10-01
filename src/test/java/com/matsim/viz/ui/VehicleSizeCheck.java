package com.matsim.viz.ui;

import com.matsim.viz.config.*;
import com.matsim.viz.domain.*;
import com.matsim.viz.engine.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

public final class VehicleSizeCheck {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path configFile = Files.createTempFile(Path.of("target"), "vehicle-size-", ".properties");
        var defaults = ConfigLoader.load(configFile);
        check(defaults.uiVehicleLengthFerryMeters() > defaults.uiVehicleLengthRailMeters(), "Ferry default must exceed rail");
        Files.writeString(configFile, "ui.vehicle.length.ferry.m=145\nui.vehicle.width.ratio.ferry=0.8\nui.vehicle.shape.ferry=OVAL\n");
        var override = ConfigLoader.load(configFile);
        check(override.uiVehicleLengthFerryMeters() == 145 && override.uiVehicleShapeFerry().equals("OVAL"), "Ferry overrides lost");
        Files.delete(configFile);
        SwingUtilities.invokeAndWait(() -> {
            try {
                Map<String, LinkSegment> links = new LinkedHashMap<>();
                Map<String, String> modes = new HashMap<>();
                var names = java.util.List.of("car", "bus", "rail", "ferry");
                VehicleTraversal[] traversals = new VehicleTraversal[names.size()];
                for (int i = 0; i < names.size(); i++) {
                    String mode = names.get(i);
                    links.put(mode, new LinkSegment(mode, "a" + i, "b" + i, 0, i * 1500,
                            15000, i * 1500, 15000, 15, 1, Set.of(mode), Map.of()));
                    modes.put(mode, mode);
                    traversals[i] = new VehicleTraversal(i, mode, mode, 0, 100);
                }
                var model = new SimulationModel(new NetworkData(Map.of(), links, 0, 0, 15000, 4500),
                        traversals, Map.of(), modes, Map.of(), Map.of(), Map.of(), null);
                var playback = new PlaybackController(model, 0, 100, 1); playback.seek(50);
                var panel = new NetworkPanel(model, playback); panel.setSize(1000, 700); panel.setSuppressOverlays(true);
                check(panel.getMinVehicleLengthPixels() == defaults.uiMinVehicleLengthPixels(), "Length floor differs from config");
                check(panel.getMinVehicleWidthPixels() == defaults.uiMinVehicleWidthPixels(), "Width floor differs from config");
                check(panel.getFerryVehicleLengthMeters() == defaults.uiVehicleLengthFerryMeters(), "Ferry field differs from config");
                Color[] colors = {Color.CYAN, Color.YELLOW, Color.RED, Color.GREEN};
                for (int i = 0; i < names.size(); i++) panel.setModeColor(names.get(i), colors[i]);
                BufferedImage image = new BufferedImage(1000, 700, BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics(); panel.paint(graphics); graphics.dispose();
                int[] widths = new int[4];
                for (int i = 0; i < colors.length; i++) {
                    int minX = 1000, maxX = -1, minY = 700, maxY = -1;
                    for (int y = 0; y < 700; y++) for (int x = 0; x < 1000; x++) {
                        if (image.getRGB(x, y) == colors[i].getRGB()) {
                            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                        }
                    }
                    widths[i] = maxX - minX + 1;
                    check(widths[i] >= defaults.uiMinVehicleLengthPixels() - 1, "Overview length too small: " + names.get(i));
                    check(maxY - minY + 1 >= defaults.uiMinVehicleWidthPixels() - 1, "Overview width too small: " + names.get(i));
                }
                check(widths[3] > widths[2] && widths[2] > widths[0], "Ferry/rail are not visibly longer");
                ImageIO.write(image, "png", Path.of("target/vehicle-sizes.png").toFile());
                double previous = -1, total = 0;
                for (var mark : OverviewVehicleLayout.arrange(new double[]{0.5,0.5,0.5}, 12, 0.75, new double[]{10,20,30})) {
                    check(mark.start() > previous && mark.end() <= 12, "Mixed-length marks overlap or exceed link");
                    total += mark.end() - mark.start(); previous = mark.end();
                }
                check(total <= 9.000001, "Mixed lengths exceed coverage budget");
            } catch (Exception ex) { throw new AssertionError(ex); }
        });
        System.out.println("PASS: modest overview pixels, distinct car/rail/ferry lengths, independent ferry config and crowded-link gaps.");
    }
}
