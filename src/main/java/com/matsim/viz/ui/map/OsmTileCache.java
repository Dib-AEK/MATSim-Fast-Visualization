package com.matsim.viz.ui.map;

import com.matsim.viz.config.AppDefaults;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Shared, on-demand tile cache for the visualizer and network editor. */
public final class OsmTileCache implements AutoCloseable {
    private final Path tileRoot;
    private final MapStyle style;
    private final ExecutorService downloader = Executors.newFixedThreadPool(AppDefaults.Maps.DOWNLOAD_THREADS, task -> {
        Thread thread = new Thread(task, "osm-tile-downloader");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, BufferedImage> memory = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> entry) {
                    return size() > AppDefaults.Maps.MEMORY_TILES;
                }
            });
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> retryAfter = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile boolean downloadFailed;

    public OsmTileCache(Path cacheDir) { this(cacheDir, AppDefaults.Maps.STYLE); }
    public OsmTileCache(Path cacheDir, MapStyle style) {
        tileRoot = cacheDir.resolve("osm-tiles");
        this.style = Objects.requireNonNull(style);
    }
    public static int tileSize() { return 256; }
    public boolean hasDownloadFailure() { return downloadFailed; }

    public BufferedImage getTile(int zoom, int x, int y, Component target) {
        return getTile(zoom, x, y, () -> { if (target != null) target.repaint(); });
    }

    public BufferedImage getTile(int zoom, int x, int y, Runnable changed) {
        if (closed || zoom < 0 || zoom > AppDefaults.Maps.MAX_ZOOM || y < 0 || y >= (1 << zoom)) return null;
        int wrappedX = Math.floorMod(x, 1 << zoom);
        String key = zoom + ":" + wrappedX + ":" + y;
        BufferedImage cached = memory.get(key);
        if (cached != null) return cached;
        if (inFlight.size() >= AppDefaults.Maps.MAX_PENDING_TILES || System.currentTimeMillis() < retryAfter.getOrDefault(key, 0L)) return null;
        if (inFlight.add(key)) {
            try {
                downloader.submit(() -> load(zoom, wrappedX, y, key, changed));
            } catch (RejectedExecutionException ignored) { inFlight.remove(key); }
        }
        return null;
    }

    private void load(int zoom, int x, int y, String key, Runnable changed) {
        HttpURLConnection connection = null;
        try {
            if (closed) return;
            Path path = tileRoot.resolve(zoom + "/" + x + "/" + y + ".png");
            BufferedImage image = null;
            if (Files.exists(path)) {
                try { image = ImageIO.read(path.toFile()); } catch (IOException ignored) { }
            }
            if (image == null) {
                connection = (HttpURLConnection) URI.create(AppDefaults.Maps.TILE_URL + zoom + "/" + x + "/" + y + ".png").toURL().openConnection();
                connection.setRequestProperty("User-Agent", "MATSim-Fast-Visualization/1.0");
                connection.setConnectTimeout(AppDefaults.Maps.CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(AppDefaults.Maps.READ_TIMEOUT_MS);
                if (connection.getResponseCode() != 200) throw new IOException("Tile unavailable");
                try (var input = connection.getInputStream()) { image = ImageIO.read(input); }
                if (image == null) throw new IOException("Invalid tile image");
                // Retain tiles on disk (at least 7 days); never prefetch unseen areas.
                try {
                    Files.createDirectories(path.getParent());
                    ImageIO.write(image, "png", path.toFile());
                } catch (IOException ignored) { /* The memory cache still works on a read-only disk. */ }
            }
            memory.put(key, style.apply(image));
            retryAfter.remove(key);
            downloadFailed = false;
        } catch (IOException | RuntimeException ex) {
            if (retryAfter.size() > AppDefaults.Maps.MAX_RETRY_ENTRIES) retryAfter.clear();
            retryAfter.put(key, System.currentTimeMillis() + AppDefaults.Maps.RETRY_DELAY_MS);
            downloadFailed = true;
        } finally {
            if (connection != null) connection.disconnect();
            inFlight.remove(key);
            if (!closed) SwingUtilities.invokeLater(changed);
        }
    }

    @Override public void close() {
        closed = true;
        downloader.shutdownNow();
        memory.clear();
    }
}
