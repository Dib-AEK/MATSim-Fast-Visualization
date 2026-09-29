package com.matsim.viz.ui;

import com.matsim.viz.domain.*;
import com.matsim.viz.engine.*;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.*;
import javax.swing.SwingUtilities;

/** Render actual prepared bins, including missing link entries, to catch boundary jumps. */
public final class HeatmapTransitionCheck {
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
    private static int pixel(NetworkPanel panel, PlaybackController playback, double time) {
        playback.seek(time);
        BufferedImage image=new BufferedImage(600,400,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();panel.paint(g);g.dispose();
        return image.getRGB(200,200)&255;
    }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            var link=new LinkSegment("road","a","b",0,50,100,50,100,15,2,Set.of("car"),Map.of());
            var model=new SimulationModel(new NetworkData(Map.of(),Map.of("road",link),0,0,100,100),
                    new VehicleTraversal[]{new VehicleTraversal(0,"one","road",0,10),
                            new VehicleTraversal(1,"two","road",130,140)},
                    Map.of(),Map.of("one","car","two","car"),Map.of(),Map.of(),Map.of(),null);
            var playback=new PlaybackController(model,0,180,1);
            var panel=new NetworkPanel(model,playback);
            panel.setSize(600,400);panel.setSuppressOverlays(true);
            panel.setHeatmapTimeBinSeconds(60);
            panel.setFlowHeatmapLowColor(Color.BLACK);panel.setFlowHeatmapHighColor(Color.WHITE);
            panel.setSpeedHeatmapLowColor(Color.BLACK);panel.setSpeedHeatmapHighColor(Color.WHITE);
            panel.setSpeedRatioHeatmapLowColor(Color.BLACK);panel.setSpeedRatioHeatmapHighColor(Color.WHITE);
            for(var mode: List.of(NetworkPanel.VisualizationMode.FLOW_HEATMAP,
                    NetworkPanel.VisualizationMode.SPEED_HEATMAP,NetworkPanel.VisualizationMode.SPEED_RATIO_HEATMAP)) {
                panel.setVisualizationMode(mode);panel.preprocessHeatmapsForCurrentSettings();
                for(boolean overview: new boolean[]{false,true}) {
                    panel.setZoomDetail(overview?1000:0.3,overview?2000:1.5,0.75);
                    int peak=pixel(panel,playback,0), half=pixel(panel,playback,30), empty=pixel(panel,playback,60);
                    check(peak>empty+50,"No visible heatmap difference: "+mode);
                    check(Math.abs(half-(peak+empty)/2.0)<=2,"Colour does not interpolate linearly: "+mode);
                    check(Math.abs(pixel(panel,playback,59.999)-empty)<=1,"Jump into empty bin: "+mode);
                    check(Math.abs(pixel(panel,playback,90)-half)<=2,"Colour does not fade back in: "+mode);
                    check(Math.abs(pixel(panel,playback,119.999)-pixel(panel,playback,120))<=1,"Jump into populated bin: "+mode);
                    check(pixel(panel,playback,30)==half,"Seeking changed the interpolation result");
                }
            }
            var reverse=new LinkSegment("reverse","b","a",100,50,0,50,100,15,2,Set.of("car"),Map.of());
            var opposing=new SimulationModel(new NetworkData(Map.of(),Map.of("road",link,"reverse",reverse),0,0,100,100),
                    new VehicleTraversal[]{new VehicleTraversal(0,"one","road",0,10),
                            new VehicleTraversal(1,"two","reverse",60,65),
                            new VehicleTraversal(2,"three","road",130,140)},
                    Map.of(),Map.of("one","car","two","car","three","car"),Map.of(),Map.of(),Map.of(),null);
            var oppositePlayback=new PlaybackController(opposing,0,180,1);
            var oppositePanel=new NetworkPanel(opposing,oppositePlayback);
            oppositePanel.setSize(600,400);oppositePanel.setSuppressOverlays(true);
            oppositePanel.setHeatmapTimeBinSeconds(60);oppositePanel.setZoomDetail(1000,2000,0.75);
            oppositePanel.setVisualizationMode(NetworkPanel.VisualizationMode.SPEED_HEATMAP);
            oppositePanel.setSpeedHeatmapLowColor(Color.BLACK);oppositePanel.setSpeedHeatmapHighColor(Color.WHITE);
            oppositePanel.preprocessHeatmapsForCurrentSettings();
            int first=pixel(oppositePanel,oppositePlayback,0), next=pixel(oppositePanel,oppositePlayback,60);
            check(Math.abs(pixel(oppositePanel,oppositePlayback,30)-(first+next)/2.0)<=2,
                    "Opposing directions must aggregate before interpolating");
            check(Math.abs(pixel(oppositePanel,oppositePlayback,59.999)-next)<=1,
                    "Opposing direction change caused a colour jump");
        });
        System.out.println("PASS: heatmap colours fade continuously into/out of empty bins, in overview and detail, including seeks.");
    }
}

