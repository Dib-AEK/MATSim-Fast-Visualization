package com.matsim.viz.ui.map;

import com.matsim.viz.config.AppDefaults;
import java.awt.image.BufferedImage;

/** Local palettes of the same OSM tiles, retaining their labels and attribution. */
public enum MapStyle {
    OPENSTREETMAP("OpenStreetMap"), LIGHT("Light monochrome"), DARK("Dark monochrome");

    private final String label;
    MapStyle(String label) { this.label = label; }
    @Override public String toString() { return label; }

    /** Applied once on the tile worker, never during painting; source tiles stay unchanged. */
    BufferedImage apply(BufferedImage source) {
        if (this == OPENSTREETMAP) return source;
        int width = source.getWidth(), height = source.getHeight();
        int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i];
            int luminance = (54 * ((rgb >>> 16) & 255) + 183 * ((rgb >>> 8) & 255)
                    + 19 * (rgb & 255)) >>> 8;
            // Quiet land/road tones, with enough contrast for place and street names.
            int gray = this == LIGHT ? AppDefaults.Maps.LIGHT_MIN_GRAY + (luminance * (AppDefaults.Maps.LIGHT_MAX_GRAY - AppDefaults.Maps.LIGHT_MIN_GRAY) / 255) : AppDefaults.Maps.DARK_MAX_GRAY - (luminance * (AppDefaults.Maps.DARK_MAX_GRAY - AppDefaults.Maps.DARK_MIN_GRAY) / 255);
            pixels[i] = (rgb & 0xff000000) | (gray << 16) | (gray << 8) | gray;
        }
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, width, height, pixels, 0, width);
        return result;
    }
}
