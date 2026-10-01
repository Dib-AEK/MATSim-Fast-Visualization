package com.matsim.viz.ui;

import com.matsim.viz.domain.*;
import com.matsim.viz.engine.*;
import com.matsim.viz.parser.MatsimEventsProcessor;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.events.*;
import org.matsim.core.events.algorithms.EventWriterXML;
import java.nio.file.*;
import java.util.*;
import javax.swing.SwingUtilities;

public final class PublicTransportModesCheck {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path events = Files.createTempFile(Path.of("target"), "pt-modes-", ".xml.gz");
        List<String> modes = List.of("bus", "tram", "rail", "ferry", "gondola", "cable-car", "lake-shuttle");
        Map<String, String> vehicleModes = new HashMap<>();
        Map<String, LinkSegment> links = new HashMap<>();
        var writer = new EventWriterXML(events.toString());
        for (String mode : modes) {
            var vehicle = Id.createVehicleId(mode);
            var driver = Id.createPersonId("driver-" + mode);
            var link = Id.createLinkId(mode);
            vehicleModes.put(mode, mode);
            links.put(mode, new LinkSegment(mode, "a", "b", 0, 0, 100, 100, 140, 15, 1, Set.of(mode), Map.of()));
            writer.handleEvent(new VehicleEntersTrafficEvent(0, driver, link, vehicle, "car", 1));
            writer.handleEvent(new LinkLeaveEvent(10, vehicle, link));
            writer.handleEvent(new VehicleLeavesTrafficEvent(10, driver, link, vehicle, "car", 1));
        }
        writer.closeFile();
        var parsed = new MatsimEventsProcessor().readTraversals(events, vehicleModes);
        check(parsed.traversals().length == modes.size(), "PT movements lost or duplicated");
        var network = new NetworkData(Map.of(), links, 0, 0, 100, 100);
        var model = new SimulationModel(network, parsed.traversals(), parsed.vehicleToPerson(), parsed.vehicleToMode(),
                Map.of(), Map.of(), Map.of("stop", new PtStopPoint("stop", 0, 0, Set.of("lake-shuttle"))), null);
        for (String mode : modes) {
            check(model.isPublicTransportMode(mode), "PT mode unrecognized: " + mode);
            check(model.defaultTransportModes(model.availableLinkModes()).contains(mode), "Network default hides " + mode);
            check(model.defaultTransportModes(model.availableTripModes()).contains(mode), "Trip default hides " + mode);
        }
        check(model.isPublicTransportMode(" RAIL ") && model.isPublicTransportMode("pt"), "PT normalization failed");
        for (String mode : List.of("car", "bike", "truck", "taxi", "walk", "car_passenger", "other")) {
            check(!model.isPublicTransportMode(mode), "Non-PT mode classified as PT: " + mode);
        }
        SwingUtilities.invokeAndWait(() -> {
            try {
                var panel = new NetworkPanel(model, new PlaybackController(model, 0, 10, 1));
                var renderLink = NetworkPanel.class.getDeclaredMethod("shouldRenderLink", LinkSegment.class);
                var renderTrip = NetworkPanel.class.getDeclaredMethod("shouldRenderTripMode", String.class);
                var ptHeatmap = NetworkPanel.class.getDeclaredMethod("isPtMode", String.class);
                renderLink.setAccessible(true); renderTrip.setAccessible(true); ptHeatmap.setAccessible(true);
                for (String mode : modes) {
                    check((boolean)renderLink.invoke(panel, links.get(mode)), "Renderer hides PT link: " + mode);
                    check((boolean)renderTrip.invoke(panel, mode), "Renderer hides PT trip: " + mode);
                    check((boolean)ptHeatmap.invoke(panel, mode), "PT heatmap excludes: " + mode);
                }
                panel.setSelectedLinkModes(Set.of());
                check(!(boolean)renderLink.invoke(panel, links.get("ferry")), "None no longer hides links");
            } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
        });
        Files.deleteIfExists(events);
        System.out.println("PASS: ferry/rail/custom PT movements, default filters, PT heatmaps, non-PT exclusion and None.");
    }
}
