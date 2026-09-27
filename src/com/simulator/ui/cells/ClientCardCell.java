package com.simulator.ui.cells;

import com.simulator.model.Client;
import com.simulator.model.ViolationLevel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

/**
 * A client in the sidebar registry list: name, telemetry and level badge.
 */
public class ClientCardCell extends ListCell<Client> {

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
