package com.matsim.viz.parser;

import com.matsim.viz.config.AppDefaults;
import com.matsim.viz.domain.*;
import org.apache.commons.csv.CSVFormat;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.index.strtree.STRtree;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Optional display geometry; leaves the MATSim network, lengths and events untouched. */
public final class DetailedNetworkGeometry {
    private final Map<String,LinkPolyline> lines;
    private final STRtree index=new STRtree();
    private final int merged, rejectedRows, unmatched;
    private DetailedNetworkGeometry(Map<String,LinkPolyline> lines,int merged,int rejectedRows,int unmatched){
        this.lines=Map.copyOf(lines);this.merged=merged;this.rejectedRows=rejectedRows;this.unmatched=unmatched;
        lines.forEach((id,line)->index.insert(line.bounds(),id));index.build();
    }
    public LinkPolyline get(String id){return lines.get(id);}
    public int size(){return lines.size();}
    public int mergedCount(){return merged;}
    public String summary(){return size()+" links matched ("+merged+" merged); "+unmatched+" straight fallbacks; "+rejectedRows+" invalid/duplicate CSV rows.";}
    public void query(double minX,double minY,double maxX,double maxY,Set<String> result){
        for(Object id:index.query(new Envelope(minX,maxX,minY,maxY)))result.add((String)id);
    }
    public static List<Path> discover(Path config) throws IOException {
        if(config==null||config.toAbsolutePath().getParent()==null)return List.of();
        try(var files=Files.list(config.toAbsolutePath().getParent())){
            return files.filter(Files::isRegularFile).filter(p->p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("detailed_network.csv")).sorted().toList();
        }
    }
    private static String header(String value){return value.replace("\ufeff","").replace("_","").replace(" ","").toLowerCase(Locale.ROOT);}
    public static DetailedNetworkGeometry load(Path file,NetworkData network) throws IOException {
        Set<String> needed=new HashSet<>(network.getLinks().keySet());
        for(var link:network.getLinks().values())needed.addAll(chain(link));
        Map<String,LinkPolyline> original=new HashMap<>();Set<String> invalid=new HashSet<>();int rejected=0;
        char delimiter=',';
        try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){String first=reader.readLine();if(first!=null&&first.contains(";"))delimiter=';';}
        var format=CSVFormat.DEFAULT.builder().setDelimiter(delimiter).setHeader().setSkipHeaderRecord(true).setTrim(true).get();
        var wkt=new WKTReader();
        try(var reader=Files.newBufferedReader(file,StandardCharsets.UTF_8);var csv=format.parse(reader)){
            String idColumn=null,geometryColumn=null;
            for(String name:csv.getHeaderNames()){if(header(name).equals("linkid"))idColumn=name;if(header(name).equals("geometry"))geometryColumn=name;}
            if(idColumn==null||geometryColumn==null)throw new IOException("CSV needs LinkId (or link_id) and Geometry columns");
            for(var row:csv){
                String id=row.get(idColumn).trim();if(!needed.contains(id))continue;
                if(original.containsKey(id)||invalid.contains(id)){original.remove(id);invalid.add(id);rejected++;continue;}
                try {
                    Geometry geometry=wkt.read(row.get(geometryColumn));
                    // A discontinuous multi-part geometry cannot safely define vehicle travel.
                    if(!(geometry instanceof LineString line))throw new IllegalArgumentException("Expected LINESTRING");
                    List<Double> points=new ArrayList<>();double lastX=Double.NaN,lastY=Double.NaN;
                    for(Coordinate c:line.getCoordinates())if(c.x!=lastX||c.y!=lastY){points.add(c.x);points.add(c.y);lastX=c.x;lastY=c.y;}
                    original.put(id,new LinkPolyline(points.stream().mapToDouble(Double::doubleValue).toArray()));
                }catch(Exception ex){invalid.add(id);rejected++;}
            }
        }catch(UncheckedIOException ex){throw ex.getCause();}
        Map<String,LinkPolyline> resolved=new HashMap<>();int merged=0;
        for(var link:network.getLinks().values()){
            List<String> ids=chain(link);boolean combined=ids.size()>1;
            if(ids.isEmpty())ids=List.of(link.id());
            LinkPolyline line=assemble(ids,original,link);
            // Some preprocessing pipelines omit the retained ID from old_link_id.
            if(line==null&&!ids.contains(link.id())){
                var withFirst=new ArrayList<>(ids);withFirst.add(0,link.id());line=assemble(withFirst,original,link);
                if(line==null){var withLast=new ArrayList<>(ids);withLast.add(link.id());line=assemble(withLast,original,link);}
                combined=true;
            }
            if(line!=null){resolved.put(link.id(),line);if(combined)merged++;}
        }
        return new DetailedNetworkGeometry(resolved,merged,rejected,network.getLinks().size()-resolved.size());
    }
    private static List<String> chain(LinkSegment link){
        String ids=link.attribute("old_link_id");
        return ids==null||ids.isBlank()?List.of():Arrays.stream(ids.split("_")).map(String::trim).filter(s->!s.isEmpty()).toList();
    }
    private static LinkPolyline assemble(List<String> ids,Map<String,LinkPolyline> source,LinkSegment link){
        LinkPolyline line=join(ids,source,link);
        if(line!=null)return line;
        var reversed=new ArrayList<>(ids);Collections.reverse(reversed);return join(reversed,source,link);
    }
    private static LinkPolyline join(List<String> ids,Map<String,LinkPolyline> source,LinkSegment link){
        List<Double> points=new ArrayList<>();double x=link.fromX(),y=link.fromY();
        for(String id:ids){
            var part=source.get(id);if(part==null)return null;
            boolean reverse=Math.hypot(part.x(part.size()-1)-x,part.y(part.size()-1)-y)<Math.hypot(part.x(0)-x,part.y(0)-y);
            int first=reverse?part.size()-1:0;
            if(Math.hypot(part.x(first)-x,part.y(first)-y)>AppDefaults.Geometry.JOIN_TOLERANCE)return null;
            if(points.isEmpty()){points.add(x);points.add(y);}
            for(int j=1;j<part.size();j++){int k=reverse?part.size()-1-j:j;double px=part.x(k),py=part.y(k);if(px!=x||py!=y){points.add(px);points.add(py);x=px;y=py;}}
        }
        if(points.size()<4||Math.hypot(x-link.toX(),y-link.toY())>AppDefaults.Geometry.JOIN_TOLERANCE)return null;
        points.set(points.size()-2,link.toX());points.set(points.size()-1,link.toY());
        try{return new LinkPolyline(points.stream().mapToDouble(Double::doubleValue).toArray());}catch(IllegalArgumentException ex){return null;}
    }
}
