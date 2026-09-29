package com.matsim.viz.ui;

import com.matsim.viz.domain.LinkSegment;
import java.util.*;

/** Stable display slots for coincident straight links, independent of viewport and filters. */
final class CarriagewayLayout {
    record Slot(double centerLanes, int gapSteps) {
        double offset(double laneWidth, double gap) {
            return centerLanes * laneWidth + gapSteps * gap;
        }
    }
    private record Point(double x, double y) {
        static Point of(double x, double y) {
            return new Point(x == 0 ? 0 : x, y == 0 ? 0 : y);
        }
    }
    private record Direction(Point from, Point to) {
        Direction reverse() { return new Direction(to, from); }
    }

    static int lanes(LinkSegment link) {
        return Double.isFinite(link.lanes()) ? Math.max(1, (int) Math.ceil(link.lanes())) : 1;
    }

    static Map<String, Slot> build(Collection<LinkSegment> links) {
        Map<Direction, List<LinkSegment>> groups = new HashMap<>();
        for (LinkSegment link : links) {
            Direction key = new Direction(Point.of(link.fromX(), link.fromY()), Point.of(link.toX(), link.toY()));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(link);
        }
        Map<String, Slot> result = new HashMap<>();
        groups.forEach((direction, group) -> {
            group.sort(Comparator.comparing(LinkSegment::id));
            boolean reverse = !direction.from.equals(direction.to) && groups.containsKey(direction.reverse());
            double total = group.stream().mapToDouble(CarriagewayLayout::lanes).sum();
            double cursor = reverse ? 0 : -total / 2;
            for (int i = 0; i < group.size(); i++) {
                LinkSegment link = group.get(i);
                double half = lanes(link) / 2.0;
                result.put(link.id(), new Slot(cursor + half, reverse ? i + 1 : i));
                cursor += 2 * half;
            }
        });
        return Map.copyOf(result);
    }
}
