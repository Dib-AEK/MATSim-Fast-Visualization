package com.matsim.viz.ui.fx;

import com.matsim.viz.config.AppConfig;
import com.matsim.viz.domain.ColorMode;
import com.matsim.viz.domain.VehicleShape;
import com.matsim.viz.engine.PlaybackController;
import com.matsim.viz.engine.SimulationModel;
import com.matsim.viz.ui.NetworkPanel;
import com.matsim.viz.ui.PanelVideoRecorder;
import com.matsim.viz.ui.map.OsmBackground;
import com.matsim.viz.ui.TimeFormat;
import com.matsim.viz.ui.editor.NetworkEditorPanel;
import com.matsim.viz.ui.editor.TransitEditorPane;
import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.embed.swing.SwingNode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.FileChooser;

import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private ExecutorService heatmapPreprocessExecutor;
    private volatile boolean heatmapPreprocessInProgress;
    private NetworkEditorPanel activeEditor;
    private TransitEditorPane transitEditor;
    private static Path startupTransitSchedule, startupTransitVehicles;
    public static void setTransitSources(Path schedule, Path vehicles) {
        startupTransitSchedule=schedule;startupTransitVehicles=vehicles;
    }
    private boolean editorHasUnsavedChanges() {
        return (activeEditor!=null&&getOnEdt(activeEditor::hasUnsavedChanges))
                || (transitEditor!=null&&transitEditor.hasUnsavedChanges());
    }
    private javafx.scene.Parent editorRoot;
    private javafx.scene.Parent visualizationRoot;
    private boolean editorActive, editorLoading, applicationClosing, editorSaving;

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
        heatmapPreprocessExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "heatmap-preprocess");
            thread.setDaemon(true);
            return thread;
        });

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
            if (editorSaving) { event.consume(); return; }
            if (editorHasUnsavedChanges()) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "The network or transit schedule has unsaved changes. Close without saving?", ButtonType.OK, ButtonType.CANCEL);
                confirm.initOwner(stage);
                if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) { event.consume(); return; }
            }
            applicationClosing = true;
            animation.stop();
            runOnEdt(networkPanel::disposeMapResources);
            if (videoRecorder.isRecording()) {
                try { videoRecorder.stop(); } catch (IOException ignored) { }
            }
            if(transitEditor!=null)transitEditor.close();
            if (activeEditor != null) runOnEdt(activeEditor::disposeResources);
            if (heatmapPreprocessExecutor != null) {
                heatmapPreprocessExecutor.shutdownNow();
            }
            Platform.exit();
        });

    }

    private TopBarBundle buildTopBar(Stage owner, SimulationModel model, PlaybackController playbackController, NetworkPanel networkPanel) {
        VBox wrapper = new VBox();
        wrapper.getStyleClass().add("top-wrap");

        // Wrap complete control groups instead of compressing every label into one row.
        FlowPane controls = new FlowPane(18, 10);
        controls.setMinWidth(0);
        wrapper.setMinWidth(0);
        controls.getStyleClass().add("toolbar");
        controls.setPadding(new Insets(10, 14, 10, 14));
        controls.setAlignment(Pos.CENTER_LEFT);

        Button playPauseButton = new Button("Play");
        playPauseButton.getStyleClass().add("accent-button");
        playPauseButton.setOnAction(e -> playbackController.togglePlaying());

        Label timeCaption = new Label("Time");
        timeCaption.getStyleClass().add("field-caption");

        Slider timeSlider = new Slider(playbackController.getStartTime(), playbackController.getEndTime(), playbackController.getCurrentTime());
        timeSlider.setPrefWidth(240);
        timeSlider.setBlockIncrement(1.0);

        Label timeValue = new Label(TimeFormat.hhmmss(playbackController.getCurrentTime()));
        timeValue.getStyleClass().add("mono-value");

        Label speedCaption = new Label("Speed");
        speedCaption.getStyleClass().add("field-caption");

        Slider speedSlider = new Slider(1, 600, playbackController.getSpeedMultiplier());
        speedSlider.setPrefWidth(150);
        speedSlider.valueProperty().addListener((obs, oldValue, newValue) ->
                playbackController.setSpeedMultiplier(newValue.doubleValue()));

        Label speedValue = new Label(formatSpeed(playbackController.getSpeedMultiplier()));
        speedValue.getStyleClass().add("mono-value");
        speedSlider.valueProperty().addListener((obs, oldValue, newValue) -> speedValue.setText(formatSpeed(newValue.doubleValue())));

        Label rendererCaption = new Label("Renderer");
        rendererCaption.getStyleClass().add("field-caption");
        Label rendererValue = new Label(detectRendererMode());
        rendererValue.getStyleClass().add("mono-value");

        Label windowCaption = new Label("Window");
        windowCaption.getStyleClass().add("field-caption");
        ComboBox<String> windowModeCombo = new ComboBox<>(FXCollections.observableArrayList("Windowed", "Fullscreen"));
        windowModeCombo.setValue("Windowed");
        windowModeCombo.setPrefWidth(Region.USE_COMPUTED_SIZE);

        Label screenCaption = new Label("Screen");
        screenCaption.getStyleClass().add("field-caption");
        List<DisplayScreenOption> screenOptions = buildScreenOptions();
        ComboBox<DisplayScreenOption> screenCombo = new ComboBox<>(FXCollections.observableArrayList(screenOptions));
        screenCombo.setPrefWidth(Region.USE_COMPUTED_SIZE);
        DisplayScreenOption initialScreen = largestScreenOption(screenOptions);
        if (initialScreen != null) {
            screenCombo.setValue(initialScreen);
        }

        final boolean[] applyingWindowMode = {false};

        final double[] windowedX = {Double.NaN};
        final double[] windowedY = {Double.NaN};
        final double[] windowedW = {Double.NaN};
        final double[] windowedH = {Double.NaN};

        Runnable captureWindowedBounds = () -> {
            if (!owner.isMaximized() && !owner.isFullScreen()) {
                windowedX[0] = owner.getX();
                windowedY[0] = owner.getY();
                windowedW[0] = owner.getWidth();
                windowedH[0] = owner.getHeight();
            }
        };

        windowModeCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue == null) {
                return;
            }
            if (applyingWindowMode[0]) {
                return;
            }

            applyingWindowMode[0] = true;
            try {
                if ("Fullscreen".equals(newValue)) {
                    captureWindowedBounds.run();
                    owner.setMaximized(false);

                    DisplayScreenOption selectedScreen = screenCombo.getValue();
                    Screen targetScreen = selectedScreen == null ? Screen.getPrimary() : selectedScreen.screen();
                    moveStageToScreen(owner, targetScreen, true);

                    owner.setFullScreen(true);
                } else {
                    owner.setFullScreen(false);
                    owner.setMaximized(false);

                    if (Double.isFinite(windowedW[0]) && Double.isFinite(windowedH[0])) {
                        owner.setWidth(Math.max(owner.getMinWidth(), windowedW[0]));
                        owner.setHeight(Math.max(owner.getMinHeight(), windowedH[0]));
                    }
                    if (Double.isFinite(windowedX[0]) && Double.isFinite(windowedY[0])) {
                        owner.setX(windowedX[0]);
                        owner.setY(windowedY[0]);
                    }
                }
            } finally {
                applyingWindowMode[0] = false;
            }
        });

        screenCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue == null || applyingWindowMode[0]) {
                return;
            }

            applyingWindowMode[0] = true;
            try {
                owner.setFullScreen(false);
                owner.setMaximized(false);
                moveStageToScreen(owner, newValue.screen(), false);
                windowModeCombo.setValue("Windowed");
            } finally {
                applyingWindowMode[0] = false;
            }
        });

        Runnable syncScreenSelection = () -> {
            if (applyingWindowMode[0]) {
                return;
            }
            DisplayScreenOption closest = closestScreenOption(screenOptions, owner);
            if (closest != null) {
                screenCombo.setValue(closest);
            }
        };
        owner.xProperty().addListener((obs, oldValue, newValue) -> syncScreenSelection.run());
        owner.yProperty().addListener((obs, oldValue, newValue) -> syncScreenSelection.run());

        owner.fullScreenProperty().addListener((obs, wasFullScreen, isFullScreen) -> {
            if (applyingWindowMode[0]) {
                return;
            }
            applyingWindowMode[0] = true;
            try {
                windowModeCombo.setValue(isFullScreen ? "Fullscreen" : "Windowed");
            } finally {
                applyingWindowMode[0] = false;
            }
        });

        Platform.runLater(() -> {
            DisplayScreenOption largest = largestScreenOption(screenOptions);
            applyingWindowMode[0] = true;
            try {
                if (largest != null) {
                    screenCombo.setValue(largest);
                }

                owner.setFullScreen(false);
                owner.setMaximized(false);

                Screen target = largest == null ? Screen.getPrimary() : largest.screen();
                moveStageToScreen(owner, target, true);

                captureWindowedBounds.run();
                owner.setFullScreen(true);
                windowModeCombo.setValue("Fullscreen");
            } finally {
                applyingWindowMode[0] = false;
            }
        });

        Button quitButton = new Button("Quit");
        quitButton.getStyleClass().add("danger-button");
        quitButton.setOnAction(e -> owner.fireEvent(new javafx.stage.WindowEvent(owner, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST)));

        ComboBox<PanelVideoRecorder.Quality> qualityCombo = new ComboBox<>(
                FXCollections.observableArrayList(PanelVideoRecorder.Quality.values()));
        qualityCombo.setValue(parseRecordingQualityDefault());
        qualityCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(PanelVideoRecorder.Quality value) {
                return value == null ? "" : value.label();
            }
            @Override public PanelVideoRecorder.Quality fromString(String value) {
                throw new UnsupportedOperationException("Recording presets are not editable");
            }
        });
        qualityCombo.setPrefWidth(Region.USE_COMPUTED_SIZE);
        qualityCombo.setTooltip(new javafx.scene.control.Tooltip(
                "Presentation 4K: high-quality H.264 MP4 at 15 fps. Capture may run slower than real time; "
                        + "the saved video keeps the selected playback speed. Files can be large."));

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

        Label videoCaption = new Label("Video");
        videoCaption.getStyleClass().add("field-caption");
        controls.getChildren().addAll(
                toolbarGroup(playPauseButton, recordButton, quitButton),
                toolbarGroup(timeCaption, timeSlider, timeValue),
                toolbarGroup(speedCaption, speedSlider, speedValue),
                toolbarGroup(videoCaption, qualityCombo),
                toolbarGroup(windowCaption, windowModeCombo),
                toolbarGroup(screenCaption, screenCombo),
                toolbarGroup(rendererCaption, rendererValue)
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

    private static HBox toolbarGroup(Node... controls) {
        HBox group = new HBox(8, controls);
        group.setAlignment(Pos.CENTER_LEFT);
        group.setMinWidth(Region.USE_PREF_SIZE);
        for (Node control : controls) {
            if (control instanceof Region region) {
                // Recompute when text/font changes (Play/Pause, Stop, Encoding, display scale).
                region.setMinWidth(Region.USE_PREF_SIZE);
            }
        }
        return group;
    }

    private NodeBundle buildSidePanel(Stage owner, SimulationModel model, NetworkPanel networkPanel) {
        VBox content = new VBox(12);
        content.getStyleClass().add("side-content");
        content.setPadding(new Insets(12));

        VBox visualizationCard = createCard("Visualization");
        ComboBox<VisualizationLayerChoice> visualizationModeCombo = new ComboBox<>(
            FXCollections.observableArrayList(buildVisualizationChoices())
        );
        visualizationModeCombo.setMaxWidth(Double.MAX_VALUE);
        visualizationModeCombo.setValue(choiceForMode(getOnEdt(networkPanel::getVisualizationMode)));
        final boolean[] applyingVisualizationChoice = {false};
        visualizationModeCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue == null || applyingVisualizationChoice[0]) {
                return;
            }

            if (newValue.openNetworkEditor()) {
                openNetworkEditorWindow(owner, model, networkPanel);
                applyingVisualizationChoice[0] = true;
                try {
                    VisualizationLayerChoice fallback = oldValue != null && !oldValue.openNetworkEditor()
                            ? oldValue
                            : choiceForMode(getOnEdt(networkPanel::getVisualizationMode));
                    visualizationModeCombo.setValue(fallback);
                } finally {
                    applyingVisualizationChoice[0] = false;
                }
                return;
            }

            runOnEdt(() -> networkPanel.setVisualizationMode(newValue.mode()));
        });
        visualizationCard.getChildren().add(visualizationModeCombo);

        TitledPane networkModesCard = buildModeSelectionCard(
                "Network Modes",
                model.availableLinkModes(),
            defaultTransportModes(model.availableLinkModes()),
                selected -> runOnEdt(() -> networkPanel.setSelectedLinkModes(selected))
        );

        TitledPane tripModesCard = buildModeSelectionCard(
                "Trip Modes",
                model.availableTripModes(),
            defaultTransportModes(model.availableTripModes()),
                selected -> runOnEdt(() -> networkPanel.setSelectedTripModes(selected))
        );

        TitledPane heatmapTripModesCard = buildModeSelectionCard(
            "Heatmap Shown Modes",
            model.availableTripModes(),
            defaultTransportModes(model.availableTripModes()),
            selected -> runOnEdt(() -> networkPanel.setSelectedHeatmapTripModes(selected))
        );

        TitledPane ptStopModesCard = buildModeSelectionCard(
            "PT Stop Modes",
            model.availablePtStopModes(),
            defaultTransportModes(model.availablePtStopModes()),
            selected -> runOnEdt(() -> networkPanel.setSelectedPtStopModes(selected))
        );

        Button preprocessButton = new Button("Apply Bin + Preprocess");
        preprocessButton.getStyleClass().add("accent-button");
        preprocessButton.setMaxWidth(Double.MAX_VALUE);
        VBox heatmapSettingsCard = buildHeatmapSettingsCard(networkPanel, preprocessButton);

        CheckBox separateNetworkModesToggle = new CheckBox("Allow separate network mode filter");
        separateNetworkModesToggle.setSelected(getOnEdt(networkPanel::isUseSeparateHeatmapNetworkModes));
        separateNetworkModesToggle.setOnAction(e -> runOnEdt(() ->
            networkPanel.setUseSeparateHeatmapNetworkModes(separateNetworkModesToggle.isSelected())
        ));
        heatmapSettingsCard.getChildren().add(separateNetworkModesToggle);

        VBox flowHeatmapColorsCard = buildHeatmapColorsCard(
            "Flow Color Map",
            NetworkPanel.VisualizationMode.FLOW_HEATMAP,
            networkPanel
        );
        VBox speedHeatmapColorsCard = buildHeatmapColorsCard(
            "Speed Color Map",
            NetworkPanel.VisualizationMode.SPEED_HEATMAP,
            networkPanel
        );
        VBox speedRatioHeatmapColorsCard = buildHeatmapColorsCard(
            "Speed Ratio Color Map",
            NetworkPanel.VisualizationMode.SPEED_RATIO_HEATMAP,
            networkPanel
        );
        VBox ptStopBubbleSizeCard = buildPtStopBubbleSizeCard(networkPanel);

        VBox displaySettingsCard = buildDisplaySettingsCard(owner, model, networkPanel);
        VBox appearanceCard = buildAppearanceCard(owner, networkPanel);
        VBox bottleneckCard = buildBottleneckCard(networkPanel);

        content.getChildren().addAll(
            visualizationCard,
            networkModesCard,
            tripModesCard,
            heatmapTripModesCard,
            ptStopModesCard,
            heatmapSettingsCard,
            flowHeatmapColorsCard,
            speedHeatmapColorsCard,
            speedRatioHeatmapColorsCard,
            ptStopBubbleSizeCard,
            displaySettingsCard,
            appearanceCard,
            bottleneckCard,
            buildZoomDetailCard(networkPanel),
            buildMapBackgroundCard(networkPanel)
        );

        // Mode changes hide the whole section; content visibility is owned by TitledPane.
        List<Node> sections = new ArrayList<>();
        Map<Node, Node> sectionHeaders = new LinkedHashMap<>();
        for (Node child : content.getChildren()) {
            if (child instanceof TitledPane pane) {
                pane.setExpanded(false);
                pane.getStyleClass().remove("card");
                sections.add(pane);
            } else if (child instanceof VBox card && card.getChildren().get(0) instanceof Label title) {
                card.getChildren().remove(0);
                card.getStyleClass().remove("card");
                card.setPadding(new Insets(10));
                TitledPane pane = new TitledPane(title.getText(), card);
                pane.setExpanded(false);
                sectionHeaders.put(card, pane);
                sections.add(pane);
            }
        }
        content.getChildren().setAll(sections);

        Runnable refreshModePanels = () -> {
            VisualizationLayerChoice currentChoice = visualizationModeCombo.getValue();
            NetworkPanel.VisualizationMode mode = currentChoice == null
                ? NetworkPanel.VisualizationMode.VEHICLES
                : currentChoice.mode();
            if (mode == null) {
            mode = NetworkPanel.VisualizationMode.VEHICLES;
            }

            boolean vehicleMode = mode == NetworkPanel.VisualizationMode.VEHICLES;
            boolean flowMode = mode == NetworkPanel.VisualizationMode.FLOW_HEATMAP
                    || mode == NetworkPanel.VisualizationMode.PT_FLOW_HEATMAP
                    || mode == NetworkPanel.VisualizationMode.PT_STOP_BUBBLES;
                boolean ptStopMode = mode == NetworkPanel.VisualizationMode.PT_STOP_BUBBLES;
            boolean speedMode = mode == NetworkPanel.VisualizationMode.SPEED_HEATMAP;
            boolean speedRatioMode = mode == NetworkPanel.VisualizationMode.SPEED_RATIO_HEATMAP;
            boolean allowSeparateNetworkModes = separateNetworkModesToggle.isSelected();

            setVisibleManaged(sectionHeaders.getOrDefault(networkModesCard, networkModesCard), vehicleMode || allowSeparateNetworkModes);
            setVisibleManaged(sectionHeaders.getOrDefault(tripModesCard, tripModesCard), vehicleMode);
            setVisibleManaged(sectionHeaders.getOrDefault(displaySettingsCard, displaySettingsCard), vehicleMode);
            setVisibleManaged(sectionHeaders.getOrDefault(bottleneckCard, bottleneckCard), vehicleMode);

            setVisibleManaged(sectionHeaders.getOrDefault(heatmapTripModesCard, heatmapTripModesCard), !vehicleMode && !ptStopMode);
            setVisibleManaged(sectionHeaders.getOrDefault(ptStopModesCard, ptStopModesCard), ptStopMode);
            setVisibleManaged(sectionHeaders.getOrDefault(heatmapSettingsCard, heatmapSettingsCard), !vehicleMode);
            setVisibleManaged(sectionHeaders.getOrDefault(flowHeatmapColorsCard, flowHeatmapColorsCard), flowMode);
            setVisibleManaged(sectionHeaders.getOrDefault(speedHeatmapColorsCard, speedHeatmapColorsCard), speedMode);
            setVisibleManaged(sectionHeaders.getOrDefault(speedRatioHeatmapColorsCard, speedRatioHeatmapColorsCard), speedRatioMode);
            setVisibleManaged(sectionHeaders.getOrDefault(ptStopBubbleSizeCard, ptStopBubbleSizeCard), ptStopMode);

            boolean heatmapMode = !vehicleMode;
            preprocessButton.setDisable(!heatmapMode || heatmapPreprocessInProgress);
            preprocessButton.setText(heatmapPreprocessInProgress ? "Preprocessing..." : "Apply Bin + Preprocess");
        };

        separateNetworkModesToggle.selectedProperty().addListener((obs, oldValue, newValue) -> refreshModePanels.run());
        refreshModePanels.run();

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.getStyleClass().add("side-scroll");
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportWidth(320);
        scrollPane.setMinWidth(300);

        visualizationModeCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            refreshModePanels.run();
            if (newValue == null || newValue.openNetworkEditor() || applyingVisualizationChoice[0]
                    || newValue.mode() == NetworkPanel.VisualizationMode.VEHICLES) return;
            TitledPane settings = (TitledPane) sectionHeaders.get(heatmapSettingsCard);
            settings.setAnimated(false);
            settings.setExpanded(true);
            Platform.runLater(() -> {
                // A queued reveal must not steal focus after a subsequent mode change.
                if (visualizationModeCombo.getValue() != newValue) return;
                content.applyCss();
                scrollPane.layout();
                content.layout();
                double range = content.getLayoutBounds().getHeight() - scrollPane.getViewportBounds().getHeight();
                if (range > 0) scrollPane.setVvalue(Math.max(0, Math.min(1, settings.getBoundsInParent().getMinY() / range)));
                Node interval = heatmapSettingsCard.lookup("#heatmap-bin-minutes");
                if (interval != null) interval.requestFocus();
            });
        });

        return new NodeBundle(scrollPane);
    }

    private VBox buildZoomDetailCard(NetworkPanel panel) {
        VBox card = createCard("Zoom Detail");
        Label hint = new Label("Lower thresholds show road detail sooner. Values are the projected width of one lane in pixels.");
        hint.setWrapText(true);
        TextField start = new TextField(Double.toString(getOnEdt(panel::getDetailStartLanePixels)));
        TextField full = new TextField(Double.toString(getOnEdt(panel::getDetailFullLanePixels)));
        TextField coverage = new TextField(Double.toString(getOnEdt(panel::getOverviewVehicleCoverage)));
        Label coverageHint = new Label("Overview vehicle coverage: 0.2 to 0.9. Lower values leave larger gaps; default 0.75.");
        coverageHint.setWrapText(true);
        Label status = new Label();
        status.setWrapText(true);
        Button apply = new Button("Apply Zoom Detail");
        apply.setOnAction(event -> {
            try {
                double a = Double.parseDouble(start.getText().trim());
                double b = Double.parseDouble(full.getText().trim());
                double c = Double.parseDouble(coverage.getText().trim());
                runOnEdt(() -> panel.setZoomDetail(a, b, c));
                status.setText("Applied.");
            } catch (Exception ex) {
                status.setText("Use positive thresholds with full > start, and coverage between 0.2 and 0.9.");
            }
        });
        card.getChildren().addAll(hint, new Label("Detail starts (lane px)"), start,
                new Label("Full detail (lane px)"), full, coverageHint, coverage, apply, status);
        return card;
    }

    private VBox buildMapBackgroundCard(NetworkPanel panel) {
        VBox card = createCard("Map Background");
        CheckBox enabled = new CheckBox("Add OpenStreetMap background");
        Label caption = new Label("Network CRS");
        caption.getStyleClass().add("field-caption");
        TextField crs = new TextField(startupAppConfig == null ? "EPSG:2056" : startupAppConfig.uiMapCrs());
        crs.setPromptText("EPSG:2056");
        Button apply = new Button("Apply CRS");
        Label status = new Label("Use the CRS of your simulation. Map tiles need an internet connection.");
        status.setWrapText(true);
        status.getStyleClass().add("hint");
        javafx.scene.control.Hyperlink credit = new javafx.scene.control.Hyperlink("? OpenStreetMap contributors");
        credit.setOnAction(event -> getHostServices().showDocument("https://www.openstreetmap.org/copyright"));
        Runnable update = () -> {
            if (!enabled.isSelected()) {
                runOnEdt(() -> panel.setOsmBackground(null));
                status.setText("Map background off.");
                return;
            }
            String requestedCrs = crs.getText();
            enabled.setDisable(true);
            apply.setDisable(true);
            crs.setDisable(true);
            status.setText("Preparing map projection...");
            Path cache = startupCacheDir == null ? Path.of("cache") : startupCacheDir;
            java.util.concurrent.CompletableFuture.supplyAsync(() -> new OsmBackground(requestedCrs, cache))
                    .whenComplete((background, error) -> Platform.runLater(() -> {
                        try {
                            if (error != null) throw new IllegalArgumentException(error.getCause() == null
                                    ? error.getMessage() : error.getCause().getMessage());
                            runOnEdt(() -> panel.setOsmBackground(background));
                            crs.setText(background.crs());
                            status.setText("Map aligned to " + background.crs() + ". Wait for tiles to load before recording.");
                        } catch (RuntimeException ex) {
                            if (background != null) background.close();
                            enabled.setSelected(getOnEdt(panel::isMapBackgroundEnabled));
                            Throwable cause = ex;
                            while (cause.getCause() != null) cause = cause.getCause();
                            status.setText("Map unchanged: " + cause.getMessage());
                        } finally {
                            enabled.setDisable(false);
                            apply.setDisable(false);
                            crs.setDisable(false);
                        }
                    }));
        };
        enabled.setOnAction(event -> update.run());
        apply.setOnAction(event -> { enabled.setSelected(true); update.run(); });
        crs.setOnAction(event -> { enabled.setSelected(true); update.run(); });
        card.getChildren().addAll(enabled, caption, crs, apply, status, credit);
        if (startupAppConfig != null && startupAppConfig.uiMapBackground()) {
            enabled.setSelected(true);
            update.run();
        }
        return card;
    }

    private void openNetworkEditorWindow(Stage owner, SimulationModel model, NetworkPanel mainNetworkPanel) {
        if (editorLoading || editorActive) return;
        if (videoRecorder != null && (videoRecorder.isRecording() || videoRecorder.isEncoding())) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "Finish the video recording/export before opening the editor.");
            alert.initOwner(owner); alert.show(); return;
        }
        visualizationRoot = mainScene.getRoot();
        editorActive = true;
        runOnEdt(() -> mainNetworkPanel.setRenderingSuspended(true));
        if (editorRoot != null) {
            runOnEdt(() -> activeEditor.setActive(true));
            mainScene.setRoot(editorRoot); return;
        }
        editorLoading = true;
        Label loading = new Label("Preparing network editor...");
        loading.setWrapText(true);
        BorderPane loadingRoot = new BorderPane(loading);
        loadingRoot.getStyleClass().add("app-root");
        mainScene.setRoot(loadingRoot);
        Path editorCacheDir = startupCacheDir != null ? startupCacheDir : Path.of("cache");
        CompletableFuture.supplyAsync(() -> NetworkEditorPanel.prepare(model.networkData(), mainNetworkPanel.sharedSpatialIndex()))
                .whenComplete((prepared, error) -> Platform.runLater(() -> {
                    editorLoading = false;
                    if (applicationClosing) return;
                    try {
                        if (error != null) throw new IllegalStateException("Could not prepare network editor", error);
                        activeEditor = getOnEdt(() -> new NetworkEditorPanel(prepared, editorCacheDir));
                        buildNetworkEditor(owner, mainNetworkPanel, activeEditor);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                        editorActive = false;
                        if (activeEditor != null) runOnEdt(activeEditor::disposeResources);
                        activeEditor = null; editorRoot = null;
                        mainScene.setRoot(visualizationRoot);
                        runOnEdt(() -> mainNetworkPanel.setRenderingSuspended(false));
                        Alert alert = new Alert(Alert.AlertType.ERROR, ex.getMessage()); alert.initOwner(owner); alert.show();
                    }
                }));
    }

    private void buildNetworkEditor(Stage owner, NetworkPanel mainNetworkPanel, NetworkEditorPanel editorPanel) {
        SwingNode swingNode = new SwingNode();
        runOnEdt(() -> swingNode.setContent(editorPanel));

        Label selectedType = new Label("Nothing selected");
        selectedType.getStyleClass().add("field-caption");
        Label selectedId = new Label("-");
        selectedId.getStyleClass().add("mono-value");
        Label selectedMeta = new Label("Click a link or node to inspect details.");
        selectedMeta.getStyleClass().add("hint");
        selectedMeta.setWrapText(true);

        TreeView<String> attributesTree = new TreeView<>();
        attributesTree.setShowRoot(true);
        attributesTree.setPrefHeight(240);
        attributesTree.setRoot(new TreeItem<>("No selection"));

        Label crsCaption = new Label("Network Coordinate System");
        crsCaption.getStyleClass().add("field-caption");
        ComboBox<NetworkEditorPanel.CoordinateSystem> crsCombo = new ComboBox<>(
                FXCollections.observableArrayList(NetworkEditorPanel.CoordinateSystem.values())
        );
        crsCombo.setMaxWidth(Double.MAX_VALUE);
        crsCombo.setValue(getOnEdt(editorPanel::getCoordinateSystem));
        crsCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                runOnEdt(() -> editorPanel.setCoordinateSystem(newValue));
            }
        });

        List<String> availableEditorModes = getOnEdt(editorPanel::availableLinkModes);
        Set<String> initiallyVisibleModes = getOnEdt(editorPanel::visibleLinkModes);
        TitledPane editorLinkModesCard = buildModeSelectionCard(
                "Network Modes",
                availableEditorModes,
                initiallyVisibleModes,
                selected -> runOnEdt(() -> editorPanel.setVisibleLinkModes(selected))
        );

        VBox editorColorsCard = createCard("Network Colors");
        Label linkColorCaption = new Label("Link color");
        linkColorCaption.getStyleClass().add("field-caption");
        ColorPicker linkColorPicker = new ColorPicker(toFx(getOnEdt(editorPanel::getLinkColor)));
        linkColorPicker.setMaxWidth(Double.MAX_VALUE);
        linkColorPicker.setOnAction(e -> runOnEdt(() -> editorPanel.setLinkColor(toAwt(linkColorPicker.getValue()))));

        Label nodeColorCaption = new Label("Node color");
        nodeColorCaption.getStyleClass().add("field-caption");
        ColorPicker nodeColorPicker = new ColorPicker(toFx(getOnEdt(editorPanel::getNodeColor)));
        nodeColorPicker.setMaxWidth(Double.MAX_VALUE);
        nodeColorPicker.setOnAction(e -> runOnEdt(() -> editorPanel.setNodeColor(toAwt(nodeColorPicker.getValue()))));

        editorColorsCard.getChildren().addAll(
                linkColorCaption,
                linkColorPicker,
                nodeColorCaption,
                nodeColorPicker
        );

        Button createNodeButton = new Button("Create Node");
        createNodeButton.getStyleClass().add("accent-button");
        createNodeButton.setMaxWidth(Double.MAX_VALUE);
        createNodeButton.setOnAction(e -> runOnEdt(editorPanel::armCreateNode));

        Button createLinkButton = new Button("Create Link");
        createLinkButton.getStyleClass().add("accent-button");
        createLinkButton.setMaxWidth(Double.MAX_VALUE);
        createLinkButton.setOnAction(e -> runOnEdt(editorPanel::armCreateLink));

        Button editLinkButton = new Button("Edit Selected Link");
        editLinkButton.getStyleClass().add("ghost-button");
        editLinkButton.setMaxWidth(Double.MAX_VALUE);
        editLinkButton.setDisable(true);
        editLinkButton.setOnAction(e -> SwingUtilities.invokeLater(editorPanel::editSelectedLink));

        Button reverseLinkButton = new Button("Add reverse direction");
        reverseLinkButton.setMaxWidth(Double.MAX_VALUE);
        reverseLinkButton.setDisable(true);
        reverseLinkButton.setOnAction(e -> {
            javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog();
            dialog.initOwner(owner); dialog.setHeaderText("New reverse link ID");
            dialog.setContentText("Copies lanes, speed, capacity and modes in the opposite direction.");
            dialog.showAndWait().ifPresent(id -> {
                try { runOnEdt(() -> editorPanel.createReverseLink(id)); }
                catch (Exception ex) { new Alert(Alert.AlertType.ERROR, ex.getMessage()).show(); }
            });
        });

        Button deleteLinkButton = new Button("Delete Selected Link");
        deleteLinkButton.getStyleClass().add("ghost-button");
        deleteLinkButton.setMaxWidth(Double.MAX_VALUE);
        deleteLinkButton.setDisable(true);
        deleteLinkButton.setOnAction(e -> {
            if (!getOnEdt(editorPanel::hasSelectedLink)) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setHeaderText("No link selected");
                alert.setContentText("Select a link first, then delete it.");
                alert.showAndWait();
                return;
            }

            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setHeaderText("Delete selected link?");
            confirm.setContentText("This action removes the selected link from the edited network.");
            var result = confirm.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                getOnEdt(editorPanel::deleteSelectedLink);
            }
        });

        Button deleteNodeButton = new Button("Delete Selected Node");
        deleteNodeButton.getStyleClass().add("ghost-button");
        deleteNodeButton.setMaxWidth(Double.MAX_VALUE);
        deleteNodeButton.setDisable(true);
        deleteNodeButton.setOnAction(e -> {
            if (!getOnEdt(editorPanel::hasSelectedNode)) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setHeaderText("No node selected");
                alert.setContentText("Select a node first, then delete it.");
                alert.showAndWait();
                return;
            }

            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setHeaderText("Delete selected node?");
            confirm.setContentText("All links connected to this node will also be removed.");
            var result = confirm.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                int removedLinks = getOnEdt(editorPanel::deleteSelectedNodeAndConnectedLinks);
                Alert info = new Alert(Alert.AlertType.INFORMATION);
                info.setHeaderText("Node deleted");
                info.setContentText("Connected links removed: " + Math.max(0, removedLinks));
                info.showAndWait();
            }
        });

        Button saveNetworkButton = new Button("Save Modified Network");
        saveNetworkButton.getStyleClass().add("accent-button");
        saveNetworkButton.setMaxWidth(Double.MAX_VALUE);
        saveNetworkButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Save Modified MATSim Network");
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("MATSim network (*.xml)", "*.xml"),
                    new FileChooser.ExtensionFilter("MATSim network compressed (*.xml.gz)", "*.xml.gz")
            );
            chooser.setInitialFileName("network-edited.xml.gz");
            java.io.File selected = chooser.showSaveDialog(owner);
            if (selected == null) {
                return;
            }

            editorSaving = true;
            editorRoot.setDisable(true);
            runOnEdt(() -> editorPanel.setEditingLocked(true));
            saveNetworkButton.setText("Saving network...");
            NetworkEditorPanel.SaveSnapshot snapshot = getOnEdt(editorPanel::snapshotForSave);
            CompletableFuture.supplyAsync(() -> snapshot.write(selected.toPath())).whenComplete((summary, error) ->
                    Platform.runLater(() -> {
                        editorSaving = false;
                        editorRoot.setDisable(false);
                        runOnEdt(() -> editorPanel.setEditingLocked(false));
                        saveNetworkButton.setText("Save Modified Network");
                        if (error == null) {
                            runOnEdt(editorPanel::markSaved);
                            Alert ok = new Alert(Alert.AlertType.INFORMATION,
                                    "Saved " + summary.nodeCount() + " nodes and " + summary.linkCount() + " links to:\n" + summary.outputFile());
                            ok.initOwner(owner); ok.show();
                        } else {
                            Throwable cause = error.getCause() == null ? error : error.getCause();
                            Alert err = new Alert(Alert.AlertType.ERROR, cause.getMessage());
                            err.initOwner(owner); err.setHeaderText("Save failed; your edits are retained"); err.show();
                        }
                    }));
        });

        CheckBox editorMapToggle = new CheckBox("Add OpenStreetMap background");
        editorMapToggle.setOnAction(e -> runOnEdt(() -> editorPanel.setMapBackgroundEnabled(editorMapToggle.isSelected())));
        Label mapHint = new Label();
        mapHint.getStyleClass().add("hint");
        mapHint.setWrapText(true);

        Runnable refreshMapHint = () -> {
            boolean enabled = getOnEdt(editorPanel::hasGeoCoordinates);
            mapHint.setText(enabled
                    ? "OSM map background enabled. Tiles are cached in cache/osm-tiles."
                    : "OSM map background disabled for the selected coordinate system.");
        };
        crsCombo.valueProperty().addListener((obs, oldValue, newValue) -> refreshMapHint.run());
        editorMapToggle.selectedProperty().addListener((obs, old, value) -> Platform.runLater(refreshMapHint));
        refreshMapHint.run();

        TitledPane attributesSection = new TitledPane("Selected element attributes", attributesTree);
        attributesSection.setExpanded(false);
        TitledPane colorsSection = new TitledPane("Network colours", editorColorsCard);
        colorsSection.setExpanded(false);
        TitledPane mapSection = new TitledPane("Map background", new VBox(8, editorMapToggle, crsCaption, crsCombo, mapHint));
        mapSection.setExpanded(false);
        editorLinkModesCard.setExpanded(false);
        editorLinkModesCard.getStyleClass().remove("card");
        VBox inspector = new VBox(10, selectedType, selectedId, selectedMeta,
                editLinkButton, reverseLinkButton, createNodeButton, createLinkButton,
                deleteLinkButton, deleteNodeButton, saveNetworkButton,
                attributesSection, editorLinkModesCard, colorsSection, mapSection);
        transitEditor = new TransitEditorPane(owner,editorPanel,startupTransitSchedule,startupTransitVehicles,locked->{
            editorSaving=locked;
            if(editorRoot!=null)editorRoot.setDisable(locked);
            runOnEdt(()->editorPanel.setEditingLocked(locked));
        });
        TitledPane transitSection=new TitledPane("Public transport lines and schedules",transitEditor);
        transitSection.setExpanded(false);
        transitSection.expandedProperty().addListener((obs,old,expanded)->{if(expanded)transitEditor.loadDefaultFiles();});
        inspector.getChildren().add(0,transitSection);
        inspector.setPadding(new Insets(12));
        inspector.setPrefWidth(360);
        inspector.getStyleClass().add("side-content");

        Button back = new Button("Back to simulation");
        back.setOnAction(e -> {
            runOnEdt(() -> editorPanel.setActive(false));
            mainScene.setRoot(visualizationRoot);
            editorActive = false;
            runOnEdt(() -> mainNetworkPanel.setRenderingSuspended(false));
        });
        Button closeEditor = new Button("Close editor");
        closeEditor.setOnAction(e -> {
            if (editorHasUnsavedChanges()) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "Close the editor and discard unsaved network changes?", ButtonType.OK, ButtonType.CANCEL);
                confirm.initOwner(owner);
                if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            }
            if(transitEditor!=null){transitEditor.close();transitEditor=null;}
            runOnEdt(editorPanel::disposeResources);
            activeEditor = null; editorRoot = null; editorActive = false;
            mainScene.setRoot(visualizationRoot);
            runOnEdt(() -> mainNetworkPanel.setRenderingSuspended(false));
        });
        Button undoButton = new Button("Undo"), redoButton = new Button("Redo");
        undoButton.setDisable(true); redoButton.setDisable(true);
        undoButton.setOnAction(e -> runOnEdt(editorPanel::undo));
        redoButton.setOnAction(e -> runOnEdt(editorPanel::redo));
        Button fit = new Button("Fit network"), cancelTool = new Button("Cancel tool");
        fit.setOnAction(e -> runOnEdt(editorPanel::fitNetwork));
        cancelTool.setOnAction(e -> runOnEdt(editorPanel::cancelTool));
        Label editStatus = new Label("No unsaved road edits");
        ComboBox<String> searchType = new ComboBox<>(FXCollections.observableArrayList("Link ID", "Node ID"));
        searchType.getSelectionModel().selectFirst();
        TextField searchId = new TextField(); searchId.setPromptText("Exact ID");
        Button find = new Button("Find");
        Label searchStatus = new Label(); searchStatus.setWrapText(true);
        Runnable findSelection = () -> {
            boolean found = getOnEdt(() -> editorPanel.findById(searchId.getText().trim(), "Node ID".equals(searchType.getValue())));
            searchStatus.setText(found ? "Selected and centred." : "ID not found.");
        };
        find.setOnAction(e -> findSelection.run()); searchId.setOnAction(e -> findSelection.run());
        inspector.getChildren().addAll(0, List.of(searchType, searchId, find, searchStatus));
        FlowPane editorToolbar = new FlowPane(8, 8, back, undoButton, redoButton, fit, cancelTool, closeEditor, editStatus);
        editorToolbar.setPadding(new Insets(10));
        Label help = new Label("Drag: pan | Click: select | Wheel: zoom | Edits are retained when returning to simulation. Save exports a separate MATSim network.");
        help.setWrapText(true); help.setPadding(new Insets(8));

        runOnEdt(() -> editorPanel.setSelectionListener((node, link) -> {
            boolean canUndo = editorPanel.canUndo(), canRedo = editorPanel.canRedo(), dirty = editorPanel.hasUnsavedChanges();
            Platform.runLater(() -> {
            undoButton.setDisable(!canUndo); redoButton.setDisable(!canRedo);
            reverseLinkButton.setDisable(link == null);
            editStatus.setText(dirty ? "Unsaved network changes" : "No unsaved road edits");
            if (node != null) {
                selectedType.setText("Selected Node");
                selectedId.setText(node.id());
                selectedMeta.setText(String.format(Locale.ROOT, "x=%.4f, y=%.4f", node.x(), node.y()));
                attributesTree.setRoot(buildNodeTree(node));
                editLinkButton.setDisable(true);
                deleteLinkButton.setDisable(true);
                deleteNodeButton.setDisable(false);
                return;
            }

            if (link != null) {
                selectedType.setText("Selected Link");
                selectedId.setText(link.id());
                selectedMeta.setText(link.fromNodeId() + " -> " + link.toNodeId());
                attributesTree.setRoot(buildLinkTree(link));
                editLinkButton.setDisable(false);
                deleteLinkButton.setDisable(false);
                deleteNodeButton.setDisable(true);
                return;
            }

            selectedType.setText("Nothing selected");
            selectedId.setText("-");
            selectedMeta.setText("Click a link or node to inspect details.");
            attributesTree.setRoot(new TreeItem<>("No selection"));
            editLinkButton.setDisable(true);
            deleteLinkButton.setDisable(true);
            deleteNodeButton.setDisable(true);
        }); }));

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setCenter(swingNode);
        ScrollPane editorScroll = new ScrollPane(inspector);
        editorScroll.getStyleClass().add("side-scroll");
        editorScroll.setFitToWidth(true); editorScroll.setPrefWidth(380);
        root.setRight(editorScroll);
        root.setTop(editorToolbar);
        root.setBottom(help);
        editorRoot = root;
        mainScene.setRoot(root);
    }

    private static TreeItem<String> buildNodeTree(com.matsim.viz.domain.NodePoint node) {
        TreeItem<String> root = new TreeItem<>("Node");
        root.setExpanded(true);
        root.getChildren().add(treeKV("id", node.id()));
        root.getChildren().add(treeKV("x", String.format(Locale.ROOT, "%.6f", node.x())));
        root.getChildren().add(treeKV("y", String.format(Locale.ROOT, "%.6f", node.y())));
        return root;
    }

    private static TreeItem<String> buildLinkTree(com.matsim.viz.domain.LinkSegment link) {
        TreeItem<String> root = new TreeItem<>("Link");
        root.setExpanded(true);

        TreeItem<String> core = new TreeItem<>("Core Fields");
        core.setExpanded(true);
        core.getChildren().add(treeKV("id", link.id()));
        core.getChildren().add(treeKV("from", link.fromNodeId()));
        core.getChildren().add(treeKV("to", link.toNodeId()));
        core.getChildren().add(treeKV("length", String.format(Locale.ROOT, "%.3f", link.length())));
        core.getChildren().add(treeKV("freeSpeed", String.format(Locale.ROOT, "%.6f", link.freeSpeed())));
        core.getChildren().add(treeKV("lanes", String.format(Locale.ROOT, "%.3f", link.lanes())));
        core.getChildren().add(treeKV("allowedModes", String.join(", ", link.allowedModes().stream().sorted().toList())));

        TreeItem<String> attrs = new TreeItem<>("Link Attributes");
        attrs.setExpanded(true);
        link.attributes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> attrs.getChildren().add(treeKV(entry.getKey(), entry.getValue())));

        root.getChildren().add(core);
        root.getChildren().add(attrs);
        return root;
    }

    private static TreeItem<String> treeKV(String key, String value) {
        TreeItem<String> node = new TreeItem<>(key);
        node.setExpanded(false);
        node.getChildren().add(new TreeItem<>(value == null ? "" : value));
        return node;
    }

    private VBox buildDisplaySettingsCard(Stage owner, SimulationModel model, NetworkPanel networkPanel) {
        VBox card = createCard("Display Settings");

        Label colorCaption = new Label("Vehicle Coloring");
        colorCaption.getStyleClass().add("field-caption");

        ComboBox<ColorMode> colorModeCombo = new ComboBox<>(FXCollections.observableArrayList(ColorMode.values()));
        colorModeCombo.setMaxWidth(Double.MAX_VALUE);
        colorModeCombo.setValue(parseColorModeDefault());
        colorModeCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (newValue != null) {
                runOnEdt(() -> networkPanel.setColorMode(newValue));
            }
        });

        CheckBox queueToggle = new CheckBox("Show link vehicle counts");
        queueToggle.setSelected(false);
        queueToggle.setOnAction(e -> runOnEdt(() -> networkPanel.setShowQueues(queueToggle.isSelected())));

        Label offsetCaption = new Label("Carriageway spacing");
        offsetCaption.getStyleClass().add("field-caption");
        Slider offsetSlider = new Slider(0.0, 1.0, getOnEdt(networkPanel::getBidirectionalOffset));
        Label offsetValue = new Label(String.format("%.2f", offsetSlider.getValue()));
        offsetValue.getStyleClass().add("mono-value");
        offsetSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            runOnEdt(() -> networkPanel.setBidirectionalOffset(newValue.doubleValue()));
            offsetValue.setText(String.format("%.2f", newValue.doubleValue()));
        });

        Label laneWidthCaption = new Label("Lane width (m, display only)");
        laneWidthCaption.getStyleClass().add("field-caption");
        Slider laneWidthSlider = new Slider(1.0, 8.0, getOnEdt(networkPanel::getLaneWidthMeters));
        Label laneWidthValue = new Label(String.format("%.2f m", laneWidthSlider.getValue()));
        laneWidthValue.getStyleClass().add("mono-value");
        laneWidthSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            runOnEdt(() -> networkPanel.setLaneWidthMeters(newValue.doubleValue()));
            laneWidthValue.setText(String.format("%.2f m", newValue.doubleValue()));
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

        card.getChildren().addAll(
            colorCaption,
            colorModeCombo,
            queueToggle,
            offsetCaption,
            offsetSlider,
            offsetValue,
            laneWidthCaption, laneWidthSlider, laneWidthValue,
            colorSettingsButton
        );
        return card;
    }

    private VBox buildHeatmapSettingsCard(NetworkPanel networkPanel, Button preprocessButton) {
        VBox card = createCard("Heatmap Aggregation");

        Label binCaption = new Label("Time Bin (minutes)");
        binCaption.getStyleClass().add("field-caption");

        int currentBinSeconds = getOnEdt(networkPanel::getHeatmapTimeBinSeconds);
        Slider binSlider = new Slider(1, 120, Math.max(1, currentBinSeconds / 60.0));
        Label binValue = new Label(String.format(Locale.ROOT, "%.1f min", binSlider.getValue()));
        binValue.getStyleClass().add("mono-value");
        TextField binInput = new TextField(String.format(Locale.ROOT, "%.1f", binSlider.getValue()));
        binInput.setId("heatmap-bin-minutes");
        binInput.setPrefWidth(74);
        Label binInputUnit = new Label("min");
        HBox binInputRow = new HBox(6, binInput, binInputUnit);
        binInputRow.setAlignment(Pos.CENTER_LEFT);

        Label stagedHint = new Label("Choose an interval, then click Apply Bin + Preprocess below.");
        stagedHint.getStyleClass().add("hint");
        stagedHint.setWrapText(true);

        Runnable syncInputToSlider = () -> {
            try {
                double minutes = Double.parseDouble(binInput.getText().trim());
                double clamped = Math.max(binSlider.getMin(), Math.min(binSlider.getMax(), minutes));
                binSlider.setValue(clamped);
            } catch (NumberFormatException ignored) {
                binInput.setText(String.format(Locale.ROOT, "%.1f", binSlider.getValue()));
            }
        };

        binSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            double minutes = newValue.doubleValue();
            binValue.setText(String.format(Locale.ROOT, "%.1f min", minutes));
            binInput.setText(String.format(Locale.ROOT, "%.1f", minutes));
        });

        binInput.setOnAction(e -> syncInputToSlider.run());
        binInput.focusedProperty().addListener((obs, oldValue, focused) -> {
            if (!focused) {
                syncInputToSlider.run();
            }
        });

        preprocessButton.setOnAction(e -> {
            syncInputToSlider.run();
            int seconds = (int) Math.round(binSlider.getValue() * 60.0);
            runOnEdt(() -> networkPanel.setHeatmapTimeBinSeconds(seconds));
            requestHeatmapPreprocessing(networkPanel, true);
        });

        Label hint = new Label("Default is 10 minutes. Higher values smooth out short-term spikes.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);

        card.getChildren().addAll(binCaption, binSlider, binInputRow, binValue, stagedHint, preprocessButton, hint);
        return card;
    }

    private VBox buildHeatmapColorsCard(
            String title,
            NetworkPanel.VisualizationMode paletteMode,
            NetworkPanel networkPanel
    ) {
        VBox card = createCard(title);

        Label lowCaption = new Label("Low value color");
        lowCaption.getStyleClass().add("field-caption");
        ColorPicker lowPicker = new ColorPicker(toFx(getOnEdt(() ->
                switch (paletteMode) {
                    case FLOW_HEATMAP, PT_FLOW_HEATMAP -> networkPanel.getFlowHeatmapLowColor();
                    case SPEED_RATIO_HEATMAP -> networkPanel.getSpeedRatioHeatmapLowColor();
                    default -> networkPanel.getSpeedHeatmapLowColor();
                }
        )));
        lowPicker.setMaxWidth(Double.MAX_VALUE);

        Label highCaption = new Label("High value color");
        highCaption.getStyleClass().add("field-caption");
        ColorPicker highPicker = new ColorPicker(toFx(getOnEdt(() ->
                switch (paletteMode) {
                    case FLOW_HEATMAP, PT_FLOW_HEATMAP -> networkPanel.getFlowHeatmapHighColor();
                    case SPEED_RATIO_HEATMAP -> networkPanel.getSpeedRatioHeatmapHighColor();
                    default -> networkPanel.getSpeedHeatmapHighColor();
                }
        )));
        highPicker.setMaxWidth(Double.MAX_VALUE);

        lowPicker.setOnAction(e -> runOnEdt(() -> {
            switch (paletteMode) {
                case FLOW_HEATMAP, PT_FLOW_HEATMAP -> networkPanel.setFlowHeatmapLowColor(toAwt(lowPicker.getValue()));
                case SPEED_RATIO_HEATMAP -> networkPanel.setSpeedRatioHeatmapLowColor(toAwt(lowPicker.getValue()));
                default -> networkPanel.setSpeedHeatmapLowColor(toAwt(lowPicker.getValue()));
            }
        }));

        highPicker.setOnAction(e -> runOnEdt(() -> {
            switch (paletteMode) {
                case FLOW_HEATMAP, PT_FLOW_HEATMAP -> networkPanel.setFlowHeatmapHighColor(toAwt(highPicker.getValue()));
                case SPEED_RATIO_HEATMAP -> networkPanel.setSpeedRatioHeatmapHighColor(toAwt(highPicker.getValue()));
                default -> networkPanel.setSpeedHeatmapHighColor(toAwt(highPicker.getValue()));
            }
        }));

        card.getChildren().addAll(lowCaption, lowPicker, highCaption, highPicker);
        return card;
    }

    private VBox buildPtStopBubbleSizeCard(NetworkPanel networkPanel) {
        VBox card = createCard("PT Stop Bubble Size");

        Label minCaption = new Label("Minimum radius (px)");
        minCaption.getStyleClass().add("field-caption");
        Slider minSlider = new Slider(1.0, 40.0, getOnEdt(networkPanel::getPtStopBubbleMinRadiusPixels));
        Label minValue = new Label(String.format(Locale.ROOT, "%.1f px", minSlider.getValue()));
        minValue.getStyleClass().add("mono-value");

        Label maxCaption = new Label("Maximum radius (px)");
        maxCaption.getStyleClass().add("field-caption");
        Slider maxSlider = new Slider(2.0, 64.0, getOnEdt(networkPanel::getPtStopBubbleMaxRadiusPixels));
        Label maxValue = new Label(String.format(Locale.ROOT, "%.1f px", maxSlider.getValue()));
        maxValue.getStyleClass().add("mono-value");

        minSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            double min = newValue.doubleValue();
            if (maxSlider.getValue() < min) {
                maxSlider.setValue(min);
            }
            runOnEdt(() -> networkPanel.setPtStopBubbleMinRadiusPixels(min));
            minValue.setText(String.format(Locale.ROOT, "%.1f px", min));
            maxValue.setText(String.format(Locale.ROOT, "%.1f px", maxSlider.getValue()));
        });

        maxSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            double max = newValue.doubleValue();
            if (max < minSlider.getValue()) {
                minSlider.setValue(max);
            }
            runOnEdt(() -> networkPanel.setPtStopBubbleMaxRadiusPixels(max));
            maxValue.setText(String.format(Locale.ROOT, "%.1f px", max));
            minValue.setText(String.format(Locale.ROOT, "%.1f px", minSlider.getValue()));
        });

        Label hint = new Label("Controls bubble size scaling for PT stop volumes.");
        hint.getStyleClass().add("hint");
        hint.setWrapText(true);

        card.getChildren().addAll(
                minCaption,
                minSlider,
                minValue,
                maxCaption,
                maxSlider,
                maxValue,
                hint
        );
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

        Label transparencyLabel = new Label("Road transparency");
        transparencyLabel.getStyleClass().add("field-caption");
        Slider transparency = new Slider(0, 100, 100 * (1 - getOnEdt(networkPanel::getRoadOpacity)));
        transparency.setId("road-transparency");
        transparency.setBlockIncrement(5);
        transparency.setMaxWidth(Double.MAX_VALUE);
        transparency.setTooltip(new javafx.scene.control.Tooltip(
                "0%: solid roads. 100%: hidden roads. Vehicles and the map stay visible."));
        Label transparencyValue = new Label();
        transparencyValue.setMinWidth(45);
        transparencyValue.textProperty().bind(transparency.valueProperty().asString("%.0f%%"));
        HBox transparencyRow = new HBox(8, transparency, transparencyValue);
        HBox.setHgrow(transparency, Priority.ALWAYS);
        transparencyRow.setAlignment(Pos.CENTER_LEFT);
        transparency.valueProperty().addListener((obs, oldValue, newValue) -> {
            double opacity = 1 - newValue.doubleValue() / 100;
            SwingUtilities.invokeLater(() -> networkPanel.setRoadOpacity(opacity));
        });

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

        card.getChildren().addAll(themeToggle, roadLabel, roadPicker, transparencyLabel, transparencyRow, bgLabel, bgPicker, vehicleSettingsButton);
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

    private static void setVisibleManaged(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static List<VisualizationLayerChoice> buildVisualizationChoices() {
        List<VisualizationLayerChoice> choices = new ArrayList<>();
        for (NetworkPanel.VisualizationMode mode : NetworkPanel.VisualizationMode.values()) {
            choices.add(new VisualizationLayerChoice(mode.toString(), mode, false));
        }
        choices.add(new VisualizationLayerChoice("Network Editor", NetworkPanel.VisualizationMode.VEHICLES, true));
        return choices;
    }

    private static VisualizationLayerChoice choiceForMode(NetworkPanel.VisualizationMode mode) {
        NetworkPanel.VisualizationMode safeMode = mode == null ? NetworkPanel.VisualizationMode.VEHICLES : mode;
        return new VisualizationLayerChoice(safeMode.toString(), safeMode, false);
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
            networkPanel.setVisualizationMode(parseVisualizationMode(config.uiVisualizationMode(), NetworkPanel.VisualizationMode.VEHICLES));
            networkPanel.setShowQueues(config.uiShowQueues());
            networkPanel.setBidirectionalOffset(config.uiBidirectionalOffset());
            networkPanel.setLaneWidthMeters(config.uiLaneWidthMeters());
            networkPanel.setShowBottleneck(config.uiShowBottleneck());
            networkPanel.setBottleneckDivisor(config.uiBottleneckDivisor());
            networkPanel.setHeatmapTimeBinSeconds(config.uiHeatmapTimeBinSeconds());
            networkPanel.setFlowHeatmapLowColor(parseHexColor(config.uiFlowColorLow(), networkPanel.getFlowHeatmapLowColor()));
            networkPanel.setFlowHeatmapHighColor(parseHexColor(config.uiFlowColorHigh(), networkPanel.getFlowHeatmapHighColor()));
            networkPanel.setSpeedHeatmapLowColor(parseHexColor(config.uiSpeedColorLow(), networkPanel.getSpeedHeatmapLowColor()));
            networkPanel.setSpeedHeatmapHighColor(parseHexColor(config.uiSpeedColorHigh(), networkPanel.getSpeedHeatmapHighColor()));
            networkPanel.setSpeedRatioHeatmapLowColor(parseHexColor(config.uiSpeedRatioColorLow(), networkPanel.getSpeedRatioHeatmapLowColor()));
            networkPanel.setSpeedRatioHeatmapHighColor(parseHexColor(config.uiSpeedRatioColorHigh(), networkPanel.getSpeedRatioHeatmapHighColor()));
            networkPanel.setKeepVehiclesVisibleWhenZoomedOut(config.uiKeepVehiclesVisibleWhenZoomedOut());
            networkPanel.setMinVehicleLengthPixels(config.uiMinVehicleLengthPixels());
            networkPanel.setMinVehicleWidthPixels(config.uiMinVehicleWidthPixels());
            networkPanel.setZoomDetail(config.uiDetailStartLanePixels(), config.uiDetailFullLanePixels(), config.uiOverviewVehicleCoverage());

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
            return PanelVideoRecorder.Quality.PRESENTATION_4K;
        }
        return parseRecordingQuality(config.recordingDefaultQuality(), PanelVideoRecorder.Quality.PRESENTATION_4K);
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

    private static String detectRendererMode() {
        try {
            Class<?> pipelineClass = Class.forName("com.sun.prism.GraphicsPipeline");
            Method getPipeline = pipelineClass.getMethod("getPipeline");
            Object pipeline = getPipeline.invoke(null);
            if (pipeline == null) {
                return "CPU";
            }

            String name = pipeline.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (name.contains("sw")) {
                return "CPU";
            }
            return "GPU";
        } catch (Exception ex) {
            return "Unknown";
        }
    }

    private static List<DisplayScreenOption> buildScreenOptions() {
        List<DisplayScreenOption> options = new ArrayList<>();
        List<Screen> screens = Screen.getScreens();
        for (int i = 0; i < screens.size(); i++) {
            Screen screen = screens.get(i);
            Rectangle2D bounds = screen.getVisualBounds();
            String label = "Screen " + (i + 1) + " (" + (int) bounds.getWidth() + "x" + (int) bounds.getHeight() + ")";
            options.add(new DisplayScreenOption(label, screen));
        }
        return options;
    }

    private static DisplayScreenOption closestScreenOption(List<DisplayScreenOption> options, Stage stage) {
        if (options.isEmpty()) {
            return null;
        }

        double centerX = stage.getX() + Math.max(1.0, stage.getWidth()) * 0.5;
        double centerY = stage.getY() + Math.max(1.0, stage.getHeight()) * 0.5;
        List<Screen> candidates = Screen.getScreensForRectangle(centerX, centerY, 1, 1);
        Screen screen = candidates.isEmpty() ? Screen.getPrimary() : candidates.get(0);

        for (DisplayScreenOption option : options) {
            if (option.screen().equals(screen)) {
                return option;
            }
        }
        return options.getFirst();
    }

    private static DisplayScreenOption largestScreenOption(List<DisplayScreenOption> options) {
        if (options == null || options.isEmpty()) {
            return null;
        }

        DisplayScreenOption largest = options.getFirst();
        double largestArea = area(largest.screen().getVisualBounds());
        for (int i = 1; i < options.size(); i++) {
            DisplayScreenOption candidate = options.get(i);
            double area = area(candidate.screen().getVisualBounds());
            if (area > largestArea) {
                largest = candidate;
                largestArea = area;
            }
        }
        return largest;
    }

    private static double area(Rectangle2D bounds) {
        return Math.max(0.0, bounds.getWidth()) * Math.max(0.0, bounds.getHeight());
    }

    private static void moveStageToScreen(Stage stage, Screen screen, boolean preserveSize) {
        Rectangle2D bounds = screen.getVisualBounds();
        double targetWidth;
        double targetHeight;
        if (preserveSize) {
            targetWidth = Math.min(Math.max(stage.getMinWidth(), stage.getWidth()), bounds.getWidth());
            targetHeight = Math.min(Math.max(stage.getMinHeight(), stage.getHeight()), bounds.getHeight());
            stage.setWidth(targetWidth);
            stage.setHeight(targetHeight);
        } else {
            targetWidth = Math.min(Math.max(stage.getMinWidth(), bounds.getWidth() * 0.86), bounds.getWidth());
            targetHeight = Math.min(Math.max(stage.getMinHeight(), bounds.getHeight() * 0.86), bounds.getHeight());
            stage.setWidth(targetWidth);
            stage.setHeight(targetHeight);
        }

        double x = bounds.getMinX() + (bounds.getWidth() - targetWidth) * 0.5;
        double y = bounds.getMinY() + (bounds.getHeight() - targetHeight) * 0.5;
        stage.setX(x);
        stage.setY(y);
    }

    private void requestHeatmapPreprocessing(NetworkPanel networkPanel, boolean force) {
        if (heatmapPreprocessInProgress) {
            return;
        }
        boolean alreadyPrepared = getOnEdt(networkPanel::isHeatmapPreparedForCurrentSettings);
        if (!force && alreadyPrepared) {
            return;
        }

        heatmapPreprocessInProgress = true;
        runOnEdt(networkPanel::markHeatmapPreprocessingStarted);

        CompletableFuture.runAsync(() -> {
            networkPanel.preprocessHeatmapsForCurrentSettings();
        }, heatmapPreprocessExecutor).whenComplete((ignored, error) -> Platform.runLater(() -> {
            runOnEdt(networkPanel::markHeatmapPreprocessingFinished);
            heatmapPreprocessInProgress = false;

            if (error != null) {
                Alert alert = new Alert(Alert.AlertType.ERROR, error.getMessage());
                alert.setHeaderText("Heatmap preprocessing failed");
                alert.showAndWait();
            }
        }));
    }

    private static NetworkPanel.VisualizationMode parseVisualizationMode(
            String raw,
            NetworkPanel.VisualizationMode fallback
    ) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return NetworkPanel.VisualizationMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private static java.awt.Color parseHexColor(String raw, java.awt.Color fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.length() != 6) {
            return fallback;
        }
        try {
            int rgb = Integer.parseInt(value, 16);
            return new java.awt.Color(rgb);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private AnimationTimer createAnimationTimer(PlaybackController playbackController, NetworkPanel networkPanel, PlaybackUiState uiState) {
        return new AnimationTimer() {
            private long previousNanos = -1L;
            private final java.util.concurrent.atomic.AtomicBoolean presentationFramePending =
                    new java.util.concurrent.atomic.AtomicBoolean();

            @Override
            public void handle(long now) {
                if (editorActive || editorLoading) { previousNanos = now; return; }
                if (previousNanos < 0) {
                    previousNanos = now;
                    return;
                }

                if (heatmapPreprocessInProgress) {
                    previousNanos = now;
                    runOnEdt(networkPanel::repaint);
                    return;
                }

                if (videoRecorder.isPresentationRecording()) {
                    previousNanos = now;
                    if (!presentationFramePending.compareAndSet(false, true)) return;
                    runOnEdt(() -> {
                        try {
                            if (videoRecorder.isPresentationRecording() && !networkPanel.isRenderingSuspended()) {
                                // Advance by one video frame, regardless of how long rendering takes.
                                videoRecorder.captureFrame(networkPanel);
                                playbackController.tick(videoRecorder.presentationFrameSeconds());
                                networkPanel.repaint();
                            }
                        } finally {
                            Platform.runLater(() -> {
                                uiState.syncing()[0] = true;
                                uiState.timeSlider().setValue(playbackController.getCurrentTime());
                                uiState.syncing()[0] = false;
                                uiState.timeValue().setText(TimeFormat.hhmmss(playbackController.getCurrentTime()));
                                uiState.playPauseButton().setText(playbackController.isPlaying() ? "Pause" : "Play");
                                presentationFramePending.set(false);
                            });
                        }
                    });
                    return;
                }
                // Finish an in-flight presentation frame before returning to real-time playback.
                if (presentationFramePending.get()) {
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
                    if (networkPanel.isRenderingSuspended()) {
                        return;
                    }
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

    private record DisplayScreenOption(String label, Screen screen) {
        @Override
        public String toString() {
            return label;
        }
    }

    private record VisualizationLayerChoice(
            String label,
            NetworkPanel.VisualizationMode mode,
            boolean openNetworkEditor
    ) {
        @Override
        public String toString() {
            return label;
        }
    }
}
