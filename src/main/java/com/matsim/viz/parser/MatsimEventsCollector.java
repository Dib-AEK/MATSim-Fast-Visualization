package com.matsim.viz.parser;

import com.matsim.viz.domain.VehicleTraversal;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.LinkEnterEvent;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.events.handler.LinkEnterEventHandler;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler;
import org.matsim.vehicles.Vehicle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MatsimEventsCollector implements
        LinkEnterEventHandler,
        LinkLeaveEventHandler,
        VehicleEntersTrafficEventHandler,
        VehicleLeavesTrafficEventHandler,
        PersonDepartureEventHandler,
        PersonEntersVehicleEventHandler {

    private final Map<Id<Vehicle>, ActiveLinkState> activeByVehicle = new HashMap<>(64_000);
    private final Map<String, String> vehicleToPerson = new HashMap<>();
    private final Map<String, String> vehicleToMode = new HashMap<>();
    private final Map<String, String> personToLatestMode = new HashMap<>();
    private final List<VehicleTraversal> traversals = new ArrayList<>(1_000_000);

    @Override
    public void handleEvent(LinkEnterEvent event) {
        Id<Vehicle> vehicleId = event.getVehicleId();
        String linkId = event.getLinkId().toString();
        double time = event.getTime();

        ActiveLinkState previous = activeByVehicle.put(vehicleId, new ActiveLinkState(linkId, time));
        if (previous != null) {
            appendTraversal(vehicleId.toString(), previous.linkId(), previous.enterTimeSeconds(), time);
        }
    }

    @Override
    public void handleEvent(LinkLeaveEvent event) {
        Id<Vehicle> vehicleId = event.getVehicleId();
        ActiveLinkState entered = activeByVehicle.remove(vehicleId);
        if (entered != null) {
            appendTraversal(vehicleId.toString(), entered.linkId(), entered.enterTimeSeconds(), event.getTime());
        }
    }

    @Override
    public void handleEvent(VehicleLeavesTrafficEvent event) {
        Id<Vehicle> vehicleId = event.getVehicleId();
        ActiveLinkState entered = activeByVehicle.remove(vehicleId);
        if (entered != null) {
            appendTraversal(vehicleId.toString(), entered.linkId(), entered.enterTimeSeconds(), event.getTime());
        }
    }

    @Override
    public void handleEvent(VehicleEntersTrafficEvent event) {
        String vehicleId = event.getVehicleId().toString();
        String personId = event.getPersonId().toString();
        vehicleToPerson.putIfAbsent(vehicleId, personId);

        String mode = firstNonBlank(
                normalizeMode(event.getAttributes().get("networkMode")),
                normalizeMode(event.getAttributes().get("legMode")),
                personToLatestMode.get(personId)
        );
        if (mode != null) {
            vehicleToMode.putIfAbsent(vehicleId, mode);
        }
    }

    @Override
    public void handleEvent(PersonEntersVehicleEvent event) {
        String vehicleId = event.getVehicleId().toString();
        String personId = event.getPersonId().toString();
        vehicleToPerson.putIfAbsent(vehicleId, personId);
        String mode = personToLatestMode.get(personId);
        if (mode != null) {
            vehicleToMode.putIfAbsent(vehicleId, mode);
        }
    }

    @Override
    public void handleEvent(PersonDepartureEvent event) {
        String personId = event.getPersonId().toString();
        String departureVehicle = firstNonBlank(
                event.getAttributes().get("vehicle"),
                event.getAttributes().get("vehicleId"),
                event.getAttributes().get("vehicle_id")
        );
        if (departureVehicle != null) {
            vehicleToPerson.putIfAbsent(departureVehicle, personId);
        }

        String mode = normalizeMode(event.getAttributes().get("legMode"));
        if (mode != null) {
            personToLatestMode.put(personId, mode);
        }
    }

    @Override
    public void reset(int iteration) {
        activeByVehicle.clear();
        vehicleToPerson.clear();
        vehicleToMode.clear();
        personToLatestMode.clear();
        traversals.clear();
    }

    public EventsParseResult snapshotResult() {
        return new EventsParseResult(
                traversals.toArray(new VehicleTraversal[0]),
                new HashMap<>(vehicleToPerson),
                new HashMap<>(vehicleToMode)
        );
    }

    private void appendTraversal(String vehicleId, String linkId, double enter, double leave) {
        double safeLeave = Math.max(leave, enter + 0.05);
        traversals.add(new VehicleTraversal(traversals.size(), vehicleId, linkId, enter, safeLeave));
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return null;
        }
        return mode.toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private record ActiveLinkState(String linkId, double enterTimeSeconds) {
    }
}
