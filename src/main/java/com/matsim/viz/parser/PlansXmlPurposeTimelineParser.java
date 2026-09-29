package com.matsim.viz.parser;

import com.matsim.viz.domain.TripPurposeWindow;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.StreamingPopulationReader;
import org.matsim.core.scenario.ScenarioUtils;
import java.nio.file.Path;
import java.util.*;

/** Streams MATSim plans without loading a second population into memory. */
public final class PlansXmlPurposeTimelineParser {
    public Map<String, List<TripPurposeWindow>> parse(Path plansFile) {
        Map<String, List<TripPurposeWindow>> byPerson = new java.util.concurrent.ConcurrentHashMap<>();
        var reader = new StreamingPopulationReader(ScenarioUtils.createScenario(ConfigUtils.createConfig()));
        reader.addAlgorithm(person -> {
            var plan = person.getSelectedPlan();
            if (plan == null) return;
            Leg pending = null;
            for (var element : plan.getPlanElements()) {
                if (element instanceof Leg leg) {
                    pending = leg;
                } else if (element instanceof Activity activity && pending != null) {
                    if (pending.getDepartureTime().isDefined() && activity.getType() != null) {
                        double departure = pending.getDepartureTime().seconds();
                        double duration = pending.getTravelTime().isDefined() ? pending.getTravelTime().seconds() : 0;
                        String id = person.getId().toString();
                        byPerson.computeIfAbsent(id, ignored -> new ArrayList<>()).add(new TripPurposeWindow(
                                id, departure, departure + Math.max(0, duration), activity.getType(), pending.getMode()));
                    }
                    pending = null;
                }
            }
        });
        reader.readFile(plansFile.toString());
        byPerson.values().forEach(windows -> windows.sort(Comparator.comparingDouble(TripPurposeWindow::departureTimeSeconds)));
        return byPerson;
    }
}
