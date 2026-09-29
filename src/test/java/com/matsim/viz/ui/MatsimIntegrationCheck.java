package com.matsim.viz.ui;

import com.matsim.viz.parser.*;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.events.*;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.algorithms.EventWriterXML;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.scenario.ScenarioUtils;
import java.nio.file.*;
import java.util.*;

/** Round trips files written by MATSim through the visualization's public readers. */
public final class MatsimIntegrationCheck {
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory(Path.of("target"), "matsim-check-");
        var network = NetworkUtils.createNetwork();
        var factory = network.getFactory();
        var a = factory.createNode(Id.createNodeId("a"), new Coord(2_600_000, 1_200_000));
        var b = factory.createNode(Id.createNodeId("b"), new Coord(2_600_100, 1_200_100));
        network.addNode(a); network.addNode(b);
        var link = factory.createLink(Id.createLinkId("road"), a, b);
        link.setAllowedModes(Set.of("car", "bus")); link.setLength(150); link.setFreespeed(15);
        link.setCapacity(1800); link.setNumberOfLanes(2); link.getAttributes().putAttribute("custom", "retained");
        network.addLink(link);
        Path networkFile = dir.resolve("network.xml.gz");
        new NetworkWriter(network).write(networkFile.toString());
        var parsed = new MatsimNetworkParser().parse(networkFile).getLinks().get("road");
        check(parsed.lanes() == 2 && parsed.allowsMode("bus") && "retained".equals(parsed.attribute("custom")),
                "Native network attributes were lost");

        Path eventsFile = dir.resolve("events.xml.gz");
        EventWriterXML writer = new EventWriterXML(eventsFile.toString());
        writer.handleEvent(new PersonDepartureEvent(10, Id.createPersonId("p"), link.getId(), "car", "car"));
        writer.handleEvent(new VehicleEntersTrafficEvent(10, Id.createPersonId("p"), link.getId(), Id.createVehicleId("v"), "car", 0));
        writer.handleEvent(new LinkEnterEvent(11, Id.createVehicleId("v"), link.getId()));
        writer.handleEvent(new LinkLeaveEvent(21, Id.createVehicleId("v"), link.getId()));
        writer.closeFile();
        var events = new MatsimEventsParser().parse(eventsFile);
        check(events.traversals().length == 2 && events.traversals()[0].enterTimeSeconds() == 10
                && events.traversals()[0].leaveTimeSeconds() == 11
                && events.traversals()[1].enterTimeSeconds() == 11 && events.traversals()[1].leaveTimeSeconds() == 21, "Native event traversal times changed");
        check("car".equals(events.vehicleToMode().get("v")), "Typed event mode not preserved");

        var scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        var population = scenario.getPopulation();
        var pf = population.getFactory();
        var person = pf.createPerson(Id.createPersonId("p"));
        var plan = pf.createPlan();
        var home = pf.createActivityFromCoord("home", a.getCoord()); home.setEndTime(10);
        var leg = pf.createLeg("car"); leg.setDepartureTime(10); leg.setTravelTime(20);
        plan.addActivity(home); plan.addLeg(leg); plan.addActivity(pf.createActivityFromCoord("work", b.getCoord()));
        person.addPlan(plan); person.setSelectedPlan(plan); population.addPerson(person);
        Path plansFile = dir.resolve("plans.xml.gz");
        new PopulationWriter(population).write(plansFile.toString());
        var windows = new PlansXmlPurposeTimelineParser().parse(plansFile).get("p");
        check(windows.size() == 1 && windows.get(0).departureTimeSeconds() == 10
                && windows.get(0).arrivalTimeSeconds() == 30 && "work".equals(windows.get(0).purpose()),
                "Native streaming plan extraction changed the trip purpose/time");
        System.out.println("PASS: MATSim gzip network/attributes, typed events and streaming selected plans.");
    }
}
