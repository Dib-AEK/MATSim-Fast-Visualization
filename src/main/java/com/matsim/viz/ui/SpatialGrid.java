package com.matsim.viz.ui;

import com.matsim.viz.domain.LinkSegment;
import com.matsim.viz.domain.NetworkData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Uniform grid index for fast viewport-to-link queries in large MATSim networks.
 */
public final class SpatialGrid {
    private static final int MIN_GRID_SIZE = 50;
    private static final int MAX_GRID_SIZE = 1500;

    private final double minX;
    private final double minY;
    private final double maxX;
    private final double maxY;
    private final int cols;
    private final int rows;
    private final double cellWidth;
    private final double cellHeight;
    private final Map<Long, String[]> cells;

    private SpatialGrid(
            double minX,
            double minY,
            double maxX,
            double maxY,
            int cols,
            int rows,
            double cellWidth,
            double cellHeight,
            Map<Long, String[]> cells
    ) {
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
        this.cols = cols;
        this.rows = rows;
        this.cellWidth = cellWidth;
        this.cellHeight = cellHeight;
        this.cells = cells;
    }

    public static SpatialGrid build(NetworkData networkData) {
        int linkCount = Math.max(1, networkData.getLinks().size());
        int gridSize = clamp((int) Math.round(Math.sqrt(linkCount)), MIN_GRID_SIZE, MAX_GRID_SIZE);

        double minX = networkData.getMinX();
        double minY = networkData.getMinY();
        double maxX = networkData.getMaxX();
        double maxY = networkData.getMaxY();

        double spanX = Math.max(1e-9, maxX - minX);
        double spanY = Math.max(1e-9, maxY - minY);

        double cellWidth = spanX / gridSize;
        double cellHeight = spanY / gridSize;

        List<LinkSegment> links = new ArrayList<>(networkData.getLinks().values());
        boolean canParallelize = Runtime.getRuntime().availableProcessors() > 1 && links.size() >= 5_000;
        Map<Long, List<String>> staging = (canParallelize ? links.parallelStream() : links.stream()).collect(
            HashMap::new,
            (acc, link) -> addLinkToCellMap(acc, link, minX, minY, cellWidth, cellHeight, gridSize),
            SpatialGrid::mergeCellMaps
        );

        Map<Long, String[]> packed = new HashMap<>(Math.max(16, staging.size()));
        for (Map.Entry<Long, List<String>> entry : staging.entrySet()) {
            List<String> value = entry.getValue();
            packed.put(entry.getKey(), value.toArray(String[]::new));
        }

        return new SpatialGrid(minX, minY, maxX, maxY, gridSize, gridSize, cellWidth, cellHeight, packed);
    }

    private static void addLinkToCellMap(
            Map<Long, List<String>> target,
            LinkSegment link,
            double minX,
            double minY,
            double cellWidth,
            double cellHeight,
            int gridSize
    ) {
        int minCol = toCellX(Math.min(link.fromX(), link.toX()), minX, cellWidth, gridSize);
        int maxCol = toCellX(Math.max(link.fromX(), link.toX()), minX, cellWidth, gridSize);
        int minRow = toCellY(Math.min(link.fromY(), link.toY()), minY, cellHeight, gridSize);
        int maxRow = toCellY(Math.max(link.fromY(), link.toY()), minY, cellHeight, gridSize);

        for (int row = minRow; row <= maxRow; row++) {
            for (int col = minCol; col <= maxCol; col++) {
                long key = packCell(col, row);
                target.computeIfAbsent(key, ignored -> new ArrayList<>()).add(link.id());
            }
        }
    }

    private static void mergeCellMaps(Map<Long, List<String>> left, Map<Long, List<String>> right) {
        for (Map.Entry<Long, List<String>> entry : right.entrySet()) {
            left.merge(entry.getKey(), entry.getValue(), (existing, incoming) -> {
                existing.addAll(incoming);
                return existing;
            });
        }
    }

    public void query(double queryMinX, double queryMinY, double queryMaxX, double queryMaxY, Set<String> out) {
        if (out == null) {
            return;
        }

        double boundedMinX = Math.max(minX, Math.min(maxX, Math.min(queryMinX, queryMaxX)));
        double boundedMaxX = Math.max(minX, Math.min(maxX, Math.max(queryMinX, queryMaxX)));
        double boundedMinY = Math.max(minY, Math.min(maxY, Math.min(queryMinY, queryMaxY)));
        double boundedMaxY = Math.max(minY, Math.min(maxY, Math.max(queryMinY, queryMaxY)));

        int minCol = toCellX(boundedMinX, minX, cellWidth, cols);
        int maxCol = toCellX(boundedMaxX, minX, cellWidth, cols);
        int minRow = toCellY(boundedMinY, minY, cellHeight, rows);
        int maxRow = toCellY(boundedMaxY, minY, cellHeight, rows);

        for (int row = minRow; row <= maxRow; row++) {
            for (int col = minCol; col <= maxCol; col++) {
                String[] links = cells.get(packCell(col, row));
                if (links == null) {
                    continue;
                }
                for (String linkId : links) {
                    out.add(linkId);
                }
            }
        }
    }

    private static long packCell(int col, int row) {
        return ((long) row << 32) | (col & 0xffffffffL);
    }

    private static int toCellX(double x, double minX, double cellWidth, int cols) {
        int idx = (int) ((x - minX) / cellWidth);
        return clamp(idx, 0, cols - 1);
    }

    private static int toCellY(double y, double minY, double cellHeight, int rows) {
        int idx = (int) ((y - minY) / cellHeight);
        return clamp(idx, 0, rows - 1);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
