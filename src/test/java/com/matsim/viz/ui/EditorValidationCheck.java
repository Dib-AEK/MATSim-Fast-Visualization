package com.matsim.viz.ui;

import com.matsim.viz.domain.*;
import com.matsim.viz.ui.editor.NetworkEditorPanel;
import com.matsim.viz.config.AppDefaults;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.swing.SwingUtilities;

public final class EditorValidationCheck {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var nodes = Map.of("a", new NodePoint("a",0,0), "b", new NodePoint("b",100,0));
        var bad = new LinkSegment("existing-invalid", "a", "b", 0,0,100,0,0,Double.NaN,-1,Set.of("car"),Map.of("capacity","0"));
        var good = new LinkSegment("edited-road", "a", "b",0,0,100,0,100,15,1,Set.of("car"),Map.of("capacity","1800"));
        var snapshot = new NetworkEditorPanel.SaveSnapshot(nodes, Map.of(bad.id(),bad,good.id(),good));
        var issues = snapshot.validationIssues();
        check(issues.size()==1 && issues.containsKey(bad.id()), "Must identify unchanged invalid link");
        for (String field : List.of("length", "speed", "lanes", "capacity")) check(issues.get(bad.id()).contains(field), "Missing field " + field);
        Path dir=Files.createTempDirectory(Path.of("target"),"editor-validation-");
        Path output=dir.resolve("protected.xml"); Files.writeString(output,"preserve me");
        try { snapshot.write(output); throw new AssertionError("Invalid network exported"); }
        catch (NetworkEditorPanel.NetworkValidationException expected) { check(expected.issues().equals(issues),"Save report differs"); }
        check(Files.readString(output).equals("preserve me"),"Failed save replaced destination");
        SwingUtilities.invokeAndWait(()->{
            var data=new NetworkData(nodes,Map.of(bad.id(),bad),0,0,100,100);
            var panel=new NetworkEditorPanel(NetworkEditorPanel.prepare(data,null),dir);
            try {
                panel.setSize(700,500);panel.highlightValidationIssues(issues);
                var image=new BufferedImage(700,500,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();panel.paint(g);g.dispose();
                int pink=0;for(int y=0;y<500;y++)for(int x=0;x<700;x++)if(image.getRGB(x,y)==AppDefaults.Editor.INVALID_LINK.getRGB())pink++;
                check(pink>0,"Invalid link not highlighted");
                panel.updateLink(bad.id(),100,50,1,2200,Set.of("car"));
                check(panel.snapshotForSave().validationIssues().isEmpty(),"Corrected link still invalid");
                panel.undo();check(panel.snapshotForSave().validationIssues().size()==1,"Undo lost invalid-link detection");
                panel.redo();check(panel.snapshotForSave().validationIssues().isEmpty(),"Redo remained invalid");
            } finally { panel.disposeResources(); }
        });
        System.out.println("PASS: all invalid fields and unchanged links reported, pink highlighting, correction/undo/redo and failed-save preservation.");
    }
}
