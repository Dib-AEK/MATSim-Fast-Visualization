package com.matsim.viz.ui;

import com.matsim.viz.domain.*;
import com.matsim.viz.engine.*;
import java.util.*;

/** Random seeks, event boundaries, reverse updates and an independent historical-replay benchmark. */
public final class PlaybackSeekCheck {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        int count = 300_000;
        var links = new HashMap<String,LinkSegment>();
        for (int i=0;i<1000;i++) links.put("l"+i,new LinkSegment("l"+i,"a","b",0,0,100,0,100,10,1,Set.of("car"),Map.of()));
        var traversals = new VehicleTraversal[count];
        Random random = new Random(123);
        for (int i=0;i<count;i++) {
            double enter = random.nextInt(86400);
            double duration = i%97==0 ? 0 : i%101==0 ? 20000 : 1+random.nextInt(120);
            traversals[i]=new VehicleTraversal(i,"v"+(i%1000),"l"+(i%1000),enter,enter+duration);
        }
        var model = new SimulationModel(new NetworkData(Map.of(),links,0,0,100,100),traversals,
                Map.of(),Map.of(),Map.of(),Map.of(),Map.of(),null);
        var controller = new PlaybackController(model,0,model.maxTime(),1);
        for(int trial=0;trial<100;trial++) {
            double time = trial%3==0 ? random.nextInt(86400) : trial%3==1
                    ? controller.getCurrentTime()-0.5 : controller.getCurrentTime()+1.5;
            controller.seek(time); time=controller.getCurrentTime();
            Set<Integer> expected=new HashSet<>(); Map<String,Integer> queues=new HashMap<>();
            for(int i=0;i<count;i++) if(model.traversalEnterTime(i)<=time && model.traversalLeaveTime(i)>time) {
                expected.add(i);queues.merge(model.traversalLinkId(i),1,Integer::sum);
            }
            check(expected.equals(controller.getActiveTraversalIndexes()),"Incorrect seek at " + time);
            check(queues.equals(controller.getLinkQueueCountsView()),"Queue mismatch");
            for(var snapshot:controller.snapshotLinkState(links.keySet()).entrySet()) {
                check(snapshot.getValue().traversalIndexes().length==queues.get(snapshot.getKey()),"Snapshot mismatch");
                for(int index:snapshot.getValue().traversalIndexes()) check(expected.contains(index),"Stale traversal");
            }
        }
        controller.seek(50000);controller.setPlaying(true);controller.tick(0.5);
        Set<Integer> ticked=controller.getActiveTraversalIndexes();controller.seek(0);controller.seek(50000.5);
        check(ticked.equals(controller.getActiveTraversalIndexes()),"Tick and seek differ");
        double target=80000;
        long begin=System.nanoTime();
        Set<Integer> legacy=new HashSet<>(); Map<String,List<Integer>> byLink=new HashMap<>();
        for(int event=0;event<model.firstTransitionAfter(target);event++) {
            int i=model.transitionTraversalIndex(event);String link=model.traversalLinkId(i);
            if(model.traversalLeaveTime(i)<=model.traversalEnterTime(i))continue;
            if(model.transitionEnter(event)){legacy.add(i);byLink.computeIfAbsent(link,k->new ArrayList<>()).add(i);}
            else {legacy.remove(i);var active=byLink.get(link);active.remove(Integer.valueOf(i));if(active.isEmpty())byLink.remove(link);}
        }
        double legacyMs=(System.nanoTime()-begin)/1e6;
        controller.seek(0);begin=System.nanoTime();controller.seek(target);double indexedMs=(System.nanoTime()-begin)/1e6;
        check(legacy.equals(controller.getActiveTraversalIndexes()),"Indexed seek differs from event replay");
        System.out.printf("PASS: 300,000 traversals, random forward/backward seeks, zero-duration boundaries, queue/snapshot parity; replay %.2f ms, indexed seek %.2f ms.%n",legacyMs,indexedMs);
    }
}
