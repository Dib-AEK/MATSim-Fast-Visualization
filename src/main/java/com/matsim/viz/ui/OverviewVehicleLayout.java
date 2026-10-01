package com.matsim.viz.ui;

/** Packs representative vehicle marks without exceeding the link's screen length. */
final class OverviewVehicleLayout {
    record Mark(int vehicleIndex, double start, double end) {}

    static Mark[] arrange(double[] sortedProgress, double length, double coverage) {
        double[] lengths = new double[sortedProgress.length];
        java.util.Arrays.fill(lengths, 6 * coverage);
        return arrange(sortedProgress, length, coverage, lengths);
    }

    static Mark[] arrange(double[] sortedProgress, double length, double coverage, double[] desiredLengths) {
        if (sortedProgress.length == 0 || length <= 0) return new Mark[0];
        if (desiredLengths.length != sortedProgress.length) throw new IllegalArgumentException("Missing vehicle lengths");
        // Below three pixels per vehicle, sample evenly instead of creating a solid stripe.
        int count = Math.min(sortedProgress.length, Math.max(1, (int) (length / 3)));
        int[] indexes = new int[count];
        double total = 0;
        for (int i = 0; i < count; i++) {
            indexes[i] = Math.min(sortedProgress.length - 1, (int) ((i + 0.5) * sortedProgress.length / count));
            total += desiredLengths[indexes[i]];
        }
        double scale = Math.min(1, length * coverage / total);
        double remaining = total * scale / coverage;
        Mark[] marks = new Mark[count];
        double previousEnd = 0;
        for (int i = 0; i < count; i++) {
            int index = indexes[i];
            double markLength = desiredLengths[index] * scale;
            double slot = markLength / coverage;
            double desired = sortedProgress[index] * length - slot / 2;
            double slotStart = Math.max(previousEnd, Math.min(length - remaining, Math.max(0, desired)));
            double start = slotStart + (slot - markLength) / 2;
            marks[i] = new Mark(index, start, start + markLength);
            previousEnd = slotStart + slot;
            remaining -= slot;
        }
        return marks;
    }
}
