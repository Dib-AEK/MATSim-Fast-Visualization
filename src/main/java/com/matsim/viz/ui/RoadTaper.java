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
    private com.matsim.viz.domain.LinkPolyline curve;
    private double curveWidth;
    private final double dx, dy;
    private double reach;
    private Section start, end;
    private Path2D cachedSurface;

    RoadTaper(double x, double y, double dx, double dy, double nx, double ny, double width, double reach) {
        base = new Section(x, y, nx * width, ny * width);
        this.dx = dx;
        this.dy = dy;
        this.reach = Math.min(0.4, reach / Math.max(1e-9,Math.hypot(dx, dy)));
    }

    RoadTaper(com.matsim.viz.domain.LinkPolyline curve,double width,double reach) {
        this(curve.x(0),curve.y(0),curve.x(curve.size()-1)-curve.x(0),curve.y(curve.size()-1)-curve.y(0),0,1,width,reach);
        this.curve=curve;this.curveWidth=width;this.reach=Math.min(0.4,reach/curve.length());
    }

    void start(Section section) { start = section; cachedSurface = null; }
    void end(Section section) { end = section; cachedSurface = null; }

    Section section(double t) {
        Section local=baseSection(t);
        Section target = t < reach ? start : t > 1 - reach ? end : null;
        if (target == null) return local;
        boolean atStart = t < reach;
        double u = (atStart ? t : 1 - t) / reach;
        double weight = 1 - u * u * (3 - 2 * u);
        Section endpoint=baseSection(atStart?0:1);
        return new Section(local.x+weight*(target.x-endpoint.x),local.y+weight*(target.y-endpoint.y),
                local.acrossX+weight*(target.acrossX-local.acrossX),local.acrossY+weight*(target.acrossY-local.acrossY));
    }
    private Section baseSection(double t){
        if(curve==null)return new Section(base.x+dx*t,base.y+dy*t,base.acrossX,base.acrossY);
        var p=curve.at(t,0);return new Section(p.x(),p.y(),-Math.sin(p.angle())*curveWidth,Math.cos(p.angle())*curveWidth);
    }

    Path2D surface() {
        if (cachedSurface != null) return cachedSurface;
        Path2D path = new Path2D.Double();
        var samples=new TreeSet<Double>();
        for(int i=0;i<=16;i++){samples.add(reach*i/16);samples.add(1-reach+reach*i/16);}
        if(curve!=null)for(int i=0;i<curve.size();i++)samples.add(curve.fraction(i));
        var times=new ArrayList<>(samples);
        for (int side = 0; side < 2; side++) {
            for (int i = 0; i < times.size(); i++) {
                double t=times.get(side==0?i:times.size()-1-i);
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
