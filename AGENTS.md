# Agent guide: MATSim Fast Visualization

## Purpose and working conventions

This is a Windows desktop Java application for replaying MATSim outputs, inspecting
congestion/public transport, recording presentation videos, and editing networks and
transit schedules. It visualizes simulated link occupancy; it is not a microscopic
lane-changing simulator. Preserve MATSim event times and scenario semantics.

- Read this file, `README.md`, and the relevant implementation before changing behavior.
- Check `git status --short` first. Preserve existing user edits and unrelated work.
- Keep solutions small and use MATSim readers, event handlers, transformations, and
  writers instead of hand-parsing scenario XML or inventing parallel domain logic.
- Put **every new application default or tunable resource setting in
  `src/main/java/com/matsim/viz/config/AppDefaults.java`**. Do not introduce a second
  default in a field initializer, dialog, reset handler, property fallback, or test helper.
- Runtime counters, empty-state sentinels, mathematical constants, protocol/version
  identifiers, and CSS layout/style rules belong with their implementation, not in
  `AppDefaults`. Do not turn every numeric literal into an application setting.
- Prefer targeted changes; `NetworkPanel` and `FxVisualizerApp` are large, so navigate
  by method names rather than reading or rewriting the whole file.
- Do not edit actual scenario inputs or delete caches to validate a code change.
  Use synthetic fixtures and write generated artifacts under `target/`.

## Build and run

Use JDK 21+, Maven, and Python. `pom.xml` pins MATSim `2026.0-2025w33`, JavaFX
`21.0.4` with Windows classifiers, and JCodec `0.2.5`. MATSim brings GeoTools/JTS
and Commons CSV transitively. Do not upgrade dependencies as part of unrelated work.

From the repository root (PowerShell):

```powershell
mvn -q -DskipTests compile
mvn -q exec:java
```

`Main` reads `config/app.properties`; its `matsim.config.file` selects the scenario.
The checked-in path is machine-specific. Runtime arguments select cache behavior,
not the MATSim config path:

```powershell
mvn -q exec:java '-Dexec.args=--build-cache'
mvn -q exec:java '-Dexec.args=--gui-only'
mvn -q exec:java '-Dexec.args=--overwrite-cache'
```

`--build-cache` processes without GUI, `--gui-only` requires an existing cache, and
`--overwrite-cache` rebuilds it. `cache/` and `target/` can be large generated folders;
exclude them from broad source searches. Prefer `rg`; use Python or PowerShell
`Select-String` if it is unavailable. Use explicit UTF-8 for source edits.

## Configuration: one source of defaults

`AppDefaults` is a non-instantiable class with static nested sections:

- `Paths`, `Playback`, `Rendering`: startup paths, clock defaults, rendering backend.
- `Display`, `Vehicles`, `Heatmap`, `Camera`: display choices, sizes, shapes, bins,
  zoom and visibility. Units are part of the names.
- `Motion`: simulation-time junction handoff duration and traversal fraction.
- `Network`, `Colors`: renderer tuning and palettes. Collections are immutable;
  consumers copy palettes before supporting edits.
- `Recording`: preset dimensions/frame rates, H.264 QP, RAM and spill limits.
- `Maps`, `Geometry`: map CRS/style/tile resources and detailed CSV geometry policy.
- `Editor`, `Transit`, `Spatial`, `Window`: planner creation defaults, indexing and UI.
- `Parsing`: initial event-collection capacities (allocation hints, not scenario limits).

Precedence is `AppDefaults` → explicit `config/app.properties` overrides → live UI
changes for the current session. `ConfigLoader` maps supported property keys into
the immutable `AppConfig` record. The shipped properties file contains scenario
overrides only, so editing a default actually takes effect. Not every internal
default is a properties key. To add an overridable setting, update `AppDefaults`,
`AppConfig`, `ConfigLoader`, the consumer/UI, and documentation together. Verify
both startup and reset/fallback paths. Do not replace explicit user overrides.

## Architecture and where to work

Paths below are relative to `src/main/java/com/matsim/viz/`.

| Area | Files and responsibilities |
| --- | --- |
| Startup | `Main`: config, input resolution, processed cache, model/controller, JavaFX launch |
| Scenario inputs | `parser/MatsimScenarioLoader`, `ResolvedSimulationInputs`, `MatsimScenarioBundle`: native MATSim config/scenario and output resolution |
| Network | `parser/MatsimNetworkParser`, `MatsimNetworkConverter`: native reader to immutable `domain/NetworkData`, `LinkSegment`, `NodePoint`; preserve link attributes |
| Events | `parser/MatsimEventsProcessor`, `MatsimEventsCollector`: `EventsManager`, `MatsimEventsReader`, typed handlers, traversals and PT stop interactions |
| Metadata | Other `parser/` readers: selected-plan purpose timelines, persons/trips CSV, transit mode enrichment |
| Cache | `cache/SimulationFingerprint`, `SimulationCacheStore`, `CachedSimulationData`: input fingerprint and binary processed-data cache |
| Playback | `engine/SimulationModel`: indexed arrays/transitions; `PlaybackController`: time, seeks, active traversals by link, counts |
| Rendering | `ui/NetworkPanel`: world/screen transforms, viewport query, cached road/map layers, vehicles, heatmaps, recording paint path |
| Road geometry | `ui/CarriagewayLayout`, `RoadTaper`, `OverviewVehicleLayout`, `SpatialGrid`: directional offsets, lane transitions, overview gaps and culling |
| Detailed CSV | `parser/DetailedNetworkGeometry`, `domain/LinkPolyline`: discovery, WKT reading, chain joining, arc-length interpolation and spatial index |
| Desktop UI | `ui/fx/FxVisualizerApp`: JavaFX controls, collapsible sidebar, embedded Swing renderer, editor lifecycle; `ui/VisualizerFrame` is the older Swing shell |
| Maps | `ui/map/OsmBackground`, `OsmTileCache`, `MapStyle`: projection mesh, async tile IO, local light/dark palettes |
| Video | `ui/PanelVideoRecorder`, `RecordingFrameStore`, `FfmpegVideoEncoder`, `H264Mp4Encoder`: capture, bounded lossless frame storage, MP4 encoding |
| Network editor | `ui/editor/NetworkEditorPanel` (including `PreparedNetwork`), `EditableNetworkMap`: prepared editor data, cached view, selection, mutation/undo, native network export |
| Transit editor | `ui/editor/TransitEditorPane`, `TransitEditorModel`: lines/routes/stops/departures/vehicles, validation and native export bundle |
| UI styling | `src/main/resources/com/matsim/viz/ui/fx/theme.css`, `theme-light.css` |

Data flow: config → resolved MATSim files → cache load or native parsing → indexed
`SimulationModel` → `PlaybackController` → `FxVisualizerApp`/`NetworkPanel`.
Detailed CSV geometry is loaded separately as an optional display layer.

## Invariants and common pitfalls

### Threading and responsiveness

- JavaFX controls belong to the FX application thread; Swing components and renderer
  state belong to the Swing EDT. Use the existing `runOnEdt`/`getOnEdt` helpers and
  `Platform.runLater` patterns. Avoid reciprocal blocking calls between UI threads.
- Parse files, prepare editor networks, preprocess heatmaps, load tiles, write frames,
  and encode video on workers. Publish results on the appropriate UI thread.
- Preserve editor generation/disposal checks so stale worker results cannot replace
  the current view. Do not clone the entire scenario per repaint or per edit.
- Preserve viewport culling and pan/zoom raster reuse. Geometry/settings changes must
  invalidate affected caches; opacity can composite cached layers without rebuilding.

### Vehicles, transit and geometry

- Vehicle traffic/link events establish road motion. Passenger boarding is not an
  extra vehicle. Handle first-link traffic entry, departure/abort/end of duty, and
  zero-duration traversals without stale buses or duplicated occupancy.
- Teleported PT has no invented road trajectory. See `docs/BUS_EVENT_AUDIT.md` and
  `BusEventsCheck` before changing bus handling.
- PT classification and initial mode selections are shared through
  `SimulationModel.isPublicTransportMode` / `defaultTransportModes`. They combine
  `AppDefaults.Transit.MODES` with schedule/stop modes; do not add UI-only bus/tram
  whitelists. The **Public transport** shortcut selects only matching available
  modes and publishes one update. See `docs/PT_MODE_AUDIT.md` for the scenario audit.
- Opposite directions can have identical geometry. `CarriagewayLayout` separates
  them; keep roads, markings, vehicles, labels and heatmaps aligned.
- CSV filenames end with `detailed_network.csv` beside the MATSim config. WKT
  `LINESTRING` coordinates must be in the network CRS. For merged links, use the
  entire `old_link_id` chain, orient and join its parts, and verify endpoints.
  Missing/invalid chains fall back to the XML chord, never a partial retained link.
- Usable CSV geometry enables itself by default. Users can uncheck it. Vehicles
  interpolate by arc length; curved bounds must participate in viewport queries.
  This does not alter XML lengths/event timing or editor export geometry.
- Overview rendering must keep visible gaps between vehicles and introduce lane
  details progressively. Heatmaps interpolate adjacent time bins, including empty bins.
- Vehicle pixel floors also apply to overview markers. Rail and ferry have distinct
  lengths; `OverviewVehicleLayout` packs variable-length marks within the coverage
  budget. Preserve this when changing visibility. Ferry has separate geometry UI/config.

### Maps, video and editing

- Default CRS is EPSG:2056; MATSim transforms Web Mercator tiles into network space.
  Light/dark styles recolor OSM locally, not a separate provider. Keep original disk
  tiles untouched and attribution visible, including when recording overlays are hidden.
- Capture presentation frames at output resolution. The live viewport must not resize.
  RAM is bounded; spill frames are lossless PNG on a bounded worker queue. H.264
  encoding happens after capture; preserve frame order and failure recovery files.
  MP4 uses 8-bit YUV 4:2:0 and appropriate H.264 levels for Windows compatibility.
- Editor data is separate from playback. Keep undo bounded, validate numeric fields,
  and use native MATSim writers. Transit routes need connected paths, ordered stop
  offsets, valid vehicles, and non-overlapping duties. Preserve times beyond 24:00.
- Cache format/semantic changes require reviewing both the cache schema in
  `SimulationFingerprint` and the version in `SimulationCacheStore`.

## Validation

The regression checks are standalone Java `main` programs, **not JUnit tests**.
`mvn test` alone does not run them. For a shared-defaults or renderer refactor:

```powershell
mvn dependency:build-classpath '-Dmdep.outputFile=target/test-classpath.txt'
python scripts/check-rendering.py
```

The script compiles all main/test sources into `target/visual-check`, then runs
`PlaybackSeekCheck`, `VehicleMotionCheck`, `VehicleSizeCheck`, `PublicTransportModesCheck`, `DefaultsCheck`, `DetailedGeometryCheck`, `NetworkEditorCheck`, `TransitEditorCheck`,
`HeatmapTransitionCheck`, `PanOpacityCheck`, `RoadTaperCheck`, `ZoomDetailCheck`,
`CarriagewayRenderingCheck`, `RecordingBufferCheck`, `RecordingQualityCheck`,
`MapBackgroundCheck`, `MatsimIntegrationCheck`, and `BusEventsCheck`.

Fixtures are synthetic and checks do not require the user's Geneva scenario or
public tile downloads. Rendering checks produce PNG/MP4 artifacts under `target/`;
inspect relevant images when changing appearance. For a targeted run, reuse the
classpath from `target/test-classpath.txt`, prefix `target/visual-check`, and run
`com.matsim.viz.ui.<CheckName>` with `-Djava.awt.headless=true`. JavaFX UI smoke checks
must also include `src/main/resources` on the classpath and run without headless mode.

Use `git diff --check` and inspect the final diff. Report what changed, checks run,
and any verification limits. Keep README and this guide consistent with changes to
configuration, workflow, architecture, or test commands.

Connected traversal neighbours are indexed once in `SimulationModel`. `NetworkPanel`
blends lane/curve poses near their common event boundary without changing occupancy.
Keep handoffs stateless for seeking/recording; never connect temporal gaps or teleports.
`VehicleMotionCheck` validates boundary position/heading and seek independence.

Playback seeking uses `SimulationModel`'s primitive interval tree (sorted enter index
and subtree latest leave). Small seeks undo/apply transitions, large seeks query active
intervals; keep enter-inclusive/leave-exclusive semantics, including zero-duration events.
The index is derived, so it does not change serialized cache formats. Slider requests
are coalesced on FX animation pulses; do not replay all historical events per drag.

`FfmpegVideoEncoder` probes real hardware at the requested dimensions/FPS, then tries
native CPU and finally JCodec in automatic mode. FFmpeg is optional (portable installer
in `scripts/install-ffmpeg.ps1`); never make playback depend on it. Preserve lossless
source frames for retries, bounded pipe buffers, process timeouts/cleanup and Windows
H.264 YUV420 compatibility. Explicit backend requests must not silently fall back.

Map tile arrivals refresh `NetworkPanel.refreshMapLayer` at the cached camera, without
rebuilding roads. Preserve the pan-offset signs and cached raster scale (recording may
retain a higher-resolution background). `OsmBackground` caches network-space tile meshes
with bounded LRU retention; the affine fast path checks every mesh vertex in output
pixels before replacing clipped triangles. Do not loosen CRS/recording alignment to
improve speed. `MapBackgroundCheck` covers road-cache identity and pan-safe refresh.

Volume heatmaps (FLOW_HEATMAP/PT_FLOW_HEATMAP) draw variable-width strokes instead of
physical lane surfaces; hide the cached road layer in those views so wide physical
roads cannot obscure low-volume widths. Use the fixed daily colour intensity scale
for widths too, including overview aggregation and temporal interpolation. UI min/max
controls update immediately and must preserve finite ordered bounds. Defaults live in
`AppDefaults.Heatmap`; `VolumeWidthCheck` covers both volume views and zoom levels.

Editor detailed geometry shares the immutable `DetailedNetworkGeometry` with playback
(or loads it on the preparation worker). All picked polyline segments refer to the
owning XML link ID, including `old_link_id` chains; do not expand merged links into
separate editable records. Numeric edits preserve provenance. Changed endpoints/new
links fall back to XML chords. Transit overlays and picking follow the same curves.
Double-click opens the link dialog; invalid inputs retain the form. Length, speed,
lanes and capacity must be finite and strictly positive, and modes cannot be empty.
The editor's map raster is separate from its transparent roads raster; refresh tiles
at the cached camera without invalidating roads. Compatible Java2D images have a
software fallback. `EditorDetailedGeometryCheck` covers merged selection, geometry
toggle, curved viewport bounds, map-only refresh, validation, undo and native export.

Network editor validation collects all link issues with IDs, field names and values
in `SaveSnapshot.validationIssues()`. Export throws `NetworkValidationException` with
the same report before touching the destination. FX shows a virtualized list with
locate/edit actions and publishes pink highlights on EDT. Keep unchanged input links
in the scan. Export first calls `clampSpeedsForExport`, applying Editor's 10?300 km/h
bounds in m/s to every link (including infinities), as requested by the user. NaN
remains invalid. Normalization changes only the exported copy; the manual check still
reports raw editor values. Preserve all non-speed fields and native MATSim writing. `EditorValidationCheck` covers
reporting, highlights, corrections/undo and preservation of a failed-save destination.
The Swing link form is scrollable and screen-bounded; sizing defaults live in Editor.
