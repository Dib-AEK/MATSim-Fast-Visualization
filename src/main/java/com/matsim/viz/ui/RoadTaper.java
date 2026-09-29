package com.matsim.viz.ui;

import com.matsim.viz.domain.LinkSegment;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.*;

/** Display-only width transitions; simulation link lengths and lane counts stay unchanged. */
final class RoadTaper {
    record Join(String incoming, String outgoing) { }
    record Section(double x, double y, double acrossX, double acrossY) {
        Point2D.Double point(double laneFraction) {
            return new Point2D.Double(x + acrossX * laneFraction, y + acrossY * laneFraction);
        }
    }

    static List<Join> joins(Collection<LinkSegment> links) {
        Map<String, List<LinkSegment>> incoming = new HashMap<>(), outgoing = new HashMap<>();
        Map<String, Set<String>> neighbors = new HashMap<>();
        for (LinkSegment link : links) {
            incoming.computeIfAbsent(link.toNodeId(), k -> new ArrayList<>()).add(link);
            outgoing.computeIfAbsent(link.fromNodeId(), k -> new ArrayList<>()).add(link);
            neighbors.computeIfAbsent(link.fromNodeId(), k -> new HashSet<>()).add(link.toNodeId());
            neighbors.computeIfAbsent(link.toNodeId(), k -> new HashSet<>()).add(link.fromNodeId());
        }
        List<Join> result = new ArrayList<>();
        incoming.forEach((node, entering) -> {
            if (neighbors.get(node).size() != 2 || neighbors.get(node).contains(node)) return;
            for (LinkSegment a : entering) {
                List<LinkSegment> candidates = outgoing.getOrDefault(node, List.of()).stream()
                        .filter(b -> !b.toNodeId().equals(a.fromNodeId())).toList();
                if (candidates.size() != 1) continue;
                LinkSegment b = candidates.get(0);
                if (entering.stream().filter(other -> !other.fromNodeId().equals(b.toNodeId())).count() != 1) continue;
                if (CarriagewayLayout.lanes(a) == CarriagewayLayout.lanes(b)) continue;
                if (Collections.disjoint(a.allowedModes(), b.allowedModes())) continue;
                double ax = a.toX() - a.fromX(), ay = a.toY() - a.fromY();
                double bx = b.toX() - b.fromX(), by = b.toY() - b.fromY();
                if (ax * bx + ay * by <= 0.5 * Math.hypot(ax, ay) * Math.hypot(bx, by)) continue;
                if (Math.hypot(a.toX() - b.fromX(), a.toY() - b.fromY()) > 1e-6) continue;
                result.add(new Join(a.id(), b.id()));
            }
        });
        return List.copyOf(result);
    }

    private final Section base;
    private final double dx, dy, reach;
    private Section start, end;
    private Path2D cachedSurface;

    RoadTaper(double x, double y, double dx, double dy, double nx, double ny, double width, double reach) {
        base = new Section(x, y, nx * width, ny * width);
        this.dx = dx;
        this.dy = dy;
        this.reach = Math.min(0.4, reach / Math.hypot(dx, dy));
    }

    void start(Section section) { start = section; cachedSurface = null; }
    void end(Section section) { end = section; cachedSurface = null; }

    Section section(double t) {
        Section target = t < reach ? start : t > 1 - reach ? end : null;
        double x = base.x + dx * t, y = base.y + dy * t;
        if (target == null) return new Section(x, y, base.acrossX, base.acrossY);
        boolean atStart = t < reach;
        double u = (atStart ? t : 1 - t) / reach;
        double weight = 1 - u * u * (3 - 2 * u);
        return new Section(x + weight * (target.x - base.x - (atStart ? 0 : dx)),
                y + weight * (target.y - base.y - (atStart ? 0 : dy)),
                base.acrossX + weight * (target.acrossX - base.acrossX),
                base.acrossY + weight * (target.acrossY - base.acrossY));
    }

    Path2D surface() {
        if (cachedSurface != null) return cachedSurface;
        Path2D path = new Path2D.Double();
        for (int side = 0; side < 2; side++) {
            for (int i = 0; i < 34; i++) {
                int sample = side == 0 ? i : 33 - i;
                double t = sample <= 16 ? reach * sample / 16.0
                        : 1 - reach + reach * (sample - 17) / 16.0;
                Point2D.Double point = section(t).point(side == 0 ? -0.5 : 0.5);
                if (side == 0 && i == 0) path.moveTo(point.x, point.y);
                else path.lineTo(point.x, point.y);
            }
        }
        path.closePath();
        cachedSurface = path;
        return path;
    }
}
