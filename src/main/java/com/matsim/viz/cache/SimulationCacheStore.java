package com.matsim.viz.cache;

import com.matsim.viz.domain.LinkSegment;
import com.matsim.viz.domain.NetworkData;
import com.matsim.viz.domain.NodePoint;
import com.matsim.viz.domain.VehicleMetadata;
import com.matsim.viz.domain.VehicleTraversal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class SimulationCacheStore {
    private static final int MAGIC = 0x4D565A31; // MVZ1
    private static final int VERSION = 4;

    private final Path cacheDir;

    public SimulationCacheStore(Path cacheDir) {
        this.cacheDir = cacheDir.toAbsolutePath().normalize();
    }

    public boolean exists(String cacheKey) {
        return Files.exists(cacheFile(cacheKey));
    }

    public void delete(String cacheKey) throws IOException {
        Files.deleteIfExists(cacheFile(cacheKey));
    }

    public CachedSimulationData load(String cacheKey) throws IOException {
        Path file = cacheFile(cacheKey);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file), 256 * 1024), 256 * 1024))) {
            int magic = in.readInt();
            int version = in.readInt();
            if (magic != MAGIC || version != VERSION) {
                throw new IOException("Unsupported cache format: " + file);
            }

            NetworkData network = readNetworkData(in);
            VehicleTraversal[] traversals = readTraversals(in);
            Map<String, String> vehicleToPerson = readStringMap(in);
            Map<String, String> vehicleToMode = readStringMap(in);
            Map<String, VehicleMetadata> metadataByPerson = readMetadataMap(in);

            return new CachedSimulationData(network, traversals, vehicleToPerson, vehicleToMode, metadataByPerson);
        }
    }

    public void save(String cacheKey, CachedSimulationData data) throws IOException {
        Files.createDirectories(cacheDir);
        Path file = cacheFile(cacheKey);

        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file), 256 * 1024), 256 * 1024))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);

            writeNetworkData(out, data.networkData());
            writeTraversals(out, data.traversals());
            writeStringMap(out, data.vehicleToPerson());
            writeStringMap(out, data.vehicleToMode());
            writeMetadataMap(out, data.metadataByPerson());
        }
    }

    private Path cacheFile(String cacheKey) {
        return cacheDir.resolve(cacheKey + ".mviz.bin.gz");
    }

    private static void writeNetworkData(DataOutputStream out, NetworkData data) throws IOException {
        out.writeDouble(data.getMinX());
        out.writeDouble(data.getMinY());
        out.writeDouble(data.getMaxX());
        out.writeDouble(data.getMaxY());

        out.writeInt(data.getNodes().size());
        for (NodePoint node : data.getNodes().values()) {
            writeString(out, node.id());
            out.writeDouble(node.x());
            out.writeDouble(node.y());
        }

        out.writeInt(data.getLinks().size());
        for (LinkSegment link : data.getLinks().values()) {
            writeString(out, link.id());
            writeString(out, link.fromNodeId());
            writeString(out, link.toNodeId());
            out.writeDouble(link.fromX());
            out.writeDouble(link.fromY());
            out.writeDouble(link.toX());
            out.writeDouble(link.toY());
            out.writeDouble(link.length());
            out.writeDouble(link.freeSpeed());
            out.writeDouble(link.lanes());
            out.writeInt(link.allowedModes().size());
            for (String mode : link.allowedModes()) {
                writeString(out, mode);
            }
        }
    }

    private static NetworkData readNetworkData(DataInputStream in) throws IOException {
        double minX = in.readDouble();
        double minY = in.readDouble();
        double maxX = in.readDouble();
        double maxY = in.readDouble();

        int nodeCount = in.readInt();
        Map<String, NodePoint> nodes = new HashMap<>(Math.max(16, nodeCount));
        for (int i = 0; i < nodeCount; i++) {
            String id = readString(in);
            double x = in.readDouble();
            double y = in.readDouble();
            nodes.put(id, new NodePoint(id, x, y));
        }

        int linkCount = in.readInt();
        Map<String, LinkSegment> links = new HashMap<>(Math.max(16, linkCount));
        for (int i = 0; i < linkCount; i++) {
            String id = readString(in);
            String fromNodeId = readString(in);
            String toNodeId = readString(in);
            double fromX = in.readDouble();
            double fromY = in.readDouble();
            double toX = in.readDouble();
            double toY = in.readDouble();
            double length = in.readDouble();
            double freeSpeed = in.readDouble();
            double lanes = in.readDouble();
            int modeCount = in.readInt();
            Set<String> modes = new HashSet<>(Math.max(4, modeCount));
            for (int j = 0; j < modeCount; j++) {
                modes.add(readString(in));
            }

            links.put(id, new LinkSegment(
                    id,
                    fromNodeId,
                    toNodeId,
                    fromX,
                    fromY,
                    toX,
                    toY,
                    length,
                    freeSpeed,
                    lanes,
                    modes
            ));
        }

        return new NetworkData(nodes, links, minX, minY, maxX, maxY);
    }

    private static void writeTraversals(DataOutputStream out, VehicleTraversal[] traversals) throws IOException {
        out.writeInt(traversals.length);
        for (VehicleTraversal traversal : traversals) {
            out.writeInt(traversal.index());
            writeString(out, traversal.vehicleId());
            writeString(out, traversal.linkId());
            out.writeDouble(traversal.enterTimeSeconds());
            out.writeDouble(traversal.leaveTimeSeconds());
        }
    }

    private static VehicleTraversal[] readTraversals(DataInputStream in) throws IOException {
        int count = in.readInt();
        VehicleTraversal[] traversals = new VehicleTraversal[count];
        for (int i = 0; i < count; i++) {
            int index = in.readInt();
            String vehicleId = readString(in);
            String linkId = readString(in);
            double enter = in.readDouble();
            double leave = in.readDouble();
            traversals[i] = new VehicleTraversal(index, vehicleId, linkId, enter, leave);
        }
        return traversals;
    }

    private static void writeStringMap(DataOutputStream out, Map<String, String> map) throws IOException {
        out.writeInt(map.size());
        for (Map.Entry<String, String> entry : map.entrySet()) {
            writeString(out, entry.getKey());
            writeString(out, entry.getValue());
        }
    }

    private static Map<String, String> readStringMap(DataInputStream in) throws IOException {
        int size = in.readInt();
        Map<String, String> map = new HashMap<>(Math.max(16, size));
        for (int i = 0; i < size; i++) {
            map.put(readString(in), readString(in));
        }
        return map;
    }

    private static void writeMetadataMap(DataOutputStream out, Map<String, VehicleMetadata> map) throws IOException {
        out.writeInt(map.size());
        for (Map.Entry<String, VehicleMetadata> entry : map.entrySet()) {
            writeString(out, entry.getKey());
            VehicleMetadata metadata = entry.getValue();
            writeString(out, metadata.personId());
            writeNullableString(out, metadata.tripPurpose());
            writeNullableInteger(out, metadata.age());
            writeNullableString(out, metadata.sex());
        }
    }

    private static Map<String, VehicleMetadata> readMetadataMap(DataInputStream in) throws IOException {
        int size = in.readInt();
        Map<String, VehicleMetadata> map = new HashMap<>(Math.max(16, size));
        for (int i = 0; i < size; i++) {
            String key = readString(in);
            String personId = readString(in);
            String tripPurpose = readNullableString(in);
            Integer age = readNullableInteger(in);
            String sex = readNullableString(in);
            map.put(key, new VehicleMetadata(personId, tripPurpose, age, sex));
        }
        return map;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("Negative string length in cache stream");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Unexpected end of cache stream while reading string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeNullableString(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            writeString(out, value);
        }
    }

    private static String readNullableString(DataInputStream in) throws IOException {
        return in.readBoolean() ? readString(in) : null;
    }

    private static void writeNullableInteger(DataOutputStream out, Integer value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeInt(value);
        }
    }

    private static Integer readNullableInteger(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readInt() : null;
    }
}
