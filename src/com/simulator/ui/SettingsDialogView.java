package com.simulator.ui;

import com.simulator.model.RequestType;
import com.simulator.model.SimulationMode;
import com.simulator.model.SimulationSettings;
import com.simulator.policy.PolicyType;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The settings modal.
 *
 * <p>Works on a draft: the spinners are seeded from {@code settings} and only
 * written back when Save is pressed, so closing with the cross discards the
 * edits. Reset Defaults re-populates the form and likewise leaves the live
 * settings untouched.
 */
public final class SettingsDialogView {

    private static final int CARD_WIDTH = 500;
    private static final int CARD_HEIGHT = 680;

    private SettingsDialogView() {
    }

    /**
     * Shows the dialog and returns when it closes.
     *
     * @param settings     the live settings, written only on Save
     * @param onSaved      run after a successful save, so the dashboard can
     *                     refresh what it advertises
     * @param exportScenario / {@code importScenario} / {@code exportLogCsv} let
     *                     the dialog drive the file actions without knowing
     *                     where they end up
     */
    public static Stage show(SimulationSettings settings,
                             Runnable onSaved,
                             Runnable exportScenario,
                             Runnable importScenario,
                             Runnable exportLogCsv) {
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initStyle(StageStyle.TRANSPARENT);
        dialog.setTitle("Settings");

        final double[] dragOffset = new double[]{0, 0};

        // Main card container
        VBox card = new VBox(0);
        card.setStyle(
                "-fx-background-color: #0b1329;" +
                "-fx-border-color: #334155;" +
                "-fx-border-width: 1.5;" +
                "-fx-background-radius: 12;" +
                "-fx-border-radius: 12;" +
                "-fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.75), 25, 0.35, 0, 8);"
        );
        card.setMaxWidth(CARD_WIDTH);
        card.setPrefWidth(CARD_WIDTH);

        // Header bar (draggable)
        HBox headerBar = new HBox(12);
        headerBar.setAlignment(Pos.CENTER_LEFT);
        headerBar.setPadding(new Insets(16, 20, 16, 20));
        headerBar.setCursor(javafx.scene.Cursor.MOVE);
        headerBar.setStyle("-fx-background-color: #0f172a; -fx-background-radius: 12 12 0 0;");

        headerBar.setOnMousePressed(e -> {
            dragOffset[0] = e.getSceneX();
            dragOffset[1] = e.getSceneY();
        });
        headerBar.setOnMouseDragged(e -> {
            dialog.setX(e.getScreenX() - dragOffset[0]);
            dialog.setY(e.getScreenY() - dragOffset[1]);
        });

        Label titleLabel = new Label("SETTINGS");
        titleLabel.setFont(Font.font("System", FontWeight.BOLD, 17));
        titleLabel.setTextFill(Color.WHITE);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        Button closeIconButton = new Button("✕");
        closeIconButton.setPrefSize(28, 28);
        closeIconButton.setMinSize(28, 28);
        closeIconButton.setMaxSize(28, 28);
        closeIconButton.setFont(Font.font("System", FontWeight.BOLD, 12));
        closeIconButton.setTextFill(Color.web("#94a3b8"));
        closeIconButton.setStyle(
                "-fx-background-color: rgba(255, 255, 255, 0.05);" +
                "-fx-background-radius: 14;" +
                "-fx-cursor: hand;" +
                "-fx-padding: 0;"
        );
        closeIconButton.setOnMouseEntered(e -> closeIconButton.setStyle(
                "-fx-background-color: #ef4444;" +
                "-fx-text-fill: white;" +
                "-fx-background-radius: 14;" +
                "-fx-cursor: hand;" +
                "-fx-padding: 0;"
        ));
        closeIconButton.setOnMouseExited(e -> closeIconButton.setStyle(
                "-fx-background-color: rgba(255, 255, 255, 0.05);" +
                "-fx-text-fill: #94a3b8;" +
                "-fx-background-radius: 14;" +
                "-fx-cursor: hand;" +
                "-fx-padding: 0;"
        ));
        closeIconButton.setOnAction(e -> dialog.close());

        headerBar.getChildren().addAll(titleLabel, headerSpacer, closeIconButton);

        // Content area with padding
        VBox content = new VBox(16);
        content.setPadding(new Insets(20));
        content.setStyle("-fx-background-color: transparent;");

        // Helper to create styled sections
        Function<Label, VBox> createSection = (Label sectionLabel) -> {
            sectionLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
            sectionLabel.setTextFill(Color.web("#94a3b8"));
            VBox section = new VBox(8, sectionLabel);
            section.setStyle("-fx-background-color: transparent;");
            return section;
        };

        // Helper to create styled spinners
        Function<int[], Spinner<Integer>> createSpinner = (params) -> {
            int min = params[0];
            int max = params[1];
            int initial = params[2];
            Spinner<Integer> spinner = new Spinner<>(min, max, initial);
            spinner.setEditable(true);
            spinner.setPrefWidth(110);
            spinner.setStyle(
                    "-fx-background-color: #1e293b;" +
                    "-fx-text-fill: white;" +
                    "-fx-border-color: #334155;" +
                    "-fx-background-radius: 6;" +
                    "-fx-border-radius: 6;"
            );
            spinner.getEditor().setStyle("-fx-background-color: transparent; -fx-text-fill: white;");
            return spinner;
        };

        // --- Policy Selection ---
        Label policyLabel = new Label("Rate Limit Policy");
        VBox policySection = createSection.apply(policyLabel);
        ComboBox<PolicyType> policyCombo =
                createComboBox(PolicyType.values(), settings.getPolicy());
        policySection.getChildren().add(policyCombo);

        // --- Simulation Mode ---
        // Placed next to the policy because it answers the same question: what
        // shape of traffic is the limiter being shown?
        Label modeLabel = new Label("Simulation Mode");
        VBox modeSection = createSection.apply(modeLabel);

        ComboBox<SimulationMode> modeCombo =
                createComboBox(SimulationMode.values(), settings.getSimulationMode());

        Label modeNote = new Label(
                "Single Client serves one client per tick, round-robin. "
                        + "Multiple Clients put every registered client on the wire "
                        + "in the same tick.");
        modeNote.setWrapText(true);
        modeNote.setFont(Font.font("System", 9));
        modeNote.setTextFill(Color.web("#64748b"));
        modeSection.getChildren().addAll(modeCombo, modeNote);

        // --- Window Size ---
        Label windowLabel = new Label("Window Size (seconds)");
        VBox windowSection = createSection.apply(windowLabel);
        Spinner<Integer> windowSpinner = createSpinner.apply(new int[]{1, 3600, settings.getWindowSeconds()});
        windowSection.getChildren().add(windowSpinner);

        // --- Violation Thresholds ---
        Label thresholdsLabel = new Label("Violation Score Thresholds");
        VBox thresholdsSection = createSection.apply(thresholdsLabel);

        Spinner<Integer> warningSpinner = createSpinner.apply(new int[]{1, 1000, settings.getWarningThreshold()});
        Spinner<Integer> highSpinner = createSpinner.apply(new int[]{1, 1000, settings.getHighThreshold()});
        Spinner<Integer> criticalSpinner = createSpinner.apply(new int[]{1, 1000, settings.getCriticalThreshold()});

        Label warningLabel = new Label("WARNING (≥)");
        warningLabel.setTextFill(Color.web("#f59e0b"));
        warningLabel.setFont(Font.font("System", 10));

        Label highLabel = new Label("HIGH (≥)");
        highLabel.setTextFill(Color.web("#f97316"));
        highLabel.setFont(Font.font("System", 10));

        Label criticalLabel = new Label("CRITICAL (≥)");
        criticalLabel.setTextFill(Color.web("#ef4444"));
        criticalLabel.setFont(Font.font("System", 10));

        HBox thresholdsRow = new HBox(16,
                new VBox(4, warningLabel, warningSpinner),
                new VBox(4, highLabel, highSpinner),
                new VBox(4, criticalLabel, criticalSpinner)
        );
        thresholdsSection.getChildren().add(thresholdsRow);

        // --- Severity Multipliers ---
        Label severityLabel = new Label("Request Type Severity Multipliers");
        VBox severitySection = createSection.apply(severityLabel);

        HBox severityRow = new HBox(12);
        Map<RequestType, Spinner<Integer>> severitySpinners = new EnumMap<>(RequestType.class);
        for (RequestType type : RequestType.values()) {
            Spinner<Integer> spinner = createSpinner.apply(new int[]{1, 10, settings.getSeverityMultipliers().getOrDefault(type, 1)});
            severitySpinners.put(type, spinner);
            Label typeLabel = new Label(type.name());
            typeLabel.setTextFill(Color.web("#94a3b8"));
            typeLabel.setFont(Font.font("System", 10));
            severityRow.getChildren().add(new VBox(4, typeLabel, spinner));
        }
        severitySection.getChildren().add(severityRow);

        // --- Decay Amount ---
        Label decayLabel = new Label("Violation Score Decay (per accepted request)");
        VBox decaySection = createSection.apply(decayLabel);
        Spinner<Integer> decaySpinner = createSpinner.apply(new int[]{0, 50, settings.getDecayAmount()});
        decaySection.getChildren().add(decaySpinner);

        // --- Custom Thresholds ---
        Label customThresholdsLabel = new Label("Custom Thresholds (per request type, overrides auto)");
        VBox customThresholdsSection = createSection.apply(customThresholdsLabel);

        HBox customThresholdsRow = new HBox(12);
        Map<RequestType, Spinner<Integer>> customThresholdSpinners = new EnumMap<>(RequestType.class);
        for (RequestType type : RequestType.values()) {
            Spinner<Integer> spinner = createSpinner.apply(new int[]{0, 10000, settings.getCustomThresholds().getOrDefault(type, 0)});
            customThresholdSpinners.put(type, spinner);
            Label typeLabel = new Label(type.name());
            typeLabel.setTextFill(Color.web("#94a3b8"));
            typeLabel.setFont(Font.font("System", 10));
            customThresholdsRow.getChildren().add(new VBox(4, typeLabel, spinner));
        }
        customThresholdsSection.getChildren().add(customThresholdsRow);

        Label customNote = new Label("0 = auto-calculated from history");
        customNote.setFont(Font.font("System", 9));
        customNote.setTextFill(Color.web("#64748b"));
        customThresholdsSection.getChildren().add(customNote);

        // --- Scenario File ---
        // Placed above the Save row because import replaces live state outright
        // rather than editing the draft, so it must not sit next to Save in a
        // way that implies the two compose.
        Label scenarioLabel = new Label("Scenario File");
        VBox scenarioSection = createSection.apply(scenarioLabel);

        Label scenarioNote = new Label(
                "Export or import the settings and the client registry, including each "
                        + "client's telemetry. The request log is not included; it can be "
                        + "exported separately as CSV.");
        scenarioNote.setWrapText(true);
        scenarioNote.setFont(Font.font("System", 9));
        scenarioNote.setTextFill(Color.web("#64748b"));

        Button exportScenarioButton = makeDialogButton("Export scenario...", "#2563eb", 150);
        exportScenarioButton.setOnAction(e -> exportScenario.run());

        Button importScenarioButton = makeDialogButton("Import scenario...", "#f59e0b", 150);
        importScenarioButton.setOnAction(e -> importScenario.run());

        Button exportLogButton = makeDialogButton("Export log (CSV)...", "#1e293b", 150);
        exportLogButton.setTextFill(Color.web("#94a3b8"));
        exportLogButton.setStyle(
                "-fx-background-color: #1e293b;"
                        + "-fx-text-fill: #94a3b8;"
                        + "-fx-border-color: #334155;"
                        + "-fx-border-width: 1;"
                        + "-fx-background-radius: 8;"
                        + "-fx-cursor: hand;"
        );
        exportLogButton.setOnAction(e -> exportLogCsv.run());

        HBox scenarioRow = new HBox(10, exportScenarioButton, importScenarioButton, exportLogButton);
        scenarioSection.getChildren().addAll(scenarioNote, scenarioRow);

        // --- Buttons ---
        HBox buttonRow = new HBox(12);
        buttonRow.setAlignment(Pos.CENTER_RIGHT);
        buttonRow.setPadding(new Insets(8, 0, 0, 0));

        Button resetButton = new Button("Reset Defaults");
        resetButton.setPrefWidth(130);
        resetButton.setPrefHeight(34);
        resetButton.setFont(Font.font("System", FontWeight.BOLD, 11));
        resetButton.setTextFill(Color.web("#94a3b8"));
        resetButton.setStyle(
                "-fx-background-color: #1e293b;" +
                "-fx-background-radius: 8;" +
                "-fx-cursor: hand;" +
                "-fx-border-color: #334155;" +
                "-fx-border-width: 1;"
        );
        resetButton.setOnMouseEntered(e -> resetButton.setStyle(
                "-fx-background-color: #334155;" +
                "-fx-text-fill: white;" +
                "-fx-background-radius: 8;" +
                "-fx-cursor: hand;" +
                "-fx-border-color: #334155;" +
                "-fx-border-width: 1;"
        ));
        resetButton.setOnMouseExited(e -> resetButton.setStyle(
                "-fx-background-color: #1e293b;" +
                "-fx-text-fill: #94a3b8;" +
                "-fx-background-radius: 8;" +
                "-fx-cursor: hand;" +
                "-fx-border-color: #334155;" +
                "-fx-border-width: 1;"
        ));
        // Reset only re-populates the form. It must not touch the live settings,
        // or closing the dialog with the cross would silently apply a reset the
        // user never saved.
        resetButton.setOnAction(e -> {
            policyCombo.setValue(SimulationSettings.DEFAULT_POLICY);
            modeCombo.setValue(SimulationSettings.DEFAULT_SIMULATION_MODE);
            windowSpinner.getValueFactory().setValue(SimulationSettings.DEFAULT_WINDOW_SECONDS);
            warningSpinner.getValueFactory().setValue(SimulationSettings.DEFAULT_WARNING_THRESHOLD);
            highSpinner.getValueFactory().setValue(SimulationSettings.DEFAULT_HIGH_THRESHOLD);
            criticalSpinner.getValueFactory().setValue(SimulationSettings.DEFAULT_CRITICAL_THRESHOLD);
            decaySpinner.getValueFactory().setValue(SimulationSettings.DEFAULT_DECAY_AMOUNT);
            for (RequestType type : RequestType.values()) {
                severitySpinners.get(type).getValueFactory()
                        .setValue(SimulationSettings.DEFAULT_SEVERITY_MULTIPLIERS.get(type));
                customThresholdSpinners.get(type).getValueFactory()
                        .setValue(SimulationSettings.DEFAULT_CUSTOM_THRESHOLD);
            }
        });

        Button saveButton = new Button("Save");
        saveButton.setPrefWidth(100);
        saveButton.setPrefHeight(34);
        saveButton.setFont(Font.font("System", FontWeight.BOLD, 11));
        saveButton.setTextFill(Color.WHITE);
        saveButton.setStyle(
                "-fx-background-color: #2563eb;" +
                "-fx-background-radius: 8;" +
                "-fx-cursor: hand;"
        );
        saveButton.setOnMouseEntered(e -> saveButton.setStyle(
                "-fx-background-color: #1d4ed8;" +
                "-fx-background-radius: 8;" +
                "-fx-cursor: hand;"
        ));
        saveButton.setOnMouseExited(e -> saveButton.setStyle(
                "-fx-background-color: #2563eb;" +
                "-fx-background-radius: 8;" +
                "-fx-cursor: hand;"
        ));
        saveButton.setOnAction(e -> {
            int warning = warningSpinner.getValue();
            int high = highSpinner.getValue();
            int critical = criticalSpinner.getValue();

            // A level is derived by descending comparisons, so an out of order
            // trio would report CRITICAL for almost any score.
            if (warning > high || high > critical) {
                NoticeDialog.error(
                        "Thresholds must increase: WARNING <= HIGH <= CRITICAL.\n"
                                + "Got " + warning + " / " + high + " / " + critical + "."
                );
                return;
            }

            settings.setPolicy(policyCombo.getValue());
            settings.setSimulationMode(modeCombo.getValue());
            settings.setWindowSeconds(windowSpinner.getValue());
            settings.setWarningThreshold(warning);
            settings.setHighThreshold(high);
            settings.setCriticalThreshold(critical);
            settings.setDecayAmount(decaySpinner.getValue());
            for (RequestType type : RequestType.values()) {
                settings.getSeverityMultipliers().put(type, severitySpinners.get(type).getValue());
                settings.getCustomThresholds().put(type, customThresholdSpinners.get(type).getValue());
            }
            onSaved.run();
            dialog.close();
        });

        buttonRow.getChildren().addAll(resetButton, saveButton);

        // Add all sections to content
        content.getChildren().addAll(
                policySection,
                modeSection,
                windowSection,
                thresholdsSection,
                severitySection,
                decaySection,
                customThresholdsSection,
                scenarioSection,
                buttonRow
        );

        // Scroll pane for content
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-background: transparent;" +
                "-fx-border-color: transparent;" +
                "-fx-padding: 0;"
        );

        // Add to card
        card.getChildren().addAll(headerBar, scrollPane);

        // The card keeps its natural size and is inset inside a larger scene, so
        // the drop shadow has somewhere to render instead of being clipped.
        card.setMaxHeight(CARD_HEIGHT);
        card.setPrefHeight(CARD_HEIGHT);

        StackPane root = new StackPane(card);
        StackPane.setMargin(card, new Insets(NoticeDialog.SHADOW_MARGIN));
        root.setStyle("-fx-background-color: transparent;");
        Scene scene = new Scene(
                root,
                CARD_WIDTH + NoticeDialog.SHADOW_MARGIN * 2,
                CARD_HEIGHT + NoticeDialog.SHADOW_MARGIN * 2
        );
        scene.setFill(Color.TRANSPARENT);

        var cssUrl = SettingsDialogView.class.getResource("style.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        dialog.setScene(scene);
        dialog.showAndWait();
        return dialog;
    }

    /**
     * One consistently styled choice box, seeded with the live setting so the
     * draft starts from what is in effect.
     */
    private static <T> ComboBox<T> createComboBox(T[] choices, T selected) {
        ComboBox<T> combo = new ComboBox<>(FXCollections.observableArrayList(choices));
        combo.setValue(selected);
        combo.setPrefWidth(280);
        combo.setStyle(
                "-fx-background-color: #1e293b;" +
                        "-fx-text-fill: white;" +
                        "-fx-border-color: #334155;" +
                        "-fx-background-radius: 6;" +
                        "-fx-border-radius: 6;"
        );
        return combo;
    }

    /** One consistently styled button for the settings dialog. */
    private static Button makeDialogButton(String text, String background, int width) {
        Button button = new Button(text);
        button.setPrefWidth(width);
        button.setPrefHeight(30);
        button.setFont(Font.font("System", FontWeight.BOLD, 10));
        button.setTextFill(Color.WHITE);
        button.setStyle(
                "-fx-background-color: " + background + ";"
                        + "-fx-background-radius: 8;"
                        + "-fx-cursor: hand;"
        );
        return button;
    }
}
