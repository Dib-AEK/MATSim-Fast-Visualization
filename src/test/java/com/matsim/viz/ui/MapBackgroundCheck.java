package com.matsim.viz.ui;

import com.matsim.viz.ui.map.*;
import com.matsim.viz.domain.*;
import com.matsim.viz.engine.*;
import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/** Offline projection/warp/cache checks using a generated tile; no public tile requests. */
public final class MapBackgroundCheck {
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path cache = Files.createTempDirectory(Path.of("target"), "map-check-");
        try (OsmBackground swiss = new OsmBackground("EPSG:2056", cache)) {
            swiss.validateLocation(2_600_000, 1_200_000);
            var map = swiss.toMap(2_600_000, 1_200_000);
            var roundTrip = swiss.toNetwork(map.getX(), map.getY());
            check(Math.hypot(roundTrip.getX() - 2_600_000, roundTrip.getY() - 1_200_000) < 0.05,
                    "Swiss CRS round trip exceeds 5 cm");
            check(map.getX() > 800_000 && map.getX() < 900_000 && map.getY() > 5_900_000 && map.getY() < 6_000_000,
                    "Swiss coordinates were projected to the wrong place/axis");
        }
        try (OsmBackground geographic = new OsmBackground("EPSG:4326", cache)) {
            var map = geographic.toMap(8, 47);
            check(Math.abs(map.getX() - Math.toRadians(8) * 6_378_137) < 0.01, "Longitude/latitude axis order changed");
        }
        boolean invalidRejected = false;
        try (OsmBackground ignored = new OsmBackground("EPSG:NOT_A_CRS", cache)) {
            throw new AssertionError("Invalid CRS accepted");
        } catch (IllegalArgumentException expected) { invalidRejected = true; }
        check(invalidRejected, "Invalid CRS must be rejected");

        BufferedImage tile = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) tile.setRGB(x, y, new Color(x, y, 100).getRGB());
        Path tilePath = cache.resolve("osm-tiles/2/2/1.png");
        Files.createDirectories(tilePath.getParent());
        ImageIO.write(tile, "png", tilePath.toFile());
        // Disk-hit completion must release in-flight state and notify the Swing renderer.
        try (OsmTileCache tiles = new OsmTileCache(cache)) {
            CountDownLatch ready = new CountDownLatch(1);
            tiles.getTile(2, 2, 1, ready::countDown);
            check(ready.await(5, TimeUnit.SECONDS), "Disk tile did not notify renderer");
            check(tiles.getTile(2, 2, 1, () -> {}) != null, "Disk cache hit was not retained");
        }
        try (OsmBackground background = new OsmBackground("EPSG:3857", cache)) {
            double tileMeters = 2 * Math.PI * 6_378_137 / 4;
            double pixelMeters = tileMeters / 256;
            BufferedImage rendered = new BufferedImage(224, 224, BufferedImage.TYPE_INT_RGB);
            CountDownLatch ready = new CountDownLatch(1);
            Runnable paint = () -> {
                Graphics2D g = rendered.createGraphics();
                background.draw(g, 224, 224,
                        (x, y) -> new Point2D.Double((x + 16) * pixelMeters, tileMeters - (y + 16) * pixelMeters),
                        (x, y) -> new Point2D.Double(x / pixelMeters - 16, (tileMeters - y) / pixelMeters - 16),
                        ready::countDown);
                g.dispose();
            };
            SwingUtilities.invokeAndWait(paint);
            check(ready.await(5, TimeUnit.SECONDS), "Map tile did not load");
            SwingUtilities.invokeAndWait(paint);
            for (int y = 8; y < 220; y += 17) for (int x = 8; x < 220; x += 17) {
                Color color = new Color(rendered.getRGB(x, y));
                check(Math.abs(color.getRed() - (x + 16)) <= 2 && Math.abs(color.getGreen() - (y + 16)) <= 2,
                        "Tile mesh distorted or left a seam at " + x + "," + y);
            }
            ImageIO.write(rendered, "png", cache.resolve("mesh-check.png").toFile());

            // Exercise the main panel and recording path, including mandatory map credit.
            double lowX = 48 * pixelMeters, highX = 208 * pixelMeters;
            double lowY = tileMeters - highX, highY = tileMeters - lowX;
            var link = new LinkSegment("road", "a", "b", lowX, lowY, highX, highY, highX - lowX,
                    15, 1, Set.of("car"), Map.of());
            var network = new NetworkData(Map.of(), Map.of("road", link), lowX, lowY, highX, highY);
            var model = new SimulationModel(network, null, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null);
            BufferedImage recorded = new BufferedImage(448, 448, BufferedImage.TYPE_INT_RGB);
            SwingUtilities.invokeAndWait(() -> {
                var panel = new NetworkPanel(model, new PlaybackController(model, 0, 1, 1));
                panel.setSize(224, 224);
                panel.setSuppressOverlays(true);
                panel.setRoadOpacity(0);
                panel.setOsmBackground(background);
                Graphics2D g = recorded.createGraphics();
                panel.paintRecordingFrame(g, 448, 448);
                g.dispose();
                check(new Color(recorded.getRGB(4, 440)).getRed() > 200,
                        "Map attribution disappeared from recording when overlays were suppressed");
                Color mapPixel = new Color(recorded.getRGB(100, 150));
                check(mapPixel.getRed() > 50 && mapPixel.getGreen() > 50,
                        "The network layer hid the map background");
                panel.setOsmBackground(null);
                check(!panel.isMapBackgroundEnabled(), "Map toggle did not clear background");
            });
            ImageIO.write(recorded, "png", cache.resolve("map-recording-preview.png").toFile());
        }
        System.out.println("PASS: EPSG:2056 round trip, EPSG:4326 axes, CRS validation, disk cache and tile reprojection. " + cache);
    }
}
