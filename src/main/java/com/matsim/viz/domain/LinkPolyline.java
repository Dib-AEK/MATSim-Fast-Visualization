package com.matsim.viz.domain;

import java.awt.geom.Path2D;
import java.util.Arrays;
import org.locationtech.jts.geom.Envelope;

/** Immutable coordinates and distance-based interpolation, in world or screen units. */
public final class LinkPolyline {
    private final double[] xy, distance;
    private final Envelope bounds;
    public LinkPolyline(double[] coordinates) {
        if(coordinates.length<4 || coordinates.length%2!=0)throw new IllegalArgumentException("A line needs two points");
        double[] clean=new double[coordinates.length];int count=0;
        for(int i=0;i<coordinates.length;i+=2){
            if(!Double.isFinite(coordinates[i])||!Double.isFinite(coordinates[i+1]))throw new IllegalArgumentException("Invalid coordinate");
            if(count==0||clean[count-2]!=coordinates[i]||clean[count-1]!=coordinates[i+1]){clean[count++]=coordinates[i];clean[count++]=coordinates[i+1];}
        }
        if(count<4)throw new IllegalArgumentException("Empty line");
        xy=Arrays.copyOf(clean,count);distance=new double[xy.length/2];bounds=new Envelope();
        for(int i=0;i<size();i++){
            if(!Double.isFinite(x(i))||!Double.isFinite(y(i)))throw new IllegalArgumentException("Invalid coordinate");
            bounds.expandToInclude(x(i),y(i));
            if(i>0)distance[i]=distance[i-1]+Math.hypot(x(i)-x(i-1),y(i)-y(i-1));
        }
        if(length()<=0)throw new IllegalArgumentException("Empty line");
    }
    public int size(){return distance.length;}
    public double x(int i){return xy[2*i];}
    public double y(int i){return xy[2*i+1];}
    public double fraction(int vertex){return distance[vertex]/length();}
    public double length(){return distance[distance.length-1];}
    public Envelope bounds(){return new Envelope(bounds);}
    public record Position(double x,double y,double angle) { }
    public Position at(double fraction,double offset){
        double d=Math.max(0,Math.min(1,fraction))*length();
        int i=Arrays.binarySearch(distance,d);
        i=i>=0?Math.min(i,size()-2):Math.max(0,-i-2);
        while(i<size()-2 && distance[i+1]==distance[i])i++;
        double span=distance[i+1]-distance[i],t=span>0?(d-distance[i])/span:0;
        double dx=x(i+1)-x(i),dy=y(i+1)-y(i),len=Math.hypot(dx,dy);
        return new Position(x(i)+dx*t-(len>0?dy/len*offset:0),y(i)+dy*t+(len>0?dx/len*offset:0),Math.atan2(dy,dx));
    }
    /** Joined offset polyline with bounded mitres; avoids spikes at sharp bends. */
    public LinkPolyline offset(double offset){
        if(offset==0)return this;
        double[] points=new double[xy.length];
        for(int i=0;i<size();i++){
            int a=Math.max(0,i-1),b=Math.min(size()-1,i+1);
            double ax=x(i)-x(a),ay=y(i)-y(a),bx=x(b)-x(i),by=y(b)-y(i);
            double al=Math.hypot(ax,ay),bl=Math.hypot(bx,by);
            if(al==0){ax=bx;ay=by;al=bl;}if(bl==0){bx=ax;by=ay;bl=al;}
            double nx=-ay/al-by/bl,ny=ax/al+bx/bl;
            double denominator=1+(ax*bx+ay*by)/(al*bl);
            if(denominator<0.1){nx=-by/bl;ny=bx/bl;}else{nx/=denominator;ny/=denominator;}
            double norm=Math.hypot(nx,ny);if(norm>3){nx*=3/norm;ny*=3/norm;}
            points[2*i]=x(i)+nx*offset;points[2*i+1]=y(i)+ny*offset;
        }
        return new LinkPolyline(points);
    }
    public Path2D path(double offset,double start,double end){
        LinkPolyline line=offset==0?this:offset(offset);
        var path=new Path2D.Double();var first=line.at(start,0);path.moveTo(first.x(),first.y());
        for(int i=1;i<line.size()-1;i++){
            double t=line.distance[i]/line.length();if(t>start&&t<end)path.lineTo(line.x(i),line.y(i));
        }
        var last=line.at(end,0);path.lineTo(last.x(),last.y());return path;
    }
}
