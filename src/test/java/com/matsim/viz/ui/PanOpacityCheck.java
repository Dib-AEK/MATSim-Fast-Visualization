package com.matsim.viz.ui;

import com.matsim.viz.domain.*;
import com.matsim.viz.engine.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.*;
import javax.swing.SwingUtilities;

public final class PanOpacityCheck {
    private static Object field(NetworkPanel panel, String name) throws Exception {
        Field field = NetworkPanel.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(panel);
    }
    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
    private static BufferedImage paint(NetworkPanel panel) {
        BufferedImage image = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); panel.paint(g); g.dispose(); return image;
    }
    private static void mouse(NetworkPanel panel, int type, int x, int y) {
        panel.dispatchEvent(new MouseEvent(panel, type, 0, 0, x, y, 1, false, MouseEvent.BUTTON1));
    }
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Map<String, LinkSegment> links = new LinkedHashMap<>();
                for (int x=0; x<100; x++) for (int y=0; y<100; y++) {
                    String id=x+":"+y;
                    links.put(id, new LinkSegment(id, id, (x+1)+":"+y, x*10, y*10,
                            (x+1)*10, y*10, 10, 15, 2, Set.of("car"), Map.of()));
                }
                SimulationModel model = new SimulationModel(new NetworkData(Map.of(),links,0,0,1000,1000),
                        new VehicleTraversal[0],Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),null);
                NetworkPanel panel = new NetworkPanel(model,new PlaybackController(model,0,100,1));
                panel.setSize(1000,800);
                panel.setMapBackground(Color.BLACK); panel.setMapRoad(Color.WHITE);
                panel.setRoadOpacity(1); // Controlled opaque baseline, independent of the user's default opacity.
                BufferedImage opaque = paint(panel);
                Object layer = field(panel,"cachedRoadLayer");
                panel.setRoadOpacity(0);
                BufferedImage transparent = paint(panel);
                check(layer == field(panel,"cachedRoadLayer"), "Opacity rebuilt the road cache");
                panel.setRoadOpacity(0.5);
                BufferedImage half = paint(panel);
                int samples=0;
                for(int y=120;y<650;y++) for(int x=100;x<700;x++) {
                    int a=opaque.getRGB(x,y)&255, b=transparent.getRGB(x,y)&255, c=half.getRGB(x,y)&255;
                    if(a>200 && b==0) {
                        check(Math.abs(c-a*0.5)<=2,"Road opacity not composited as a single layer"); samples++;
                    }
                }
                check(samples>100,"Opacity test found no road pixels");
                panel.setRoadOpacity(1);
                mouse(panel,MouseEvent.MOUSE_PRESSED,300,300);
                long start=System.nanoTime();
                for(int i=1;i<=20;i++) {
                    mouse(panel,MouseEvent.MOUSE_DRAGGED,300+i*5,300+i*3); paint(panel);
                    check(layer == field(panel,"cachedRoadLayer"),"Normal drag rebuilt the road cache");
                }
                double cachedMs=(System.nanoTime()-start)/1e6/20;
                mouse(panel,MouseEvent.MOUSE_RELEASED,400,360);
                BufferedImage reused=paint(panel);
                check(field(panel,"dragStart")==null,"Drag state not released");
                panel.setMapRoad(Color.WHITE); // Force fresh geometry at the same final camera.
                start=System.nanoTime();
                BufferedImage fresh=paint(panel);
                double rebuildMs=(System.nanoTime()-start)/1e6;
                long error=0;
                for(int y=120;y<650;y++) for(int x=100;x<700;x++)
                    error+=Math.abs((reused.getRGB(x,y)&255)-(fresh.getRGB(x,y)&255));
                check(error/(530.0*600)<0.2,"Cached pan is misaligned with fresh rendering");
                layer=field(panel,"cachedRoadLayer");
                mouse(panel,MouseEvent.MOUSE_PRESSED,400,360);
                mouse(panel,MouseEvent.MOUSE_DRAGGED,950,360); paint(panel);
                check(layer!=field(panel,"cachedRoadLayer"),"Cache did not refresh beyond its margin");
                LinkSegment road = new LinkSegment("road", "a", "b", 0, 50, 100, 50,
                        100, 15, 2, Set.of("car"), Map.of());
                SimulationModel traffic = new SimulationModel(new NetworkData(Map.of(),Map.of("road",road),0,0,100,100),
                        new VehicleTraversal[]{new VehicleTraversal(0,"car","road",0,100)},
                        Map.of(),Map.of("car","car"),Map.of(),Map.of(),Map.of(),null);
                PlaybackController playback = new PlaybackController(traffic,0,100,1);
                playback.seek(50);
                NetworkPanel vehiclePanel = new NetworkPanel(traffic,playback);
                vehiclePanel.setSize(800,800); vehiclePanel.setMapBackground(Color.BLACK);
                vehiclePanel.setSuppressOverlays(true); vehiclePanel.setRoadOpacity(0);
                BufferedImage visibleVehicle=paint(vehiclePanel);
                int vehiclePixels=0;
                for(int y=200;y<600;y++) for(int x=200;x<600;x++)
                    if((visibleVehicle.getRGB(x,y)&0xffffff)!=0) vehiclePixels++;
                check(vehiclePixels>10,"Road transparency also hid vehicles");
                System.out.printf("PASS: transparency blending/cache reuse; 10,000 links: cached pan %.2f ms/frame, fresh render %.2f ms.%n",cachedMs,rebuildMs);
            } catch(Exception ex) { throw new RuntimeException(ex); }
        });
    }
}
