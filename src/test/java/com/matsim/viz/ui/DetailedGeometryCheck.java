package com.matsim.viz.ui;
import com.matsim.viz.domain.*;
import com.matsim.viz.parser.DetailedNetworkGeometry;
import com.matsim.viz.engine.*;
import java.nio.file.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.geom.Point2D;
import java.util.*;
import javax.swing.SwingUtilities;
import javax.imageio.ImageIO;

public final class DetailedGeometryCheck {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    static LinkSegment link(String id,double x1,double y1,double x2,double y2,String chain){return new LinkSegment(id,"n"+x1+","+y1,"n"+x2+","+y2,x1,y1,x2,y2,200,10,1,Set.of("car"),chain==null?Map.of():Map.of("old_link_id",chain));}
    static NetworkData network(LinkSegment... links){var map=new LinkedHashMap<String,LinkSegment>();for(var link:links)map.put(link.id(),link);return new NetworkData(Map.of(),map,0,0,100,100);}
    static BufferedImage paint(NetworkPanel panel){var image=new BufferedImage(900,600,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();panel.paintRecordingFrame(g);g.dispose();return image;}
    static Object field(Object object,String name)throws Exception{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static Point2D point(NetworkPanel panel,double x,double y)throws Exception{var method=NetworkPanel.class.getDeclaredMethod("worldToScreen",double.class,double.class);method.setAccessible(true);return (Point2D)method.invoke(panel,x,y);}
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory(Path.of("target"),"geometry-check-");Path config=dir.resolve("config.xml");
        check(DetailedNetworkGeometry.discover(config).isEmpty(),"Missing file discovered");
        Path csv=dir.resolve("test_detailed_network.csv");
        Files.writeString(csv,"\ufeffLinkId,Geometry\n"+
            "curve,\"LINESTRING(100 0,100 50,0 50,0 0)\"\n"+
            "1,\"LINESTRING(0 0,0 50)\"\n"+
            "2,\"LINESTRING(100 50,0 50)\"\n"+
            "3,\"LINESTRING(100 50,100 0)\"\n"+
            "bad,\"LINESTRING(2600000 1200000,2600100 1200000)\"\n");
        check(DetailedNetworkGeometry.discover(config).equals(java.util.List.of(csv.toAbsolutePath())),"Discovery failed");
        var network=network(link("curve",0,0,100,0,null));var loaded=DetailedNetworkGeometry.load(csv,network);
        check(loaded.size()==1&&loaded.get("curve").length()==200,"Reversed geometry was not oriented/loaded");
        var midway=loaded.get("curve").at(0.5,0);check(midway.x()==50&&midway.y()==50,"Travel is not distance based");
        for(String retained:java.util.List.of("1","3"))for(String chain:java.util.List.of("1_2_3","3_2_1",retained.equals("1")?"2_3":"1_2")){
            var merged=DetailedNetworkGeometry.load(csv,network(link(retained,0,0,100,0,chain)));
            check(merged.size()==1&&merged.mergedCount()==1&&merged.get(retained).length()==200,"Merged chain failed: "+retained+"/"+chain);
        }
        check(DetailedNetworkGeometry.load(csv,network(link("1",0,0,100,0,"1_missing_3"))).size()==0,"Incomplete chain incorrectly used retained fragment");
        check(DetailedNetworkGeometry.load(csv,network(link("bad",0,0,100,0,null))).size()==0,"Wrong CRS geometry accepted");
        Set<String> visible=new HashSet<>();loaded.query(40,49,60,51,visible);check(visible.contains("curve"),"Curved excursion absent from spatial query");
        SwingUtilities.invokeAndWait(()->{try{
            var model=new SimulationModel(network,new VehicleTraversal[]{new VehicleTraversal(0,"car","curve",0,100)},Map.of(),Map.of("car","car"),Map.of(),Map.of(),Map.of(),null);
            var playback=new PlaybackController(model,0,100,1);playback.seek(50);
            var panel=new NetworkPanel(model,playback);panel.setSize(900,600);
            panel.setDetailedGeometryEnabled(true);check(!panel.isDetailedGeometryEnabled(),"Enabled missing geometry");
            BufferedImage straight=paint(panel);panel.setDetailedGeometry(loaded);panel.setDetailedGeometryEnabled(true);
            BufferedImage curved=paint(panel);var curves=(Map<?,?>)field(panel,"screenCurves");check(curves.size()==1,"No curve rendered");
            var line=(LinkPolyline)curves.values().iterator().next();var center=line.at(.5,0);var expected=point(panel,50,50);
            check(Math.hypot(center.x()-expected.getX(),center.y()-expected.getY())<.001,"Wrong screen curve position");
            check(straight.getRGB((int)center.x(),(int)center.y())!=curved.getRGB((int)center.x(),(int)center.y()),"Vehicle/road stayed on endpoint chord");
            // Remove only the vehicle; the pixels at its curved location must change.
            playback.seek(100);BufferedImage roadOnly=paint(panel);
            check(curved.getRGB((int)center.x(),(int)center.y())!=roadOnly.getRGB((int)center.x(),(int)center.y()),"Vehicle did not follow curved path");
            playback.seek(50);panel.setDetailedGeometryEnabled(false);BufferedImage restored=paint(panel);
            check(Arrays.equals(straight.getRGB(0,0,900,600,null,0,900),restored.getRGB(0,0,900,600,null,0,900)),"Toggle did not restore straight rendering");
            panel.setDetailedGeometryEnabled(true);ImageIO.write(paint(panel),"png",Path.of("target/detailed-geometry.png").toFile());
            // Query a close view of the bend, with both endpoints below the viewport.
            var zoom=NetworkPanel.class.getDeclaredField("zoom");zoom.setAccessible(true);zoom.set(panel,10.0);
            var px=NetworkPanel.class.getDeclaredField("panX");px.setAccessible(true);px.set(panel,450-50*(double)field(panel,"baseScale")*10);
            var py=NetworkPanel.class.getDeclaredField("panY");py.setAccessible(true);py.set(panel,300-50*(double)field(panel,"baseScale")*10);
            paint(panel);check(!((Map<?,?>)field(panel,"screenCurves")).isEmpty(),"Offscreen endpoints culled visible bend");
        }catch(Exception ex){throw new RuntimeException(ex);}});
        System.out.println("PASS: CSV discovery, reversed geometry, merged chains, safe fallbacks, distance-based vehicles, viewport bends and reversible toggle.");
    }
}
