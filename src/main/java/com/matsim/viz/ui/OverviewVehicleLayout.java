package com.matsim.viz.ui;

/** Packs representative vehicle marks without exceeding the link's screen length. */
final class OverviewVehicleLayout {
    record Mark(int vehicleIndex, double start, double end) {}

    static Mark[] arrange(double[] sortedProgress, double length, double coverage) {
        if (sortedProgress.length == 0 || length <= 0) return new Mark[0];
        // Below three pixels per vehicle, sample evenly instead of creating a solid stripe.
        int count = Math.min(sortedProgress.length, Math.max(1, (int) (length / 3)));
        double spacing = Math.min(6, length / count);
        double markLength = spacing * coverage;
        Mark[] marks = new Mark[count];
        double previous = -spacing;
        for (int i = 0; i < count; i++) {
            int index = Math.min(sortedProgress.length - 1, (int) ((i + 0.5) * sortedProgress.length / count));
            double desired = sortedProgress[index] * length - markLength / 2;
            double latest = length - markLength - (count - 1 - i) * spacing;
            double start = Math.max(previous + spacing, Math.min(latest, Math.max(0, desired)));
            marks[i] = new Mark(index, start, start + markLength);
            previous = start;
        }
        return marks;
    }
}
