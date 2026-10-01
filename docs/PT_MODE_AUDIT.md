# Geneva public transport audit — 1 October 2026

`config/app.properties` selects `scenario_geneva/geneva_config.xml`. This audit
read the current `simulation_output/output_transitSchedule.xml.gz`,
`output_network.xml.gz`, and `output_events.xml.gz` without changing them. It used
the application's `TransitScheduleParser`, `MatsimNetworkParser`, and
`MatsimEventsProcessor` (native MATSim readers and typed event handlers).

| Schedule mode | Scheduled vehicles | Vehicles with positive-duration traversals | Positive-duration traversals | Boarding/alighting interactions |
| --- | ---: | ---: | ---: | ---: |
| bus | 10,806 | 10,806 | 1,893,214 | 102,508 |
| tram | 1,812 | 1,812 | 87,690 | 48,508 |
| rail | 745 | 745 | 13,305 | 37,172 |
| ferry | 234 | 234 | 774 | 284 |

The network has 31,323 links allowing bus, 685 allowing tram, 405 allowing rail,
56 allowing ferry, and 1,788 allowing generic `pt` (mode counts can overlap).
The output config enables transit in the mobsim and lists
`funicular,other,subway,rail,ferry,cable-car,tram,gondola` as SBB deterministic
service modes. The current event file nevertheless contains positive link
movements for all four scheduled modes above. Ferry and rail therefore need no
invented schedule-based trajectories. These counts describe this run, not every
possible MATSim/SBB event configuration.

The visibility problem was the default mode whitelist: it selected car, bike,
truck, bus and tram while omitting rail, ferry and generic pt. PT heatmap and
vehicle classification also used a separate incomplete list.

Initial filters now combine configured road defaults with all standard PT modes
and transit modes found in schedule stops/interactions. A single model predicate
drives defaults, PT heatmaps, vehicle classification, and the **Public transport**
shortcut. The shortcut only checks PT entries already present in its own list;
it unchecks car, bike, truck, taxi and other non-PT entries. Unknown custom modes
count as PT when identified by schedule/stop metadata, not by name guessing.

Validation: `PublicTransportModesCheck` exercises native events for bus, tram,
rail, ferry, gondola, cable-car and a custom lake-shuttle mode, plus initial
network/trip visibility, PT classification and None behavior. A JavaFX smoke
check exercised All/None/Public transport on both primary mode cards and verified
one callback per click; its preview is generated at `target/pt-mode-controls.png`.
The audit log is generated at `target/pt-scenario-audit.log`.
