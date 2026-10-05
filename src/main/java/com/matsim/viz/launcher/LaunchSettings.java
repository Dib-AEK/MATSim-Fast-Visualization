package com.matsim.viz.launcher;

import com.matsim.viz.config.AppConfig;
import com.matsim.viz.config.AppDefaults;
import com.matsim.viz.config.ConfigLoader;
import com.matsim.viz.parser.ResolvedSimulationInputs;
import org.matsim.core.config.ConfigUtils;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Portable startup selection; paths are absolute so launching does not depend on the working directory. */
public final class LaunchSettings {
    private LaunchSettings() { }
    public static final String CACHE_SUFFIX = ".mviz.bin.gz";
    public record Selection(AppConfig config, ResolvedSimulationInputs inputs, String cacheKey, Path transitVehicles, Path detailedGeometry) {
        public boolean networkOnly() { return cacheKey == null && inputs.eventsFile() == null; }
    }

    public static Properties read(Path file) throws IOException {
        Properties result = new Properties();
        try (var in = Files.newInputStream(file)) { result.load(in); }
        return result;
    }
    public static void write(Path file, Properties settings) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (var out = Files.newOutputStream(file)) { settings.store(out, "MATSim visualization startup"); }
    }
    public static List<Path> caches(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(CACHE_SUFFIX))
                    .sorted().toList();
        }
    }
    private static Path path(Properties p, String key, boolean required) {
        String value = p.getProperty(key, "").trim();
        if (value.isEmpty()) {
            if (required) throw new IllegalArgumentException("Choose a file for " + key);
            return null;
        }
        Path file = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("File not found: " + file);
        return file;
    }
    public static Selection resolve(Properties settings) {
        Properties p = new Properties(); p.putAll(settings);
        String cacheDir = p.getProperty("cache.dir", "").trim();
        boolean networkOnly = "network".equals(p.getProperty("launch.mode"));
        if (cacheDir.isEmpty() && networkOnly) cacheDir = Path.of(System.getProperty("user.home"),
                AppDefaults.Launcher.USER_DIRECTORY, AppDefaults.Paths.CACHE_DIR).toString();
        if (cacheDir.isEmpty()) throw new IllegalArgumentException("Choose a cache folder");
        p.setProperty("cache.dir", Path.of(cacheDir).toAbsolutePath().normalize().toString());
        Path geometry = path(p, "input.geometry", false);
        if (networkOnly) {
            Path network = path(p, "input.network", true);
            p.remove("matsim.config.file");
            var config = ConfigUtils.createConfig();
            config.network().setInputFile(network.toString());
            return new Selection(ConfigLoader.load(p), new ResolvedSimulationInputs(null, network, null, null,
                    null, null, null, null, config), null, null, geometry);
        }
        if ("cache".equals(p.getProperty("launch.mode", AppDefaults.Launcher.MODE))) {
            Path file = path(p, "launch.cache.file", true);
            String name = file.getFileName().toString();
            if (!name.endsWith(CACHE_SUFFIX)) throw new IllegalArgumentException("Choose a .mviz.bin.gz cache");
            p.setProperty("cache.dir", file.getParent().toString());
            p.remove("matsim.config.file");
            return new Selection(ConfigLoader.load(p), new ResolvedSimulationInputs(null, null, null, null,
                    null, null, null, null, ConfigUtils.createConfig()), name.substring(0, name.length()-CACHE_SUFFIX.length()), null, geometry);
        }
        Path configFile = path(p, "matsim.config.file", true);
        p.setProperty("matsim.config.file", configFile.toString());
        var config = ConfigUtils.loadConfig(configFile.toString());
        Path network=path(p,"input.network",true), events=path(p,"input.events",true);
        Path population=path(p,"input.population",false), trips=path(p,"input.trips",false);
        Path persons=path(p,"input.persons",false), plans=path(p,"input.plans",false);
        Path schedule=path(p,"input.schedule",false), vehicles=path(p,"input.vehicles",false);
        config.network().setInputFile(network.toString());
        config.plans().setInputFile(population == null ? null : population.toString());
        config.transit().setTransitScheduleFile(schedule == null ? null : schedule.toString());
        config.transit().setVehiclesFile(vehicles == null ? null : vehicles.toString());
        config.transit().setUseTransit(schedule != null);
        return new Selection(ConfigLoader.load(p), new ResolvedSimulationInputs(configFile, network, population, events,
                trips, persons, plans, schedule, config), null, vehicles, geometry);
    }
}
