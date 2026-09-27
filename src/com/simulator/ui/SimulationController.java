package com.simulator.ui;

import com.simulator.io.HistoryLoader;
import com.simulator.io.ScenarioIO;
import com.simulator.io.ScenarioService;
import com.simulator.model.Client;
import com.simulator.model.DailyAcceptedIndex;
import com.simulator.model.Log;
import com.simulator.model.Request;
import com.simulator.model.RequestType;
import com.simulator.model.SimulationSettings;
import com.simulator.policy.FixedWindowPolicy;
import com.simulator.policy.PolicyType;
import com.simulator.policy.RatePolicy;
import com.simulator.policy.SlidingWindowPolicy;
import com.simulator.ui.cells.ClientCardCell;
import com.simulator.ui.cells.LogCell;
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
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class SimulationController {

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
    private final SimulationSettings settings = new SimulationSettings();

    // Auto Simulation
    private Timeline autoSimTimeline;
    private Timeline trafficTimeline;
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

    /** The open settings dialog, so file choosers can be parented to it. */
    private Stage settingsDialog = null;

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
        List<HistoryLoader.HistoryRow> rows;
        try {
            rows = HistoryLoader.readRows();
        } catch (Exception e) {
            Platform.runLater(() -> onHistoryLoadFailed(e));
            return;
        }
        Platform.runLater(() -> mergeHistoryRows(rows));
    }
    /** Application thread half: resolve clients, build the logs, publish in one change. */
    private void mergeHistoryRows(List<HistoryLoader.HistoryRow> rows) {
        Map<String, Client> clientsByName = new HashMap<>();
        for (Client existing : clients) {
            clientsByName.putIfAbsent(existing.getName(), existing);
        }

        List<Client> discovered = new ArrayList<>();
        List<Log> history = new ArrayList<>(rows.size() + logs.size());

        for (HistoryLoader.HistoryRow row : rows) {
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

    private void updatePolicyDisplay() {
        if (policyDisplayLabel != null) {
            policyDisplayLabel.setText("Policy: " + settings.getPolicy());
        }
        updateRateLimitDisplay(clientChoiceBox.getValue(), typeChoiceBox.getValue());
    }

    private void updateRateLimitDisplay(Client client, RequestType type) {
        if (rateLimitLabel != null && type != null) {
            rateLimitLabel.setText(resolveThreshold(client, type) + " / window");
        }
        if (activeWindowLabel != null) {
            activeWindowLabel.setText(settings.getWindowSeconds() + "S");
        }
    }

    /**
     * The client's busiest day, or 0 when it is unknown.
     *
     * <p>Kept separate so the null client never reaches the index, which is
     * keyed by client identity and would reject a null key.
     */
    private int busiestDayCount(Client client, RequestType type) {
        return client == null ? 0 : acceptedIndex.maxDailyAccepted(client, type);
    }

    private int resolveThreshold(Client client, RequestType type) {
        return settings.resolveThreshold(type, busiestDayCount(client, type));
    }

    @FXML
    private void onSettings() {
        showSettingsDialog();
    }

    // ================================================================
    // Scenario export / import
    //
    // A scenario file holds the settings plus the client registry and each
    // client's telemetry. It deliberately does not hold the log history:
    // re-importing old timestamps would feed the "busiest day" calculation
    // that derives every rate limit, so a round trip could silently change
    // the very limits it was meant to preserve. The log is exportable on
    // its own, as CSV, for inspection.
    // ================================================================

    private static final String SCENARIO_FILE_EXTENSION = ".gatekeeper.json";
    private static final String LOG_FILE_EXTENSION = ".csv";

    private void exportScenario() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export scenario");
        chooser.setInitialFileName("gatekeeper-scenario" + SCENARIO_FILE_EXTENSION);
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("GateKeeper scenario", "*" + SCENARIO_FILE_EXTENSION));

        File target = chooser.showSaveDialog(ownerStage());
        if (target == null) {
            return;
        }
        if (!target.getName().endsWith(SCENARIO_FILE_EXTENSION)) {
            target = new File(target.getParentFile(), target.getName() + SCENARIO_FILE_EXTENSION);
        }

        try {
            ScenarioIO.writeScenario(target,
                    ScenarioService.build(settings, clients));
            showSuccess("Exported settings and " + clients.size()
                    + " client(s) to:\n" + target.getName());
        } catch (IOException e) {
            showValidationError("Export failed: " + e.getMessage());
        }
    }

    private void importScenario() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import scenario");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("GateKeeper scenario", "*" + SCENARIO_FILE_EXTENSION));

        File source = chooser.showOpenDialog(ownerStage());
        if (source == null) {
            return;
        }

        Map<String, Object> document;
        try {
            document = ScenarioIO.readScenario(source);
        } catch (IOException e) {
            showValidationError("Could not read the scenario:\n" + e.getMessage());
            return;
        }

        // Parse and range-check everything before touching any live state, so a
        // bad file can never leave the application half-imported.
        ScenarioService.ImportedScenario incoming;
        try {
            incoming = ScenarioService.parse(document);
        } catch (ScenarioService.ScenarioFormatException e) {
            showValidationError("The scenario file is not valid:\n" + e.getMessage());
            return;
        }

        boolean proceed = confirmDestructive(
                "Importing replaces the current settings and the whole client registry ("
                        + clients.size() + " client(s) now), and clears the request log, the "
                        + "rate limiter window and the history derived limits.\n\nContinue?");
        if (!proceed) {
            return;
        }

        applyImportedScenario(incoming);
        showSuccess("Imported " + incoming.clients().size()
                + " client(s) and their settings.\nThe request log and history were cleared.");
    }

    private void exportLogCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export request log");
        chooser.setInitialFileName("gatekeeper-log" + LOG_FILE_EXTENSION);
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("CSV", "*" + LOG_FILE_EXTENSION));

        File target = chooser.showSaveDialog(ownerStage());
        if (target == null) {
            return;
        }
        if (!target.getName().endsWith(LOG_FILE_EXTENSION)) {
            target = new File(target.getParentFile(), target.getName() + LOG_FILE_EXTENSION);
        }

        List<Log> snapshot;
        try {
            snapshot = new ArrayList<>(logs);
        } catch (RuntimeException e) {
            showValidationError("Could not snapshot the log: " + e.getMessage());
            return;
        }

        try {
            ScenarioIO.writeLogCsv(target, snapshot);
            showSuccess("Exported " + snapshot.size() + " log entr(ies) to:\n" + target.getName());
        } catch (IOException e) {
            showValidationError("Export failed: " + e.getMessage());
        }
    }

    /**
     * The half of an import the controller owns: the validated scenario goes
     * into the settings, and the live request log, limiter window and daily
     * index are reset to match.
     */
    private void applyImportedScenario(ScenarioService.ImportedScenario scenario) {
        ScenarioService.applySettings(scenario, settings);

        suppressIndexSync = true;
        try {
            logs.clear();
            acceptedIndex.clear();
        } finally {
            suppressIndexSync = false;
        }
        requests.clear();

        clients.clear();
        clients.addAll(scenario.clients());

        updatePolicyDisplay();
        updateRateLimitDisplay(clientChoiceBox.getValue(), typeChoiceBox.getValue());
    }

    /**
     * The window a file chooser should be parented to, so it opens centred on
     * the settings dialog rather than behind it. Null is fine, and means the
     * chooser centres on the screen.
     */
    private Stage ownerStage() {
        return settingsDialog;
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
            // Held so a file chooser opened from inside the dialog can be
            // parented to it and open centred rather than behind.
            settingsDialog = SettingsDialogView.show(
                    settings,
                    this::updatePolicyDisplay,
                    this::exportScenario,
                    this::importScenario,
                    this::exportLogCsv);
        } finally {
            settingsDialog = null;
            if (resumeAfterDialog) {
                startAutoSimulation();
            }
        }
    }

    private void showValidationError(String message) {
        NoticeDialog.error(message);
    }

    /** Reports the outcome of an export, import or other completed action. */
    private void showSuccess(String message) {
        NoticeDialog.success(message);
    }

    private boolean confirmDestructive(String message) {
        return NoticeDialog.confirmDestructive(message);
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
        Duration window = Duration.ofSeconds(settings.getWindowSeconds());

        Map<RequestType, Integer> currentSeverity = new EnumMap<>(RequestType.class);
        currentSeverity.putAll(settings.getSeverityMultipliers());

        int[] violationThresholds = {settings.getWarningThreshold(), settings.getHighThreshold(), settings.getCriticalThreshold()};
        int currentDecay = settings.getDecayAmount();

        if (settings.getPolicy() == PolicyType.SLIDING_WINDOW) {
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
        LocalDateTime cutoff = now.minus(Duration.ofSeconds(settings.getWindowSeconds()));
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

        issueRequest(client, type);
    }

    /**
     * Evaluates and records one request for an explicit client and type.
     *
     * <p>Callers pass these in rather than having them read back off the combo
     * boxes. The auto simulation moves the on-screen selection and then calls
     * this; had it gone through {@link #onSingleRequest()}, a user click landing
     * between those two steps would have silently redirected the request to
     * whichever client was selected at that instant.
     */
    private void issueRequest(Client client, RequestType type) {

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

        // The selection is read once, so all twenty requests belong to the same
        // client and type rather than whatever the combo boxes happened to hold.
        for (int i = 0; i < 20; i++) {
            issueRequest(client, type);
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

        // Presentation only: move the visible selection so the user can follow
        // along. The request itself is issued for the client and type decided
        // above, never for whatever the combo boxes hold by then.
        clientChoiceBox.getSelectionModel().select(client);
        ClientList.getSelectionModel().select(client);
        ClientList.scrollTo(client);
        typeChoiceBox.getSelectionModel().select(randomType);

        // No CRITICAL short-circuit here: a locked out client has to keep
        // asking, otherwise the policy never gets the chance to lift the
        // lockout once its cooldown expires.
        issueRequest(client, randomType);
    }

    private void setupTrafficChart() {

        trafficSeries.setName("Valid Requests");

        trafficChart.getData().add(trafficSeries);

        trafficYAxis.setAutoRanging(true);
        trafficYAxis.setForceZeroInRange(true);
    }

    private void startTrafficTimer() {

        trafficTimeline = new Timeline(
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

        trafficTimeline.setCycleCount(Timeline.INDEFINITE);
        trafficTimeline.play();
    }

    /**
     * Stops every timer this controller owns.
     *
     * <p>Called from {@code MainApp.stop()} when the last window closes. The
     * button is deliberately left alone: nothing is on screen to look at it, and
     * touching the scene graph during shutdown is best avoided.
     */
    void shutdown() {
        isAutoSimRunning = false;

        if (autoSimTimeline != null) {
            autoSimTimeline.stop();
            autoSimTimeline = null;
        }
        if (trafficTimeline != null) {
            trafficTimeline.stop();
            trafficTimeline = null;
        }
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
        AbuseReportView.show(clients);
    }

}
