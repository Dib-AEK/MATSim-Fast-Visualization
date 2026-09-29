package com.matsim.viz.ui.editor;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.*;
import javafx.stage.*;
import org.matsim.api.core.v01.Id;
import org.matsim.pt.transitSchedule.api.*;
import org.matsim.vehicles.VehicleType;
import javax.swing.SwingUtilities;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Editable native transit data, loaded only when the planner opens this section. */
public final class TransitEditorPane extends VBox {
    private final Window owner;
    private final NetworkEditorPanel network;
    private final Consumer<Boolean> busy;
    private TransitEditorModel model;
    private boolean loading, closed, draftDirty, populating;
    private final Label status=new Label("Load the scenario transit files, or create a new transit system.");
    private final ComboBox<String> lines=new ComboBox<>(), routes=new ComboBox<>();
    private final CheckBox show=new CheckBox("Show PT lines"), all=new CheckBox("Show all lines (may be slower)");
    private final TextField mode=new TextField("bus");
    private final TextArea path=new TextArea();
    private final TableView<Row> stops=new TableView<>(), departures=new TableView<>();
    private final VBox editor=new VBox(8);
    private final Path defaultSchedule, defaultVehicles;
    private record Row(List<SimpleStringProperty> cells) {
        Row(String... values){this(Arrays.stream(values).map(SimpleStringProperty::new).toList());}
        String text(int index){return cells.get(index).get().trim();}
    }
    public TransitEditorPane(Window owner,NetworkEditorPanel network,Path schedule,Path vehicles,Consumer<Boolean> busy){
        super(8);this.owner=owner;this.network=network;this.defaultSchedule=schedule;this.defaultVehicles=vehicles;this.busy=busy;
        setPadding(new Insets(8));status.setWrapText(true);
        Button load=new Button("Load transit schedule + vehicles"),empty=new Button("New transit system");
        load.setOnAction(e->loadFiles());empty.setOnAction(e->load(null,null));
        getChildren().addAll(load,empty,status,editor);editor.setDisable(true);
        show.setSelected(true);show.selectedProperty().addListener((o,a,b)->refreshOverlay());all.selectedProperty().addListener((o,a,b)->refreshOverlay());
        lines.setMaxWidth(Double.MAX_VALUE);routes.setMaxWidth(Double.MAX_VALUE);
        lines.setPromptText("Select line");routes.setPromptText("Select route / direction");
        lines.valueProperty().addListener((o,a,b)->{
            if(populating)return;
            if(!discardDraft()){populating=true;lines.setValue(a);populating=false;return;}
            refreshRoutes();
        });
        routes.valueProperty().addListener((o,a,b)->{
            if(populating)return;
            if(!discardDraft()){populating=true;routes.setValue(a);populating=false;return;}
            refreshRoute();
        });
        Button addLine=button("New line",()->{
            String id=ask("New line ID","");if(id==null)return;String name=ask("Line name",id);if(name==null)return;
            if(!discardDraft())return;model.addLine(id,name);refreshLines(id);
        });
        Button addRoute=button("New route / direction",()->{
            if(lines.getValue()==null)throw new IllegalArgumentException("Select or create a line first");
            if(!discardDraft())return;
            String id=ask("New route ID","");if(id==null)return;
            model.addRoute(lines.getValue(),id,"bus");refreshRoutes();routes.setValue(id);
        });
        path.setPrefRowCount(3);path.setWrapText(true);path.setPromptText("Ordered network link IDs, separated by spaces or commas");
        mode.textProperty().addListener((o,a,b)->changed());path.textProperty().addListener((o,a,b)->changed());
        Button appendLink=button("Append selected road link",()->{
            String id=onEdt(network::selectedLinkId);
            if(id==null)throw new IllegalArgumentException("Hide PT lines, then click a road link on the map first");
            path.appendText((path.getText().isBlank()?"":" ")+id);
        });
        table(stops,"Stop ID","Arrival offset","Departure offset","Wait","Board","Alight");
        table(departures,"Departure ID","Time","Vehicle ID");
        Button addStop=button("Add stop to route",()->{
            String id=ask("Existing stop ID (use Create stop for a new facility)","");if(id==null)return;
            if(!model.schedule().getFacilities().containsKey(Id.create(id,TransitStopFacility.class)))throw new IllegalArgumentException("Stop ID not found");
            stops.getItems().add(new Row(id,"00:00:00","00:00:00","true","true","true"));changed();
        });
        Button removeStop=button("Remove stop from route",()->{stops.getItems().remove(stops.getSelectionModel().getSelectedItem());changed();});
        Button up=button("Move up",()->moveStop(-1)),down=button("Move down",()->moveStop(1));
        Button createStop=button("Create stop",()->stopDialog(true));
        Button editStop=button("Edit selected stop",()->stopDialog(false));
        Button moveStop=button("Place selected stop on map",()->{
            Row row=stops.getSelectionModel().getSelectedItem();if(row==null)throw new IllegalArgumentException("Select a stop row first");
            String id=row.text(0);status.setText("Click the new stop location on the map; then choose its network link. Shared stops move for all routes.");
            SwingUtilities.invokeLater(()->network.pickLocation(point->Platform.runLater(()->safe(()->{
                var stop=model.schedule().getFacilities().get(Id.create(id,TransitStopFacility.class));
                String link=ask("Network link ID at the new stop location",stop.getLinkId()==null?"":stop.getLinkId().toString());
                if(link==null)return;checkLink(link);model.moveStop(id,point.x,point.y,link);refreshOverlay();status.setText("Stop moved. Apply the route and validate before export.");
            }))));
        });
        Button locate=button("Centre on selected stop",()->{
            Row row=stops.getSelectionModel().getSelectedItem();if(row==null)return;
            var stop=model.schedule().getFacilities().get(Id.create(row.text(0),TransitStopFacility.class));
            if(stop!=null)SwingUtilities.invokeLater(()->network.centreOn(stop.getCoord().getX(),stop.getCoord().getY()));
        });
        Button addDeparture=button("Add departure",()->{departures.getItems().add(new Row("new-"+(departures.getItems().size()+1),"07:00:00",""));changed();});
        Button removeDeparture=button("Remove departure",()->{departures.getItems().remove(departures.getSelectionModel().getSelectedItem());changed();});
        Button generate=button("Generate departures",this::generateDepartures);
        Button apply=button("Apply route changes",this::applyRoute);apply.getStyleClass().add("accent-button");
        Button revert=button("Revert unapplied route changes",()->{draftDirty=false;refreshRoute();});
        Button type=button("Add / edit vehicle type",this::typeDialog),vehicle=button("Add / assign vehicle",this::vehicleDialog);
        Button inspectVehicles=button("Inspect vehicles",this::inspectVehicles);
        Button validate=button("Validate transit scenario",this::validate);
        Button export=button("Export network + schedule + vehicles",this::export);export.getStyleClass().add("accent-button");
        Label help=new Label("Offsets are relative to departure time. Use HH:MM:SS, including hours above 24. Double-click table cells to edit; Enter commits. Changing a shared stop affects every route using it.");help.setWrapText(true);
        VBox routeBox=new VBox(6,new Label("Transport mode"),mode,new Label("Network path (ordered link IDs)"),path,appendLink);
        VBox stopBox=new VBox(6,stops,new FlowPane(5,5,addStop,removeStop,up,down),new FlowPane(5,5,createStop,editStop,moveStop,locate));
        VBox departureBox=new VBox(6,departures,new FlowPane(5,5,addDeparture,removeDeparture,generate));
        editor.getChildren().addAll(show,all,new Label("Line"),lines,addLine,new Label("Route / direction"),routes,addRoute,
                section("Route path",routeBox),section("Stops and offsets",stopBox),section("Departures",departureBox),help,
                apply,revert,section("Transit vehicles",new VBox(6,inspectVehicles,type,vehicle)),validate,export);
    }
    private TitledPane section(String title,Node content){var pane=new TitledPane(title,content);pane.setExpanded(false);return pane;}
    private void table(TableView<Row> table,String... columns){
        table.setEditable(true);table.setPrefHeight(220);table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        for(int i=0;i<columns.length;i++){final int index=i;var col=new TableColumn<Row,String>(columns[i]);col.setPrefWidth(i==0?130:110);
            col.setCellValueFactory(v->v.getValue().cells().get(index));col.setCellFactory(TextFieldTableCell.forTableColumn());
            col.setOnEditCommit(e->{e.getRowValue().cells().get(index).set(e.getNewValue());changed();});table.getColumns().add(col);}
    }
    private Button button(String title,Runnable action){var button=new Button(title);button.setMaxWidth(Double.MAX_VALUE);button.setOnAction(e->safe(action));return button;}
    private void safe(Runnable action){try{action.run();}catch(Exception ex){status.setText(ex.getMessage());}}
    private void changed(){if(!populating){draftDirty=true;status.setText("Route has unapplied changes. Click Apply route changes before export.");}}
    public boolean hasUnsavedChanges(){return draftDirty||(model!=null&&model.isDirty());}
    public void close(){closed=true;model=null;}
    private boolean discardDraft(){
        if(!draftDirty)return true;
        Alert alert=new Alert(Alert.AlertType.CONFIRMATION,"Discard unapplied route changes?",ButtonType.OK,ButtonType.CANCEL);alert.initOwner(owner);
        if(alert.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return false;draftDirty=false;return true;
    }
    private String ask(String title,String initial){var dialog=new TextInputDialog(initial);dialog.initOwner(owner);dialog.setHeaderText(title);return dialog.showAndWait().map(String::trim).orElse(null);}
    private Path choose(String title,Path initial){var chooser=new FileChooser();chooser.setTitle(title);chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("MATSim XML","*.xml","*.xml.gz"));if(initial!=null&&Files.isDirectory(initial.getParent()))chooser.setInitialDirectory(initial.getParent().toFile());var file=chooser.showOpenDialog(owner);return file==null?null:file.toPath();}
    public void loadDefaultFiles(){if(model==null&&!loading&&defaultSchedule!=null&&Files.isRegularFile(defaultSchedule))load(defaultSchedule,defaultVehicles);}
    private void loadFiles(){
        Path schedule=choose("Open transit schedule",defaultSchedule);if(schedule==null)return;
        Path vehicles=choose("Open transit vehicles (Cancel to add vehicles manually)",defaultVehicles);
        load(schedule,vehicles);
    }
    private void load(Path schedule,Path vehicles){
        if(loading)return;
        if(hasUnsavedChanges()){Alert alert=new Alert(Alert.AlertType.CONFIRMATION,"Replace this transit session and discard unsaved transit changes?",ButtonType.OK,ButtonType.CANCEL);alert.initOwner(owner);if(alert.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return;}
        loading=true;setDisable(true);status.setText("Reading MATSim transit files...");
        CompletableFuture.supplyAsync(()->TransitEditorModel.load(schedule,vehicles)).whenComplete((loaded,error)->Platform.runLater(()->{
            loading=false;if(closed)return;setDisable(false);
            if(error!=null){status.setText("Load failed: "+error.getMessage());return;}
            model=loaded;draftDirty=false;editor.setDisable(false);refreshLines(null);
            status.setText("Loaded "+model.schedule().getTransitLines().size()+" lines, "+model.schedule().getFacilities().size()+" stops, "+model.vehicles().getVehicles().size()+" vehicles.");
        }));
    }
    private void refreshLines(String selected){populating=true;lines.setItems(FXCollections.observableArrayList(model.schedule().getTransitLines().keySet().stream().map(Object::toString).sorted().toList()));if(selected!=null)lines.setValue(selected);else lines.getSelectionModel().selectFirst();populating=false;refreshRoutes();}
    private void refreshRoutes(){populating=true;routes.getItems().clear();var line=model==null?null:model.line(lines.getValue());if(line!=null)routes.getItems().addAll(line.getRoutes().keySet().stream().map(Object::toString).sorted().toList());routes.getSelectionModel().selectFirst();populating=false;refreshRoute();}
    private void refreshRoute(){
        populating=true;stops.getItems().clear();departures.getItems().clear();path.clear();var route=currentRoute();
        if(route!=null){mode.setText(route.getTransportMode());path.setText(String.join(" ",model.routeLinks(route)));
            for(var row:model.stops(route))stops.getItems().add(new Row(row.facility(),row.arrival(),row.departure(),""+row.await(),""+row.boarding(),""+row.alighting()));
            for(var row:model.departures(route))departures.getItems().add(new Row(row.id(),row.time(),row.vehicle()));}
        populating=false;draftDirty=false;refreshOverlay();
    }
    private TransitRoute currentRoute(){return model==null||lines.getValue()==null||routes.getValue()==null?null:model.route(lines.getValue(),routes.getValue());}
    private void refreshOverlay(){
        if(model==null||closed)return;var paths=model.overlays(lines.getValue(),all.isSelected());String line=lines.getValue(),route=routes.getValue();boolean visible=show.isSelected();
        SwingUtilities.invokeLater(()->network.setTransitOverlay(paths,line,route,visible,(l,r)->Platform.runLater(()->{if(!closed){lines.setValue(l);if(Objects.equals(lines.getValue(),l))routes.setValue(r);}})));
    }
    private static boolean flag(String value){if(!value.equalsIgnoreCase("true")&&!value.equalsIgnoreCase("false"))throw new IllegalArgumentException("Wait, Board and Alight must be true or false");return Boolean.parseBoolean(value);}
    private void applyRoute(){
        if(currentRoute()==null)throw new IllegalArgumentException("Select a route first");
        List<String> ids=Arrays.stream(path.getText().split("[\\s,]+" )).filter(v->!v.isBlank()).toList();
        var stopRows=stops.getItems().stream().map(r->new TransitEditorModel.StopInput(r.text(0),r.text(1),r.text(2),flag(r.text(3)),flag(r.text(4)),flag(r.text(5)))).toList();
        var departureRows=departures.getItems().stream().map(r->new TransitEditorModel.DepartureInput(r.text(0),r.text(1),r.text(2))).toList();
        model.applyRoute(lines.getValue(),routes.getValue(),mode.getText().trim(),ids,stopRows,departureRows,onEdt(()->network.linksById(ids)));
        draftDirty=false;refreshRoute();status.setText("Route applied. Export to save the transit scenario.");
    }
    private void moveStop(int direction){int i=stops.getSelectionModel().getSelectedIndex(),j=i+direction;if(i<0||j<0||j>=stops.getItems().size())return;var row=stops.getItems().remove(i);stops.getItems().add(j,row);stops.getSelectionModel().select(j);changed();}
    private Map<String,String> form(String title,LinkedHashMap<String,String> values){
        Dialog<ButtonType> dialog=new Dialog<>();dialog.initOwner(owner);dialog.setTitle(title);dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK,ButtonType.CANCEL);
        VBox content=new VBox(6);Map<String,TextField> inputs=new LinkedHashMap<>();values.forEach((label,value)->{var input=new TextField(value);inputs.put(label,input);content.getChildren().addAll(new Label(label),input);});
        content.setPadding(new Insets(12));dialog.getDialogPane().setContent(content);
        if(dialog.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return null;Map<String,String> result=new LinkedHashMap<>();inputs.forEach((key,value)->result.put(key,value.getText().trim()));return result;
    }
    private static LinkedHashMap<String,String> fields(String... pairs){var map=new LinkedHashMap<String,String>();for(int i=0;i<pairs.length;i+=2)map.put(pairs[i],pairs[i+1]);return map;}
    private void checkLink(String id){if(onEdt(()->network.linkById(id))==null)throw new IllegalArgumentException("Network link not found: "+id);}
    private void stopDialog(boolean create){
        Row row=stops.getSelectionModel().getSelectedItem();var old=!create&&row!=null?model.schedule().getFacilities().get(Id.create(row.text(0),TransitStopFacility.class)):null;
        if(!create&&old==null)throw new IllegalArgumentException("Select a stop row first");
        var values=form(create?"Create stop facility":"Edit shared stop (all routes)",fields("ID",old==null?"":old.getId().toString(),"Name",old==null?"":Objects.toString(old.getName(),""),"X (network CRS)",old==null?"0":""+old.getCoord().getX(),"Y (network CRS)",old==null?"0":""+old.getCoord().getY(),"Network link ID",old==null?Objects.toString(onEdt(network::selectedLinkId),""):Objects.toString(old.getLinkId(),"")));
        if(values==null)return;
        if(!create&&!old.getId().toString().equals(values.get("ID")))throw new IllegalArgumentException("Existing stop IDs cannot be renamed; create a new stop instead");
        checkLink(values.get("Network link ID"));model.putStop(values.get("ID"),values.get("Name"),Double.parseDouble(values.get("X (network CRS)")),Double.parseDouble(values.get("Y (network CRS)")),values.get("Network link ID"),create);
        if(create){stops.getItems().add(new Row(values.get("ID"),"00:00:00","00:00:00","true","true","true"));changed();}
        refreshOverlay();status.setText("Stop saved in this session. Apply route changes and export when ready.");
    }
    private void generateDepartures(){
        var values=form("Add regular departures",fields("First departure","07:00:00","Last departure","09:00:00","Headway (minutes)","10","Vehicle IDs (comma-separated)","","Departure ID prefix","service-"));if(values==null)return;
        double start=TransitEditorModel.seconds(values.get("First departure")),end=TransitEditorModel.seconds(values.get("Last departure")),step=Double.parseDouble(values.get("Headway (minutes)"))*60;
        List<String> ids=Arrays.stream(values.get("Vehicle IDs (comma-separated)").split(",")).map(String::trim).filter(v->!v.isBlank()).toList();
        if(!Double.isFinite(step)||step<=0||end<start||(end-start)/step>10000||ids.isEmpty())throw new IllegalArgumentException("Use a positive headway, an ordered time range (up to 10,000 departures), and vehicle IDs");
        int i=0;for(double time=start;time<=end+1e-6;time+=step){departures.getItems().add(new Row(values.get("Departure ID prefix")+i,org.matsim.core.utils.misc.Time.writeTime(time),ids.get(i%ids.size())));i++;}changed();
    }
    private void typeDialog(){
        String id=ask("Vehicle type ID (existing or new)","");if(id==null)return;var old=model.vehicles().getVehicleTypes().get(Id.create(id,VehicleType.class));
        var values=form("Vehicle type "+id,fields("Network mode",old==null?"bus":old.getNetworkMode(),"Seats",old==null?"40":""+old.getCapacity().getSeats(),"Standing places",old==null?"40":""+old.getCapacity().getStandingRoom(),"Length (m)",old==null?"12":""+old.getLength(),"Maximum speed (km/h)",old==null?"80":""+(old.getMaximumVelocity()*3.6)));if(values==null)return;
        model.putVehicleType(id,Integer.parseInt(values.get("Seats")),Integer.parseInt(values.get("Standing places")),Double.parseDouble(values.get("Length (m)")),Double.parseDouble(values.get("Maximum speed (km/h)")),values.get("Network mode"));status.setText("Vehicle type updated.");
    }
    private void vehicleDialog(){var values=form("Add vehicle or change its type",fields("Vehicle ID","","Vehicle type ID",""));if(values!=null){model.putVehicle(values.get("Vehicle ID"),values.get("Vehicle type ID"));status.setText("Vehicle updated.");}}
    private void inspectVehicles(){
        var table=new TableView<Row>();table(table,"Vehicle ID","Type","Seats","Standing");table.setEditable(false);table.setPrefHeight(450);
        model.vehicles().getVehicles().values().stream().sorted(Comparator.comparing(v->v.getId().toString())).forEach(v->table.getItems().add(new Row(v.getId().toString(),v.getType().getId().toString(),""+v.getType().getCapacity().getSeats(),""+v.getType().getCapacity().getStandingRoom())));
        Dialog<ButtonType> dialog=new Dialog<>();dialog.initOwner(owner);dialog.setTitle("Transit vehicles");dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);dialog.getDialogPane().setContent(table);dialog.setResizable(true);dialog.showAndWait();
    }
    private void validate(){
        if(draftDirty)throw new IllegalArgumentException("Apply route changes first");
        var snapshot=onEdt(network::snapshotForSave);busy.accept(true);status.setText("Validating transit scenario...");
        CompletableFuture.runAsync(()->model.validate(snapshot.links())).whenComplete((ignored,error)->Platform.runLater(()->{
            busy.accept(false);if(closed)return;
            status.setText(error==null?"Validation passed: connected paths, ordered stops, vehicles and departure duties.":"Validation failed: "+(error.getCause()==null?error.getMessage():error.getCause().getMessage()));
        }));
    }
    private void export(){
        if(draftDirty)throw new IllegalArgumentException("Apply route changes before export");
        DirectoryChooser chooser=new DirectoryChooser();chooser.setTitle("Parent folder for a new transit scenario");var parent=chooser.showDialog(owner);if(parent==null)return;
        var snapshot=onEdt(network::snapshotForSave);busy.accept(true);setDisable(true);status.setText("Validating and exporting native MATSim files...");
        CompletableFuture.supplyAsync(()->{try{return model.exportBundle(parent.toPath(),snapshot);}catch(Exception ex){throw new java.util.concurrent.CompletionException(ex);}}).whenComplete((folder,error)->Platform.runLater(()->{
            busy.accept(false);if(closed)return;setDisable(false);
            if(error==null){model.markSaved();SwingUtilities.invokeLater(network::markSaved);status.setText("Saved network.xml.gz, transitSchedule.xml.gz and transitVehicles.xml.gz in "+folder);}
            else status.setText("Export failed: "+(error.getCause()==null?error.getMessage():error.getCause().getMessage()));
        }));
    }
    private static <T>T onEdt(Supplier<T> supplier){
        if(SwingUtilities.isEventDispatchThread())return supplier.get();var result=new java.util.concurrent.atomic.AtomicReference<T>();var failure=new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
        try{SwingUtilities.invokeAndWait(()->{try{result.set(supplier.get());}catch(RuntimeException ex){failure.set(ex);}});}catch(Exception ex){throw new IllegalStateException(ex);}
        if(failure.get()!=null)throw failure.get();return result.get();
    }
}
