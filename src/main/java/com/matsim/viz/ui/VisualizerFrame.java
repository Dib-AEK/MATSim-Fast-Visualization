package com.matsim.viz.ui;

import com.matsim.viz.domain.ColorMode;
import com.matsim.viz.engine.PlaybackController;
import com.matsim.viz.engine.SimulationModel;

import javax.swing.JButton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JSlider;
import javax.swing.JOptionPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public final class VisualizerFrame extends JFrame {
    private final PlaybackController playbackController;
    private final JSlider timeSlider;
    private final JLabel timeLabel;
    private final JLabel speedLabel;
    private final JButton playPauseButton;

    private boolean sliderDrivenByPlayback;

    public VisualizerFrame(SimulationModel model, PlaybackController playbackController) {
        super("MATSim Fast Visualization");
        this.playbackController = playbackController;

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());

        NetworkPanel networkPanel = new NetworkPanel(model, playbackController);
        JPanel sidePanel = buildFilterPanel(model, networkPanel);
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, networkPanel, sidePanel);
        splitPane.setResizeWeight(1.0);
        splitPane.setOneTouchExpandable(true);
        splitPane.setDividerLocation(1160);
        networkPanel.setMinimumSize(new Dimension(700, 400));
        sidePanel.setMinimumSize(new Dimension(250, 300));
        add(splitPane, BorderLayout.CENTER);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT));

        playPauseButton = new JButton("Play");
        playPauseButton.addActionListener(e -> {
            playbackController.togglePlaying();
            refreshControlState();
        });

        int duration = (int) Math.max(1, Math.round(playbackController.getEndTime() - playbackController.getStartTime()));
        timeSlider = new JSlider(0, duration, 0);
        timeSlider.setPaintTicks(false);
        timeSlider.setPaintLabels(false);
        timeSlider.setPreferredSize(new java.awt.Dimension(420, 24));
        timeSlider.addChangeListener(e -> {
            if (sliderDrivenByPlayback) {
                return;
            }
            double target = playbackController.getStartTime() + timeSlider.getValue();
            playbackController.seek(target);
        });

        int initialSpeed = (int) Math.round(Math.max(1.0, Math.min(600.0, playbackController.getSpeedMultiplier())));
        JSlider speedSlider = new JSlider(1, 600, initialSpeed);
        speedSlider.setPreferredSize(new Dimension(220, 24));
        speedSlider.addChangeListener(e -> {
            playbackController.setSpeedMultiplier(speedSlider.getValue());
            refreshSpeedLabel(speedSlider.getValue());
        });
        speedLabel = new JLabel();
        refreshSpeedLabel(initialSpeed);

        JComboBox<ColorMode> colorCombo = new JComboBox<>(ColorMode.values());
        colorCombo.setSelectedItem(ColorMode.DEFAULT);
        colorCombo.addActionListener(e -> {
            ColorMode mode = (ColorMode) colorCombo.getSelectedItem();
            if (mode != null) {
                networkPanel.setColorMode(mode);
            }
        });

        JCheckBox queuesCheckbox = new JCheckBox("Show Link Queues", false);
        queuesCheckbox.addActionListener(e -> networkPanel.setShowQueues(queuesCheckbox.isSelected()));

        timeLabel = new JLabel();

        controls.add(playPauseButton);
        controls.add(new JLabel("Time"));
        controls.add(timeSlider);
        controls.add(timeLabel);
        controls.add(new JLabel("Speed"));
        controls.add(speedSlider);
        controls.add(speedLabel);
        controls.add(new JLabel("Color"));
        controls.add(colorCombo);
        controls.add(queuesCheckbox);

        add(controls, BorderLayout.NORTH);

        final long[] previousTickNanos = {System.nanoTime()};
        Timer timer = new Timer(33, e -> {
            long now = System.nanoTime();
            double deltaSeconds = (now - previousTickNanos[0]) / 1_000_000_000.0;
            previousTickNanos[0] = now;
            playbackController.tick(Math.min(0.20, Math.max(0.001, deltaSeconds)));
        });
        timer.setCoalesce(true);
        timer.start();

        playbackController.addListener(() -> SwingUtilities.invokeLater(() -> {
            refreshControlState();
            networkPanel.repaint();
        }));

        refreshControlState();
        setSize(1400, 900);
        setLocationRelativeTo(null);
    }

    private JPanel buildFilterPanel(SimulationModel model, NetworkPanel networkPanel) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(380, 0));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        networkPanel.setSelectedLinkModes(new LinkedHashSet<>(model.availableLinkModes()));
        networkPanel.setSelectedTripModes(new LinkedHashSet<>(model.availableTripModes()));

        JPanel linkModesPanel = createModeCheckboxPanel(
                "Network Modes",
                model.availableLinkModes(),
                selected -> networkPanel.setSelectedLinkModes(selected)
        );

        JPanel tripModesPanel = createModeCheckboxPanel(
                "Trip Modes",
                model.availableTripModes(),
                selected -> networkPanel.setSelectedTripModes(selected)
        );

        JPanel modeColorPanel = createModeColorPanel(model, networkPanel);
        JPanel purposeColorPanel = createPurposeColorPanel(model, networkPanel);
        JPanel sexColorPanel = createSexColorPanel(networkPanel);
        JPanel ageGroupPanel = createAgeGroupPanel(networkPanel);
        JPanel vehicleGeometryPanel = createVehicleGeometryPanel(networkPanel);

        content.add(linkModesPanel);
        content.add(tripModesPanel);
        content.add(modeColorPanel);
        content.add(ageGroupPanel);
        content.add(sexColorPanel);
        content.add(vehicleGeometryPanel);
        content.add(purposeColorPanel);

        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createModeCheckboxPanel(String title, List<String> modes, Consumer<LinkedHashSet<String>> onSelectionChanged) {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBorder(BorderFactory.createTitledBorder(title));
        outer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 260));

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton selectAllButton = new JButton("All");
        JButton clearButton = new JButton("None");
        selectAllButton.setMargin(new java.awt.Insets(2, 6, 2, 6));
        clearButton.setMargin(new java.awt.Insets(2, 6, 2, 6));
        header.add(selectAllButton);
        header.add(clearButton);

        JPanel checksPanel = new JPanel();
        checksPanel.setLayout(new BoxLayout(checksPanel, BoxLayout.Y_AXIS));

        Map<String, JCheckBox> checkBoxes = new LinkedHashMap<>();
        for (String mode : modes) {
            JCheckBox box = new JCheckBox(mode, true);
            box.setToolTipText(mode);
            box.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
            checkBoxes.put(mode, box);
            checksPanel.add(box);
            box.addActionListener(e -> publishSelections(checkBoxes, onSelectionChanged));
        }

        selectAllButton.addActionListener(e -> {
            checkBoxes.values().forEach(box -> box.setSelected(true));
            publishSelections(checkBoxes, onSelectionChanged);
        });

        clearButton.addActionListener(e -> {
            checkBoxes.values().forEach(box -> box.setSelected(false));
            publishSelections(checkBoxes, onSelectionChanged);
        });

        publishSelections(checkBoxes, onSelectionChanged);

        outer.add(header, BorderLayout.NORTH);
        outer.add(new JScrollPane(checksPanel), BorderLayout.CENTER);
        return outer;
    }

    private JPanel createModeColorPanel(SimulationModel model, NetworkPanel networkPanel) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Trip Mode Colors"));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 260));

        JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));

        for (String mode : model.availableTripModes()) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            JLabel label = new JLabel(mode);
            label.setPreferredSize(new Dimension(220, 20));

            JButton colorButton = createColorButton(networkPanel.getModeColor(mode));
            colorButton.addActionListener(e -> {
                Color selected = JColorChooser.showDialog(this, "Choose Color for " + mode, networkPanel.getModeColor(mode));
                if (selected != null) {
                    networkPanel.setModeColor(mode, selected);
                    colorButton.setBackground(selected);
                }
            });

            row.add(label);
            row.add(colorButton);
            listPanel.add(row);
        }

        panel.add(new JScrollPane(listPanel), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createVehicleGeometryPanel(NetworkPanel networkPanel) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Vehicle Geometry"));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));

        JPanel carRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JLabel carLabel = new JLabel("Car-like length (m)");
        carLabel.setPreferredSize(new Dimension(140, 20));
        JSlider carSlider = new JSlider(2, 20, (int) Math.round(networkPanel.getCarLikeVehicleLengthMeters()));
        carSlider.setPreferredSize(new Dimension(150, 22));
        JLabel carValue = new JLabel(String.format("%.1f", networkPanel.getCarLikeVehicleLengthMeters()));
        carSlider.addChangeListener(e -> {
            double value = carSlider.getValue();
            networkPanel.setCarLikeVehicleLengthMeters(value);
            carValue.setText(String.format("%.1f", value));
        });
        carRow.add(carLabel);
        carRow.add(carSlider);
        carRow.add(carValue);

        JPanel bikeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JLabel bikeLabel = new JLabel("Bike length (m)");
        bikeLabel.setPreferredSize(new Dimension(140, 20));
        JSlider bikeSlider = new JSlider(1, 10, (int) Math.round(networkPanel.getBikeVehicleLengthMeters()));
        bikeSlider.setPreferredSize(new Dimension(150, 22));
        JLabel bikeValue = new JLabel(String.format("%.1f", networkPanel.getBikeVehicleLengthMeters()));
        bikeSlider.addChangeListener(e -> {
            double value = bikeSlider.getValue();
            networkPanel.setBikeVehicleLengthMeters(value);
            bikeValue.setText(String.format("%.1f", value));
        });
        bikeRow.add(bikeLabel);
        bikeRow.add(bikeSlider);
        bikeRow.add(bikeValue);

        panel.add(carRow);
        panel.add(bikeRow);
        return panel;
    }

    private JPanel createPurposeColorPanel(SimulationModel model, NetworkPanel networkPanel) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Trip Purpose Colors"));
        panel.setMaximumSize(new Dimension(280, 140));

        JComboBox<String> purposeCombo = new JComboBox<>(model.availableTripPurposes().toArray(String[]::new));
        purposeCombo.setPreferredSize(new Dimension(170, 24));

        JButton purposeColorButton = createColorButton(
                purposeCombo.getItemCount() == 0 ? new Color(0x666666) : networkPanel.getTripPurposeColor((String) purposeCombo.getSelectedItem())
        );

        purposeCombo.addActionListener(e -> {
            String purpose = (String) purposeCombo.getSelectedItem();
            if (purpose != null) {
                purposeColorButton.setBackground(networkPanel.getTripPurposeColor(purpose));
            }
        });

        JButton chooseButton = new JButton("Set");
        chooseButton.addActionListener(e -> {
            String purpose = (String) purposeCombo.getSelectedItem();
            if (purpose == null) {
                return;
            }
            Color selected = JColorChooser.showDialog(this, "Choose Color for " + purpose, networkPanel.getTripPurposeColor(purpose));
            if (selected != null) {
                networkPanel.setTripPurposeColor(purpose, selected);
                purposeColorButton.setBackground(selected);
            }
        });

        panel.add(new JLabel("Purpose"));
        panel.add(purposeCombo);
        panel.add(purposeColorButton);
        panel.add(chooseButton);
        return panel;
    }

    private JPanel createSexColorPanel(NetworkPanel networkPanel) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        panel.setBorder(BorderFactory.createTitledBorder("Sex Colors"));
        panel.setMaximumSize(new Dimension(280, 140));

        List<String> categories = new ArrayList<>(networkPanel.getSexCategories());
        if (categories.isEmpty()) {
            categories = Arrays.asList("male", "female", "other", "unknown");
        }

        JComboBox<String> categoryCombo = new JComboBox<>(categories.toArray(String[]::new));
        categoryCombo.setPreferredSize(new Dimension(170, 24));

        JButton categoryColorButton = createColorButton(
                categoryCombo.getItemCount() == 0 ? new Color(0x666666) : networkPanel.getSexColor((String) categoryCombo.getSelectedItem())
        );

        categoryCombo.addActionListener(e -> {
            String category = (String) categoryCombo.getSelectedItem();
            if (category != null) {
                categoryColorButton.setBackground(networkPanel.getSexColor(category));
            }
        });

        JButton chooseButton = new JButton("Set");
        chooseButton.addActionListener(e -> {
            String category = (String) categoryCombo.getSelectedItem();
            if (category == null) {
                return;
            }
            Color selected = JColorChooser.showDialog(this, "Choose Color for " + category, networkPanel.getSexColor(category));
            if (selected != null) {
                networkPanel.setSexColor(category, selected);
                categoryColorButton.setBackground(selected);
            }
        });

        panel.add(new JLabel("Category"));
        panel.add(categoryCombo);
        panel.add(categoryColorButton);
        panel.add(chooseButton);
        return panel;
    }

    private JPanel createAgeGroupPanel(NetworkPanel networkPanel) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Age Groups"));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 320));

        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        JTextField binsField = new JTextField(ageBoundsToText(networkPanel.getAgeBinUpperBounds()), 16);
        JButton applyBinsButton = new JButton("Apply Bins");
        applyBinsButton.setToolTipText("Comma-separated upper bounds (e.g. 17,35,59)");
        header.add(new JLabel("Upper bounds"));
        header.add(binsField);
        header.add(applyBinsButton);

        JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));

        Runnable refreshAgeRows = () -> {
            listPanel.removeAll();
            for (int i = 0; i < networkPanel.getAgeGroupCount(); i++) {
                int ageGroupIndex = i;
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
                JLabel label = new JLabel(networkPanel.getAgeGroupLabel(ageGroupIndex));
                label.setPreferredSize(new Dimension(220, 20));

                JButton colorButton = createColorButton(networkPanel.getAgeGroupColor(ageGroupIndex));
                colorButton.addActionListener(e -> {
                    Color selected = JColorChooser.showDialog(
                            this,
                            "Choose Color for age " + networkPanel.getAgeGroupLabel(ageGroupIndex),
                            networkPanel.getAgeGroupColor(ageGroupIndex)
                    );
                    if (selected != null) {
                        networkPanel.setAgeGroupColor(ageGroupIndex, selected);
                        colorButton.setBackground(selected);
                    }
                });

                row.add(label);
                row.add(colorButton);
                listPanel.add(row);
            }
            listPanel.revalidate();
            listPanel.repaint();
        };

        applyBinsButton.addActionListener(e -> {
            try {
                int[] bounds = parseAgeBounds(binsField.getText());
                networkPanel.setAgeBinUpperBounds(bounds);
                binsField.setText(ageBoundsToText(networkPanel.getAgeBinUpperBounds()));
                refreshAgeRows.run();
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Invalid age bins", JOptionPane.WARNING_MESSAGE);
            }
        });

        refreshAgeRows.run();

        panel.add(header, BorderLayout.NORTH);
        panel.add(new JScrollPane(listPanel), BorderLayout.CENTER);
        return panel;
    }

    private static int[] parseAgeBounds(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Please provide at least one positive age bound.");
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

    private JButton createColorButton(Color color) {
        JButton button = new JButton();
        button.setPreferredSize(new Dimension(28, 20));
        button.setBackground(color);
        button.setOpaque(true);
        button.setBorder(BorderFactory.createLineBorder(new Color(0x333333)));
        return button;
    }

    private void publishSelections(Map<String, JCheckBox> checkBoxes, Consumer<LinkedHashSet<String>> onSelectionChanged) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (Map.Entry<String, JCheckBox> entry : checkBoxes.entrySet()) {
            if (entry.getValue().isSelected()) {
                selected.add(entry.getKey());
            }
        }
        onSelectionChanged.accept(selected);
    }

    private void refreshControlState() {
        playPauseButton.setText(playbackController.isPlaying() ? "Pause" : "Play");
        timeLabel.setText(TimeFormat.hhmmss(playbackController.getCurrentTime()));

        sliderDrivenByPlayback = true;
        int value = (int) Math.round(playbackController.getCurrentTime() - playbackController.getStartTime());
        timeSlider.setValue(Math.max(timeSlider.getMinimum(), Math.min(timeSlider.getMaximum(), value)));
        sliderDrivenByPlayback = false;
    }

    private void refreshSpeedLabel(int speed) {
        speedLabel.setText("x" + speed + " (max 1h/6s)");
    }
}
