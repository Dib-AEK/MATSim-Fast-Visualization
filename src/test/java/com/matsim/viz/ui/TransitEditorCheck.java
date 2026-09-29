package com.matsim.viz.ui;
import com.matsim.viz.ui.editor.*;
import com.matsim.viz.domain.*;
import java.util.*;
import java.nio.file.*;
import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
public final class TransitEditorCheck {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    static TransitEditorModel.StopInput stop(String id,String arrival,String departure){return new TransitEditorModel.StopInput(id,arrival,departure,true,true,true);}
    public static void main(String[] args)throws Exception{
        var model=TransitEditorModel.load(null,null);
        check(model.line(null)==null,"Empty selection failed");
        var nodes=Map.of("a",new NodePoint("a",0,0),"b",new NodePoint("b",100,0),"c",new NodePoint("c",200,0));
        var links=Map.of("ab",new LinkSegment("ab","a","b",0,0,100,0,100,15,1,Set.of("bus"),Map.of("capacity","1000")),"bc",new LinkSegment("bc","b","c",100,0,200,0,100,15,1,Set.of("bus"),Map.of("capacity","1000")));
        model.addLine("10","Town bus");model.addRoute("10","out","bus");
        model.putStop("s1","Start",10,0,"ab",true);model.putStop("s2","End",190,0,"bc",true);
        model.putVehicleType("bus",40,30,12,80,"bus");model.putVehicle("v1","bus");
        var stops=List.of(stop("s1","","00:00:00"),stop("s2","00:05:00",""));
        var departures=List.of(new TransitEditorModel.DepartureInput("d1","25:00:00","v1"));
        model.applyRoute("10","out","bus",List.of("ab","bc"),stops,departures,links);
        var original=model.route("10","out");original.getAttributes().putAttribute("planner","test");
        boolean rejected=false;
        try{model.applyRoute("10","out","bus",List.of("bc","ab"),stops,departures,links);}catch(IllegalArgumentException e){rejected=true;}
        check(rejected&&model.route("10","out")==original,"Invalid path replaced working route");
        model.putStop("mid","Middle",90,0,"ab",true);
        model.applyRoute("10","out","bus",List.of("ab","bc"),List.of(stops.getFirst(),stop("mid","00:02:00","00:02:20"),stops.getLast()),departures,links);
        model.moveStop("mid",95,2,"ab");model.validate(links);
        check(model.overlays("10",false).getFirst().stops().size()==3,"Overlay stops missing");
        Path folder=model.exportBundle(Path.of("target"),new NetworkEditorPanel.SaveSnapshot(nodes,links));
        var loaded=TransitEditorModel.load(folder.resolve("transitSchedule.xml.gz"),folder.resolve("transitVehicles.xml.gz"));loaded.validate(links);
        var route=loaded.route("10","out");
        check(route.getStops().size()==3&&loaded.departures(route).getFirst().time().equals("25:00:00"),"Schedule round trip failed");
        check("test".equals(route.getAttributes().getAttribute("planner")),"Route metadata lost");
        check(loaded.schedule().getFacilities().get(Id.create("mid",TransitStopFacility.class)).getCoord().getY()==2,"Stop move lost");
        check(loaded.vehicles().getVehicles().get(Id.createVehicleId("v1")).getType().getCapacity().getSeats()==40,"Vehicle capacity lost");
        var overlapping=List.of(departures.getFirst(),new TransitEditorModel.DepartureInput("d2","25:01:00","v1"));
        loaded.applyRoute("10","out","bus",List.of("ab","bc"),loaded.stops(route),overlapping,links);
        rejected=false;try{loaded.validate(links);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"Overlapping vehicle duties accepted");
        System.out.println("PASS: transit creation, transactional route edits, stops, >24h times, native export/reload, metadata and vehicle duties.");
    }
}
