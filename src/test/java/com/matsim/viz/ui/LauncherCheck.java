package com.matsim.viz.ui;

import com.matsim.viz.Main;
import com.matsim.viz.launcher.LaunchSettings;
import com.matsim.viz.parser.*;
import org.matsim.api.core.v01.*;
import org.matsim.api.core.v01.events.*;
import org.matsim.core.config.*;
import org.matsim.core.network.*;
import org.matsim.core.network.io.NetworkWriter;
import org.matsim.core.events.algorithms.EventWriterXML;
import java.nio.file.*;
import java.util.*;

public final class LauncherCheck {
    static void check(boolean valid,String text){if(!valid)throw new AssertionError(text);}
    public static void main(String[] args)throws Exception{
        Path dir=Files.createTempDirectory(Path.of("target").toAbsolutePath(),"launcher space é ");
        var config=ConfigUtils.createConfig();config.network().setInputFile("missing.xml");
        config.plans().setInputFile("missing-population.xml");config.controller().setOutputDirectory("output");
        Path xml=dir.resolve("config.xml");new ConfigWriter(config).write(xml.toString());
        Files.createDirectories(dir.resolve("output"));
        var network=NetworkUtils.createNetwork();
        var a=NetworkUtils.createAndAddNode(network,Id.createNodeId("a"),new Coord(0,0));
        var b=NetworkUtils.createAndAddNode(network,Id.createNodeId("b"),new Coord(100,0));
        var link=NetworkUtils.createAndAddLink(network,Id.createLinkId("road"),a,b,100,15,1800,1);
        Path net=dir.resolve("output/output_network.xml.gz");new NetworkWriter(network).write(net.toString());
        Path events=dir.resolve("output/output_events.xml.gz");var writer=new EventWriterXML(events.toString());
        writer.handleEvent(new VehicleEntersTrafficEvent(0,Id.createPersonId("p"),link.getId(),Id.createVehicleId("v"),"car",0));
        writer.handleEvent(new LinkLeaveEvent(30,Id.createVehicleId("v"),link.getId()));writer.closeFile();
        var discovery=new MatsimScenarioLoader().discoverInputs(xml);
        check(discovery.files().get("network").equals(net)&&discovery.files().get("events").equals(events),"Output detection failed");
        check(!discovery.warnings().isEmpty(),"Missing optional population not reported");
        Properties settings=new Properties();settings.setProperty("matsim.config.file",xml.toString());
        settings.setProperty("cache.dir",dir.resolve("cache").toString());
        discovery.files().forEach((key,value)->settings.setProperty("input."+key,value.toString()));
        settings.setProperty("input.population","");
        Path override=dir.resolve("manual-network.xml.gz");Files.copy(net,override);settings.setProperty("input.network",override.toString());
        var selected=LaunchSettings.resolve(settings);
        check(selected.inputs().networkFile().equals(override),"Override ignored");
        check(new MatsimScenarioLoader().load(selected.inputs()).networkData().getLinks().containsKey("road"),"Optional population or missing config paths prevented load");
        Path session=dir.resolve("launch.properties");LaunchSettings.write(session,settings);
        Main.main(new String[]{"--launch-settings",session.toString(),"--build-cache"});
        var caches=LaunchSettings.caches(dir.resolve("cache"));check(caches.size()==1,"Cache not built in selected folder");
        settings.setProperty("launch.mode","cache");settings.setProperty("launch.cache.file",caches.getFirst().toString());
        settings.setProperty("matsim.config.file",dir.resolve("not-present.xml").toString());
        settings.setProperty("input.network",dir.resolve("not-present-network.xml").toString());
        check(LaunchSettings.resolve(settings).inputs().matsimConfigFile()==null,"Cache mode still requires config");
        LaunchSettings.write(session,settings);Main.main(new String[]{"--launch-settings",session.toString(),"--build-cache"});
        settings.setProperty("launch.mode","files");boolean rejected=false;try{LaunchSettings.resolve(settings);}catch(IllegalArgumentException ex){rejected=true;}
        check(rejected,"Missing config was accepted in file mode");
        System.out.println("PASS: native partial discovery, editable paths, optional metadata, spaces/Unicode, cache build and source-free cache loading. "+dir);
    }
}
