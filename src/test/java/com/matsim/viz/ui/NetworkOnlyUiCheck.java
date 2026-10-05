package com.matsim.viz.ui;

import com.matsim.viz.Main;
import com.matsim.viz.ui.editor.NetworkEditorPanel;
import javafx.application.Platform;
import javafx.embed.swing.SwingNode;
import javafx.scene.control.*;
import javafx.stage.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;

/** Run without headless mode, with a synthetic network-only launch-settings file. */
public final class NetworkOnlyUiCheck {
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void main(String[] args)throws Exception{
        var timer=Executors.newSingleThreadScheduledExecutor();int[] phase={0};
        timer.schedule(()->{System.err.println("FAIL: network-only UI timed out");System.exit(2);},60,TimeUnit.SECONDS);
        timer.scheduleAtFixedRate(()->{try{Platform.runLater(()->{try{
            for(Window window:new ArrayList<>(Window.getWindows()))if(window instanceof Stage stage&&stage.isShowing()){
                var root=stage.getScene().getRoot();
                var swing=root.lookupAll("*").stream().filter(n->n instanceof SwingNode).map(n->(SwingNode)n).findFirst().orElse(null);
                if(swing==null)continue;
                if(phase[0]==0 && swing.getContent() instanceof NetworkPanel panel){
                    boolean[] ready={false};SwingUtilities.invokeAndWait(()->ready[0]=panel.sharedDetailedGeometry()!=null && panel.isDetailedGeometryEnabled());
                    if(!ready[0])continue;
                    int buttons=0;
                    for(var node:root.lookupAll(".button"))if(node instanceof Button b && (b.getText().equals("Play")||b.getText().contains("Record"))){check(b.isDisabled(),"Movement button enabled: "+b.getText());buttons++;}
                    check(buttons==2,"Missing Play/Record buttons");
                    ComboBox choice=(ComboBox)root.lookup("#visualization-choice");
                    check(choice.getValue().toString().equals("Network only"),"Not in network view");
                    for(Object item:List.copyOf(choice.getItems()))if(!item.toString().equals("Network only")&&!item.toString().equals("Network Editor")){
                        choice.setValue(item);check(choice.getValue().toString().equals("Network only"),"Unavailable view selected");
                    }
                    phase[0]=1;
                    Object editor=choice.getItems().stream().filter(v->v.toString().equals("Network Editor")).findFirst().orElseThrow();choice.setValue(editor);
                } else if(phase[0]==1 && swing.getContent() instanceof NetworkEditorPanel editor){
                    SwingUtilities.invokeAndWait(()->{
                        check(editor.isDetailedGeometryEnabled(),"Editor lost manual CSV");
                        editor.updateLink("road",100,36,2,2200,Set.of("car"));
                        editor.snapshotForSave().write(Path.of("target/network-only-ui-export.xml.gz"));editor.markSaved();
                    });
                    phase[0]=2;System.out.println("PASS: network-only GUI, explicit CSV in viewer/editor, disabled Play/Record and guarded simulation modes, network editing/export.");
                    stage.fireEvent(new WindowEvent(stage,WindowEvent.WINDOW_CLOSE_REQUEST));stage.close();Platform.exit();
                }
            }
        }catch(Throwable ex){ex.printStackTrace();System.exit(1);}});}catch(IllegalStateException ignored){}},1,1,TimeUnit.SECONDS);
        try{Main.main(new String[]{"--launch-settings",args[0]});check(phase[0]==2,"Viewer exited before checks");System.exit(0);}finally{timer.shutdownNow();}
    }
}
