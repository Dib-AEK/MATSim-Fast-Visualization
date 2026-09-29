package com.matsim.viz.ui.editor;

import com.matsim.viz.domain.LinkSegment;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.utils.misc.OptionalTime;
import org.matsim.core.utils.misc.Time;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.*;
import java.nio.file.*;
import java.util.*;

/** Native MATSim editing session. Mutate on FX; export only while its UI is locked. */
public final class TransitEditorModel {
    public record StopInput(String facility, String arrival, String departure, boolean await,
                            boolean boarding, boolean alighting) { }
    public record DepartureInput(String id, String time, String vehicle) { }
    private final TransitSchedule schedule;
    private final Vehicles vehicles;
    private boolean dirty;

    private TransitEditorModel(TransitSchedule schedule, Vehicles vehicles) {
        this.schedule=schedule;this.vehicles=vehicles;
    }
    public static TransitEditorModel load(Path scheduleFile, Path vehiclesFile) {
        var scenario=ScenarioUtils.createScenario(ConfigUtils.createConfig());
        if(scheduleFile!=null)new TransitScheduleReader(scenario).readFile(scheduleFile.toString());
        Vehicles vehicles=VehicleUtils.createVehiclesContainer();
        if(vehiclesFile!=null)new MatsimVehicleReader(vehicles).readFile(vehiclesFile.toString());
        return new TransitEditorModel(scenario.getTransitSchedule(),vehicles);
    }
    public TransitSchedule schedule(){return schedule;}
    public Vehicles vehicles(){return vehicles;}
    public boolean isDirty(){return dirty;}
    public void markSaved(){dirty=false;}
    public TransitLine line(String id){return id==null?null:schedule.getTransitLines().get(Id.create(id,TransitLine.class));}
    public TransitRoute route(String line,String route){
        TransitLine l=line(line);return l==null||route==null?null:l.getRoutes().get(Id.create(route,TransitRoute.class));
    }
    public void addLine(String id,String name){
        requireId(id);if(line(id)!=null)throw new IllegalArgumentException("Line ID already exists");
        var line=schedule.getFactory().createTransitLine(Id.create(id,TransitLine.class));line.setName(name);
        schedule.addTransitLine(line);dirty=true;
    }
    public void addRoute(String lineId,String routeId,String mode){
        requireId(routeId);requireId(mode);var line=line(lineId);
        if(line==null)throw new IllegalArgumentException("Select a line first");
        if(route(lineId,routeId)!=null)throw new IllegalArgumentException("Route ID already exists in this line");
        line.addRoute(schedule.getFactory().createTransitRoute(Id.create(routeId,TransitRoute.class),null,List.of(),mode));dirty=true;
    }
    public List<String> routeLinks(TransitRoute route){
        if(route.getRoute()==null)return List.of();
        var networkRoute=route.getRoute();List<String> ids=new ArrayList<>();
        if(networkRoute.getStartLinkId()!=null)ids.add(networkRoute.getStartLinkId().toString());
        networkRoute.getLinkIds().forEach(id->ids.add(id.toString()));
        if(networkRoute.getEndLinkId()!=null && (ids.size()!=1 || !ids.getFirst().equals(networkRoute.getEndLinkId().toString())))
            ids.add(networkRoute.getEndLinkId().toString());
        return ids;
    }
    public List<StopInput> stops(TransitRoute route){
        return route.getStops().stream().map(s->new StopInput(s.getStopFacility().getId().toString(),
                format(s.getArrivalOffset()),format(s.getDepartureOffset()),s.isAwaitDepartureTime(),s.isAllowBoarding(),s.isAllowAlighting())).toList();
    }
    public List<DepartureInput> departures(TransitRoute route){
        return route.getDepartures().values().stream().sorted(Comparator.comparingDouble(Departure::getDepartureTime))
                .map(d->new DepartureInput(d.getId().toString(),Time.writeTime(d.getDepartureTime()),d.getVehicleId()==null?"":d.getVehicleId().toString())).toList();
    }
    public void applyRoute(String lineId,String routeId,String mode,List<String> links,List<StopInput> stopRows,
                           List<DepartureInput> departureRows,Map<String,LinkSegment> network){
        requireId(mode);TransitRoute old=route(lineId,routeId);
        if(old==null)throw new IllegalArgumentException("Select a route first");
        List<TransitRouteStop> stops=new ArrayList<>();
        for(StopInput row:stopRows){
            var facility=schedule.getFacilities().get(Id.create(row.facility(),TransitStopFacility.class));
            if(facility==null)throw new IllegalArgumentException("Unknown stop: "+row.facility());
            var stop=schedule.getFactory().createTransitRouteStop(facility,offset(row.arrival()),offset(row.departure()));
            stop.setAwaitDepartureTime(row.await());stop.setAllowBoarding(row.boarding());stop.setAllowAlighting(row.alighting());stops.add(stop);
        }
        List<Id<Link>> ids=links.stream().map(Id::createLinkId).toList();
        var networkRoute=ids.isEmpty()?null:RouteUtils.createLinkNetworkRouteImpl(ids.getFirst(),
                ids.size()<3?List.of():ids.subList(1,ids.size()-1),ids.getLast());
        var replacement=schedule.getFactory().createTransitRoute(old.getId(),networkRoute,stops,mode);
        replacement.setDescription(old.getDescription());
        old.getAttributes().getAsMap().forEach(replacement.getAttributes()::putAttribute);
        Set<String> departureIds=new HashSet<>();
        for(DepartureInput row:departureRows){
            requireId(row.id());requireId(row.vehicle());
            if(!departureIds.add(row.id()))throw new IllegalArgumentException("Duplicate departure ID: "+row.id());
            var departure=schedule.getFactory().createDeparture(Id.create(row.id(),Departure.class),seconds(row.time()));
            departure.setVehicleId(Id.createVehicleId(row.vehicle()));
            var previous=old.getDepartures().get(departure.getId());
            if(previous!=null)previous.getAttributes().getAsMap().forEach(departure.getAttributes()::putAttribute);
            replacement.addDeparture(departure);
        }
        validateRoute(replacement,network);
        line(lineId).removeRoute(old);line(lineId).addRoute(replacement);dirty=true;
    }
    public void putStop(String id,String name,double x,double y,String linkId,boolean create){
        requireId(id);requireId(linkId);
        if(!Double.isFinite(x)||!Double.isFinite(y))throw new IllegalArgumentException("Stop coordinates must be finite");
        var key=Id.create(id,TransitStopFacility.class);var stop=schedule.getFacilities().get(key);
        if(create){if(stop!=null)throw new IllegalArgumentException("Stop ID already exists");
            stop=schedule.getFactory().createTransitStopFacility(key,new Coord(x,y),false);schedule.addStopFacility(stop);
        } else if(stop==null)throw new IllegalArgumentException("Stop does not exist");
        stop.setName(name);stop.setCoord(new Coord(x,y));stop.setLinkId(Id.createLinkId(linkId));dirty=true;
    }
    public void moveStop(String id,double x,double y,String linkId){
        var stop=schedule.getFacilities().get(Id.create(id,TransitStopFacility.class));
        if(stop==null)throw new IllegalArgumentException("Select a stop first");
        putStop(id,stop.getName(),x,y,linkId,false);
    }
    public void putVehicleType(String id,int seats,int standing,double length,double maxKmh,String networkMode){
        requireId(id);requireId(networkMode);
        if(seats<0||standing<0||seats+standing<=0||!Double.isFinite(length)||length<=0||!Double.isFinite(maxKmh)||maxKmh<=0)
            throw new IllegalArgumentException("Use nonnegative capacities, at least one passenger place, and positive length/speed");
        var key=Id.create(id,VehicleType.class);var type=vehicles.getVehicleTypes().get(key);
        if(type==null){type=VehicleUtils.createVehicleType(key);vehicles.addVehicleType(type);}
        type.getCapacity().setSeats(seats);type.getCapacity().setStandingRoom(standing);
        type.setLength(length);type.setMaximumVelocity(maxKmh/3.6);type.setNetworkMode(networkMode);dirty=true;
    }
    public void putVehicle(String id,String typeId){
        requireId(id);var type=vehicles.getVehicleTypes().get(Id.create(typeId,VehicleType.class));
        if(type==null)throw new IllegalArgumentException("Unknown vehicle type");
        var key=Id.createVehicleId(id);var old=vehicles.getVehicles().get(key);
        var vehicle=VehicleUtils.createVehicle(key,type);
        if(old!=null){old.getAttributes().getAsMap().forEach(vehicle.getAttributes()::putAttribute);vehicles.removeVehicle(key);}
        vehicles.addVehicle(vehicle);dirty=true;
    }
    public List<NetworkEditorPanel.TransitPath> overlays(String selectedLine,boolean all){
        List<NetworkEditorPanel.TransitPath> result=new ArrayList<>();
        for(var line:schedule.getTransitLines().values()){
            if(!all&&!line.getId().toString().equals(selectedLine))continue;
            for(var route:line.getRoutes().values())result.add(new NetworkEditorPanel.TransitPath(line.getId().toString(),route.getId().toString(),routeLinks(route),
                    route.getStops().stream().map(s->{var f=s.getStopFacility();return new NetworkEditorPanel.TransitStopMarker(f.getId().toString(),f.getName(),f.getCoord().getX(),f.getCoord().getY());}).toList()));
        }
        return List.copyOf(result);
    }
    public void validate(Map<String,LinkSegment> network){
        if(schedule.getTransitLines().isEmpty())throw new IllegalArgumentException("Add a transit line before exporting");
        Map<String,List<double[]>> duties=new HashMap<>();
        for(var line:schedule.getTransitLines().values()){
            if(line.getRoutes().isEmpty())throw new IllegalArgumentException("Line "+line.getId()+" has no routes");
            for(var route:line.getRoutes().values()){
                try{validateRoute(route,network);}catch(IllegalArgumentException ex){throw new IllegalArgumentException(line.getId()+" / "+route.getId()+": "+ex.getMessage());}
                double duration=effective(route.getStops().getLast().getDepartureOffset(),route.getStops().getLast().getArrivalOffset());
                for(var d:route.getDepartures().values())duties.computeIfAbsent(d.getVehicleId().toString(),k->new ArrayList<>()).add(new double[]{d.getDepartureTime(),d.getDepartureTime()+duration});
            }
        }
        for(var entry:duties.entrySet()){
            var runs=entry.getValue();runs.sort(Comparator.comparingDouble(a->a[0]));
            for(int i=1;i<runs.size();i++)if(runs.get(i)[0]<runs.get(i-1)[1])throw new IllegalArgumentException("Vehicle "+entry.getKey()+" has overlapping departures; assign another vehicle or adjust times");
        }
    }
    private void validateRoute(TransitRoute route,Map<String,LinkSegment> network){
        List<String> ids=routeLinks(route);
        if(ids.isEmpty())throw new IllegalArgumentException("Add an ordered network-link path");
        LinkSegment previous=null;
        for(String id:ids){var link=network.get(id);if(link==null)throw new IllegalArgumentException("Missing network link "+id);
            if(previous!=null&&!previous.toNodeId().equals(link.fromNodeId()))throw new IllegalArgumentException("Disconnected route path: "+previous.id()+" -> "+id);previous=link;}
        if(route.getStops().size()<2)throw new IllegalArgumentException("A route needs at least two stops");
        double time=-1;int pathIndex=0;
        for(var stop:route.getStops()){
            var facility=stop.getStopFacility();
            if(facility.getLinkId()==null)throw new IllegalArgumentException("Stop "+facility.getId()+" needs a network link");
            String stopLink=facility.getLinkId().toString();
            while(pathIndex<ids.size()&&!ids.get(pathIndex).equals(stopLink))pathIndex++;
            if(pathIndex==ids.size())throw new IllegalArgumentException("Stop "+facility.getId()+" is not on the route in stop order");
            double arrival=effective(stop.getArrivalOffset(),stop.getDepartureOffset());
            double departure=effective(stop.getDepartureOffset(),stop.getArrivalOffset());
            if(!Double.isFinite(arrival)||!Double.isFinite(departure)||arrival<0||arrival<time||departure<arrival)throw new IllegalArgumentException("Stop times must be finite, nonnegative, nondecreasing and departure >= arrival");time=departure;
        }
        if(route.getDepartures().isEmpty())throw new IllegalArgumentException("Add at least one departure");
        for(var d:route.getDepartures().values()){
            if(!Double.isFinite(d.getDepartureTime())||d.getDepartureTime()<0)throw new IllegalArgumentException("Invalid departure time");
            if(d.getVehicleId()==null||!vehicles.getVehicles().containsKey(d.getVehicleId()))throw new IllegalArgumentException("Departure "+d.getId()+" refers to an unknown vehicle");
        }
    }
    public Path exportBundle(Path parent,NetworkEditorPanel.SaveSnapshot network) throws java.io.IOException {
        validate(network.links());
        Path folder=Files.createTempDirectory(parent,"transit-scenario-");
        network.write(folder.resolve("network.xml.gz"));
        new TransitScheduleWriter(schedule).writeFile(folder.resolve("transitSchedule.xml.gz").toString());
        new MatsimVehicleWriter(vehicles).writeFile(folder.resolve("transitVehicles.xml.gz").toString());
        return folder;
    }
    public static double seconds(String text){double time=Time.parseTime(text.trim());if(!Double.isFinite(time)||time<0)throw new IllegalArgumentException("Use a nonnegative time, e.g. 07:30:00 (hours may exceed 24)");return time;}
    private static OptionalTime offset(String text){return text==null||text.isBlank()?OptionalTime.undefined():OptionalTime.defined(seconds(text));}
    private static double effective(OptionalTime a,OptionalTime b){if(a.isDefined())return a.seconds();if(b.isDefined())return b.seconds();throw new IllegalArgumentException("Each stop needs an arrival or departure offset");}
    private static String format(OptionalTime time){return time.isDefined()?Time.writeTime(time.seconds()):"";}
    private static void requireId(String id){if(id==null||id.isBlank())throw new IllegalArgumentException("ID/mode cannot be blank");}
}
