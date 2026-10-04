package com.matsim.viz.config;

import com.matsim.viz.ui.map.MapStyle;
import java.awt.Color;
import java.util.List;
import java.util.Set;

/**
 * Single source for application defaults and tunable rendering/resource settings.
 * Values in app.properties override the matching ConfigLoader fallbacks; live UI
 * changes override those values for the current session. Units appear in names.
 * Keep mutable runtime state and file-format/mathematical constants out of here.
 * Collections are immutable; consumers must copy them before editing.
 */
public final class AppDefaults {
    private AppDefaults() { }

    public record VideoPreset(String label, int width, int height, int fps) { }

    public static final class Paths {
        private Paths() { }
        public static final String CACHE_DIR = "cache";
        public static final String APP_CONFIG = "config/app.properties";
    }

    public static final class Launcher {
        private Launcher() { }
        public static final String USER_DIRECTORY = ".matsim-viz";
        public static final int WIDTH_PIXELS = 940;
        public static final int HEIGHT_PIXELS = 720;
        public static final double SCREEN_FRACTION = 0.9;
        public static final int SCAN_DELAY_MS = 600;
        public static final String MODE = "files";
        public static final String WORKER_MAX_HEAP = "-XX:MaxRAMPercentage=75.0";
    }

    public static final class Playback {
        private Playback() { }
        public static final int START_SECONDS = 0;
        public static final int END_SECONDS = 86_400;
        public static final int SPEED = 60;
        public static final int INCREMENTAL_SEEK_TRANSITIONS = 20_000;
    }

    public static final class Rendering {
        private Rendering() { }
        public static final String BACKEND = "auto";
        public static final String JAVA2D_PIPELINE = "auto";
        public static final boolean JAVA2D_FORCE_VRAM = false;
    }

    public static final class Display {
        private Display() { }
        public static final boolean THEME_DARK = true;
        public static final String COLOR_MODE = "DEFAULT";
        public static final boolean SHOW_QUEUES = false;
        public static final double BIDIRECTIONAL_OFFSET = 0.1;
        public static final double LANE_WIDTH_M = 3.5;
        public static final boolean SHOW_BOTTLENECK = false;
        public static final double BOTTLENECK_DIVISOR = 6.0;
        public static final boolean KEEP_VEHICLES_VISIBLE_WHEN_ZOOMED_OUT = true;
        public static final double MIN_VEHICLE_LENGTH_PX = 4.0;
        public static final double MIN_VEHICLE_WIDTH_PX = 2.0;
        public static final String VISUALIZATION_MODE = "VEHICLES";
        public static final double ZOOM_DETAIL_START_LANE_PX = 0.3;
        public static final double ZOOM_DETAIL_FULL_LANE_PX = 1.5;
        public static final double OVERVIEW_VEHICLE_COVERAGE = 0.75;
        public static final double SAMPLE_SIZE = 1.0;
        public static final double PT_STOP_BUBBLE_MIN_RADIUS_PIXELS = 3.5;
        public static final double PT_STOP_BUBBLE_MAX_RADIUS_PIXELS = 24.0;
        public static final float ROAD_OPACITY = 0.7f;
        public static final boolean SUPPRESS_OVERLAYS = false;
        public static final Set<String> TRANSPORT_MODES = Set.of("car", "bike", "truck", "bus", "tram");
        public static final boolean SECTIONS_EXPANDED = false;
    }

    public static final class Vehicles {
        private Vehicles() { }
        public static final double LENGTH_CAR_M = 7.0;
        public static final double LENGTH_BIKE_M = 3.0;
        public static final double LENGTH_TRUCK_M = 10.0;
        public static final double LENGTH_BUS_M = 12.0;
        public static final double LENGTH_RAIL_M = 35.0;
        public static final double LENGTH_FERRY_M = 45.0;
        public static final double WIDTH_RATIO_FERRY = 0.90;
        public static final String SHAPE_FERRY = "ARROW";
        public static final double MAX_FERRY_LENGTH_M = 200.0;
        public static final double RAIL_MIN_LENGTH_MULTIPLIER = 1.25;
        public static final double FERRY_MIN_LENGTH_MULTIPLIER = 1.5;
        public static final double WIDTH_RATIO_CAR = 0.70;
        public static final double WIDTH_RATIO_BIKE = 0.30;
        public static final double WIDTH_RATIO_TRUCK = 0.95;
        public static final double WIDTH_RATIO_BUS = 0.85;
        public static final double WIDTH_RATIO_RAIL = 0.90;
        public static final String SHAPE_CAR = "RECTANGLE";
        public static final String SHAPE_BIKE = "DIAMOND";
        public static final String SHAPE_TRUCK = "RECTANGLE";
        public static final String SHAPE_BUS = "OVAL";
        public static final String SHAPE_RAIL = "ARROW";
    }

    public static final class Heatmap {
        private Heatmap() { }
        public static final int TIME_BIN_SECONDS = 600;
        public static final double VOLUME_MIN_WIDTH_PX = 1.5;
        public static final double VOLUME_MAX_WIDTH_PX = 10.0;
        public static final double VOLUME_WIDTH_CONTROL_MIN_PX = 0.5;
        public static final double VOLUME_WIDTH_CONTROL_MAX_PX = 40.0;
        public static final double VOLUME_WIDTH_CONTROL_STEP_PX = 0.5;
        public static final String FLOW_COLOR_LOW = "#F7F7F7";
        public static final String FLOW_COLOR_HIGH = "#7A0014";
        public static final String SPEED_COLOR_LOW = "#F7F7F7";
        public static final String SPEED_COLOR_HIGH = "#0C4A86";
        public static final String SPEED_RATIO_COLOR_LOW = "#F7F7F7";
        public static final String SPEED_RATIO_COLOR_HIGH = "#0A5D2A";
        public static final boolean SEPARATE_NETWORK_MODES = false;
    }

    public static final class Recording {
        private Recording() { }
        public static final String DEFAULT_QUALITY = "PRESENTATION_4K";
        public static final VideoPreset PRESENTATION_4K = new VideoPreset("Presentation 4K / 15 fps", 3840, 2160, 15);
        public static final VideoPreset VIEWPORT_SYNC = new VideoPreset("Viewport native (app sync)", 0, 0, 0);
        public static final VideoPreset MEDIUM = new VideoPreset("720p 30fps", 1280, 720, 30);
        public static final VideoPreset HIGH = new VideoPreset("1080p 30fps", 1920, 1080, 30);
        public static final VideoPreset HIGH_60 = new VideoPreset("1080p 60fps", 1920, 1080, 60);
        public static final VideoPreset QHD = new VideoPreset("1440p 30fps", 2560, 1440, 30);
        public static final VideoPreset QHD_60 = new VideoPreset("1440p 60fps", 2560, 1440, 60);
        public static final VideoPreset UHD = new VideoPreset("4K 30fps", 3840, 2160, 30);
        public static final VideoPreset UHD_60 = new VideoPreset("4K 60fps", 3840, 2160, 60);
        public static final int FALLBACK_DISPLAY_FPS = 60;
        public static final int SPILL_QUEUE_FRAMES = 2;
        public static final long BUFFER_MIB = 512L;
        public static final long MAX_BUFFER_MIB = 4096L;
        public static final int HEAP_BUDGET_DIVISOR = 4;
        public static final int AVAILABLE_BUDGET_DIVISOR = 3;
        public static final int H264_QP = 18;
        public static final String ENCODER = "auto";
        public static final String FFMPEG_EXECUTABLE = "tools/ffmpeg/bin/ffmpeg.exe";
        public static final String FFMPEG_PATH_COMMAND = "ffmpeg";
        public static final java.util.List<String> ENCODER_ORDER = java.util.List.of("h264_nvenc", "h264_qsv", "h264_amf", "libx264");
        public static final String NVENC_PRESET = "p5";
        public static final String QSV_PRESET = "medium";
        public static final String X264_PRESET = "fast";
        public static final String AMF_QUALITY = "quality";
        public static final int ENCODER_PROBE_TIMEOUT_SECONDS = 20;
        public static final int ENCODER_STALL_TIMEOUT_SECONDS = 120;
        public static final int ENCODER_WATCHDOG_SECONDS = 5;
        public static final int ENCODER_PIPE_BUFFER_BYTES = 1024 * 1024;
    }

    public static final class Maps {
        private Maps() { }
        public static final int MAX_VIEW_TILES = 128;
        public static final int MAX_ZOOM = 19;
        public static final int DARK_MIN_GRAY = 25;
        public static final int DARK_MAX_GRAY = 210;
        public static final int LIGHT_MAX_GRAY = 250;
        public static final int LIGHT_MIN_GRAY = 85;
        public static final boolean BACKGROUND = false;
        public static final String CRS = "EPSG:2056";
        public static final MapStyle STYLE = MapStyle.OPENSTREETMAP;
        public static final int DOWNLOAD_THREADS = 2;
        public static final int MEMORY_TILES = 256;
        public static final int MAX_PENDING_TILES = 128;
        public static final int MAX_RETRY_ENTRIES = 1024;
        public static final int CONNECT_TIMEOUT_MS = 4000;
        public static final int READ_TIMEOUT_MS = 6000;
        public static final long RETRY_DELAY_MS = 60_000L;
        public static final String TILE_URL = "https://tile.openstreetmap.org/";
        public static final int PROJECTION_MESH = 4;
        public static final int PROJECTION_CACHE_TILES = 256;
        public static final double AFFINE_MAX_ERROR_PX = 0.25;
    }

    public static final class Network {
        private Network() { }
        public static final int MIN_CACHE_MARGIN_PX = 128;
        public static final int MAX_CACHE_MARGIN_PX = 384;
        public static final int PARALLEL_MIN_VISIBLE_LINKS = 200;
        public static final int PARALLEL_MIN_ACTIVE_TRAVERSALS = 8_000;
        public static final int MAX_SORTED_TRAVERSALS_PER_GROUP = 512;
        public static final int VIEWPORT_WORLD_PADDING_PX = 48;
        public static final double MIN_VEHICLE_LENGTH_METERS = 0.8;
        public static final Color DEFAULT_BACKGROUND = new Color(0x111820);
        public static final Color DEFAULT_ROAD = new Color(0x394550);
        public static final Color LIGHT_BACKGROUND = new Color(0xECEFF4);
        public static final Color LIGHT_ROAD = new Color(0xB0B8C8);
        public static final Color QUEUE_LABEL = new Color(0xFF3D3D);
        public static final Color BOTTLENECK_NORMAL = new Color(0x2E86FF);
        public static final Color BOTTLENECK_CONGESTED = new Color(0xE03030);
        public static final Color DEFAULT_HEATMAP_LOW = Color.decode(AppDefaults.Heatmap.FLOW_COLOR_LOW);
        public static final Color DEFAULT_FLOW_HEATMAP_HIGH = Color.decode(AppDefaults.Heatmap.FLOW_COLOR_HIGH);
        public static final Color DEFAULT_SPEED_HEATMAP_HIGH = Color.decode(AppDefaults.Heatmap.SPEED_COLOR_HIGH);
    }

    public static final class Editor {
        private Editor() { }
        public static final Color BACKGROUND = new Color(0x0F1115);
        public static final Color LINK_COLOR = new Color(0x808A9D);
        public static final Color NODE_COLOR_DEFAULT = new Color(0xC7CEDD);
        public static final Color INVALID_LINK = new Color(0xFF4081);
        public static final int DIALOG_WIDTH_PIXELS = 620;
        public static final int DIALOG_HEIGHT_PIXELS = 420;
        public static final double DIALOG_SCREEN_FRACTION = 0.7;
        public static final Color SELECTED_LINK = new Color(0xF05D23);
        public static final Color SELECTED_NODE = new Color(0x1FA2FF);
        public static final double VIEWPORT_MARGIN_PIXELS = 80.0;
        public static final double BIDIRECTIONAL_OFFSET_PIXELS = 3.2;
        public static final int CACHE_MARGIN = 256;
        public static final double LINK_CAPACITY = 900.0;
        public static final double LINK_SPEED_KMH = 50.0;
        public static final double SAVE_MIN_SPEED_KMH = 10.0;
        public static final double SAVE_MAX_SPEED_KMH = 300.0;
        public static final double LINK_LANES = 1.0;
        public static final String LINK_MODE = "car";
        public static final int UNDO_LIMIT = 50;
    }

    public static final class Spatial {
        private Spatial() { }
        public static final int MIN_GRID_SIZE = 50;
        public static final int MAX_GRID_SIZE = 1500;
    }

    public static final class Geometry {
        private Geometry() { }
        public static final boolean ENABLED_WHEN_AVAILABLE = true;
        /** Maximum endpoint/join mismatch in network coordinate units. */
        public static final double JOIN_TOLERANCE = 2.0;
    }

    public static final class Motion {
        private Motion() { }
        public static final double HANDOFF_SECONDS = 2.0;
        public static final double HANDOFF_LINK_FRACTION = 0.20;
        public static final double CONTIGUOUS_TIME_TOLERANCE_SECONDS = 0.000001;
    }

    public static final class Camera {
        private Camera() { }
        public static final double INITIAL_ZOOM = 1.0;
        public static final double INITIAL_PAN_PIXELS = 20.0;
        public static final double WHEEL_FACTOR = 1.15;
        public static final double MIN_ZOOM = 0.05;
        public static final double MAX_ZOOM = 2048;
        public static final double MAX_PIXELS_PER_METER = 24;
        public static final int EDITOR_ZOOM_SETTLE_MS = 120;
    }

    public static final class Colors {
        private Colors() { }
        public static final Color MAP_ATTRIBUTION_TEXT = new Color(0x263442);
        public static final Color EDITOR_STATUS = new Color(0xECF2FF);
        public static final Color EDITOR_LABEL = new Color(0xE5ECF8);
        public static final Color PENDING_NODE = new Color(0xFFD166);
        public static final Color EDITED_LINK = new Color(0x55E4FF);
        public static final Color TRANSIT_STOP = new Color(0xEF7B26);
        public static final Color SELECTED_TRANSIT_LINE = new Color(0xFFBC42);
        public static final Color LIGHT_LEGEND_BORDER = new Color(0x909090);
        public static final Color LIGHT_OVERLAY_TEXT = new Color(0x202020);
        public static final Color LIGHT_OVERLAY_BORDER = new Color(0xB0B0B0);
        public static final Color DARK_OVERLAY_BORDER = new Color(0x5A5A5A);
        public static final Color LIGHT_OVERLAY_BACKGROUND = new Color(0xF0F0F0);
        public static final Color DARK_OVERLAY_BACKGROUND = new Color(0x101010);
        public static final Color VEHICLE_WINDOW = new Color(0x182B3A);
        public static final Color LIGHT_ROAD_OUTLINE = new Color(0x8995A2);
        public static final Color DARK_ROAD_OUTLINE = new Color(0x65717B);
        public static final Color DARK_OVERLAY_TEXT = new Color(0xEFEFEF);
        public static final Color BUSY_LIGHT_BACKGROUND = new Color(0xF3F3F3);
        public static final Color BUSY_DARK_BACKGROUND = new Color(0x151515);
        public static final Color LIGHT_SECONDARY_TEXT = new Color(0x262626);
        public static final Color DARK_SECONDARY_TEXT = new Color(0xDFDFDF);
        public static final Color LIGHT_PROGRESS_FILL = new Color(0x306FBA);
        public static final Color DARK_PROGRESS_FILL = new Color(0x5DA9FF);
        public static final Color LIGHT_PROGRESS_TRACK = new Color(0xD6D6D6);
        public static final Color DARK_PROGRESS_TRACK = new Color(0x2F2F2F);
        public static final Color LIGHT_DIALOG_TEXT = new Color(0x1A1A1A);
        public static final Color DARK_DIALOG_TEXT = new Color(0xECECEC);
        public static final Color LIGHT_DIALOG_TITLE = new Color(0x2A2A2A);
        public static final Color WHITE = new Color(0xFFFFFF);
        public static final Color MUTED_GRAY = new Color(0x777777);
        public static final Color DARK_SPINNER = new Color(0xB5B5B5);
        public static final Color LIGHT_DIALOG_BORDER = new Color(0xA0A0A0);
        public static final Color DARK_DIALOG_BORDER = new Color(0x6A6A6A);
        public static final Color PROGRESS_LIGHT_BACKGROUND = new Color(0xF2F2F2);
        public static final Color PROGRESS_DARK_BACKGROUND = new Color(0x121212);
        public static final Color SWATCH_BORDER = new Color(0x333333);
        public static final Color MISSING_METADATA = new Color(0x666666);
        public static final Color MISSING_MODE = new Color(0x555555);
        public static final Color DEFAULT_CAR_MODE_COLOR = new Color(0xFF0BAECF);
        public static final Color DEFAULT_BIKE_MODE_COLOR = new Color(0x87EA0B);
        public static final List<Color> PALETTE = List.of(new Color(0x1F77B4),
            new Color(0x2CA02C),
            new Color(0xFF7F0E),
            new Color(0xD62728),
            new Color(0x17BECF),
            new Color(0x9467BD),
            new Color(0x8C564B),
            new Color(0xE377C2),
            new Color(0x11116B),
            new Color(0x003300));
        public static final List<Color> AGE_GROUP_COLORS = List.of(new Color(0x2BB673),
            new Color(0x00A6FB),
            new Color(0xFF8C42),
            new Color(0xD7263D),
            new Color(0xff00ff),
            new Color(0x330033));
        public static final List<Integer> AGE_BIN_UPPER_BOUNDS = List.of(17, 35, 59);
        public static final Color MODE_BUS = new Color(0xF0FF17);
        public static final Color MODE_TRAM = new Color(0xFF0F6F);
        public static final Color MODE_RAIL = new Color(0x6C0202);
        public static final Color MODE_TRAIN = new Color(0xFD0000);
        public static final Color MODE_SUBWAY = new Color(0x0077CC);
        public static final Color MODE_METRO = new Color(0x0077CC);
        public static final Color MODE_FERRY = new Color(0x00FFC3);
        public static final Color MODE_FUNICULAR = new Color(0x8855BB);
        public static final Color SEX_MALE = new Color(0xFF0BAECF, true);
        public static final Color SEX_FEMALE = new Color(0xFF4FA3);
        public static final Color SEX_OTHER = new Color(0xFFC857);
        public static final Color SEX_UNKNOWN = new Color(0x9A9A9A);
    }

    public static final class Transit {
        private Transit() { }
        /** Standard PT labels; the loaded schedule can add scenario-specific modes. */
        public static final Set<String> MODES = Set.of("pt", "bus", "tram", "rail", "train",
                "subway", "metro", "ferry", "funicular", "cable-car", "cable_car", "gondola", "trolleybus");
        public static final String STOP_OFFSET = "00:00:00";
        public static final boolean WAIT_FOR_DEPARTURE = true;
        public static final boolean ALLOW_BOARDING = true;
        public static final boolean ALLOW_ALIGHTING = true;
        public static final int MAX_GENERATED_DEPARTURES = 10_000;
        public static final String MODE = "bus";
        public static final String FIRST_DEPARTURE = "07:00:00";
        public static final String LAST_DEPARTURE = "09:00:00";
        public static final String HEADWAY_MINUTES = "10";
        public static final String SEATS = "40";
        public static final String STANDING_PLACES = "40";
        public static final String VEHICLE_LENGTH_M = "12";
        public static final String VEHICLE_SPEED_KMH = "80";
        public static final String DEPARTURE_PREFIX = "service-";
        public static final boolean SHOW_LINES = true;
    }

    public static final class Window {
        private Window() { }
        public static final int LEGACY_WIDTH = 1400;
        public static final int LEGACY_HEIGHT = 900;
        public static final int LEGACY_TIMER_MS = 33;
        public static final int NETWORK_WIDTH = 1200;
        public static final int NETWORK_HEIGHT = 800;
        public static final int EDITOR_WIDTH = 1200;
        public static final int EDITOR_HEIGHT = 850;
        public static final int WIDTH = 1540;
        public static final int HEIGHT = 980;
        public static final int MIN_WIDTH = 1180;
        public static final int MIN_HEIGHT = 760;
    }

    /** Initial collection capacities only; none of these limits scenario size. */
    public static final class Parsing {
        private Parsing() { }
        public static final int ACTIVE_VEHICLES = 64_000;
        public static final int STOP_VEHICLES = 32_000;
        public static final int TRAVERSALS = 1_000_000;
        public static final int STOP_INTERACTIONS = 256_000;
    }
}
