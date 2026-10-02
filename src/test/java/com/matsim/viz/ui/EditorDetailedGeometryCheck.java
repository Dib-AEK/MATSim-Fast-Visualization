package com.matsim.viz.ui;
import com.matsim.viz.ui.editor.NetworkEditorPanel;
import com.matsim.viz.parser.DetailedNetworkGeometry;
import com.matsim.viz.domain.*;
import java.nio.file.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.*;
import javax.swing.SwingUtilities;
import javax.imageio.ImageIO;
public final class EditorDetailedGeometryCheck {
 static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
 static Object field(Object target,String name)throws Exception{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
 static Point point(NetworkEditorPanel panel,double x,double y)throws Exception{var m=panel.getClass().getDeclaredMethod("worldToScreen",double.class,double.class);m.setAccessible(true);var p=(Point2D)m.invoke(panel,x,y);return new Point((int)Math.round(p.getX()),(int)Math.round(p.getY()));}
 static BufferedImage paint(NetworkEditorPanel panel){var image=new BufferedImage(700,500,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();panel.paint(g);g.dispose();return image;}
 public static void main(String[] args)throws Exception{
  Path dir=Files.createTempDirectory(Path.of("target"),"editor-geometry-");Path csv=dir.resolve("fixture_detailed_network.csv");
  Files.writeString(csv,"link_id,geometry\n1,\"LINESTRING(0 0,0 50)\"\n2,\"LINESTRING(0 50,100 50)\"\n3,\"LINESTRING(100 50,100 0)\"\n");
  var link=new LinkSegment("1","a","b",0,0,100,0,200,15,2,Set.of("car"),Map.of("capacity","1800","old_link_id","1_2_3"));
  var network=new NetworkData(Map.of("a",new NodePoint("a",0,0),"b",new NodePoint("b",100,0)),Map.of("1",link),0,0,100,100);
  var geometry=DetailedNetworkGeometry.load(csv,network);var prepared=NetworkEditorPanel.prepare(network,null,geometry);
  NetworkEditorPanel.SaveSnapshot[] saved={null};
  SwingUtilities.invokeAndWait(()->{var panel=new NetworkEditorPanel(prepared,dir);try{
   panel.setSize(700,500);check(panel.isDetailedGeometryEnabled(),"Geometry not enabled by default");paint(panel);
   for(double[] xy:new double[][]{{0,25},{50,50},{100,25}}){
    Point p=point(panel,xy[0],xy[1]);panel.dispatchEvent(new MouseEvent(panel,MouseEvent.MOUSE_CLICKED,0,0,p.x,p.y,1,false,MouseEvent.BUTTON1));
    check("1".equals(panel.selectedLinkId()),"Old chain segment does not select merged XML link");paint(panel);
   }
   ImageIO.write(paint(panel),"png",dir.resolve("selected-merged-link.png").toFile());
   Object roads=field(panel,"drawingCache"),map=field(panel,"mapLayer");
   var dirty=panel.getClass().getDeclaredField("mapDirty");dirty.setAccessible(true);dirty.setBoolean(panel,true);paint(panel);
   check(roads==field(panel,"drawingCache"),"Map refresh rebuilt editor roads");
   panel.updateLink("1",200,36,3,2400,Set.of("car","bus"));paint(panel);
   check(map==field(panel,"mapLayer"),"Link edit replaced unchanged map raster");
   var changed=panel.snapshotForSave().links().get("1");check(changed.freeSpeed()==10&&changed.lanes()==3&&changed.attributes().get("old_link_id").equals("1_2_3"),"Merged update/provenance lost");
   for(double bad:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})for(int axis=0;axis<4;axis++){
    boolean rejected=false;try{panel.updateLink("1",axis==0?bad:200,axis==1?bad:36,axis==2?bad:3,axis==3?bad:2400,Set.of("car"));}catch(IllegalArgumentException expected){rejected=true;}
    check(rejected,"Invalid numeric value accepted");check(panel.snapshotForSave().links().get("1").equals(changed),"Invalid update mutated link");
   }
   panel.undo();check(panel.snapshotForSave().links().get("1").lanes()==2,"Merged undo failed");panel.redo();saved[0]=panel.snapshotForSave();
   panel.setDetailedGeometryEnabled(false);paint(panel);
   var picks=(java.util.List<?>)field(panel,"renderedPickLinks");check(picks.size()==1,"Straight toggle retained curve segments");
   panel.setDetailedGeometryEnabled(true);
   var zoom=panel.getClass().getDeclaredField("zoom");zoom.setAccessible(true);zoom.setDouble(panel,10);panel.centreOn(50,50);paint(panel);
   check(!((java.util.List<?>)field(panel,"renderedPickLinks")).isEmpty(),"Curved excursion culled by XML chord bounds");
   check(network.getLinks().get("1").lanes()==2,"Playback source was modified");
  }catch(Exception ex){throw new AssertionError(ex);}finally{panel.disposeResources();}});
  Path xml=dir.resolve("edited.xml.gz");saved[0].write(xml);
  var restored=org.matsim.core.network.NetworkUtils.createNetwork();new org.matsim.core.network.io.MatsimNetworkReader(restored).readFile(xml.toString());
  var edited=restored.getLinks().get(org.matsim.api.core.v01.Id.createLinkId("1"));
  check(restored.getLinks().size()==1&&edited.getCapacity()==2400&&"1_2_3".equals(edited.getAttributes().getAttribute("old_link_id")),"Merged native export lost chain or changes");
  System.out.println("PASS: editor merged curves, picking, toggle, viewport bends, map-only refresh, strict validation, undo and native export. "+dir);
 }
}
