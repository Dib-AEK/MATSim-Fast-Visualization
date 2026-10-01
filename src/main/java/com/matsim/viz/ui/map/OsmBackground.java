package com.matsim.viz.ui.map;

import com.matsim.viz.config.AppDefaults;
import org.matsim.api.core.v01.Coord;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.BiFunction;

/** Projects Web Mercator tiles into the network CRS, using MATSim's CRS transformations. */
public final class OsmBackground implements AutoCloseable {
    private static final double HALF_WORLD = Math.PI * 6_378_137;

    private final String crs;
    private final CoordinateTransformation toMap;
    private final CoordinateTransformation toNetwork;
    private final OsmTileCache tiles;
    private record TileGeometry(int zoom, int x, int y) { }
    private final java.util.Map<TileGeometry, Coord[][]> projectedTiles = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<TileGeometry, Coord[][]> entry) {
            return size() > AppDefaults.Maps.PROJECTION_CACHE_TILES;
        }
    };
    private String status = "Loading map tiles...";

    public OsmBackground(String networkCrs, Path cacheDir) {
        this(networkCrs, cacheDir, AppDefaults.Maps.STYLE);
    }

    public OsmBackground(String networkCrs, Path cacheDir, MapStyle style) {
        crs = networkCrs == null ? "" : networkCrs.trim().toUpperCase(Locale.ROOT);
        if (crs.isEmpty()) throw new IllegalArgumentException("Enter the network CRS, for example EPSG:2056.");
        try {
            toMap = TransformationFactory.getCoordinateTransformation(crs, "EPSG:3857");
            toNetwork = TransformationFactory.getCoordinateTransformation("EPSG:3857", crs);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Unknown or unsupported network CRS: " + crs, ex);
        }
        tiles = new OsmTileCache(cacheDir, style);
    }

    public String crs() { return crs; }

    public Coord toMap(double x, double y) { return toMap.transform(new Coord(x, y)); }
    public Coord toNetwork(double x, double y) { return toNetwork.transform(new Coord(x, y)); }

    public void validateLocation(double x, double y) {
        Coord point = toMap(x, y);
        if (!finite(point) || Math.abs(point.getX()) > HALF_WORLD || Math.abs(point.getY()) > HALF_WORLD) {
            throw new IllegalArgumentException("The network is outside map coverage in " + crs + ". Check its coordinate system.");
        }
    }

    public void draw(Graphics2D graphics, int width, int height,
                     BiFunction<Double, Double, Point2D.Double> screenToWorld,
                     BiFunction<Double, Double, Point2D.Double> worldToScreen, Runnable changed) {
        try {
            // Sample all edges and the interior: projected bounds need more than two corners.
            double minX = Double.POSITIVE_INFINITY, minY = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
            for (int row = 0; row <= 4; row++) {
                for (int col = 0; col <= 4; col++) {
                    Point2D.Double world = screenToWorld.apply(width * col / 4.0, height * row / 4.0);
                    Coord map = toMap(world.x, world.y);
                    if (!finite(map)) throw new IllegalArgumentException("Invalid map coordinates");
                    minX = Math.min(minX, map.getX()); maxX = Math.max(maxX, map.getX());
                    minY = Math.min(minY, map.getY()); maxY = Math.max(maxY, map.getY());
                }
            }
            if (minX > HALF_WORLD || maxX < -HALF_WORLD || minY > HALF_WORLD || maxY < -HALF_WORLD) {
                status = "Outside map coverage — check network CRS";
                return;
            }
            minX = Math.max(-HALF_WORLD, minX); maxX = Math.min(HALF_WORLD, maxX);
            minY = Math.max(-HALF_WORLD, minY); maxY = Math.min(HALF_WORLD, maxY);
            double metersPerPixel = Math.max((maxX - minX) / width, (maxY - minY) / height);
            int zoom = Math.max(0, Math.min(AppDefaults.Maps.MAX_ZOOM, (int) Math.round(Math.log(2 * HALF_WORLD / (256 * metersPerPixel)) / Math.log(2))));
            int x0, x1, y0, y1;
            double tileMeters;
            do {
                tileMeters = 2 * HALF_WORLD / (1 << zoom);
                x0 = tileIndex(minX + HALF_WORLD, tileMeters, zoom);
                x1 = tileIndex(maxX + HALF_WORLD, tileMeters, zoom);
                y0 = tileIndex(HALF_WORLD - maxY, tileMeters, zoom);
                y1 = tileIndex(HALF_WORLD - minY, tileMeters, zoom);
                if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) <= AppDefaults.Maps.MAX_VIEW_TILES || zoom == 0) break;
                zoom--;
            } while (true);

            int missing = 0;
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    BufferedImage tile = tiles.getTile(zoom, x, y, changed);
                    if (tile == null) { missing++; continue; }
                    drawProjectedTile(graphics, tile, new TileGeometry(zoom, x, y), -HALF_WORLD + x * tileMeters,
                            HALF_WORLD - y * tileMeters, tileMeters, worldToScreen);
                }
            }
            status = missing == 0 ? "" : tiles.hasDownloadFailure()
                    ? "Some map tiles unavailable — check connection" : "Loading map tiles...";
        } catch (RuntimeException ex) {
            status = "Cannot align map — check network CRS";
        }
    }

    private static int tileIndex(double position, double tileMeters, int zoom) {
        return Math.max(0, Math.min((1 << zoom) - 1, (int) Math.floor(position / tileMeters)));
    }

    private void drawProjectedTile(Graphics2D graphics, BufferedImage image, TileGeometry key, double left, double top,
                                   double meters, BiFunction<Double, Double, Point2D.Double> screen) {
        int mesh = AppDefaults.Maps.PROJECTION_MESH;
        Coord[][] world = projectedTiles.get(key);
        if (world == null) {
            world = new Coord[mesh + 1][mesh + 1];
            for (int row = 0; row <= mesh; row++) for (int col = 0; col <= mesh; col++) {
                Coord coord = toNetwork(left + meters * col / mesh, top - meters * row / mesh);
                if (!finite(coord)) return;
                world[row][col] = coord;
            }
            projectedTiles.put(key, world);
        }
        Point2D.Double[][] points = new Point2D.Double[mesh + 1][mesh + 1];
        for (int row = 0; row <= mesh; row++) for (int col = 0; col <= mesh; col++) {
            points[row][col] = screen.apply(world[row][col].getX(), world[row][col].getY());
        }
        // Most city-scale projected tiles are effectively affine. Bound the approximation
        // in output pixels, so high-resolution recordings automatically demand more accuracy.
        var origin = points[0][0]; var right = points[0][mesh]; var bottom = points[mesh][0];
        var device = graphics.getTransform();
        boolean affine = true;
        for (int row = 0; row <= mesh && affine; row++) for (int col = 0; col <= mesh; col++) {
            double x = origin.x + (right.x-origin.x)*col/mesh + (bottom.x-origin.x)*row/mesh;
            double y = origin.y + (right.y-origin.y)*col/mesh + (bottom.y-origin.y)*row/mesh;
            var error = device.deltaTransform(new Point2D.Double(points[row][col].x-x, points[row][col].y-y), null);
            if (Math.hypot(error.getX(), error.getY()) > AppDefaults.Maps.AFFINE_MAX_ERROR_PX) { affine = false; break; }
        }
        if (affine) {
            var g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(image, new AffineTransform((right.x-origin.x)/image.getWidth(),
                        (right.y-origin.y)/image.getWidth(), (bottom.x-origin.x)/image.getHeight(),
                        (bottom.y-origin.y)/image.getHeight(), origin.x, origin.y), null);
            } finally { g.dispose(); }
            return;
        }
        double step = image.getWidth() / (double) AppDefaults.Maps.PROJECTION_MESH;
        for (int row = 0; row < AppDefaults.Maps.PROJECTION_MESH; row++) {
            for (int col = 0; col < AppDefaults.Maps.PROJECTION_MESH; col++) {
                double x = col * step, y = row * step;
                triangle(graphics, image, x, y, step, points[row][col], points[row][col + 1], points[row + 1][col]);
                triangle(graphics, image, x + step, y + step, -step,
                        points[row + 1][col + 1], points[row + 1][col], points[row][col + 1]);
            }
        }
    }

    private static void triangle(Graphics2D graphics, BufferedImage image, double x, double y, double step,
                                 Point2D.Double a, Point2D.Double b, Point2D.Double c) {
        Point2D.Double horizontal = b, vertical = c;
        double m00 = (horizontal.x - a.x) / step, m10 = (horizontal.y - a.y) / step;
        double m01 = (vertical.x - a.x) / step, m11 = (vertical.y - a.y) / step;
        AffineTransform transform = new AffineTransform(m00, m10, m01, m11,
                a.x - m00 * x - m01 * y, a.y - m10 * x - m11 * y);
        Path2D clip = new Path2D.Double();
        clip.moveTo(a.x, a.y); clip.lineTo(b.x, b.y); clip.lineTo(c.x, c.y); clip.closePath();
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.clip(clip);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, transform, null);
        } finally { g.dispose(); }
    }

    public void drawAttribution(Graphics2D g, int width, int height) {
        String text = "© OpenStreetMap contributors · openstreetmap.org/copyright";
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        int textWidth = g.getFontMetrics().stringWidth(text);
        int x = Math.max(0, width - textWidth - 16);
        g.setColor(new Color(255, 255, 255, 235));
        g.fillRect(x, height - 24, textWidth + 16, 24);
        g.setColor(AppDefaults.Colors.MAP_ATTRIBUTION_TEXT);
        g.drawString(text, x + 8, height - 8);
        if (!status.isEmpty()) {
            int w = g.getFontMetrics().stringWidth(status);
            g.setColor(new Color(255, 255, 255, 235));
            g.fillRect(0, height - 48, w + 16, 24);
            g.setColor(AppDefaults.Colors.MAP_ATTRIBUTION_TEXT);
            g.drawString(status, 8, height - 32);
        }
    }

    private static boolean finite(Coord coord) {
        return Double.isFinite(coord.getX()) && Double.isFinite(coord.getY());
    }

    @Override public void close() { tiles.close(); projectedTiles.clear(); }
}
