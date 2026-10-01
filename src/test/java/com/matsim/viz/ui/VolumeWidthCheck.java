package com.matsim.viz.ui;
import com.matsim.viz.domain.*;
import com.matsim.viz.engine.*;
import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
public final class VolumeWidthCheck {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static BufferedImage paint(NetworkPanel panel){var image=new BufferedImage(600,400,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();panel.paintRecordingFrame(g);g.dispose();return image;}
 static int thickness(NetworkPanel panel,BufferedImage image,double y)throws Exception{
  var method=NetworkPanel.class.getDeclaredMethod("worldToScreen",double.class,double.class);method.setAccessible(true);
  var point=(Point2D)method.invoke(panel,50.0,y);int count=0;
  for(int row=(int)point.getY()-20;row<point.getY()+20;row++)if((image.getRGB((int)point.getX(),row)&255)>180)count++;
  return count;
 }
 public static void main(String[] args)throws Exception{
  SwingUtilities.invokeAndWait(()->{try{
   Map<String,LinkSegment> links=new HashMap<>();var traversals=new ArrayList<VehicleTraversal>();Map<String,String> modes=new HashMap<>();
   for(int i=0;i<3;i++){
    links.put("l"+i,new LinkSegment("l"+i,"a"+i,"b"+i,0,20+30*i,100,20+30*i,100,15,3,Set.of("bus"),Map.of()));
    for(int j=0;j<(i==0?0:i==1?1:10);j++){String id="v"+i+"-"+j;modes.put(id,"bus");traversals.add(new VehicleTraversal(traversals.size(),id,"l"+i,j,j+1));}
   }
   var model=new SimulationModel(new NetworkData(Map.of(),links,0,0,100,100),traversals.toArray(VehicleTraversal[]::new),Map.of(),modes,Map.of(),Map.of(),Map.of(),null);
   var playback=new PlaybackController(model,0,180,1);var panel=new NetworkPanel(model,playback);panel.setSize(600,400);
   panel.setSuppressOverlays(true);panel.setRoadOpacity(1);panel.setHeatmapTimeBinSeconds(30);
   panel.setFlowHeatmapLowColor(Color.WHITE);panel.setFlowHeatmapHighColor(Color.WHITE);panel.setVolumeWidthPixels(2,12);
   for(var mode:java.util.List.of(NetworkPanel.VisualizationMode.FLOW_HEATMAP,NetworkPanel.VisualizationMode.PT_FLOW_HEATMAP)){
    panel.setVisualizationMode(mode);panel.preprocessHeatmapsForCurrentSettings();
    for(boolean overview:new boolean[]{false,true}){
     panel.setZoomDetail(overview?1000:0.01,overview?2000:0.02,0.75);playback.seek(0);var image=paint(panel);
     int low=thickness(panel,image,20),medium=thickness(panel,image,50),high=thickness(panel,image,80);
     check(Math.abs(low-2)<=1 && Math.abs(high-12)<=1 && medium>low && medium<high,"Volume thickness/order incorrect: "+low+","+medium+","+high);
     if(!overview && mode==NetworkPanel.VisualizationMode.FLOW_HEATMAP)ImageIO.write(image,"png",Path.of("target/volume-width.png").toFile());
     panel.setVolumeWidthPixels(4,4);check(Math.abs(thickness(panel,paint(panel),80)-4)<=1,"Live width change not applied");panel.setVolumeWidthPixels(2,12);
    }
   }
   var width=NetworkPanel.class.getDeclaredMethod("volumeWidthPixels",double.class);width.setAccessible(true);
   double atStart=(double)width.invoke(panel,NetworkPanel.interpolateHeatmapIntensity(10,0,10,0));
   double atHalf=(double)width.invoke(panel,NetworkPanel.interpolateHeatmapIntensity(10,0,10,0.5));
   double atEnd=(double)width.invoke(panel,NetworkPanel.interpolateHeatmapIntensity(10,0,10,1));
   check(Math.abs(atHalf-(atStart+atEnd)/2)<1e-9,"Width does not interpolate smoothly");
   boolean rejected=false;try{panel.setVolumeWidthPixels(20,2);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"Inverted range accepted");
  }catch(Exception ex){throw new AssertionError(ex);}});
  System.out.println("PASS: road/PT volume widths, min/max bounds, overview/detail, live updates and time interpolation.");
 }
}
