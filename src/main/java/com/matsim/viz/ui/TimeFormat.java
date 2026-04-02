package com.matsim.viz.ui;

public final class TimeFormat {
    private TimeFormat() {
    }

    public static String hhmmss(double seconds) {
        int total = Math.max(0, (int) Math.floor(seconds));
        int h = total / 3600;
        int m = (total % 3600) / 60;
        int s = total % 60;
        return String.format("%02d:%02d:%02d", h, m, s);
    }
}
