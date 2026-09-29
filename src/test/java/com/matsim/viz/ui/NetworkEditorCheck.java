package com.matsim.viz.ui;

import com.matsim.viz.ui.editor.NetworkEditorPanel;
import com.matsim.viz.domain.*;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.api.core.v01.Id;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

public final class NetworkEditorCheck {
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private static void paint(NetworkEditorPanel panel) {
        var image=new BufferedImage(1000,700,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();panel.paint(g);g.dispose();
    }
    private static Object field(Object target,String name) throws Exception {
        var f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    public static void main(String[] args) throws Exception {
        Map<String,NodePoint> nodes=new LinkedHashMap<>();
        Map<String,LinkSegment> links=new LinkedHashMap<>();
        for(int i=0;i<=10000;i++) nodes.put("n"+i,new NodePoint("n"+i,i%101*10,i/101*10));
        for(int i=0;i<10000;i++) {
            NodePoint a=nodes.get("n"+i),b=nodes.get("n"+(i+1));
            links.put("l"+i,new LinkSegment("l"+i,a.id(),b.id(),a.x(),a.y(),b.x(),b.y(),100,15,2,Set.of("car"),Map.of("capacity","1800","name","Test road")));
        }
        NetworkData data=new NetworkData(nodes,links,0,0,1000,1000);
        SpatialGrid index=SpatialGrid.build(data);
        long start=System.nanoTime();
        var prepared=NetworkEditorPanel.prepare(data,index);
        check(prepared.links().get(null)==null,"Empty selection lookup failed");
        check(prepared.spatialIndex()==index,"Editor duplicated viewer spatial index");
        check(prepared.links().get("l1")==links.get("l1"),"Editor copied immutable links");
        System.out.printf("Prepared 10,000 links off EDT in %.1f ms%n",(System.nanoTime()-start)/1e6);
        NetworkEditorPanel[] holder={null};
        NetworkEditorPanel.SaveSnapshot[] save={null};
        SwingUtilities.invokeAndWait(()->{
            try {
                var panel=new NetworkEditorPanel(prepared,Path.of("target/editor-map-cache"));holder[0]=panel;
                panel.setSize(1000,700);paint(panel);
                check(!panel.hasGeoCoordinates(),"Opening editor should not start map requests");
                check(panel.findById("l1",false),"ID search failed");paint(panel);
                Object cache=field(panel,"drawingCache");
                long before=System.nanoTime();for(int i=0;i<20;i++)paint(panel);
                check(cache==field(panel,"drawingCache"),"Unchanged selection rebuilt drawing cache");
                System.out.printf("Cached selected editor repaint: %.2f ms/frame%n",(System.nanoTime()-before)/1e6/20);
                double oldPan=(double)field(panel,"panX");
                panel.dispatchEvent(new java.awt.event.MouseEvent(panel,501,1,0,400,300,1,false,1));
                panel.dispatchEvent(new java.awt.event.MouseEvent(panel,506,2,java.awt.event.InputEvent.BUTTON1_DOWN_MASK,480,330,0,false,0));
                panel.dispatchEvent(new java.awt.event.MouseEvent(panel,502,3,0,480,330,1,false,1));
                paint(panel);
                check((double)field(panel,"panX")==oldPan+80,"Plain left drag did not pan");
                check(cache==field(panel,"drawingCache"),"Small pan rebuilt geometry");
                double oldZoom=(double)field(panel,"zoom");
                panel.dispatchEvent(new java.awt.event.MouseWheelEvent(panel,507,4,0,500,350,0,false,0,1,-1));
                paint(panel);
                check((double)field(panel,"zoom")>oldZoom,"Wheel did not zoom");
                check(cache==field(panel,"drawingCache"),"Wheel rebuilt geometry before settling");
                panel.updateLink("l1",120,50,3,2200,Set.of("car","bus"));
                check(panel.hasUnsavedChanges()&&panel.canUndo(),"Edit not tracked");
                check(Math.abs(panel.snapshotForSave().links().get("l1").freeSpeed()-50/3.6)<1e-9,"km/h conversion incorrect");
                panel.undo();check(panel.snapshotForSave().links().get("l1").lanes()==2,"Undo failed");
                panel.redo();check(panel.snapshotForSave().links().get("l1").lanes()==3,"Redo failed");
                boolean rejected=false;
                try {panel.updateLink("l1",120,Double.NaN,3,2200,Set.of("car"));}catch(IllegalArgumentException e){rejected=true;}
                check(rejected,"NaN speed accepted");
                panel.findById("l1",false);panel.createReverseLink("reverse");
                check(panel.snapshotForSave().links().get("reverse").fromNodeId().equals("n2"),"Reverse direction incorrect");
                panel.undo();check(!panel.snapshotForSave().links().containsKey("reverse"),"Reverse undo failed");
                panel.redo();
                panel.findById("n1",true);int removed=panel.deleteSelectedNodeAndConnectedLinks();
                check(removed==3,"Connected links not removed with node");
                panel.undo();check(panel.snapshotForSave().nodes().containsKey("n1")&&panel.snapshotForSave().links().containsKey("reverse"),"Node deletion undo incomplete");
                save[0]=panel.snapshotForSave();
                panel.setActive(false);check(field(panel,"drawingCache")==null,"Hidden editor kept bitmap");
                panel.setActive(true);check(panel.snapshotForSave().links().get("l1").lanes()==3,"Switching views lost changes");
                check(data.getLinks().get("l1").lanes()==2 && data.getLinks().size()==10000,"Editor modified playback source");
            }catch(Exception e){throw new RuntimeException(e);}finally{if(holder[0]!=null)holder[0].disposeResources();}
        });
        Path out=Files.createTempDirectory(Path.of("target"),"editor-check-").resolve("network.xml.gz");
        save[0].write(out);
        var restored=NetworkUtils.createNetwork();new MatsimNetworkReader(restored).readFile(out.toString());
        var edited=restored.getLinks().get(Id.createLinkId("l1"));
        check(edited.getNumberOfLanes()==3&&edited.getCapacity()==2200&&edited.getAllowedModes().contains("bus"),"MATSim round trip lost planning edits");
        check("Test road".equals(edited.getAttributes().getAttribute("name")),"Extra attribute lost");
        check(restored.getLinks().containsKey(Id.createLinkId("reverse")),"Reverse link missing from saved network");
        byte[] original=Files.readAllBytes(out);
        var invalid=new NetworkEditorPanel.SaveSnapshot(Map.of(),save[0].links());
        boolean rejected=false;try{invalid.write(out);}catch(IllegalArgumentException e){rejected=true;}
        check(rejected&&Arrays.equals(original,Files.readAllBytes(out)),"Invalid export replaced an existing network");
        System.out.println("PASS: editor search, cache, safe edits, undo/redo, reverse links, retention and native MATSim export.");
    }
}
