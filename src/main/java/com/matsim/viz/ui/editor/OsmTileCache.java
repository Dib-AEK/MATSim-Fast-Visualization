package com.matsim.viz.ui.editor;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class OsmTileCache {
    private static final String TILE_SERVER_URL = "https://tile.openstreetmap.org";
    private static final String USER_AGENT = "MATSim-Fast-Visualization/1.0";
    private static final int TILE_SIZE = 256;

    private final Path tileRoot;
    private final ExecutorService downloader;
    private final Map<String, BufferedImage> memory = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    OsmTileCache(Path cacheDir) {
        this.tileRoot = cacheDir.resolve("osm-tiles");
        int workers = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors() / 2));
        this.downloader = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "osm-tile-downloader");
            thread.setDaemon(true);
            return thread;
        });
    }

    BufferedImage getTile(int zoom, int x, int y, Component repaintTarget) {
        int wrappedX = wrapTileX(x, zoom);
        if (y < 0 || y >= (1 << zoom)) {
            return null;
        }

        String key = key(zoom, wrappedX, y);
        BufferedImage cached = memory.get(key);
        if (cached != null) {
            return cached;
        }

        if (inFlight.add(key)) {
            downloader.submit(() -> loadOrDownloadTile(zoom, wrappedX, y, key, repaintTarget));
        }
        return null;
    }

    void close() {
        downloader.shutdownNow();
    }

    static int tileSize() {
        return TILE_SIZE;
    }

    private void loadOrDownloadTile(int zoom, int x, int y, String key, Component repaintTarget) {
        try {
            Path tilePath = tilePath(zoom, x, y);
            if (Files.exists(tilePath)) {
                BufferedImage image = ImageIO.read(tilePath.toFile());
                if (image != null) {
                    memory.put(key, image);
                    requestRepaint(repaintTarget);
                    return;
                }
            }
        } catch (IOException ignored) {
            // If the cached tile is corrupt, attempt downloading again.
        }

        downloadTile(zoom, x, y, key, repaintTarget);
    }

    private void downloadTile(int zoom, int x, int y, String key, Component repaintTarget) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(TILE_SERVER_URL + "/" + zoom + "/" + x + "/" + y + ".png");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(true);

            int status = connection.getResponseCode();
            if (status != 200) {
                return;
            }

            try (InputStream stream = connection.getInputStream()) {
                BufferedImage image = ImageIO.read(stream);
                if (image == null) {
                    return;
                }

                Path tilePath = tilePath(zoom, x, y);
                Files.createDirectories(tilePath.getParent());
                ImageIO.write(image, "png", tilePath.toFile());
                memory.put(key, image);
            }

            requestRepaint(repaintTarget);
        } catch (IOException ignored) {
            // Best effort only - map background is optional.
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            inFlight.remove(key);
        }
    }

    private Path tilePath(int zoom, int x, int y) {
        return tileRoot.resolve(Integer.toString(zoom))
                .resolve(Integer.toString(x))
                .resolve(y + ".png");
    }

    private static void requestRepaint(Component repaintTarget) {
        if (repaintTarget != null) {
            SwingUtilities.invokeLater(repaintTarget::repaint);
        }
    }

    private static String key(int zoom, int x, int y) {
        return zoom + ":" + x + ":" + y;
    }

    private static int wrapTileX(int x, int zoom) {
        int tiles = 1 << zoom;
        int mod = x % tiles;
        return mod < 0 ? mod + tiles : mod;
    }
}
