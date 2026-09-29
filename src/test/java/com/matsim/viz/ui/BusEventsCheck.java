package com.matsim.viz.ui;
import com.matsim.viz.parser.*;
import com.matsim.viz.config.ConfigLoader;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.*;
import org.matsim.core.api.experimental.events.*;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.core.events.algorithms.EventWriterXML;
import java.nio.file.*;
import java.util.*;
public class BusEventsCheck {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  Path folder=Files.createTempDirectory(Path.of("target"),"bus-events-check-");Path file=folder.resolve("events.xml.gz");
  var writer=new EventWriterXML(file.toString());var v=Id.createVehicleId("bus1");var d=Id.createPersonId("driver");var p=Id.createPersonId("passenger");var a=Id.createLinkId("a");var b=Id.createLinkId("b");var stop=Id.create("stop",TransitStopFacility.class);
  writer.handleEvent(new TransitDriverStartsEvent(0,d,v,Id.create("line",TransitLine.class),Id.create("route",TransitRoute.class),Id.create("departure",Departure.class)));
  writer.handleEvent(new VehicleEntersTrafficEvent(0,d,a,v,"car",1));
  writer.handleEvent(new VehicleArrivesAtFacilityEvent(0,v,stop,0));
  for(int i=0;i<20;i++)writer.handleEvent(new PersonEntersVehicleEvent(1,Id.createPersonId("passenger"+i),v));
  writer.handleEvent(new VehicleDepartsAtFacilityEvent(2,v,stop,0));
  writer.handleEvent(new PersonEntersVehicleEvent(3,p,v)); // Not at a facility: no stale stop boarding.
  writer.handleEvent(new LinkLeaveEvent(4,v,a));writer.handleEvent(new LinkEnterEvent(4,v,b));
  writer.handleEvent(new LinkEnterEvent(4,v,b)); // Repeated event must not manufacture a frame.
  writer.handleEvent(new LinkLeaveEvent(4,v,b)); // Instantaneous crossing must not linger.
  writer.handleEvent(new LinkEnterEvent(4,v,a));writer.handleEvent(new LinkLeaveEvent(14,v,a));
  writer.handleEvent(new VehicleLeavesTrafficEvent(14,d,a,v,"car",1));
  var virtual=Id.createVehicleId("teleported");
  writer.handleEvent(new TransitDriverStartsEvent(20,d,virtual,Id.create("line",TransitLine.class),Id.create("route",TransitRoute.class),Id.create("other",Departure.class)));
  writer.handleEvent(new VehicleArrivesAtFacilityEvent(20,virtual,stop,0));writer.handleEvent(new VehicleDepartsAtFacilityEvent(25,virtual,stop,0));
  writer.handleEvent(new LinkEnterEvent(30,v,a)); // Truncated prior duty.
  writer.handleEvent(new TransitDriverStartsEvent(100,d,v,Id.create("line",TransitLine.class),Id.create("route",TransitRoute.class),Id.create("next",Departure.class)));
  writer.handleEvent(new LinkEnterEvent(100,v,b));
  writer.handleEvent(new LinkLeaveEvent(105,v,a)); // Unrelated leave must not close b.
  writer.handleEvent(new LinkLeaveEvent(110,v,b));
  writer.handleEvent(new LinkEnterEvent(120,v,a));
  writer.handleEvent(new VehicleAbortsEvent(140,v,a));
  writer.closeFile();
  var result=new MatsimEventsProcessor().readTraversals(file,Map.of("bus1","bus","teleported","bus"));
  check(result.traversals().length==4,"Phantom or missing bus traversals");
  check(result.traversals()[0].enterTimeSeconds()==0 && result.traversals()[0].leaveTimeSeconds()==4,"First link missing");
  check(result.traversals()[1].enterTimeSeconds()==4 && result.traversals()[1].leaveTimeSeconds()==14,"Link timing changed");
  check(result.traversals()[2].enterTimeSeconds()==100 && result.traversals()[2].leaveTimeSeconds()==110,"Stale duty/mismatched leave corrupted movement");
  check(result.traversals()[3].leaveTimeSeconds()==140,"Abort failed to close final traversal");
  check(result.ptStopInteractions().length==20,"Boarding duplicated buses or used a stale stop");
  check(result.ptStopInteractions()[0].mode().equals("bus"),"Network mode car replaced bus stop mode");
  check(result.vehicleToMode().get("bus1").equals("bus"),"Schedule bus mode lost");
  check(result.vehicleToPerson().get("bus1").equals("driver"),"Passenger replaced bus driver");
  var config=ConfigLoader.load(Path.of("config/app.properties"));
  check(config.uiBidirectionalOffset()==0.1 && config.uiLaneWidthMeters()==3.5,"Display defaults wrong");
  System.out.println("PASS: simulated bus first links, no passenger/instantaneous duplicates, stop clearing, teleported service exclusion, display defaults.");
 }
}
