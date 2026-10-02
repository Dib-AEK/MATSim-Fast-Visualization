package com.matsim.viz.ui;

import com.matsim.viz.domain.*;
import com.matsim.viz.ui.editor.NetworkEditorPanel;
import com.matsim.viz.config.AppDefaults;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.api.core.v01.Id;
import java.nio.file.*;
import java.util.*;

public final class EditorSpeedClampCheck {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var nodes = Map.of("a", new NodePoint("a",0,0), "b", new NodePoint("b",100,0));
        double min=AppDefaults.Editor.SAVE_MIN_SPEED_KMH/3.6, max=AppDefaults.Editor.SAVE_MAX_SPEED_KMH/3.6;
        double[] values={Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-1,0,1,min,15,max,1000};
        Map<String,LinkSegment> links=new LinkedHashMap<>();
        for(int i=0;i<values.length;i++) links.put("l"+i,new LinkSegment("l"+i,"a","b",0,0,100,0,100,values[i],2,Set.of("pt"),Map.of("capacity","2300","old_link_id","1_2")));
        Path output=Files.createTempDirectory(Path.of("target"),"speed-clamp-").resolve("network.xml.gz");
        var snapshot=new NetworkEditorPanel.SaveSnapshot(nodes,links);
        snapshot.write(output);
        var restored=NetworkUtils.createNetwork();new MatsimNetworkReader(restored).readFile(output.toString());
        for(int i=0;i<values.length;i++) {
            var link=restored.getLinks().get(Id.createLinkId("l"+i));
            check(Math.abs(link.getFreespeed()-Math.max(min,Math.min(max,values[i])))<1e-10,"Wrong exported speed "+i);
            check(link.getCapacity()==2300 && link.getLength()==100 && link.getNumberOfLanes()==2 && "1_2".equals(link.getAttributes().getAttribute("old_link_id")),"Other fields changed");
            check(links.get("l"+i).freeSpeed()==values[i],"Source changed");
        }
        links.put("nan",new LinkSegment("nan","a","b",0,0,100,0,100,Double.NaN,2,Set.of("pt"),Map.of("capacity","2300")));
        byte[] before=Files.readAllBytes(output);
        try {snapshot.write(output);throw new AssertionError("NaN accepted");}
        catch(NetworkEditorPanel.NetworkValidationException ex){check(ex.issues().size()==1 && ex.issues().containsKey("nan"),"Clamped speeds reported as invalid");}
        check(Arrays.equals(before,Files.readAllBytes(output)),"Failed save damaged destination");
        System.out.println("PASS: export clamps all speeds including infinities before validation, preserves other fields/source, rejects NaN and retains destination.");
    }
}
