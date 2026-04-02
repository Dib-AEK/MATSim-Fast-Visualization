package com.matsim.viz.ui.fx;

import com.matsim.viz.domain.ColorMode;
import com.matsim.viz.engine.PlaybackController;
import com.matsim.viz.engine.SimulationModel;
import com.matsim.viz.ui.NetworkPanel;
import com.matsim.viz.ui.TimeFormat;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class FxVisualizerApp extends Application {
    private static SimulationModel startupModel;
    private static PlaybackController startupPlaybackController;
    private static double startupSampleSize = 1.0;

    public static void launchVisualizer(SimulationModel model, PlaybackController playbackController, double sampleSize) {
        startupModel = Objects.requireNonNull(model, "model");
        startupPlaybackController = Objects.requireNonNull(playbackController, "playbackController");
        startupSampleSize = sampleSize;
        Application.launch(FxVisualizerApp.class);
    }

    @Override
    public void start(Stage stage) {
        if (startupModel == null || startupPlaybackController == null) {
            throw new IllegalStateException("Visualizer startup context is missing.");
        }

        SimulationModel model = startupModel;
        PlaybackController playbackController = startupPlaybackController;

        NetworkPanel networkPanel = getOnEdt(() -> new NetworkPanel(model, playbackController));
        runOnEdt(() -> networkPanel.setPreferredSize(new Dimension(1200, 800)));
        runOnEdt(() -> networkPanel.setSampleSize(startupSampleSize));

        SwingNode swingNode = new SwingNode();
        runOnEdt(() -> swingNode.setContent(networkPanel));

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");

        TopBarBundle topBar = buildTopBar(stage, model, playbackController, networkPanel);
        NodeBundle sidePanel = buildSidePanel(stage, model, networkPanel);

        root.setTop(topBar.root());
        root.setCenter(swingNode);
        root.setRight(sidePanel.root());

        Scene scene = new Scene(root, 1540, 980);
        scene.getStylesheets().add(Objects.requireNonNull(
                FxVisualizerApp.class.getResource("/com/matsim/viz/ui/fx/theme.css"),
                "JavaFX stylesheet not found"
        ).toExternalForm());

        stage.setTitle("MATSim Visualizer - JavaFX");
        stage.setScene(scene);
        stage.setMinWidth(1180);
        stage.setMinHeight(760);
        stage.show();

        AnimationTimer animation = createAnimationTimer(playbackController, networkPanel, topBar.uiState());
        animation.start();

        stage.setOnCloseRequest(event -> {
            animation.stop();
            Platform.exit();
        });
    }

    private TopBarBundle buildTopBar(Stage owner, SimulationModel model, PlaybackController playbackController, NetworkPanel networkPanel) {
        VBox wrapper = new VBox();
        wrapper.getStyleClass().add("top-wrap");

        HBox controls = new HBox(12);
        controls.getStyleClass().add("toolbar");
        controls.setPadding(new Insets(10, 14, 10, 14));
        controls.setAlignment(Pos.CENTER_LEFT);

        Button playPauseButton = new Button("Play");
        playPauseButton.getStyleClass().add("accent-button");
        playPauseButton.setOnAction(e -> playbackController.togglePlaying());

        Label timeCaption = new Label("Time");
        timeCaption.getStyleClass().add("field-caption");

        Slider timeSlider = new Slider(playbackController.getStartTime(), playbackController.getEndTime(), playbackController.getCurrentTime());
        timeSlider.setPrefWidth(460);
        timeSlider.setBlockIncrement(1.0);

        Label timeValue = new Label(TimeFormat.hhmmss(playbackController.getCurrentTime()));
        timeValue.getStyleClass().add("mono-value");

        Label speedCaption = new Label("Speed");
        speedCaption.getStyleClass().add("field-caption");

        Slider speedSlider = new Slider(1, 600, playbackController.getSpeedMultiplier());
        speedSlider.setPrefWidth(220);
        speedSlider.valueProperty().addListener((obs, oldValue, newValue) ->
                playbackController.setSpeedMultiplier(newValue.doubleValue()));

        Label speedValue = new Label(formatSpeed(playbackController.getSpeedMultiplier()));
        speedValue.getStyleClass().add("mono-value");
        speedSlider.valueProperty().addListener((obs, oldValue, newValue) -> speedValue.setText(formatSpeed(newValue.doubleValue())));

        Label colorCaption = new Label("Color");
        colorCaption.getStyleClass().add("field-caption");

        ComboBox<ColorMode> colorModeCombo = new ComboBox<>(FXCollections.observableArrayList(ColorMode.values()));
        colorModeCombo.setValue(ColorMode.DEFAULT);
        colorModeCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                runOnEdt(() -> networkPanel.setColorMode(newValue));
            }
        });

        Button quitButton = new Button("Quit");
        quitButton.getStyleClass().add("danger-button");
        quitButton.setOnAction(e -> Platform.exit());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        controls.getChildren().addAll(
                playPauseButton,
                timeCaption,
                timeSlider,
                timeValue,
                speedCaption,
                speedSlider,
                speedValue,
                colorCaption,
                colorModeCombo,
                spacer,
                quitButton
        );

        final boolean[] syncing = {false};
        timeSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (syncing[0]) {
                return;
            }
            playbackController.seek(newValue.doubleValue());
        });

        PlaybackUiState uiState = new PlaybackUiState(playPauseButton, timeSlider, timeValue, syncing);
        wrapper.getChildren().add(controls);
        return new TopBarBundle(wrapper, uiState);
    }

    private NodeBundle buildSidePanel(Stage owner, SimulationModel model, NetworkPanel networkPanel) {
        VBox content = new VBox(12);
        content.getStyleClass().add("side-content");
        content.setPadding(new Insets(12));

        content.getChildren().add(buildModeSelectionCard(
                "Network Modes",
                model.availableLinkModes(),
            defaultTransportModes(model.availableLinkModes()),
                selected -> runOnEdt(() -> networkPanel.setSelectedLinkModes(selected))
        ));

        content.getChildren().add(buildModeSelectionCard(
                "Trip Modes",
                model.availableTripModes(),
            defaultTransportModes(model.availableTripModes()),
                selected -> runOnEdt(() -> networkPanel.setSelectedTripModes(selected))
        ));

        content.getChildren().add(buildDisplaySettingsCard(owner, model, networkPanel));
        content.getChildren().add(buildBottleneckCard(networkPanel));

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.getStyleClass().add("side-scroll");
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportWidth(320);
        scrollPane.setMinWidth(300);

        return new NodeBundle(scrollPane);
    }

    private VBox buildDisplaySettingsCard(Stage owner, SimulationModel model, NetworkPanel networkPanel) {
        VBox card = createCard("Display Settings");

        CheckBox queueToggle = new CheckBox("Show Link Queues");
        queueToggle.setSelected(true);
        queueToggle.setOnAction(e -> runOnEdt(() -> networkPanel.setShowQueues(queueToggle.isSelected())));

        final Stage[] colorSettingsWindow = {null};
        Button colorSettingsButton = new Button("Color Settings\u2026");
        colorSettingsButton.getStyleClass().add("ghost-button");
        colorSettingsButton.setMaxWidth(Double.MAX_VALUE);
        colorSettingsButton.setOnAction(e -> {
            if (colorSettingsWindow[0] == null) {
                colorSettingsWindow[0] = createSettingsWindow(
                        owner, "Color Settings",
                        buildColorSettingsContent(model, networkPanel), 520, 760);
                colorSettingsWindow[0].setOnHidden(event -> colorSettingsWindow[0] = null);
            }
            colorSettingsWindow[0].show();
            colorSettingsWindow[0].toFront();
        });

        final Stage[] geometrySettingsWindow = {null};
        Button geometrySettingsButton = new Button("Vehicle Geometry\u2026");
        geometrySettingsButton.getStyleClass().add("ghost-button");
        geometrySettingsButton.setMaxWidth(Double.MAX_VALUE);
        geometrySettingsButton.setOnAction(e -> {
            if (geometrySettingsWindow[0] == null) {
                geometrySettingsWindow[0] = createSettingsWindow(
                        owner, "Vehicle Geometry",
                        buildGeometrySettingsContent(networkPanel), 460, 860);
                geometrySettingsWindow[0].setOnHidden(event -> geometrySettingsWindow[0] = null);
            }
            geometrySettingsWindow[0].show();
            geometrySettingsWindow[0].toFront();
        });

        card.getChildren().addAll(queueToggle, colorSettingsButton, geometrySettingsButton);
        return card;
    }

    private VBox buildBottleneckCard(NetworkPanel networkPanel) {
        VBox card = createCard("Bottleneck Detection");

        CheckBox bottleneckToggle = new CheckBox("Show Bottlenecks");
        bottleneckToggle.setSelected(false);
        bottleneckToggle.setOnAction(e -> runOnEdt(() ->
                networkPanel.setShowBottleneck(bottleneckToggle.isSelected())
        ));

        Label divisorCaption = new Label("Jam spacing (m per vehicle)");
        divisorCaption.getStyleClass().add("field-caption");

        Slider divisorSlider = new Slider(1, 20, getOnEdt(networkPanel::getBottleneckDivisor));
        Label divisorValue = new Label(String.format("%.1f", divisorSlider.getValue()));
        divisorValue.getStyleClass().add("mono-value");
        divisorSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            runOnEdt(() -> networkPanel.setBottleneckDivisor(newVal.doubleValue()));
            divisorValue.setText(String.format("%.1f", newVal.doubleValue()));
        });

        double sampleSz = getOnEdt(networkPanel::getSampleSize);
        Label sampleSizeLabel = new Label(String.format("Sample size (from config): %.4f", sampleSz));
        sampleSizeLabel.getStyleClass().add("hint");

        Label formulaLabel = new Label("Bottleneck when vehicles > sampleSize \u00D7 lanes \u00D7 length / divisor");
        formulaLabel.getStyleClass().add("hint");
        formulaLabel.setWrapText(true);

        card.getChildren().addAll(
                bottleneckToggle,
                divisorCaption, divisorSlider, divisorValue,
                sampleSizeLabel, formulaLabel
        );
        return card;
    }

    private Node buildColorSettingsContent(SimulationModel model, NetworkPanel networkPanel) {
        VBox content = new VBox(12);
        content.getStyleClass().add("side-content");
        content.setPadding(new Insets(12));
        content.getChildren().add(buildModeColorCard(model.availableTripModes(), networkPanel));
        content.getChildren().add(buildPurposeCard(model.availableTripPurposes(), networkPanel));
        content.getChildren().add(buildAgeCard(networkPanel));
        content.getChildren().add(buildSexCard(networkPanel));

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.getStyleClass().add("side-scroll");
        scrollPane.setFitToWidth(true);
        return scrollPane;
    }

    private Node buildGeometrySettingsContent(NetworkPanel networkPanel) {
        VBox content = new VBox(12);
        content.getStyleClass().add("side-content");
        content.setPadding(new Insets(12));
        content.getChildren().add(buildVehicleCard(networkPanel));

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.getStyleClass().add("side-scroll");
        scrollPane.setFitToWidth(true);
        return scrollPane;
    }

    private Stage createSettingsWindow(Stage owner, String title, Node content, double width, double height) {
        Stage window = new Stage();
        window.initOwner(owner);
        window.setTitle(title);
        Scene scene = new Scene(new BorderPane(content), width, height);
        scene.getStylesheets().add(Objects.requireNonNull(
                FxVisualizerApp.class.getResource("/com/matsim/viz/ui/fx/theme.css"),
                "JavaFX stylesheet not found"
        ).toExternalForm());
        window.setScene(scene);
        return window;
    }

    private TitledPane buildModeSelectionCard(
            String title,
            List<String> modes,
            Set<String> defaultSelected,
            Consumer<Set<String>> onSelectionChanged
    ) {
        VBox body = new VBox(4);
        body.getStyleClass().add("rows");
        body.setPadding(new Insets(6));

        HBox header = new HBox(8);
        Button allButton = new Button("All");
        Button noneButton = new Button("None");
        allButton.getStyleClass().add("ghost-button");
        noneButton.getStyleClass().add("ghost-button");
        header.getChildren().addAll(allButton, noneButton);

        VBox checks = new VBox(4);
        checks.getStyleClass().add("rows");

        Map<String, CheckBox> boxes = new LinkedHashMap<>();
        for (String mode : modes) {
            CheckBox box = new CheckBox(mode);
            box.setSelected(defaultSelected.contains(normalizeMode(mode)));
            boxes.put(mode, box);
            checks.getChildren().add(box);
            box.setOnAction(e -> publishSelections(boxes, onSelectionChanged));
        }

        allButton.setOnAction(e -> {
            boxes.values().forEach(box -> box.setSelected(true));
            publishSelections(boxes, onSelectionChanged);
        });
        noneButton.setOnAction(e -> {
            boxes.values().forEach(box -> box.setSelected(false));
            publishSelections(boxes, onSelectionChanged);
        });

        publishSelections(boxes, onSelectionChanged);

        body.getChildren().addAll(header, checks);

        TitledPane titledPane = new TitledPane(title, body);
        titledPane.setExpanded(false);
        titledPane.setAnimated(true);
        titledPane.getStyleClass().add("card");
        return titledPane;
    }

    private VBox buildModeColorCard(List<String> modes, NetworkPanel networkPanel) {
        VBox card = createCard("Trip Mode Colors");
        VBox rows = new VBox(6);
        rows.getStyleClass().add("rows");

        for (String mode : modes) {
            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            Label label = new Label(mode);
            label.setMinWidth(190);

            ColorPicker picker = new ColorPicker(toFx(getOnEdt(() -> networkPanel.getModeColor(mode))));
            picker.setOnAction(e -> runOnEdt(() -> networkPanel.setModeColor(mode, toAwt(picker.getValue()))));

            row.getChildren().addAll(label, picker);
            rows.getChildren().add(row);
        }

        card.getChildren().add(rows);
        return card;
    }

    private VBox buildPurposeCard(List<String> purposes, NetworkPanel networkPanel) {
        VBox card = createCard("Trip Purpose Colors");

        if (purposes.isEmpty()) {
            Label label = new Label("No trip purpose metadata detected.");
            label.getStyleClass().add("hint");
            card.getChildren().add(label);
            return card;
        }

        ComboBox<String> purposeCombo = new ComboBox<>(FXCollections.observableArrayList(purposes));
        purposeCombo.setMaxWidth(Double.MAX_VALUE);
        purposeCombo.getSelectionModel().selectFirst();

        ColorPicker picker = new ColorPicker(toFx(getOnEdt(() ->
                networkPanel.getTripPurposeColor(purposeCombo.getValue())
        )));

        purposeCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                picker.setValue(toFx(getOnEdt(() -> networkPanel.getTripPurposeColor(newValue))));
            }
        });

        picker.setOnAction(e -> {
            String purpose = purposeCombo.getValue();
            if (purpose != null) {
                runOnEdt(() -> networkPanel.setTripPurposeColor(purpose, toAwt(picker.getValue())));
            }
        });

        card.getChildren().addAll(purposeCombo, picker);
        return card;
    }

    private VBox buildSexCard(NetworkPanel networkPanel) {
        VBox card = createCard("Sex Colors");

        List<String> categories = new ArrayList<>(getOnEdt(networkPanel::getSexCategories));
        if (categories.isEmpty()) {
            categories = Arrays.asList("male", "female", "other", "unknown");
        }

        ComboBox<String> categoryCombo = new ComboBox<>(FXCollections.observableArrayList(categories));
        categoryCombo.setMaxWidth(Double.MAX_VALUE);
        categoryCombo.getSelectionModel().selectFirst();

        ColorPicker picker = new ColorPicker(toFx(getOnEdt(() -> networkPanel.getSexColor(categoryCombo.getValue()))));

        categoryCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                picker.setValue(toFx(getOnEdt(() -> networkPanel.getSexColor(newValue))));
            }
        });

        picker.setOnAction(e -> {
            String category = categoryCombo.getValue();
            if (category != null) {
                runOnEdt(() -> networkPanel.setSexColor(category, toAwt(picker.getValue())));
            }
        });

        card.getChildren().addAll(categoryCombo, picker);
        return card;
    }

    private VBox buildAgeCard(NetworkPanel networkPanel) {
        VBox card = createCard("Age Groups");

        TextField boundsField = new TextField(ageBoundsToText(getOnEdt(networkPanel::getAgeBinUpperBounds)));
        boundsField.setPromptText("e.g. 17,35,59");

        Button applyButton = new Button("Apply Bins");
        applyButton.getStyleClass().add("ghost-button");

        HBox top = new HBox(8, boundsField, applyButton);
        top.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(boundsField, Priority.ALWAYS);

        VBox rows = new VBox(6);
        rows.getStyleClass().add("rows");

        Runnable refreshRows = () -> {
            rows.getChildren().clear();
            int count = getOnEdt(networkPanel::getAgeGroupCount);
            for (int i = 0; i < count; i++) {
                int idx = i;
                HBox row = new HBox(10);
                row.setAlignment(Pos.CENTER_LEFT);

                Label label = new Label(getOnEdt(() -> networkPanel.getAgeGroupLabel(idx)));
                label.setMinWidth(170);

                ColorPicker picker = new ColorPicker(toFx(getOnEdt(() -> networkPanel.getAgeGroupColor(idx))));
                picker.setOnAction(e -> runOnEdt(() -> networkPanel.setAgeGroupColor(idx, toAwt(picker.getValue()))));

                row.getChildren().addAll(label, picker);
                rows.getChildren().add(row);
            }
        };

        applyButton.setOnAction(e -> {
            try {
                int[] bounds = parseAgeBounds(boundsField.getText());
                runOnEdt(() -> networkPanel.setAgeBinUpperBounds(bounds));
                boundsField.setText(ageBoundsToText(getOnEdt(networkPanel::getAgeBinUpperBounds)));
                refreshRows.run();
            } catch (IllegalArgumentException ex) {
                Alert alert = new Alert(Alert.AlertType.WARNING, ex.getMessage());
                alert.setHeaderText("Invalid age bins");
                alert.showAndWait();
            }
        });

        refreshRows.run();
        card.getChildren().addAll(top, rows);
        return card;
    }

    private VBox buildVehicleCard(NetworkPanel networkPanel) {
        VBox card = createCard("Vehicle Geometry");

        Label carLabel = new Label("Car length (m)");
        Slider carSlider = new Slider(2, 20, getOnEdt(networkPanel::getCarLikeVehicleLengthMeters));
        Label carValue = new Label(String.format("%.1f", carSlider.getValue()));
        carValue.getStyleClass().add("mono-value");
        carSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            runOnEdt(() -> networkPanel.setCarLikeVehicleLengthMeters(newValue.doubleValue()));
            carValue.setText(String.format("%.1f", newValue.doubleValue()));
        });

        Label carWidthLabel = new Label("Car width ratio");
        Slider carWidthSlider = new Slider(0.10, 2.00, getOnEdt(networkPanel::getCarLikeVehicleWidthRatio));
        Label carWidthValue = new Label(String.format("%.2f", carWidthSlider.getValue()));
        carWidthValue.getStyleClass().add("mono-value");
        carWidthSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            runOnEdt(() -> networkPanel.setCarLikeVehicleWidthRatio(newValue.doubleValue()));
            carWidthValue.setText(String.format("%.2f", newValue.doubleValue()));
        });

        Label bikeLabel = new Label("Bike length (m)");
        Slider bikeSlider = new Slider(1, 10, getOnEdt(networkPanel::getBikeVehicleLengthMeters));
        Label bikeValue = new Label(String.format("%.1f", bikeSlider.getValue()));
        bikeValue.getStyleClass().add("mono-value");
        bikeSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            runOnEdt(() -> networkPanel.setBikeVehicleLengthMeters(newValue.doubleValue()));
            bikeValue.setText(String.format("%.1f", newValue.doubleValue()));
        });

        Label bikeWidthLabel = new Label("Bike width ratio");
        Slider bikeWidthSlider = new Slider(0.10, 2.00, getOnEdt(networkPanel::getBikeVehicleWidthRatio));
        Label bikeWidthValue = new Label(String.format("%.2f", bikeWidthSlider.getValue()));
        bikeWidthValue.getStyleClass().add("mono-value");
        bikeWidthSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            runOnEdt(() -> networkPanel.setBikeVehicleWidthRatio(newValue.doubleValue()));
            bikeWidthValue.setText(String.format("%.2f", newValue.doubleValue()));
        });

        Label truckLabel = new Label("Truck length (m)");
        Slider truckSlider = new Slider(4, 30, getOnEdt(networkPanel::getTruckVehicleLengthMeters));
        Label truckValue = new Label(String.format("%.1f", truckSlider.getValue()));
        truckValue.getStyleClass().add("mono-value");
        truckSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            runOnEdt(() -> networkPanel.setTruckVehicleLengthMeters(newValue.doubleValue()));
            truckValue.setText(String.format("%.1f", newValue.doubleValue()));
        });

        Label truckWidthLabel = new Label("Truck width ratio");
        Slider truckWidthSlider = new Slider(0.10, 2.00, getOnEdt(networkPanel::getTruckVehicleWidthRatio));
        Label truckWidthValue = new Label(String.format("%.2f", truckWidthSlider.getValue()));
        truckWidthValue.getStyleClass().add("mono-value");
        truckWidthSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            runOnEdt(() -> networkPanel.setTruckVehicleWidthRatio(newValue.doubleValue()));
            truckWidthValue.setText(String.format("%.2f", newValue.doubleValue()));
        });

        Label busLabel = new Label("Bus length (m)");
        Slider busSlider = new Slider(4, 25, getOnEdt(networkPanel::getBusVehicleLengthMeters));
        Label busValue = new Label(String.format("%.1f", busSlider.getValue()));
        busValue.getStyleClass().add("mono-value");
        busSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            runOnEdt(() -> networkPanel.setBusVehicleLengthMeters(newValue.doubleValue()));
            busValue.setText(String.format("%.1f", newValue.doubleValue()));
        });

        Label busWidthLabel = new Label("Bus width ratio");
        Slider busWidthSlider = new Slider(0.10, 2.00, getOnEdt(networkPanel::getBusVehicleWidthRatio));
        Label busWidthValue = new Label(String.format("%.2f", busWidthSlider.getValue()));
        busWidthValue.getStyleClass().add("mono-value");
        busWidthSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            runOnEdt(() -> networkPanel.setBusVehicleWidthRatio(newValue.doubleValue()));
            busWidthValue.setText(String.format("%.2f", newValue.doubleValue()));
        });

        Label railLabel = new Label("Rail/Tram length (m)");
        Slider railSlider = new Slider(5, 100, getOnEdt(networkPanel::getRailVehicleLengthMeters));
        Label railValue = new Label(String.format("%.1f", railSlider.getValue()));
        railValue.getStyleClass().add("mono-value");
        railSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            runOnEdt(() -> networkPanel.setRailVehicleLengthMeters(newValue.doubleValue()));
            railValue.setText(String.format("%.1f", newValue.doubleValue()));
        });

        Label railWidthLabel = new Label("Rail/Tram width ratio");
        Slider railWidthSlider = new Slider(0.10, 2.00, getOnEdt(networkPanel::getRailVehicleWidthRatio));
        Label railWidthValue = new Label(String.format("%.2f", railWidthSlider.getValue()));
        railWidthValue.getStyleClass().add("mono-value");
        railWidthSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            runOnEdt(() -> networkPanel.setRailVehicleWidthRatio(newValue.doubleValue()));
            railWidthValue.setText(String.format("%.2f", newValue.doubleValue()));
        });

        CheckBox visibilityBoost = new CheckBox("Keep vehicles noticeable when zoomed out");
        visibilityBoost.setSelected(getOnEdt(networkPanel::isKeepVehiclesVisibleWhenZoomedOut));
        visibilityBoost.setOnAction(e -> runOnEdt(() ->
                networkPanel.setKeepVehiclesVisibleWhenZoomedOut(visibilityBoost.isSelected())
        ));

        Label minLengthPxLabel = new Label("Min visible length (px)");
        Slider minLengthPxSlider = new Slider(0.5, 30.0, getOnEdt(networkPanel::getMinVehicleLengthPixels));
        Label minLengthPxValue = new Label(String.format("%.1f", minLengthPxSlider.getValue()));
        minLengthPxValue.getStyleClass().add("mono-value");
        minLengthPxSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            runOnEdt(() -> networkPanel.setMinVehicleLengthPixels(newValue.doubleValue()));
            minLengthPxValue.setText(String.format("%.1f", newValue.doubleValue()));
        });

        Label minWidthPxLabel = new Label("Min visible width (px)");
        Slider minWidthPxSlider = new Slider(0.5, 30.0, getOnEdt(networkPanel::getMinVehicleWidthPixels));
        Label minWidthPxValue = new Label(String.format("%.1f", minWidthPxSlider.getValue()));
        minWidthPxValue.getStyleClass().add("mono-value");
        minWidthPxSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            runOnEdt(() -> networkPanel.setMinVehicleWidthPixels(newValue.doubleValue()));
            minWidthPxValue.setText(String.format("%.1f", newValue.doubleValue()));
        });

        card.getChildren().addAll(
                carLabel, carSlider, carValue,
                carWidthLabel, carWidthSlider, carWidthValue,
                bikeLabel, bikeSlider, bikeValue,
                bikeWidthLabel, bikeWidthSlider, bikeWidthValue,
                truckLabel, truckSlider, truckValue,
                truckWidthLabel, truckWidthSlider, truckWidthValue,
                busLabel, busSlider, busValue,
                busWidthLabel, busWidthSlider, busWidthValue,
                railLabel, railSlider, railValue,
                railWidthLabel, railWidthSlider, railWidthValue,
                visibilityBoost,
                minLengthPxLabel, minLengthPxSlider, minLengthPxValue,
                minWidthPxLabel, minWidthPxSlider, minWidthPxValue
        );
        return card;
    }

    private AnimationTimer createAnimationTimer(PlaybackController playbackController, NetworkPanel networkPanel, PlaybackUiState uiState) {
        return new AnimationTimer() {
            private long previousNanos = -1L;

            @Override
            public void handle(long now) {
                if (previousNanos < 0) {
                    previousNanos = now;
                    return;
                }

                double deltaSeconds = (now - previousNanos) / 1_000_000_000.0;
                previousNanos = now;
                playbackController.tick(Math.min(0.20, Math.max(0.001, deltaSeconds)));

                uiState.syncing()[0] = true;
                uiState.timeSlider().setValue(playbackController.getCurrentTime());
                uiState.syncing()[0] = false;
                uiState.timeValue().setText(TimeFormat.hhmmss(playbackController.getCurrentTime()));
                uiState.playPauseButton().setText(playbackController.isPlaying() ? "Pause" : "Play");

                runOnEdt(networkPanel::repaint);
            }
        };
    }

    private static VBox createCard(String title) {
        VBox card = new VBox(8);
        card.getStyleClass().add("card");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("card-title");
        card.getChildren().add(titleLabel);
        return card;
    }

    private static void publishSelections(Map<String, CheckBox> boxes, Consumer<Set<String>> onSelectionChanged) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (Map.Entry<String, CheckBox> entry : boxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                selected.add(entry.getKey());
            }
        }
        onSelectionChanged.accept(selected);
    }

    private static String formatSpeed(double speed) {
        return "x" + Math.max(1, (int) Math.round(speed));
    }

    private static Set<String> defaultTransportModes(List<String> availableModes) {
        Set<String> selected = new LinkedHashSet<>();
        Set<String> defaults = Set.of("car", "bike", "bicycle", "truck", "freight", "hdv",
                "bus", "tram", "rail", "train", "subway", "metro", "ferry", "funicular");
        for (String mode : availableModes) {
            String normalized = normalizeMode(mode);
            if (defaults.contains(normalized)) {
                selected.add(normalized);
            }
        }
        if (selected.isEmpty()) {
            for (String mode : availableModes) {
                selected.add(normalizeMode(mode));
            }
        }
        return selected;
    }

    private static String normalizeMode(String mode) {
        return mode == null ? "" : mode.trim().toLowerCase();
    }

    private static String ageBoundsToText(int[] bounds) {
        if (bounds == null || bounds.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < bounds.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(bounds[i]);
        }
        return builder.toString();
    }

    private static int[] parseAgeBounds(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Provide at least one positive age bound.");
        }
        String[] parts = raw.split(",");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isEmpty()) {
                throw new IllegalArgumentException("Age bounds must be comma-separated integers.");
            }
            try {
                values[i] = Integer.parseInt(part);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Age bounds must be comma-separated integers.");
            }
        }
        return values;
    }

    private static Color toFx(java.awt.Color color) {
        return Color.rgb(color.getRed(), color.getGreen(), color.getBlue(), color.getAlpha() / 255.0);
    }

    private static java.awt.Color toAwt(Color color) {
        return new java.awt.Color(
                (float) color.getRed(),
                (float) color.getGreen(),
                (float) color.getBlue(),
                (float) color.getOpacity()
        );
    }

    private static void runOnEdt(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to execute Swing task", ex);
        }
    }

    private static <T> T getOnEdt(Supplier<T> supplier) {
        if (SwingUtilities.isEventDispatchThread()) {
            return supplier.get();
        }

        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    ref.set(supplier.get());
                } catch (Throwable t) {
                    error.set(t);
                }
            });
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to execute Swing query", ex);
        }

        if (error.get() != null) {
            throw new IllegalStateException("Swing query failed", error.get());
        }
        return ref.get();
    }

    private record NodeBundle(ScrollPane root) {
    }

    private record TopBarBundle(VBox root, PlaybackUiState uiState) {
    }

    private record PlaybackUiState(Button playPauseButton, Slider timeSlider, Label timeValue, boolean[] syncing) {
    }
}
