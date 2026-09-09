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
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import java.time.Duration;
import java.time.format.DateTimeFormatter;

public class SimulationController {

    @FXML private Button AddButton;
    @FXML private Button Sendrequestbutton;
    @FXML private TextField ClientBox;
    @FXML private Label totalRequestLabel;
    @FXML private Label violationLabel;
    @FXML private ListView<Client> ClientList;
    @FXML private ListView<Log> LogList;
    @FXML private ComboBox<Client> clientChoiceBox;
    @FXML private ComboBox<RequestType> typeChoiceBox;
    @FXML private LineChart<String, Number> trafficChart;
    @FXML private CategoryAxis trafficXAxis;
    @FXML private NumberAxis trafficYAxis;
    @FXML private Circle Blinking;

    private final ObservableList<Client> clients =
            FXCollections.observableArrayList(
                    client -> new Observable[]{
                            client.totalRequestProperty(),
                            client.violationProperty()
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

    private void showAbuseReportPopUp(
            ObservableList<Client> clients
    ) {

        Stage popup = new Stage();

        popup.initModality(Modality.APPLICATION_MODAL);
        popup.initStyle(StageStyle.UNDECORATED);
        popup.setTitle("Abuse Report");

        VBox root = new VBox(16);

        root.setPadding(
                new Insets(28, 32, 28, 32)
        );

        root.setAlignment(Pos.TOP_CENTER);

        root.setStyle(
                "-fx-background-color: #0f172a;" +
                        "-fx-border-color: #334155;" +
                        "-fx-border-width: 2;"
        );

        Label title = new Label("ABUSE REPORT");

        title.setFont(
                Font.font(
                        "System",
                        FontWeight.BOLD,
                        20
                )
        );

        title.setTextFill(Color.WHITE);

        Line separator =
                new Line(0, 0, 400, 0);

        separator.setStroke(
                Color.web("#334155")
        );

        ListView<Client> listView =
                new ListView<>();

        listView.setItems(clients);

        listView.setCellFactory(
                lv -> new ClientReportCell()
        );

        listView.setPrefWidth(450);
        listView.setPrefHeight(350);

        listView.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-control-inner-background: transparent;" +
                        "-fx-background-insets: 0;" +
                        "-fx-padding: 4;"
        );

        Button closeButton =
                new Button("CLOSE");

        closeButton.setPrefWidth(110);
        closeButton.setPrefHeight(36);

        closeButton.setFont(
                Font.font(
                        "System",
                        FontWeight.BOLD,
                        12
                )
        );

        closeButton.setTextFill(Color.WHITE);

        closeButton.setStyle(
                "-fx-background-color: #334155;" +
                        "-fx-background-radius: 6;" +
                        "-fx-cursor: hand;"
        );

        closeButton.setOnAction(
                e -> popup.close()
        );

        closeButton.setOnMouseEntered(
                e -> closeButton.setStyle(
                        "-fx-background-color: #475569;" +
                                "-fx-background-radius: 6;" +
                                "-fx-cursor: hand;"
                )
        );

        closeButton.setOnMouseExited(
                e -> closeButton.setStyle(
                        "-fx-background-color: #334155;" +
                                "-fx-background-radius: 6;" +
                                "-fx-cursor: hand;"
                )
        );

        root.getChildren().addAll(
                title,
                separator,
                listView,
                closeButton
        );

        Scene scene =
                new Scene(
                        root,
                        520,
                        500
                );

        scene.setFill(Color.TRANSPARENT);

        popup.setScene(scene);
        popup.showAndWait();
    }

    private static class ClientReportCell
            extends ListCell<Client> {

        @Override
        protected void updateItem(
                Client client,
                boolean empty
        ) {

            super.updateItem(client, empty);

            setStyle(
                    "-fx-background-color: transparent;" +
                            "-fx-padding: 4;"
            );

            if (empty || client == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            VBox card = new VBox(8);

            card.setPadding(
                    new Insets(12)
            );

            card.setPrefWidth(420);

            card.setStyle(
                    "-fx-background-color: #1e293b;" +
                            "-fx-background-radius: 8;"
            );

            Label name =
                    new Label(
                            client.getName()
                    );

            name.setFont(
                    Font.font(
                            "System",
                            FontWeight.BOLD,
                            15
                    )
            );

            name.setTextFill(Color.WHITE);

            Label requests =
                    new Label(
                            "Total Requests: " +
                                    client.getTotalRequest()
                    );

            requests.setTextFill(
                    Color.web("#cbd5e1")
            );

            Label violation =
                    new Label(
                            "Violation: " +
                                    client.getViolation()
                    );

            violation.setTextFill(
                    Color.web("#cbd5e1")
            );

            Label status = new Label();

            String statusText =
                    switch (client.getLevel()) {
                        case NONE -> "Status: NORMAL";
                        case HIGH -> "Status: SUSPICIOUS";
                        case CRITICAL -> "Status: BLOCKED";
                        default -> "Status: UNKNOWN";
                    };

            status.setText(statusText);

            status.setFont(
                    Font.font(
                            "System",
                            FontWeight.BOLD,
                            13
                    )
            );

            status.setTextFill(
                    getStatusColor(
                            client.getLevel()
                    )
            );

            card.getChildren().addAll(
                    name,
                    requests,
                    violation,
                    status
            );

            setGraphic(card);
        }

        private static Color getStatusColor(
                ViolationLevel level
        ) {

            return switch (level) {
                case NONE -> Color.web("#22c55e");
                case HIGH -> Color.web("#f59e0b");
                case CRITICAL -> Color.web("#ef4444");
            };
        }
    }

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