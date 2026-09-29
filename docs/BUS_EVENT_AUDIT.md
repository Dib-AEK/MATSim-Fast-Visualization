# Geneva bus event audit (29 September 2026)

Inputs: `config/app.properties` points to
`C:/Users/abdel/Desktop/ETH/codes/scenario_geneva/geneva_config.xml`.
The inspected run is `simulation_output/output_events.xml.gz`, with its output
config, transit schedule and vehicles. The events file was last modified on
25 September 2026. This is an audit of that run, not a general bus congestion claim.

## Simulation and classification

Both configured and output transit settings enable transit in the mobsim.
The output `SBBTransit.deterministicServiceModes` lists funicular, other, subway,
rail, ferry, cable-car, tram and gondola; bus is excluded. Actual buses emit
`vehicle enters traffic` with networkMode `car`, paired link events, and facility
arrival/departure events. Schedule mode `bus` is authoritative for presentation.
`qsim.vehicleBehavior=teleport` concerns missing vehicle availability; it does not
make all scheduled buses teleported services.

## Observations

A streaming scan classified vehicle IDs through the output transit schedule:

- 8,753,736 total events; 8,445 scheduled bus vehicle IDs/start events.
- 1,571,427 bus link-entry events and 1,571,427 link-exit events.
- No repeated entry while a previous link remained open, or mismatched paired exit.
- 8,445 initial exits without a preceding link-entry event: the initial link is
  established by `VehicleEntersTrafficEvent` instead.
- 61 zero-duration link crossings; these must not become 0.05-second visible copies.
- Six vehicle aborts: close those final movements at their explicit abort time.
- Link `1974249` contained 68 distinct buses at
  71,564 seconds (19:52:44), independently checked using positive entry/exit
  intervals (`enter <= time < leave`). Link `1061285` reached 66 at 66,470 seconds.
- Vehicle `veh_63498_bus` entered link `622054` at 71,775 seconds and left at
  95,678 seconds: 23,903 seconds (6:38:23) on one link. The delay is present in
  the source events; this audit does not attribute it to a particular simulation cause.

The largest bus groups are therefore not explained by duplicated passenger events
or schedule-derived extra vehicles. The viewer uses link-event occupancy, not
independently generated schedule trajectories. It still interpolates positions
between link timestamps and visually packs vehicles to reduce overlap; this does
not recover exact lane positions or separate stop dwell from congestion.

## Changes and checks

Native MATSim handlers now include initial traffic-entry links, discard zero-duration
occupancy, ignore duplicate simultaneous entries, clear stop context on departure,
and terminate movements on vehicle aborts. New driver duties clear stale movements.
Known transit modes take priority over traffic modes for stop statistics as well as
vehicle display. Passenger boarding cannot replace the known transit vehicle driver.

`BusEventsCheck` round-trips typed events through MATSim's XML writer/reader and
checks these boundaries, reuse of a bus, mismatched exits and facility-only services.
`ZoomDetailCheck` verifies the lane-width control scales rendered geometry.
Parsed-data caches invalidate automatically when the processor code changes.
