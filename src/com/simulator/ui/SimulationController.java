package com.simulator.ui;

import com.simulator.model.Client;
import com.simulator.model.DailyAcceptedIndex;
import com.simulator.model.Log;
import com.simulator.model.Request;
import com.simulator.model.RequestType;
import com.simulator.model.ViolationLevel;
import com.simulator.policy.FixedWindowPolicy;
import com.simulator.policy.RatePolicy;
import com.simulator.policy.SlidingWindowPolicy;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.shape.SVGPath;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class SimulationController {

    public enum PolicyType {
        FIXED_WINDOW("Fixed Window"),
        SLIDING_WINDOW("Sliding Window");

        private final String displayName;
        PolicyType(String displayName) { this.displayName = displayName; }
        public String toString() { return displayName; }
    }

    private static final String HISTORY_RESOURCE = "/com/simulator/files/logs.csv";
    private static final int HISTORY_READ_BUFFER = 1 << 16;

    /** A client may exceed its busiest day by this factor before it is limited. */
    private static final double THRESHOLD_HEADROOM = 1.5;

    /** Upper bound on a history derived limit, however busy the client has been. */
    private static final int THRESHOLD_CEILING = 200;

    /**
     * How many log entries stay resident.
     *
     * <p>The bundled history is half a million rows, which is around 80 MB of
     * {@link Log} objects for a list the dashboard only ever scrolls through
     * near the top of. Trimming the tail costs nothing functionally: the daily
     * tallies that actually drive the rate limits are held in
     * {@link #acceptedIndex}, which is cumulative and ignores trimming.
     */
    private static final int MAX_RETAINED_LOGS = 50_000;

    private static final PolicyType DEFAULT_POLICY = PolicyType.FIXED_WINDOW;
    private static final int DEFAULT_WINDOW_SECONDS = 10;
    private static final int DEFAULT_WARNING_THRESHOLD = 20;
    private static final int DEFAULT_HIGH_THRESHOLD = 50;
    private static final int DEFAULT_CRITICAL_THRESHOLD = 100;
    private static final int DEFAULT_DECAY_AMOUNT = 1;

    /** Custom thresholds default to 0, meaning "derive from the client's history". */
    private static final int DEFAULT_CUSTOM_THRESHOLD = 0;

    private static final Map<RequestType, Integer> DEFAULT_SEVERITY_MULTIPLIERS;

    static {
        Map<RequestType, Integer> severity = new EnumMap<>(RequestType.class);
        severity.put(RequestType.READ, 1);
        severity.put(RequestType.WRITE, 2);
        severity.put(RequestType.LOGIN, 3);
        severity.put(RequestType.PAYMENT, 2);
        DEFAULT_SEVERITY_MULTIPLIERS = Map.copyOf(severity);
    }
    private static final DateTimeFormatter HISTORY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /** One parsed history row, before it is turned into a {@link Log}. */
    private record HistoryRow(String clientName, RequestType type,
                              LocalDateTime time, String status) {}

    @FXML private TextField ClientBox;
    @FXML private Label totalRequestLabel;
    @FXML private Label violationLabel;
    @FXML private ListView<Client> ClientList;
    @FXML private ListView<Log> LogList;
    @FXML private ComboBox<Client> clientChoiceBox;
    @FXML private ComboBox<RequestType> typeChoiceBox;
    @FXML private Button settingsButton;
    @FXML private Label policyDisplayLabel;
    @FXML private Label activeWindowLabel;
    @FXML private Label rateLimitLabel;
    @FXML private Button autoSimButton;
    @FXML private LineChart<String, Number> trafficChart;
    @FXML private NumberAxis trafficYAxis;
    @FXML private Circle Blinking;

    // Settings
    private PolicyType selectedPolicy = PolicyType.FIXED_WINDOW;
    private int windowSeconds = 10;
    private Map<RequestType, Integer> customThresholds = new EnumMap<>(RequestType.class);
    private Map<RequestType, Integer> severityMultipliers = new EnumMap<>(RequestType.class);
    private int warningThreshold = 20;
    private int highThreshold = 50;
    private int criticalThreshold = 100;
    private int decayAmount = 1;

    // Auto Simulation
    private Timeline autoSimTimeline;
    private boolean isAutoSimRunning = false;
    private int autoSimClientIndex = 0;
    private final Random random = new Random();

    private final ObservableList<Client> clients =
            FXCollections.observableArrayList(
                    client -> new Observable[]{
                            client.totalRequestProperty(),
                            client.violationCountProperty(),
                            client.violationScoreProperty(),
                            client.levelProperty()
                    }
            );

    private final ObservableList<Request> requests =
            FXCollections.observableArrayList();

    private final ObservableList<Log> logs =
            FXCollections.observableArrayList();

    /**
     * Incremental tally of accepted requests, so that deriving a rate limit from
     * the request history does not require rescanning every log on every
     * request.
     *
     * <p>Unlike {@link #logs} this is cumulative: it keeps counting accepted
     * traffic even after the display history has been trimmed, because a
     * trimmed entry still happened and still counts towards a client's busiest
     * day. Only a full reset clears it.
     */
    private final DailyAcceptedIndex acceptedIndex = new DailyAcceptedIndex();

    /**
     * Set while {@link #logs} is changed in bulk, when the index has to be
     * maintained by hand instead of by the change listener.
     */
    private boolean suppressIndexSync = false;

    private boolean historyLoading = false;

    private final ObservableList<RequestType> types =
            FXCollections.observableArrayList();

    private final XYChart.Series<String, Number> trafficSeries =
            new XYChart.Series<>();

    private int validRequestsThisSecond = 0;
    private int secondCounter = 0;

    @FXML
    public void initialize() {

        FadeTransition blinkingAnimation =
                new FadeTransition(
                        javafx.util.Duration.millis(600),
                        Blinking
                );

        blinkingAnimation.setFromValue(1.0);
        blinkingAnimation.setToValue(0.2);
        blinkingAnimation.setCycleCount(Animation.INDEFINITE);
        blinkingAnimation.setAutoReverse(true);
        blinkingAnimation.play();

        ClientList.setPlaceholder(
                createTopPlaceholder("No client yet!")
        );

        LogList.setPlaceholder(
                createTopPlaceholder("No log")
        );

        types.addAll(
                RequestType.READ,
                RequestType.LOGIN,
                RequestType.PAYMENT,
                RequestType.WRITE
        );

        initializeDefaultSettings();
        updatePolicyDisplay();

        LogList.setItems(logs);
        ClientList.setItems(clients);
        clientChoiceBox.setItems(clients);
        typeChoiceBox.setItems(types);
        typeChoiceBox.setValue(RequestType.LOGIN);
        typeChoiceBox.getSelectionModel().selectedItemProperty().addListener((obs, oldType, newType) -> updateRateLimitDisplay(clientChoiceBox.getValue(), newType));
        clientChoiceBox.getSelectionModel().selectedItemProperty().addListener((obs, oldClient, newClient) -> updateRateLimitDisplay(newClient, typeChoiceBox.getValue()));

        logs.addListener((ListChangeListener<Log>) change -> {
            if (suppressIndexSync) {
                return;
            }
            while (change.next()) {
                if (change.wasRemoved()) {
                    change.getRemoved().forEach(acceptedIndex::remove);
                }
                if (change.wasAdded()) {
                    change.getAddedSubList().forEach(acceptedIndex::add);
                }
            }
        });

        LogList.setCellFactory(listView -> new LogCell());

        LogList.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-control-inner-background: transparent;" +
                        "-fx-background-insets: 0;" +
                        "-fx-padding: 4;"
        );

        ClientList.setCellFactory(listView -> new ClientCardCell());

        ClientList.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-control-inner-background: transparent;" +
                        "-fx-background-insets: 0;" +
                        "-fx-padding: 4;"
        );

        ClientList.getSelectionModel()
                .selectedItemProperty()
                .addListener((obs, oldClient, newClient) -> {
                    if (newClient != null) {
                        clientChoiceBox
                                .getSelectionModel()
                                .select(newClient);
                    }
                });

        totalRequestLabel.textProperty().bind(
                Bindings.createStringBinding(
                        () -> String.valueOf(
                                clients.stream()
                                        .mapToInt(Client::getTotalRequest)
                                        .sum()
                        ),
                        clients
                )
        );

        violationLabel.textProperty().bind(
                Bindings.createStringBinding(
                        () -> String.valueOf(
                                clients.stream()
                                        .mapToInt(Client::getViolationCount)
                                        .sum()
                        ),
                        clients
                )
        );

        setupTrafficChart();
        startTrafficTimer();
        loadLogsFromCsv();
    }

    /**
     * Reads the request history on a background thread and merges it in one
     * batch. Parsing half a million rows takes the better part of a second, so
     * doing it inline froze the application before its window was even shown,
     * and appending row by row fired one list change event per row.
     */
    private void loadLogsFromCsv() {
        if (historyLoading) {
            return;
        }
        historyLoading = true;
        setHistoryPlaceholder("Loading request history...");

        Thread loader = new Thread(this::readHistoryAsync, "log-history-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private void readHistoryAsync() {
        List<HistoryRow> rows;
        try {
            rows = readHistoryRows();
        } catch (Exception e) {
            Platform.runLater(() -> onHistoryLoadFailed(e));
            return;
        }
        Platform.runLater(() -> mergeHistoryRows(rows));
    }
    /** Background half: file I/O, field splitting and timestamp parsing. */
    private List<HistoryRow> readHistoryRows() throws IOException {
        List<HistoryRow> rows = new ArrayList<>();

        try (InputStream is = SimulationController.class.getResourceAsStream(HISTORY_RESOURCE)) {
            if (is == null) {
                throw new FileNotFoundException("Missing classpath resource " + HISTORY_RESOURCE);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8), HISTORY_READ_BUFFER)) {

                reader.readLine();

                String line;
                while ((line = reader.readLine()) != null) {
                    HistoryRow row = parseHistoryRow(line);
                    if (row != null) {
                        rows.add(row);
                    }
                }
            }
        }

        return rows;
    }

    /** Returns {@code null} for a row that cannot be understood, so one bad line cannot abort the load. */
    private HistoryRow parseHistoryRow(String line) {
        String[] parts = line.split(",");
        if (parts.length < 4) {
            return null;
        }

        String clientName = parts[0].trim();
        String typeStr = parts[1].trim();
        String timeStr = parts[2].trim();
        String status = parts[3].trim();

        if (clientName.isEmpty() || typeStr.isEmpty() || timeStr.isEmpty()) {
            return null;
        }

        RequestType type;
        try {
            type = RequestType.valueOf(typeStr);
        } catch (IllegalArgumentException unknownType) {
            return null;
        }

        LocalDateTime time;
        try {
            time = LocalDateTime.parse(timeStr, HISTORY_TIME_FORMAT);
        } catch (DateTimeParseException unparseableTime) {
            return null;
        }

        return new HistoryRow(clientName, type, time, status);
    }

    /** Application thread half: resolve clients, build the logs, publish in one change. */
    private void mergeHistoryRows(List<HistoryRow> rows) {
        Map<String, Client> clientsByName = new HashMap<>();
        for (Client existing : clients) {
            clientsByName.putIfAbsent(existing.getName(), existing);
        }

        List<Client> discovered = new ArrayList<>();
        List<Log> history = new ArrayList<>(rows.size() + logs.size());

        for (HistoryRow row : rows) {
            Client client = clientsByName.get(row.clientName());
            if (client == null) {
                client = new Client(row.clientName());
                clientsByName.put(row.clientName(), client);
                discovered.add(client);
            }
            history.add(new Log(new Request(client, row.type(), row.time()), row.status()));
        }

        history.addAll(logs);
        history.sort(Comparator.comparing(Log::getTime).reversed());

        suppressIndexSync = true;
        try {
            clients.addAll(discovered);
            acceptedIndex.clear();
            for (Log log : history) {
                acceptedIndex.add(log);
            }
            // The index sees the whole dataset; only the display is capped.
            logs.setAll(recentOnly(history));
        } finally {
            suppressIndexSync = false;
        }

        updateRateLimitDisplay(clientChoiceBox.getValue(), typeChoiceBox.getValue());
        historyLoading = false;
        setHistoryPlaceholder(null);
    }

    private void onHistoryLoadFailed(Exception cause) {
        historyLoading = false;
        setHistoryPlaceholder("Request history unavailable: " + cause.getMessage());
    }

    /** The newest {@link #MAX_RETAINED_LOGS} entries of a time-descending list. */
    private static List<Log> recentOnly(List<Log> timeDescending) {
        if (timeDescending.size() <= MAX_RETAINED_LOGS) {
            return timeDescending;
        }
        return new ArrayList<>(timeDescending.subList(0, MAX_RETAINED_LOGS));
    }

    /**
     * Records a live log entry, keeping only the most recent
     * {@link #MAX_RETAINED_LOGS}. The index is deliberately left alone: a
     * trimmed request still happened, so it must keep counting towards that
     * client's busiest day.
     */
    private void recordLiveLog(Log log) {
        logs.addFirst(log);
        if (logs.size() > MAX_RETAINED_LOGS) {
            suppressIndexSync = true;
            try {
                logs.remove(MAX_RETAINED_LOGS, logs.size());
            } finally {
                suppressIndexSync = false;
            }
        }
    }

    /** Shows a message in the log list, or restores the normal empty state when {@code text} is null. */
    private void setHistoryPlaceholder(String text) {
        if (text == null) {
            LogList.setPlaceholder(createTopPlaceholder("No log"));
        } else {
            LogList.setPlaceholder(createTopPlaceholder(text));
        }
    }

    private void initializeDefaultSettings() {
        for (RequestType type : RequestType.values()) {
            customThresholds.put(type, DEFAULT_CUSTOM_THRESHOLD);
        }
        severityMultipliers.putAll(DEFAULT_SEVERITY_MULTIPLIERS);
    }

    private void updatePolicyDisplay() {
        if (policyDisplayLabel != null) {
            policyDisplayLabel.setText("Policy: " + selectedPolicy);
        }
        updateRateLimitDisplay(clientChoiceBox.getValue(), typeChoiceBox.getValue());
    }

    private void updateRateLimitDisplay(Client client, RequestType type) {
        if (rateLimitLabel != null && type != null) {
            rateLimitLabel.setText(resolveThreshold(client, type) + " / window");
        }
        if (activeWindowLabel != null) {
            activeWindowLabel.setText(windowSeconds + "S");
        }
    }

    /**
     * The one place a request limit is decided.
     *
     * <p>The label and the policy both go through here, so what the dashboard
     * advertises is necessarily what gets enforced. A custom threshold of 0
     * means "derive it from this client's own history", otherwise the custom
     * value wins.
     */
    private int resolveThreshold(Client client, RequestType type) {
        int custom = customThresholds.getOrDefault(type, 0);
        if (custom > 0) {
            return custom;
        }
        if (client == null) {
            return getDefaultThreshold(type);
        }
        return autoThresholdFor(client, type);
    }

    /**
     * Limit implied by a client's own history: half again its busiest day,
     * never below the built-in default and never above {@link #THRESHOLD_CEILING}.
     */
    private int autoThresholdFor(Client client, RequestType type) {
        int maxDaily = acceptedIndex.maxDailyAccepted(client, type);
        if (maxDaily <= 0) {
            return getDefaultThreshold(type);
        }
        int headroom = (int) Math.ceil(maxDaily * THRESHOLD_HEADROOM);
        return Math.max(getDefaultThreshold(type), Math.min(headroom, THRESHOLD_CEILING));
    }

    @FXML
    private void onSettings() {
        showSettingsDialog();
    }

    /**
     * A modal dialog runs a nested event loop, so a running simulation would
     * keep generating traffic behind it and move the very limits the user is
     * editing. Pause it for the duration and put it back afterwards.
     */
    private void showSettingsDialog() {
        boolean resumeAfterDialog = isAutoSimRunning;
        if (resumeAfterDialog) {
            stopAutoSimulation();
        }
        try {
            buildAndShowSettingsDialog();
        } finally {
            if (resumeAfterDialog) {
                startAutoSimulation();
            }
        }
    }

    private void showValidationError(String message) {
        Stage error = new Stage();
        error.initModality(Modality.WINDOW_MODAL);
        error.initStyle(StageStyle.UNDECORATED);

        Label text = new Label(message);
        text.setWrapText(true);
        text.setFont(Font.font("System", 12));
        text.setTextFill(Color.web("#f87171"));
        text.setPadding(new Insets(18, 22, 12, 22));

        Button dismiss = new Button("OK");
        dismiss.setPrefWidth(90);
        dismiss.setPrefHeight(32);
        dismiss.setFont(Font.font("System", FontWeight.BOLD, 11));
        dismiss.setTextFill(Color.WHITE);
        dismiss.setStyle("-fx-background-color: #ef4444; -fx-background-radius: 8; -fx-cursor: hand;");
        dismiss.setOnAction(e -> error.close());
        HBox actions = new HBox(dismiss);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setPadding(new Insets(0, 22, 18, 22));

        VBox root = new VBox(text, actions);
        root.setStyle(
                "-fx-background-color: #0b1329;"
                        + "-fx-border-color: #ef4444;"
                        + "-fx-border-width: 1.2;"
                        + "-fx-background-radius: 10;"
        );

        Scene scene = new Scene(root, 380, 150);
        var cssUrl = SimulationController.class.getResource("style.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }
        error.setScene(scene);
        error.showAndWait();
    }

    private void buildAndShowSettingsDialog() {
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
        card.setMaxWidth(500);
        card.setPrefWidth(500);

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
        java.util.function.Function<Label, VBox> createSection = (Label sectionLabel) -> {
            sectionLabel.setFont(Font.font("System", FontWeight.BOLD, 12));
            sectionLabel.setTextFill(Color.web("#94a3b8"));
            VBox section = new VBox(8, sectionLabel);
            section.setStyle("-fx-background-color: transparent;");
            return section;
        };

        // Helper to create styled spinners
        // Helper to create styled spinners
        java.util.function.Function<int[], Spinner<Integer>> createSpinner = (params) -> {
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

        // Helper to create styled combo box
        java.util.function.Supplier<ComboBox<PolicyType>> createPolicyCombo = () -> {
            ComboBox<PolicyType> combo = new ComboBox<>(FXCollections.observableArrayList(PolicyType.values()));
            combo.setValue(selectedPolicy);
            combo.setPrefWidth(280);
            combo.setStyle(
                    "-fx-background-color: #1e293b;" +
                    "-fx-text-fill: white;" +
                    "-fx-border-color: #334155;" +
                    "-fx-background-radius: 6;" +
                    "-fx-border-radius: 6;"
            );
            return combo;
        };

        // --- Policy Selection ---
        Label policyLabel = new Label("Rate Limit Policy");
        VBox policySection = createSection.apply(policyLabel);
        ComboBox<PolicyType> policyCombo = createPolicyCombo.get();
        policySection.getChildren().add(policyCombo);

        // --- Window Size ---
        Label windowLabel = new Label("Window Size (seconds)");
        VBox windowSection = createSection.apply(windowLabel);
        Spinner<Integer> windowSpinner = createSpinner.apply(new int[]{1, 3600, windowSeconds});
        windowSection.getChildren().add(windowSpinner);

        // --- Violation Thresholds ---
        Label thresholdsLabel = new Label("Violation Score Thresholds");
        VBox thresholdsSection = createSection.apply(thresholdsLabel);

        Spinner<Integer> warningSpinner = createSpinner.apply(new int[]{1, 1000, warningThreshold});
        Spinner<Integer> highSpinner = createSpinner.apply(new int[]{1, 1000, highThreshold});
        Spinner<Integer> criticalSpinner = createSpinner.apply(new int[]{1, 1000, criticalThreshold});

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
            Spinner<Integer> spinner = createSpinner.apply(new int[]{1, 10, severityMultipliers.getOrDefault(type, 1)});
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
        Spinner<Integer> decaySpinner = createSpinner.apply(new int[]{0, 50, decayAmount});
        decaySection.getChildren().add(decaySpinner);

        // --- Custom Thresholds ---
        Label customThresholdsLabel = new Label("Custom Thresholds (per request type, overrides auto)");
        VBox customThresholdsSection = createSection.apply(customThresholdsLabel);

        HBox customThresholdsRow = new HBox(12);
        Map<RequestType, Spinner<Integer>> customThresholdSpinners = new EnumMap<>(RequestType.class);
        for (RequestType type : RequestType.values()) {
            Spinner<Integer> spinner = createSpinner.apply(new int[]{0, 10000, customThresholds.getOrDefault(type, 0)});
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
        // Reset only re-populates the form. It must not touch the controller's
        // own settings, or closing the dialog with the cross would silently
        // apply a reset the user never saved.
        resetButton.setOnAction(e -> {
            policyCombo.setValue(DEFAULT_POLICY);
            windowSpinner.getValueFactory().setValue(DEFAULT_WINDOW_SECONDS);
            warningSpinner.getValueFactory().setValue(DEFAULT_WARNING_THRESHOLD);
            highSpinner.getValueFactory().setValue(DEFAULT_HIGH_THRESHOLD);
            criticalSpinner.getValueFactory().setValue(DEFAULT_CRITICAL_THRESHOLD);
            decaySpinner.getValueFactory().setValue(DEFAULT_DECAY_AMOUNT);
            for (RequestType type : RequestType.values()) {
                severitySpinners.get(type).getValueFactory()
                        .setValue(DEFAULT_SEVERITY_MULTIPLIERS.get(type));
                customThresholdSpinners.get(type).getValueFactory()
                        .setValue(DEFAULT_CUSTOM_THRESHOLD);
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
                showValidationError(
                        "Thresholds must increase: WARNING <= HIGH <= CRITICAL.\n"
                                + "Got " + warning + " / " + high + " / " + critical + "."
                );
                return;
            }

            selectedPolicy = policyCombo.getValue();
            windowSeconds = windowSpinner.getValue();
            warningThreshold = warning;
            highThreshold = high;
            criticalThreshold = critical;
            decayAmount = decaySpinner.getValue();
            for (RequestType type : RequestType.values()) {
                severityMultipliers.put(type, severitySpinners.get(type).getValue());
                customThresholds.put(type, customThresholdSpinners.get(type).getValue());
            }
            updatePolicyDisplay();
            dialog.close();
        });

        buttonRow.getChildren().addAll(resetButton, saveButton);

        // Add all sections to content
        content.getChildren().addAll(
                policySection,
                windowSection,
                thresholdsSection,
                severitySection,
                decaySection,
                customThresholdsSection,
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

        // Scene with transparent background - use card as root, size to content
        StackPane root = new StackPane(card);
        root.setStyle("-fx-background-color: transparent;");
        Scene scene = new Scene(root, 500, 680);
        scene.setFill(Color.TRANSPARENT);

        var cssUrl = SimulationController.class.getResource("style.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private Node createTopPlaceholder(String text) {

        Label label = new Label(text);

        label.setStyle(
                "-fx-text-fill: #94a3b8;" +
                        "-fx-font-size: 14;"
        );

        VBox wrapper = new VBox(label);
        wrapper.setAlignment(Pos.TOP_CENTER);
        wrapper.setPadding(new Insets(10));

        return wrapper;
    }

    @FXML
    private void onAddClient() {

        String name = ClientBox.getText().trim();

        if (name.isEmpty()) return;

        // The registry is keyed by name, and the history loader resolves names
        // the same way, so a second entry with a name already in use would be
        // invisible to its own history. Surface the existing client instead.
        Client existing = findClientByName(name);
        if (existing != null) {
            ClientList.getSelectionModel().select(existing);
            ClientList.scrollTo(existing);
            clientChoiceBox.getSelectionModel().select(existing);
            ClientBox.clear();
            return;
        }

        clients.add(new Client(name));
        ClientBox.clear();
    }

    private Client findClientByName(String name) {
        for (Client client : clients) {
            if (client.getName().equalsIgnoreCase(name)) {
                return client;
            }
        }
        return null;
    }

    private int getDefaultThreshold(RequestType type) {
        return switch (type) {
            case LOGIN -> 5;
            case PAYMENT -> 10;
            case WRITE -> 20;
            case READ -> 50;
        };
    }

    private RatePolicy getClientPolicy(Client client, RequestType type) {
        int threshold = resolveThreshold(client, type);
        Duration window = Duration.ofSeconds(windowSeconds);

        Map<RequestType, Integer> currentSeverity = new EnumMap<>(RequestType.class);
        currentSeverity.putAll(severityMultipliers);

        int[] violationThresholds = {warningThreshold, highThreshold, criticalThreshold};
        int currentDecay = decayAmount;

        if (selectedPolicy == PolicyType.SLIDING_WINDOW) {
            return new SlidingWindowPolicy(threshold, window, currentSeverity, violationThresholds, currentDecay);
        }
        return new FixedWindowPolicy(threshold, window, currentSeverity, violationThresholds, currentDecay);
    }

    /**
     * Drops requests that no policy could ever count again.
     *
     * <p>Both policies only look back at most one window from the newest request,
     * and the request under evaluation is always the newest one, so anything older
     * than {@code now - window} is dead weight. Without this the list grows for
     * the whole session and every evaluation rescans the entire history.
     */
    private void evictRequestsOutsideWindow(LocalDateTime now) {
        LocalDateTime cutoff = now.minus(Duration.ofSeconds(windowSeconds));
        requests.removeIf(request -> request.getTime().isBefore(cutoff));
    }

    @FXML
    private void onSingleRequest() {

        Client client =
                clientChoiceBox
                        .getSelectionModel()
                        .getSelectedItem();

        if (client == null) return;

        RequestType type =
                typeChoiceBox
                        .getSelectionModel()
                        .getSelectedItem();

        if (type == null) return;

        evictRequestsOutsideWindow(LocalDateTime.now());

        Request request = new Request(client, type);

        requests.add(request);

        RatePolicy clientPolicy = getClientPolicy(client, type);
        RatePolicy.Decision decision = clientPolicy.evaluate(client, type, requests);

        // A blocked request leaves no trace in the window, so a client that is
        // over its limit stays over it rather than digging itself deeper.
        if (decision.allowed()) {

            client.increaseTotalRequest();
            validRequestsThisSecond++;

            recordLiveLog(
                    new Log(
                            request,
                            Log.STATUS_ACCEPTED
                    )
            );

        } else {

            recordLiveLog(
                    new Log(
                            request,
                            Log.STATUS_BLOCKED
                    )
            );

            requests.remove(request);
        }
    }

    @FXML
    private void onBurst() {

        for (int i = 0; i < 20; i++) {
            onSingleRequest();
        }
    }

    @FXML
    private void onAutoSimulate() {
        if (isAutoSimRunning) {
            stopAutoSimulation();
        } else {
            startAutoSimulation();
        }
    }

    private void startAutoSimulation() {
        if (clients.isEmpty()) return;

        isAutoSimRunning = true;
        autoSimButton.setText("Stop Simulation");
        autoSimButton.setStyle(
                "-fx-background-color: #ef4444;" +
                "-fx-padding: 8px;"
        );

        autoSimTimeline = new Timeline(
                new KeyFrame(
                        javafx.util.Duration.millis(800),
                        event -> runAutoSimulationStep()
                )
        );
        autoSimTimeline.setCycleCount(Timeline.INDEFINITE);
        autoSimTimeline.play();
    }

    private void stopAutoSimulation() {
        isAutoSimRunning = false;
        if (autoSimTimeline != null) {
            autoSimTimeline.stop();
        }
        autoSimButton.setText("Auto Simulate");
        autoSimButton.setStyle(
                "-fx-background-color: #3b82f6;" +
                "-fx-padding: 8px;"
        );
    }

    private void runAutoSimulationStep() {
        if (clients.isEmpty()) {
            stopAutoSimulation();
            return;
        }

        Client client = clients.get(autoSimClientIndex % clients.size());
        autoSimClientIndex++;

        RequestType[] requestTypes = RequestType.values();
        RequestType randomType = requestTypes[random.nextInt(requestTypes.length)];

        clientChoiceBox.getSelectionModel().select(client);
        ClientList.getSelectionModel().select(client);
        ClientList.scrollTo(client);
        typeChoiceBox.getSelectionModel().select(randomType);

        // No CRITICAL short-circuit here: a locked out client has to keep
        // asking, otherwise the policy never gets the chance to lift the
        // lockout once its cooldown expires.
        onSingleRequest();
    }

    private void setupTrafficChart() {

        trafficSeries.setName("Valid Requests");

        trafficChart.getData().add(trafficSeries);

        trafficYAxis.setAutoRanging(true);
        trafficYAxis.setForceZeroInRange(true);
    }

    private void startTrafficTimer() {

        Timeline timeline =
                new Timeline(
                        new KeyFrame(
                                javafx.util.Duration.seconds(1),
                                event -> {

                                    secondCounter++;

                                    trafficSeries.getData().add(
                                            new XYChart.Data<>(
                                                    String.valueOf(secondCounter),
                                                    validRequestsThisSecond
                                            )
                                    );

                                    validRequestsThisSecond = 0;

                                    if (trafficSeries.getData().size() > 20) {
                                        trafficSeries.getData().removeFirst();
                                    }
                                }
                        )
                );

        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    /**
     * Resets the simulation to a coherent state.
     *
     * <p>Dropping only the log would leave every client carrying scores and
     * counters that nothing backs, and because limits are derived from the log
     * it would also change each client's effective limit without saying so. The
     * window state and the per-client telemetry go with it.
     */
    @FXML
    private void onClear() {

        if (logs.isEmpty()) return;

        resetSimulationState();
    }

    /**
     * Wipes the simulation back to a fresh start: no log, no rate limiter
     * window state, no per-client telemetry, no history derived limits.
     */
    private void resetSimulationState() {
        suppressIndexSync = true;
        try {
            logs.clear();
            acceptedIndex.clear();
        } finally {
            suppressIndexSync = false;
        }

        requests.clear();
        for (Client client : clients) {
            client.reset();
        }

        updateRateLimitDisplay(clientChoiceBox.getValue(), typeChoiceBox.getValue());
    }

    /**
     * Puts the bundled request history back, for when it has been cleared by
     * accident. Resets first so the reload replaces the state rather than
     * interleaving with it.
     */
    @FXML
    private void onReloadHistory() {
        if (historyLoading) {
            return;
        }
        resetSimulationState();
        loadLogsFromCsv();
    }

    @FXML
    private void onGenerateAbuseReport() {
        showAbuseReportPopUp(clients);
    }

    private void showAbuseReportPopUp(ObservableList<Client> clients) {
        Stage popup = new Stage();
        popup.initModality(Modality.APPLICATION_MODAL);
        popup.initStyle(StageStyle.UNDECORATED);
        popup.setTitle("Abuse Report");

        final double[] dragOffset = new double[]{0, 0};

        VBox root = new VBox(14);
        root.setPadding(new Insets(18, 22, 18, 22));
        root.setAlignment(Pos.TOP_CENTER);
        root.setStyle(
                "-fx-background-color: #0b1329;" +
                "-fx-border-color: #334155;" +
                "-fx-border-width: 1.5;" +
                "-fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.75), 25, 0.35, 0, 8);"
        );

        // ── Header Bar (Draggable) ────────────────────────────
        HBox headerBar = new HBox(12);
        headerBar.setAlignment(Pos.CENTER_LEFT);
        headerBar.setCursor(javafx.scene.Cursor.MOVE);

        headerBar.setOnMousePressed(e -> {
            dragOffset[0] = e.getSceneX();
            dragOffset[1] = e.getSceneY();
        });
        headerBar.setOnMouseDragged(e -> {
            popup.setX(e.getScreenX() - dragOffset[0]);
            popup.setY(e.getScreenY() - dragOffset[1]);
        });

        StackPane iconBadge = new StackPane();
        iconBadge.setPrefSize(34, 34);
        iconBadge.setMinSize(34, 34);
        iconBadge.setMaxSize(34, 34);
        iconBadge.setStyle(
                "-fx-background-color: rgba(56, 189, 248, 0.12);" +
                "-fx-border-color: rgba(56, 189, 248, 0.35);"
        );
        SVGPath shieldIcon = new SVGPath();
        shieldIcon.setContent("M12 2L4 5v6.09c0 5.05 3.41 9.76 8 10.91 4.59-1.15 8-5.86 8-10.91V5l-8-3zm1 14h-2v-2h2v2zm0-4h-2V7h2v5z");
        shieldIcon.setFill(Color.web("#38bdf8"));
        shieldIcon.setScaleX(1.0);
        shieldIcon.setScaleY(1.0);
        iconBadge.getChildren().add(shieldIcon);

        Label titleLabel = new Label("ABUSE REPORT");
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
        closeIconButton.setOnAction(e -> popup.close());

        headerBar.getChildren().addAll(iconBadge, titleLabel, headerSpacer, closeIconButton);

        Region headerSep = new Region();
        headerSep.setPrefHeight(1);
        headerSep.setMaxWidth(Double.MAX_VALUE);
        headerSep.setStyle("-fx-background-color: #1e293b;");

        // ── Executive KPI Summary ───────────────────────────────
        int totalClientsCount = clients.size();
        long flaggedCount = clients.stream().filter(c -> c.getViolationCount() > 0).count();
        long blockedCount = clients.stream().filter(c -> c.getLevel() == ViolationLevel.CRITICAL).count();
        int totalViolationsCount = clients.stream().mapToInt(Client::getViolationCount).sum();

        HBox kpiRow = new HBox(10);
        kpiRow.setMaxWidth(Double.MAX_VALUE);

        VBox kpi1 = createKpiCard("MONITORED CLIENTS", String.valueOf(totalClientsCount), "Active in registry", "#38bdf8");
        VBox kpi2 = createKpiCard("TOTAL VIOLATIONS", String.valueOf(totalViolationsCount), "Rate limit breaches", totalViolationsCount > 0 ? "#f59e0b" : "#10b981");
        VBox kpi3 = createKpiCard("FLAGGED THREATS", flaggedCount + " (" + blockedCount + " Blocked)", "Policy violations", blockedCount > 0 ? "#ef4444" : (flaggedCount > 0 ? "#f59e0b" : "#10b981"));

        HBox.setHgrow(kpi1, Priority.ALWAYS);
        HBox.setHgrow(kpi2, Priority.ALWAYS);
        HBox.setHgrow(kpi3, Priority.ALWAYS);
        kpiRow.getChildren().addAll(kpi1, kpi2, kpi3);

        // ── ListView ──────────────────────────────────────────
        ListView<Client> listView = createListView(clients);
        VBox.setVgrow(listView, Priority.ALWAYS);

        // ── Close Button ──────────────────────────────────────
        Button closeButton = new Button("CLOSE");
        closeButton.setPrefWidth(120);
        closeButton.setPrefHeight(34);
        closeButton.setFont(Font.font("System", FontWeight.BOLD, 12));
        closeButton.setTextFill(Color.WHITE);
        closeButton.setStyle(
                "-fx-background-color: #2563eb;" +
                "-fx-background-radius: 8;" +
                "-fx-cursor: hand;"
        );
        closeButton.setOnAction(e -> popup.close());
        closeButton.setOnMouseEntered(e -> closeButton.setStyle(
                "-fx-background-color: #1d4ed8;" +
                "-fx-background-radius: 8;" +
                "-fx-cursor: hand;"
        ));
        closeButton.setOnMouseExited(e -> closeButton.setStyle(
                "-fx-background-color: #2563eb;" +
                "-fx-background-radius: 8;" +
                "-fx-cursor: hand;"
        ));

        root.getChildren().addAll(
                headerBar,
                headerSep,
                kpiRow,
                listView,
                closeButton
        );

        Scene scene = new Scene(root, 560, 560);
        scene.setFill(Color.TRANSPARENT);

        var cssUrl = SimulationController.class.getResource("style.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }

        popup.setScene(scene);
        popup.showAndWait();
    }

    private static VBox createKpiCard(String labelText, String valueText, String subText, String accentColor) {
        VBox card = new VBox(2);
        card.getStyleClass().add("kpi-card");
        card.setAlignment(Pos.CENTER_LEFT);

        Label label = new Label(labelText);
        label.setFont(Font.font("System", FontWeight.BOLD, 9));
        label.setTextFill(Color.web("#94a3b8"));

        Label value = new Label(valueText);
        value.setFont(Font.font("System", FontWeight.BOLD, 16));
        value.setTextFill(Color.web(accentColor));

        Label sub = new Label(subText);
        sub.setFont(Font.font("System", 10));
        sub.setTextFill(Color.web("#64748b"));

        card.getChildren().addAll(label, value, sub);
        return card;
    }

    private static ListView<Client> createListView(ObservableList<Client> clients) {
        ListView<Client> listView = new ListView<>();
        listView.setItems(clients);
        listView.setCellFactory(ClientReportCell::new);
        listView.getStyleClass().add("report-list");
        listView.setMaxWidth(Double.MAX_VALUE);
        listView.setStyle(
                "-fx-background-color: transparent;" +
                "-fx-control-inner-background: transparent;" +
                "-fx-background-insets: 0;" +
                "-fx-padding: 0;" +
                "-fx-hbar-policy: never;"
        );

        Label emptyLabel = new Label("No client records available");
        emptyLabel.setFont(Font.font("System", 13));
        emptyLabel.setTextFill(Color.web("#64748b"));
        listView.setPlaceholder(emptyLabel);

        return listView;
    }

    private static class ClientReportCell extends ListCell<Client> {
        private final ListView<Client> parentListView;

        public ClientReportCell(ListView<Client> parentListView) {
            this.parentListView = parentListView;
        }

        @Override
        protected void updateItem(Client client, boolean empty) {
            super.updateItem(client, empty);

            setStyle(
                    "-fx-background-color: transparent;" +
                    "-fx-padding: 4 0 6 0;"
            );

            if (empty || client == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            VBox card = new VBox(8);
            card.setPadding(new Insets(12, 14, 12, 14));

            card.maxWidthProperty().bind(parentListView.widthProperty().subtract(24));
            card.prefWidthProperty().bind(parentListView.widthProperty().subtract(24));

            ViolationLevel level = client.getLevel();
            boolean isBlocked = level == ViolationLevel.CRITICAL;
            boolean isHigh = level == ViolationLevel.HIGH;
            boolean isWarning = level == ViolationLevel.WARNING;

            String cardBg = isBlocked ? "#1c1424" : (isHigh ? "#1a1a24" : (isWarning ? "#1a241a" : "#111a2e"));
            String cardBorder = isBlocked ? "rgba(239, 68, 68, 0.45)" : (isHigh ? "rgba(245, 158, 11, 0.4)" : (isWarning ? "rgba(249, 115, 22, 0.4)" : "#1e293b"));

            card.setStyle(
                    "-fx-background-color: " + cardBg + ";" +
                    "-fx-border-color: " + cardBorder + ";" +
                    "-fx-border-width: 1.2;" +
                    "-fx-background-radius: 10;" +
                    "-fx-border-radius: 10;"
            );

            card.setOnMouseEntered(e -> card.setStyle(
                    "-fx-background-color: #18233c;" +
                    "-fx-border-color: " + (isBlocked ? "#ef4444" : (isHigh ? "#f59e0b" : (isWarning ? "#f97316" : "#38bdf8"))) + ";" +
                    "-fx-border-width: 1.2;" +
                    "-fx-background-radius: 10;" +
                    "-fx-border-radius: 10;"
            ));
            card.setOnMouseExited(e -> card.setStyle(
                    "-fx-background-color: " + cardBg + ";" +
                    "-fx-border-color: " + cardBorder + ";" +
                    "-fx-border-width: 1.2;" +
                    "-fx-background-radius: 10;" +
                    "-fx-border-radius: 10;"
            ));

            // Row 1: Name + Status Badge
            HBox topRow = new HBox(8);
            topRow.setAlignment(Pos.CENTER_LEFT);

            Label nameLabel = new Label(client.getName());
            nameLabel.setFont(Font.font("System", FontWeight.BOLD, 14));
            nameLabel.setTextFill(Color.WHITE);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label statusBadge = new Label();
            statusBadge.setFont(Font.font("System", FontWeight.BOLD, 10));
            statusBadge.setPadding(new Insets(3, 10, 3, 10));

            if (isBlocked) {
                statusBadge.setText("BLOCKED");
                statusBadge.setTextFill(Color.web("#f87171"));
                statusBadge.setStyle(
                        "-fx-background-color: rgba(239, 68, 68, 0.15);" +
                        "-fx-border-color: rgba(239, 68, 68, 0.4);" +
                        "-fx-background-radius: 12;" +
                        "-fx-border-radius: 12;"
                );
            } else if (isHigh) {
                statusBadge.setText("SUSPICIOUS");
                statusBadge.setTextFill(Color.web("#fbbf24"));
                statusBadge.setStyle(
                        "-fx-background-color: rgba(245, 158, 11, 0.15);" +
                        "-fx-border-color: rgba(245, 158, 11, 0.4);" +
                        "-fx-background-radius: 12;" +
                        "-fx-border-radius: 12;"
                );
            } else if (isWarning) {
                statusBadge.setText("WARNING");
                statusBadge.setTextFill(Color.web("#fb923c"));
                statusBadge.setStyle(
                        "-fx-background-color: rgba(249, 115, 22, 0.15);" +
                        "-fx-border-color: rgba(249, 115, 22, 0.4);" +
                        "-fx-background-radius: 12;" +
                        "-fx-border-radius: 12;"
                );
            } else {
                statusBadge.setText("NORMAL");
                statusBadge.setTextFill(Color.web("#4ade80"));
                statusBadge.setStyle(
                        "-fx-background-color: rgba(34, 197, 94, 0.15);" +
                        "-fx-border-color: rgba(34, 197, 94, 0.4);" +
                        "-fx-background-radius: 12;" +
                        "-fx-border-radius: 12;"
                );
            }

            topRow.getChildren().addAll(nameLabel, spacer, statusBadge);

            // Row 2: Telemetry Metrics
            HBox statsRow = new HBox(16);
            statsRow.setAlignment(Pos.CENTER_LEFT);

            int totalReq = client.getTotalRequest();
            int violationCount = client.getViolationCount();
            int violationScore = client.getViolationScore();
            double abuseRate = totalReq > 0 ? (violationCount * 100.0 / totalReq) : 0.0;

            Label reqLbl = new Label("Total Requests: " + totalReq);
            reqLbl.setFont(Font.font("System", 11));
            reqLbl.setTextFill(Color.web("#94a3b8"));

            Label vioLbl = new Label("Violations: " + violationCount + " (Score: " + violationScore + ")");
            vioLbl.setFont(Font.font("System", 11));
            vioLbl.setTextFill(violationCount > 0 ? (isBlocked ? Color.web("#f87171") : Color.web("#f59e0b")) : Color.web("#94a3b8"));

            Label rateLbl = new Label("Abuse Rate: " + String.format("%.1f%%", abuseRate));
            rateLbl.setFont(Font.font("System", 11));
            rateLbl.setTextFill(abuseRate > 50 ? Color.web("#f87171") : (abuseRate > 0 ? Color.web("#fbbf24") : Color.web("#4ade80")));

            statsRow.getChildren().addAll(reqLbl, vioLbl, rateLbl);

            // Row 3: Threat Meter Bar
            int maxScore = 100;
            double score = Math.min(violationScore / (double) maxScore, 1.0);
            int scorePercent = (int) Math.round(score * 100);

            HBox threatHeader = new HBox();
            threatHeader.setAlignment(Pos.CENTER_LEFT);

            Label threatTitle = new Label("Threat Level");
            threatTitle.setFont(Font.font("System", FontWeight.BOLD, 10));
            threatTitle.setTextFill(Color.web("#94a3b8"));

            Region threatSpacer = new Region();
            HBox.setHgrow(threatSpacer, Priority.ALWAYS);

            String riskClassification = switch (level) {
                case NONE -> "LOW RISK";
                case WARNING -> "WATCH LIST";
                case HIGH -> "ELEVATED RISK";
                case CRITICAL -> "CRITICAL RISK";
            };

            Color scoreColor = (score <= 0.2)
                    ? Color.web("#22c55e")
                    : (score <= 0.5 ? Color.web("#f59e0b") : (score <= 0.8 ? Color.web("#f97316") : Color.web("#ef4444")));

            Label threatValue = new Label(scorePercent + "% • " + riskClassification);
            threatValue.setFont(Font.font("System", FontWeight.BOLD, 11));
            threatValue.setTextFill(scoreColor);

            threatHeader.getChildren().addAll(threatTitle, threatSpacer, threatValue);

            StackPane bar = new StackPane();
            bar.setAlignment(Pos.CENTER_LEFT);
            bar.setMaxWidth(Double.MAX_VALUE);

            Region track = new Region();
            track.setPrefHeight(8);
            track.setMaxWidth(Double.MAX_VALUE);
            track.setStyle(
                    "-fx-background-color: #0b1329;" +
                    "-fx-border-color: #1e293b;" +
                    "-fx-border-radius: 4;" +
                    "-fx-background-radius: 4;"
            );

            Region fill = createFill(score);
            fill.setPrefHeight(8);
            fill.maxWidthProperty().bind(bar.widthProperty().multiply(Math.max(score, 0.02)));

            bar.getChildren().addAll(track, fill);

            VBox threatSection = new VBox(3);
            threatSection.getChildren().addAll(threatHeader, bar);

            card.getChildren().addAll(topRow, statsRow, threatSection);
            setGraphic(card);
        }
    }

    private static Region createFill(double score) {
        Region fill = new Region();
        fill.setPrefHeight(8);
        fill.setMaxWidth(Double.MAX_VALUE);

        String fillColor;
        if (score <= 0.35) {
            fillColor = "linear-gradient(to right, #10b981, #22c55e)";
        } else if (score <= 0.7) {
            fillColor = "linear-gradient(to right, #f59e0b, #fbbf24)";
        } else {
            fillColor = "linear-gradient(to right, #ef4444, #f43f5e)";
        }

        fill.setStyle(
                "-fx-background-color: " + fillColor + ";" +
                "-fx-background-radius: 4;"
        );
        return fill;
    }

    // Sidebar Client Registry Card
    private static class ClientCardCell extends ListCell<Client> {

        @Override
        protected void updateItem(Client client, boolean empty) {
            super.updateItem(client, empty);

            setStyle(
                    "-fx-background-color: transparent;" +
                    "-fx-padding: 3 6;"
            );

            if (empty || client == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            HBox card = new HBox(10);
            card.setAlignment(Pos.CENTER_LEFT);
            card.setPadding(new Insets(10, 12, 10, 12));
            card.setMaxWidth(Double.MAX_VALUE);

            updateCardStyle(card, client);

            Label nameLabel = new Label(client.getName());
            nameLabel.setFont(Font.font("System", FontWeight.BOLD, 13));
            nameLabel.setTextFill(Color.WHITE);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Label statusBadge = new Label();
            statusBadge.setFont(Font.font("System", FontWeight.BOLD, 9));
            statusBadge.setPadding(new Insets(3, 8, 3, 8));

            boolean isBlocked = client.getLevel() == ViolationLevel.CRITICAL;
            boolean isHigh = client.getLevel() == ViolationLevel.HIGH;
            boolean isWarning = client.getLevel() == ViolationLevel.WARNING;

            if (isBlocked) {
                statusBadge.setText("BLOCKED");
                statusBadge.setTextFill(Color.web("#f87171"));
                statusBadge.setStyle(
                        "-fx-background-color: #450a0a;" +
                        "-fx-border-color: #ef4444;" +
                        "-fx-background-radius: 10;" +
                        "-fx-border-radius: 10;"
                );
            } else if (isHigh) {
                statusBadge.setText("SUSPICIOUS");
                statusBadge.setTextFill(Color.web("#fbbf24"));
                statusBadge.setStyle(
                        "-fx-background-color: #451a03;" +
                        "-fx-border-color: #f59e0b;" +
                        "-fx-background-radius: 10;" +
                        "-fx-border-radius: 10;"
                );
            } else if (isWarning) {
                statusBadge.setText("WARNING");
                statusBadge.setTextFill(Color.web("#fb923c"));
                statusBadge.setStyle(
                        "-fx-background-color: #3a2400;" +
                        "-fx-border-color: #f97316;" +
                        "-fx-background-radius: 10;" +
                        "-fx-border-radius: 10;"
                );
            } else {
                statusBadge.setText("ACTIVE");
                statusBadge.setTextFill(Color.web("#4ade80"));
                statusBadge.setStyle(
                        "-fx-background-color: #052e16;" +
                        "-fx-border-color: #22c55e;" +
                        "-fx-background-radius: 10;" +
                        "-fx-border-radius: 10;"
                );
            }

            card.getChildren().addAll(nameLabel, spacer, statusBadge);
            setGraphic(card);
        }

        @Override
        public void updateSelected(boolean selected) {
            super.updateSelected(selected);
            if (getItem() != null && getGraphic() instanceof HBox card) {
                updateCardStyle(card, getItem());
            }
        }

        private void updateCardStyle(HBox card, Client client) {
            boolean isBlocked = client.getLevel() == ViolationLevel.CRITICAL;
            boolean isHigh = client.getLevel() == ViolationLevel.HIGH;
            boolean isWarning = client.getLevel() == ViolationLevel.WARNING;

            String bg = isSelected() ? "#243248" : (isBlocked ? "#1c1424" : (isHigh ? "#1a1a24" : (isWarning ? "#1a241a" : "#1e293b")));
            String border = isSelected()
                    ? "#3b82f6"
                    : (isBlocked ? "#ef4444" : (isHigh ? "#f59e0b" : (isWarning ? "#f97316" : "#334155")));
            double borderWidth = isSelected() || isBlocked ? 1.5 : 1.0;

            card.setStyle(
                    "-fx-background-color: " + bg + ";" +
                    "-fx-border-color: " + border + ";" +
                    "-fx-border-width: " + borderWidth + ";" +
                    "-fx-background-radius: 8;" +
                    "-fx-border-radius: 8;"
            );
        }
    }

    // Log cell
    private static class LogCell extends ListCell<Log> {

        @Override
        protected void updateItem(Log log, boolean empty) {
            super.updateItem(log, empty);

            setStyle(
                    "-fx-background-color: transparent;" +
                            "-fx-padding: 5;"
            );

            if (empty || log == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            VBox card = new VBox(6);

            card.setPadding(new Insets(9, 12, 9, 12));

            card.setMaxWidth(Double.MAX_VALUE);

            card.setStyle(
                    "-fx-background-color: #1e293b;" +
                            "-fx-background-radius: 8;"
            );

            HBox topRow = new HBox(8);

            topRow.setAlignment(Pos.CENTER_LEFT);

            Label time = new Label(
                    log.getTime().format(CLOCK_FORMAT)
            );

            time.setFont(
                    Font.font("Monospaced", 10)
            );

            time.setTextFill(
                    Color.web("#64748b")
            );

            Label client = new Label(
                    log.getClient().getName()
            );

            client.setFont(
                    Font.font(
                            "System",
                            FontWeight.BOLD,
                            13
                    )
            );

            client.setTextFill(Color.WHITE);

            HBox.setHgrow(client, Priority.ALWAYS);

            Label status = new Label(
                    log.getStatus()
            );

            status.setFont(
                    Font.font(
                            "System",
                            FontWeight.BOLD,
                            9
                    )
            );

            status.setPadding(
                    new Insets(4, 7, 4, 7)
            );

            setStatusStyle(
                    status,
                    log.getStatus()
            );

            topRow.getChildren().addAll(
                    time,
                    client,
                    status
            );

            Label type = new Label(
                    "Request: " +
                            log.getType().toString()
            );

            type.setFont(
                    Font.font("System", 11)
            );

            type.setTextFill(
                    Color.web("#94a3b8")
            );

            card.getChildren().addAll(
                    topRow,
                    type
            );

            setGraphic(card);
        }

        private void setStatusStyle(
                Label status,
                String value
        ) {
            if (value.equalsIgnoreCase(Log.STATUS_ACCEPTED)) {

                status.setTextFill(
                        Color.web("#22c55e")
                );

                status.setStyle(
                        "-fx-background-color: #052e16;" +
                                "-fx-background-radius: 10;"
                );

            } else if (value.equalsIgnoreCase(Log.STATUS_BLOCKED)) {

                status.setTextFill(
                        Color.web("#ef4444")
                );

                status.setStyle(
                        "-fx-background-color: #450a0a;" +
                                "-fx-background-radius: 10;"
                );
            }
        }
    }
}