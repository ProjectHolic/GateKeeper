package com.simulator.ui;

import com.simulator.model.Client;
import com.simulator.model.Log;
import com.simulator.model.Request;
import com.simulator.model.RequestType;
import com.simulator.model.ViolationLevel;
import com.simulator.policy.FixedWindowPolicy;
import com.simulator.policy.RatePolicy;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
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
import java.time.Duration;
import java.time.format.DateTimeFormatter;

public class SimulationController {

    @FXML private TextField ClientBox;
    @FXML private Label totalRequestLabel;
    @FXML private Label violationLabel;
    @FXML private ListView<Client> ClientList;
    @FXML private ListView<Log> LogList;
    @FXML private ComboBox<Client> clientChoiceBox;
    @FXML private ComboBox<RequestType> typeChoiceBox;
    @FXML private LineChart<String, Number> trafficChart;
    @FXML private NumberAxis trafficYAxis;
    @FXML private Circle Blinking;

    private final ObservableList<Client> clients =
            FXCollections.observableArrayList(
                    client -> new Observable[]{
                            client.totalRequestProperty(),
                            client.violationProperty(),
                            client.levelProperty()
                    }
            );

    private final ObservableList<Request> requests =
            FXCollections.observableArrayList();

    private final ObservableList<Log> logs =
            FXCollections.observableArrayList();

    private final ObservableList<RequestType> types =
            FXCollections.observableArrayList();

    private final XYChart.Series<String, Number> trafficSeries =
            new XYChart.Series<>();

    private int validRequestsThisSecond = 0;
    private int secondCounter = 0;

    private final RatePolicy policy =
            new FixedWindowPolicy(10, Duration.ofSeconds(10));

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

        LogList.setItems(logs);
        ClientList.setItems(clients);
        clientChoiceBox.setItems(clients);
        typeChoiceBox.setItems(types);
        typeChoiceBox.setValue(RequestType.LOGIN);

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
                                        .mapToInt(Client::getViolation)
                                        .sum()
                        ),
                        clients
                )
        );

        setupTrafficChart();
        startTrafficTimer();
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

        if (!name.isEmpty()) {
            clients.add(new Client(name));
            ClientBox.clear();
        }
    }

    @FXML
    private void onSingleRequest() {

        Client client =
                clientChoiceBox
                        .getSelectionModel()
                        .getSelectedItem();

        if (client == null) return;

        if (client.getLevel() == ViolationLevel.CRITICAL) return;

        Request request =
                new Request(
                        client,
                        typeChoiceBox
                                .getSelectionModel()
                                .getSelectedItem()
                );

        requests.add(request);

        ViolationLevel level =
                policy.evaluate(client, requests);

        client.setLevel(level);

        if (level == ViolationLevel.NONE) {

            client.increaseTotalRequest();
            validRequestsThisSecond++;

            logs.add(
                    new Log(
                            request,
                            "ACCEPTED"
                    )
            );

        } else {

            logs.add(
                    new Log(
                            request,
                            "BLOCKED"
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

    @FXML
    private void onClear() {

        if (logs.isEmpty()) return;

        logs.clear();
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
                "-fx-background-radius: 14;" +
                "-fx-border-radius: 14;" +
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
        long flaggedCount = clients.stream().filter(c -> c.getViolation() > 0).count();
        long blockedCount = clients.stream().filter(c -> c.getLevel() == ViolationLevel.CRITICAL).count();
        int totalViolationsCount = clients.stream().mapToInt(Client::getViolation).sum();

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
            boolean isSuspicious = level == ViolationLevel.HIGH;

            String cardBg = isBlocked ? "#1c1424" : (isSuspicious ? "#1a1a24" : "#111a2e");
            String cardBorder = isBlocked ? "rgba(239, 68, 68, 0.45)" : (isSuspicious ? "rgba(245, 158, 11, 0.4)" : "#1e293b");

            card.setStyle(
                    "-fx-background-color: " + cardBg + ";" +
                    "-fx-border-color: " + cardBorder + ";" +
                    "-fx-border-width: 1.2;" +
                    "-fx-background-radius: 10;" +
                    "-fx-border-radius: 10;"
            );

            card.setOnMouseEntered(e -> card.setStyle(
                    "-fx-background-color: #18233c;" +
                    "-fx-border-color: " + (isBlocked ? "#ef4444" : (isSuspicious ? "#f59e0b" : "#38bdf8")) + ";" +
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
            } else if (isSuspicious) {
                statusBadge.setText("SUSPICIOUS");
                statusBadge.setTextFill(Color.web("#fbbf24"));
                statusBadge.setStyle(
                        "-fx-background-color: rgba(245, 158, 11, 0.15);" +
                        "-fx-border-color: rgba(245, 158, 11, 0.4);" +
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
            int violations = client.getViolation();
            double abuseRate = totalReq > 0 ? (violations * 100.0 / totalReq) : 0.0;

            Label reqLbl = new Label("Total Requests: " + totalReq);
            reqLbl.setFont(Font.font("System", 11));
            reqLbl.setTextFill(Color.web("#94a3b8"));

            Label vioLbl = new Label("Total Violations: " + violations);
            vioLbl.setFont(Font.font("System", 11));
            vioLbl.setTextFill(violations > 0 ? (isBlocked ? Color.web("#f87171") : Color.web("#f59e0b")) : Color.web("#94a3b8"));

            Label rateLbl = new Label("Abuse Rate: " + String.format("%.1f%%", abuseRate));
            rateLbl.setFont(Font.font("System", 11));
            rateLbl.setTextFill(abuseRate > 50 ? Color.web("#f87171") : (abuseRate > 0 ? Color.web("#fbbf24") : Color.web("#4ade80")));

            statsRow.getChildren().addAll(reqLbl, vioLbl, rateLbl);

            // Row 3: Threat Meter Bar
            int maxViolations = 50;
            double score = Math.min(violations / (double) maxViolations, 1.0);
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
                case HIGH -> "ELEVATED RISK";
                case CRITICAL -> "CRITICAL RISK";
            };

            Color scoreColor = (score <= 0.35)
                    ? Color.web("#22c55e")
                    : (score <= 0.7 ? Color.web("#f59e0b") : Color.web("#ef4444"));

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
            boolean isSuspicious = client.getLevel() == ViolationLevel.HIGH;

            if (isBlocked) {
                statusBadge.setText("BLOCKED");
                statusBadge.setTextFill(Color.web("#f87171"));
                statusBadge.setStyle(
                        "-fx-background-color: #450a0a;" +
                        "-fx-border-color: #ef4444;" +
                        "-fx-background-radius: 10;" +
                        "-fx-border-radius: 10;"
                );
            } else if (isSuspicious) {
                statusBadge.setText("SUSPICIOUS");
                statusBadge.setTextFill(Color.web("#fbbf24"));
                statusBadge.setStyle(
                        "-fx-background-color: #451a03;" +
                        "-fx-border-color: #f59e0b;" +
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
            boolean isSuspicious = client.getLevel() == ViolationLevel.HIGH;

            String bg = isSelected() ? "#243248" : (isBlocked ? "#1c1424" : "#1e293b");
            String border = isSelected()
                    ? "#3b82f6"
                    : (isBlocked ? "#ef4444" : (isSuspicious ? "#f59e0b" : "#334155"));
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
                    log.getTime().format(
                            DateTimeFormatter.ofPattern("HH:mm:ss")
                    )
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
            if (value.equalsIgnoreCase("ACCEPTED")) {

                status.setTextFill(
                        Color.web("#22c55e")
                );

                status.setStyle(
                        "-fx-background-color: #052e16;" +
                                "-fx-background-radius: 10;"
                );

            } else if (value.equalsIgnoreCase("BLOCKED")) {

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