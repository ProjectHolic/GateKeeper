package com.simulator.ui.cells;

import com.simulator.model.Client;
import com.simulator.model.ViolationLevel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * One client row in the abuse report: name, level badge, telemetry, and a
 * severity coloured threat meter.
 *
 * <p>The parent {@link ListView} is supplied so each card can bind its width to
 * the list and the empty state can be shown centred inside it.
 */
public class ClientReportCell extends ListCell<Client> {

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
}
