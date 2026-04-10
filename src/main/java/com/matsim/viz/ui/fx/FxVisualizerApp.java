package com.matsim.viz.ui.fx;

import com.matsim.viz.config.AppConfig;
import com.matsim.viz.domain.ColorMode;
import com.matsim.viz.domain.VehicleShape;
import com.matsim.viz.engine.PlaybackController;
import com.matsim.viz.engine.SimulationModel;
import com.matsim.viz.ui.NetworkPanel;
import com.matsim.viz.ui.PanelVideoRecorder;
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
import java.io.IOException;
import java.nio.file.Path;
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
    private static Path startupCacheDir;
    private static AppConfig startupAppConfig;

    private PanelVideoRecorder videoRecorder;
    private Scene mainScene;

    private static final String DARK_CSS = Objects.requireNonNull(
            FxVisualizerApp.class.getResource("/com/matsim/viz/ui/fx/theme.css"),
            "Dark theme CSS not found"
    ).toExternalForm();

    private static final String LIGHT_CSS = Objects.requireNonNull(
            FxVisualizerApp.class.getResource("/com/matsim/viz/ui/fx/theme-light.css"),
            "Light theme CSS not found"
    ).toExternalForm();

    public static void launchVisualizer(
            SimulationModel model,
            PlaybackController playbackController,
            double sampleSize,
            Path cacheDir,
            AppConfig appConfig
    ) {
        startupModel = Objects.requireNonNull(model, "model");
        startupPlaybackController = Objects.requireNonNull(playbackController, "playbackController");
        startupSampleSize = sampleSize;
        startupCacheDir = cacheDir;
        startupAppConfig = appConfig;
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
        applyStartupDefaults(networkPanel);

        Path recordDir = startupCacheDir != null ? startupCacheDir : Path.of("cache");
        videoRecorder = new PanelVideoRecorder(recordDir);

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
        scene.getStylesheets().add(DARK_CSS);
        this.mainScene = scene;

        stage.setTitle("MATSim Visualizer - JavaFX");
        stage.setScene(scene);
        stage.setMinWidth(1180);
        stage.setMinHeight(760);
        stage.show();

        AnimationTimer animation = createAnimationTimer(playbackController, networkPanel, topBar.uiState());
        animation.start();

        stage.setOnCloseRequest(event -> {
            animation.stop();
            if (videoRecorder.isRecording()) {
                try { videoRecorder.stop(); } catch (IOException ignored) { }
            }
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
        colorModeCombo.setValue(parseColorModeDefault());
        colorModeCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                runOnEdt(() -> networkPanel.setColorMode(newValue));
            }
        });

        Button quitButton = new Button("Quit");
        quitButton.getStyleClass().add("danger-button");
        quitButton.setOnAction(e -> Platform.exit());

        ComboBox<PanelVideoRecorder.Quality> qualityCombo = new ComboBox<>(
                FXCollections.observableArrayList(PanelVideoRecorder.Quality.values()));
        qualityCombo.setValue(parseRecordingQualityDefault());
        qualityCombo.setPrefWidth(230);

        Button recordButton = new Button("\u23FA Record");
        recordButton.getStyleClass().add("accent-button");
        recordButton.setOnAction(e -> {
            if (videoRecorder.isRecording()) {
                recordButton.setDisable(true);
                recordButton.setText("Encoding...");
                recordButton.getStyleClass().remove("recording-button");
                qualityCombo.setDisable(true);

                int queuedFrames = videoRecorder.queuedFrameCount();
                videoRecorder.stopAsync().whenComplete((saved, ex) -> Platform.runLater(() -> {
                    recordButton.setDisable(false);
                    recordButton.setText("\u23FA Record");
                    qualityCombo.setDisable(false);

                    if (ex != null) {
                        Alert err = new Alert(Alert.AlertType.ERROR, ex.getMessage());
                        err.setHeaderText("Failed to encode recording");
                        err.showAndWait();
                        return;
                    }

                    if (saved != null) {
                        Alert info = new Alert(Alert.AlertType.INFORMATION,
                                "Frames captured: " + queuedFrames + "\n\n"
                                        + "Video saved to:\n" + saved.toAbsolutePath());
                        info.setHeaderText("Recording Saved");
                        info.showAndWait();
                    }
                }));
            } else {
                if (videoRecorder.isEncoding()) {
                    Alert info = new Alert(Alert.AlertType.INFORMATION,
                            "Encoding is still in progress. Please wait until it finishes.");
                    info.setHeaderText("Recorder Busy");
                    info.showAndWait();
                    return;
                }
                try {
                    videoRecorder.start(qualityCombo.getValue());
                    recordButton.setText("\u23F9 Stop");
                    recordButton.getStyleClass().add("recording-button");
                    qualityCombo.setDisable(true);
                } catch (IOException ex) {
                    Alert err = new Alert(Alert.AlertType.ERROR, ex.getMessage());
                    err.setHeaderText("Failed to start recording");
                    err.showAndWait();
                }
            }
        });

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
                qualityCombo,
                recordButton,
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
        content.getChildren().add(buildAppearanceCard(owner, networkPanel));
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
        queueToggle.setSelected(false);
        queueToggle.setOnAction(e -> runOnEdt(() -> networkPanel.setShowQueues(queueToggle.isSelected())));

        Label offsetCaption = new Label("Bidirectional link offset");
        offsetCaption.getStyleClass().add("field-caption");
        Slider offsetSlider = new Slider(0.0, 1.0, getOnEdt(networkPanel::getBidirectionalOffset));
        Label offsetValue = new Label(String.format("%.2f", offsetSlider.getValue()));
        offsetValue.getStyleClass().add("mono-value");
        offsetSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            runOnEdt(() -> networkPanel.setBidirectionalOffset(newValue.doubleValue()));
            offsetValue.setText(String.format("%.2f", newValue.doubleValue()));
        });

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

        card.getChildren().addAll(queueToggle, offsetCaption, offsetSlider, offsetValue, colorSettingsButton);
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

        VBox paramArea = new VBox(8);
        paramArea.getStyleClass().add("card");

        ComboBox<String> vehicleSelector = new ComboBox<>(FXCollections.observableArrayList(
                "Car", "Bike", "Truck", "Bus", "Rail / Tram"));
        vehicleSelector.setValue("Car");
        vehicleSelector.setMaxWidth(Double.MAX_VALUE);

        Runnable[] refreshParams = {null};
        refreshParams[0] = () -> {
            paramArea.getChildren().clear();
            String selected = vehicleSelector.getValue();
            if (selected == null) return;

            Label title = new Label(selected + " Parameters");
            title.getStyleClass().add("card-title");
            paramArea.getChildren().add(title);

            // Shape
            Label shapeLabel = new Label("Shape");
            shapeLabel.getStyleClass().add("field-caption");
            ComboBox<VehicleShape> shapeCombo = new ComboBox<>(FXCollections.observableArrayList(VehicleShape.values()));
            shapeCombo.setMaxWidth(Double.MAX_VALUE);

            // Length
            Label lengthLabel = new Label("Length (m)");
            lengthLabel.getStyleClass().add("field-caption");
            Slider lengthSlider;
            Label lengthValue;

            // Width ratio
            Label widthLabel = new Label("Width ratio");
            widthLabel.getStyleClass().add("field-caption");
            Slider widthSlider;
            Label widthValue;

            switch (selected) {
                case "Car" -> {
                    shapeCombo.setValue(getOnEdt(networkPanel::getCarShape));
                    shapeCombo.valueProperty().addListener((o, ov, nv) -> { if (nv != null) runOnEdt(() -> networkPanel.setCarShape(nv)); });
                    lengthSlider = new Slider(2, 20, getOnEdt(networkPanel::getCarLikeVehicleLengthMeters));
                    lengthSlider.valueProperty().addListener((o, ov, nv) -> runOnEdt(() -> networkPanel.setCarLikeVehicleLengthMeters(nv.doubleValue())));
                    widthSlider = new Slider(0.10, 2.00, getOnEdt(networkPanel::getCarLikeVehicleWidthRatio));
                    widthSlider.valueProperty().addListener((o, ov, nv) -> runOnEdt(() -> networkPanel.setCarLikeVehicleWidthRatio(nv.doubleValue())));
                }
                case "Bike" -> {
                    shapeCombo.setValue(getOnEdt(networkPanel::getBikeShape));
                    shapeCombo.valueProperty().addListener((o, ov, nv) -> { if (nv != null) runOnEdt(() -> networkPanel.setBikeShape(nv)); });
                    lengthSlider = new Slider(1, 10, getOnEdt(networkPanel::getBikeVehicleLengthMeters));
                    lengthSlider.valueProperty().addListener((o, ov, nv) -> runOnEdt(() -> networkPanel.setBikeVehicleLengthMeters(nv.doubleValue())));
                    widthSlider = new Slider(0.10, 2.00, getOnEdt(networkPanel::getBikeVehicleWidthRatio));
                    widthSlider.valueProperty().addListener((o, ov, nv) -> runOnEdt(() -> networkPanel.setBikeVehicleWidthRatio(nv.doubleValue())));
                }
                case "Truck" -> {
                    shapeCombo.setValue(getOnEdt(networkPanel::getTruckShape));
                    shapeCombo.valueProperty().addListener((o, ov, nv) -> { if (nv != null) runOnEdt(() -> networkPanel.setTruckShape(nv)); });
                    lengthSlider = new Slider(4, 30, getOnEdt(networkPanel::getTruckVehicleLengthMeters));
                    lengthSlider.valueProperty().addListener((o, ov, nv) -> runOnEdt(() -> networkPanel.setTruckVehicleLengthMeters(nv.doubleValue())));
                    widthSlider = new Slider(0.10, 2.00, getOnEdt(networkPanel::getTruckVehicleWidthRatio));
                    widthSlider.valueProperty().addListener((o, ov, nv) -> runOnEdt(() -> networkPanel.setTruckVehicleWidthRatio(nv.doubleValue())));
                }
                case "Bus" -> {
                    shapeCombo.setValue(getOnEdt(networkPanel::getBusShape));
                    shapeCombo.valueProperty().addListener((o, ov, nv) -> { if (nv != null) runOnEdt(() -> networkPanel.setBusShape(nv)); });
                    lengthSlider = new Slider(4, 25, getOnEdt(networkPanel::getBusVehicleLengthMeters));
                    lengthSlider.valueProperty().addListener((o, ov, nv) -> runOnEdt(() -> networkPanel.setBusVehicleLengthMeters(nv.doubleValue())));
                    widthSlider = new Slider(0.10, 2.00, getOnEdt(networkPanel::getBusVehicleWidthRatio));
                    widthSlider.valueProperty().addListener((o, ov, nv) -> runOnEdt(() -> networkPanel.setBusVehicleWidthRatio(nv.doubleValue())));
                }
                default -> { // Rail / Tram
                    shapeCombo.setValue(getOnEdt(networkPanel::getRailShape));
                    shapeCombo.valueProperty().addListener((o, ov, nv) -> { if (nv != null) runOnEdt(() -> networkPanel.setRailShape(nv)); });
                    lengthSlider = new Slider(5, 100, getOnEdt(networkPanel::getRailVehicleLengthMeters));
                    lengthSlider.valueProperty().addListener((o, ov, nv) -> runOnEdt(() -> networkPanel.setRailVehicleLengthMeters(nv.doubleValue())));
                    widthSlider = new Slider(0.10, 2.00, getOnEdt(networkPanel::getRailVehicleWidthRatio));
                    widthSlider.valueProperty().addListener((o, ov, nv) -> runOnEdt(() -> networkPanel.setRailVehicleWidthRatio(nv.doubleValue())));
                }
            }

            lengthValue = new Label(String.format("%.1f", lengthSlider.getValue()));
            lengthValue.getStyleClass().add("mono-value");
            lengthSlider.valueProperty().addListener((o, ov, nv) -> lengthValue.setText(String.format("%.1f", nv.doubleValue())));

            widthValue = new Label(String.format("%.2f", widthSlider.getValue()));
            widthValue.getStyleClass().add("mono-value");
            widthSlider.valueProperty().addListener((o, ov, nv) -> widthValue.setText(String.format("%.2f", nv.doubleValue())));

            paramArea.getChildren().addAll(
                    shapeLabel, shapeCombo,
                    lengthLabel, lengthSlider, lengthValue,
                    widthLabel, widthSlider, widthValue
            );
        };

        vehicleSelector.valueProperty().addListener((o, ov, nv) -> refreshParams[0].run());
        refreshParams[0].run();

        // Global visibility settings
        VBox globalCard = createCard("Visibility");

        CheckBox visibilityBoost = new CheckBox("Keep vehicles noticeable when zoomed out");
        visibilityBoost.setSelected(getOnEdt(networkPanel::isKeepVehiclesVisibleWhenZoomedOut));
        visibilityBoost.setOnAction(e -> runOnEdt(() ->
                networkPanel.setKeepVehiclesVisibleWhenZoomedOut(visibilityBoost.isSelected())
        ));

        Label minLengthPxLabel = new Label("Min visible length (px)");
        minLengthPxLabel.getStyleClass().add("field-caption");
        Slider minLengthPxSlider = new Slider(0.5, 30.0, getOnEdt(networkPanel::getMinVehicleLengthPixels));
        Label minLengthPxValue = new Label(String.format("%.1f", minLengthPxSlider.getValue()));
        minLengthPxValue.getStyleClass().add("mono-value");
        minLengthPxSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            runOnEdt(() -> networkPanel.setMinVehicleLengthPixels(newValue.doubleValue()));
            minLengthPxValue.setText(String.format("%.1f", newValue.doubleValue()));
        });

        Label minWidthPxLabel = new Label("Min visible width (px)");
        minWidthPxLabel.getStyleClass().add("field-caption");
        Slider minWidthPxSlider = new Slider(0.5, 30.0, getOnEdt(networkPanel::getMinVehicleWidthPixels));
        Label minWidthPxValue = new Label(String.format("%.1f", minWidthPxSlider.getValue()));
        minWidthPxValue.getStyleClass().add("mono-value");
        minWidthPxSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            runOnEdt(() -> networkPanel.setMinVehicleWidthPixels(newValue.doubleValue()));
            minWidthPxValue.setText(String.format("%.1f", newValue.doubleValue()));
        });

        globalCard.getChildren().addAll(
                visibilityBoost,
                minLengthPxLabel, minLengthPxSlider, minLengthPxValue,
                minWidthPxLabel, minWidthPxSlider, minWidthPxValue
        );

        VBox wrapper = new VBox(12);
        wrapper.getStyleClass().add("side-content");
        wrapper.setPadding(new Insets(12));

        VBox selectorCard = createCard("Select Vehicle Type");
        selectorCard.getChildren().add(vehicleSelector);

        wrapper.getChildren().addAll(selectorCard, paramArea, globalCard);

        ScrollPane scrollPane = new ScrollPane(wrapper);
        scrollPane.getStyleClass().add("side-scroll");
        scrollPane.setFitToWidth(true);
        return scrollPane;
    }

    private VBox buildAppearanceCard(Stage owner, NetworkPanel networkPanel) {
        VBox card = createCard("Appearance");

        // Theme toggle
        CheckBox themeToggle = new CheckBox("Light Theme");
        themeToggle.setSelected(!getOnEdt(networkPanel::isDarkTheme));
        themeToggle.setOnAction(e -> {
            boolean light = themeToggle.isSelected();
            runOnEdt(() -> networkPanel.setDarkTheme(!light));
            if (mainScene != null) {
                mainScene.getStylesheets().clear();
                mainScene.getStylesheets().add(light ? LIGHT_CSS : DARK_CSS);
            }
        });

        // Road color
        Label roadLabel = new Label("Road color");
        roadLabel.getStyleClass().add("field-caption");
        ColorPicker roadPicker = new ColorPicker(toFx(getOnEdt(networkPanel::getMapRoad)));
        roadPicker.setMaxWidth(Double.MAX_VALUE);
        roadPicker.setOnAction(e -> runOnEdt(() -> networkPanel.setMapRoad(toAwt(roadPicker.getValue()))));

        // Background color
        Label bgLabel = new Label("Background color");
        bgLabel.getStyleClass().add("field-caption");
        ColorPicker bgPicker = new ColorPicker(toFx(getOnEdt(networkPanel::getMapBackground)));
        bgPicker.setMaxWidth(Double.MAX_VALUE);
        bgPicker.setOnAction(e -> runOnEdt(() -> networkPanel.setMapBackground(toAwt(bgPicker.getValue()))));

        // Update pickers when theme toggles
        themeToggle.setOnAction(e -> {
            boolean light = themeToggle.isSelected();
            runOnEdt(() -> networkPanel.setDarkTheme(!light));
            if (mainScene != null) {
                mainScene.getStylesheets().clear();
                mainScene.getStylesheets().add(light ? LIGHT_CSS : DARK_CSS);
            }
            roadPicker.setValue(toFx(getOnEdt(networkPanel::getMapRoad)));
            bgPicker.setValue(toFx(getOnEdt(networkPanel::getMapBackground)));
        });

        // Vehicle Settings button
        final Stage[] vehicleSettingsWindow = {null};
        Button vehicleSettingsButton = new Button("Vehicle Settings\u2026");
        vehicleSettingsButton.getStyleClass().add("ghost-button");
        vehicleSettingsButton.setMaxWidth(Double.MAX_VALUE);
        vehicleSettingsButton.setOnAction(e -> {
            if (vehicleSettingsWindow[0] == null) {
                vehicleSettingsWindow[0] = createSettingsWindow(
                        owner, "Vehicle Settings",
                        buildGeometrySettingsContent(networkPanel), 460, 700);
                vehicleSettingsWindow[0].setOnHidden(event -> vehicleSettingsWindow[0] = null);
            }
            vehicleSettingsWindow[0].show();
            vehicleSettingsWindow[0].toFront();
        });

        card.getChildren().addAll(themeToggle, roadLabel, roadPicker, bgLabel, bgPicker, vehicleSettingsButton);
        return card;
    }

    private Stage createSettingsWindow(Stage owner, String title, Node content, double width, double height) {
        Stage window = new Stage();
        window.initOwner(owner);
        window.setTitle(title);
        Scene scene = new Scene(new BorderPane(content), width, height);
        String css = (mainScene != null && mainScene.getStylesheets().contains(LIGHT_CSS)) ? LIGHT_CSS : DARK_CSS;
        scene.getStylesheets().add(css);
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

    private void applyStartupDefaults(NetworkPanel networkPanel) {
        AppConfig config = startupAppConfig;
        if (config == null) {
            return;
        }

        runOnEdt(() -> {
            networkPanel.setDarkTheme(config.uiDarkTheme());
            networkPanel.setColorMode(parseColorModeDefault());
            networkPanel.setShowQueues(config.uiShowQueues());
            networkPanel.setBidirectionalOffset(config.uiBidirectionalOffset());
            networkPanel.setShowBottleneck(config.uiShowBottleneck());
            networkPanel.setBottleneckDivisor(config.uiBottleneckDivisor());
            networkPanel.setKeepVehiclesVisibleWhenZoomedOut(config.uiKeepVehiclesVisibleWhenZoomedOut());
            networkPanel.setMinVehicleLengthPixels(config.uiMinVehicleLengthPixels());
            networkPanel.setMinVehicleWidthPixels(config.uiMinVehicleWidthPixels());

            networkPanel.setCarLikeVehicleLengthMeters(config.uiVehicleLengthCarMeters());
            networkPanel.setBikeVehicleLengthMeters(config.uiVehicleLengthBikeMeters());
            networkPanel.setTruckVehicleLengthMeters(config.uiVehicleLengthTruckMeters());
            networkPanel.setBusVehicleLengthMeters(config.uiVehicleLengthBusMeters());
            networkPanel.setRailVehicleLengthMeters(config.uiVehicleLengthRailMeters());

            networkPanel.setCarLikeVehicleWidthRatio(config.uiVehicleWidthRatioCar());
            networkPanel.setBikeVehicleWidthRatio(config.uiVehicleWidthRatioBike());
            networkPanel.setTruckVehicleWidthRatio(config.uiVehicleWidthRatioTruck());
            networkPanel.setBusVehicleWidthRatio(config.uiVehicleWidthRatioBus());
            networkPanel.setRailVehicleWidthRatio(config.uiVehicleWidthRatioRail());

            networkPanel.setCarShape(parseVehicleShape(config.uiVehicleShapeCar(), VehicleShape.RECTANGLE));
            networkPanel.setBikeShape(parseVehicleShape(config.uiVehicleShapeBike(), VehicleShape.DIAMOND));
            networkPanel.setTruckShape(parseVehicleShape(config.uiVehicleShapeTruck(), VehicleShape.RECTANGLE));
            networkPanel.setBusShape(parseVehicleShape(config.uiVehicleShapeBus(), VehicleShape.OVAL));
            networkPanel.setRailShape(parseVehicleShape(config.uiVehicleShapeRail(), VehicleShape.ARROW));
        });
    }

    private ColorMode parseColorModeDefault() {
        AppConfig config = startupAppConfig;
        if (config == null) {
            return ColorMode.DEFAULT;
        }
        return parseColorMode(config.uiColorMode(), ColorMode.DEFAULT);
    }

    private PanelVideoRecorder.Quality parseRecordingQualityDefault() {
        AppConfig config = startupAppConfig;
        if (config == null) {
            return PanelVideoRecorder.Quality.VIEWPORT_SYNC;
        }
        return parseRecordingQuality(config.recordingDefaultQuality(), PanelVideoRecorder.Quality.VIEWPORT_SYNC);
    }

    private static ColorMode parseColorMode(String raw, ColorMode fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return ColorMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static VehicleShape parseVehicleShape(String raw, VehicleShape fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return VehicleShape.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static PanelVideoRecorder.Quality parseRecordingQuality(String raw, PanelVideoRecorder.Quality fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return PanelVideoRecorder.Quality.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
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

                runOnEdt(() -> {
                    networkPanel.repaint();
                    if (videoRecorder.isRecording()) {
                        videoRecorder.captureFrame(networkPanel);
                    }
                });
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
        Set<String> defaults = Set.of("car", "bike", "truck", "bus", "tram");
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
