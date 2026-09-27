package com.simulator.ui;

import com.simulator.model.Client;
import com.simulator.model.ViolationLevel;
import com.simulator.ui.cells.ClientReportCell;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * The abuse report modal: an executive KPI summary over a per-client threat
 * breakdown.
 *
 * <p>Read only, and a snapshot in effect — it renders whatever the client list
 * holds at the moment it is opened and is blocked while it is up, so nothing
 * underneath can change underneath it.
 */
public final class AbuseReportView {

    private static final int WIDTH = 560;
    private static final int HEIGHT = 560;

    private AbuseReportView() {
    }

    public static void show(ObservableList<Client> clients) {
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

        // As in the settings dialog, the content keeps its natural size and is
        // inset inside a larger scene so the drop shadow is not clipped.
        root.setPrefSize(WIDTH, HEIGHT);
        root.setMaxSize(WIDTH, HEIGHT);

        StackPane wrapper = new StackPane(root);
        StackPane.setMargin(root, new Insets(NoticeDialog.SHADOW_MARGIN));
        wrapper.setStyle("-fx-background-color: transparent;");

        Scene scene = new Scene(
                wrapper,
                WIDTH + NoticeDialog.SHADOW_MARGIN * 2,
                HEIGHT + NoticeDialog.SHADOW_MARGIN * 2
        );
        scene.setFill(Color.TRANSPARENT);

        var cssUrl = AbuseReportView.class.getResource("style.css");
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
}
