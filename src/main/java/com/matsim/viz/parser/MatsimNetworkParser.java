package com.matsim.viz.parser;

import com.matsim.viz.domain.NetworkData;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import java.nio.file.Path;

/** Standalone network loading uses the same MATSim reader as scenario loading. */
public final class MatsimNetworkParser {
    public NetworkData parse(Path networkFile) {
        var network = NetworkUtils.createNetwork();
        new MatsimNetworkReader(network).readFile(networkFile.toString());
        return MatsimNetworkConverter.fromMatsim(network);
    }
}
