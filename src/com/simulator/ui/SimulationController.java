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
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.time.Duration;

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
    @FXML
    private Circle Blinking;

    private final ObservableList<Client> clients =
            FXCollections.observableArrayList(
                    client -> new Observable[]{
                            client.totalRequestProperty(),
                            client.violationProperty()
                    }
            );

    private final ObservableList<Request> requests = FXCollections.observableArrayList();
    private final ObservableList<Log> logs = FXCollections.observableArrayList();
    private final ObservableList<RequestType> types = FXCollections.observableArrayList();
    private final XYChart.Series<String, Number> trafficSeries =
            new XYChart.Series<>();

    private int validRequestsThisSecond = 0;
    private int secondCounter = 0;

    private final RatePolicy policy =
            new FixedWindowPolicy(10, Duration.ofSeconds(10));


    @FXML
    public void initialize() {

        FadeTransition blinkingAnimation = new FadeTransition(javafx.util.Duration.millis(600), Blinking);
        blinkingAnimation.setFromValue(1.0);
        blinkingAnimation.setToValue(0.2);
        blinkingAnimation.setCycleCount(Animation.INDEFINITE);
        blinkingAnimation.setAutoReverse(true);
        blinkingAnimation.play();

        ClientList.setPlaceholder(createTopPlaceholder("No client yet!"));
        LogList.setPlaceholder(createTopPlaceholder("No log"));
        types.addAll(RequestType.READ, RequestType.LOGIN, RequestType.PAYMENT, RequestType.WRITE);
        LogList.setItems(logs);
        ClientList.setItems(clients);
        clientChoiceBox.setItems(clients);
        typeChoiceBox.setItems(types);
        typeChoiceBox.setValue(RequestType.LOGIN);

        ClientList.getSelectionModel()
                .selectedItemProperty()
                .addListener((obs, oldClient, newClient) -> {
                    if (newClient != null) {
                        clientChoiceBox.getSelectionModel().select(newClient);
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
        label.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 14;");
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
        Client client = clientChoiceBox.getSelectionModel().getSelectedItem();

        if (client == null) return;
        if(client.getLevel() == ViolationLevel.CRITICAL) return;

        Request request = new Request(client, typeChoiceBox.getSelectionModel().getSelectedItem());
        requests.add(request);

        ViolationLevel level = policy.evaluate(client, requests);
        client.setLevel(level);

        if (level == ViolationLevel.NONE) {
            client.increaseTotalRequest();
            validRequestsThisSecond++;
            logs.add(new Log(request, "ACCEPTED"));
        } else {
            logs.add(new Log(request, "BLOCKED"));
            requests.remove(request);

        }
    }

    @FXML
    private void onBurst(){
            for(int i = 0; i < 20; i++){
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
        Timeline timeline = new Timeline(
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
                                trafficSeries.getData().remove(0);
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
}