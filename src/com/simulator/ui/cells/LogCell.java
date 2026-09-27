package com.simulator.ui.cells;

import com.simulator.model.Log;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.time.format.DateTimeFormatter;

/**
 * One request log entry: who, what, when, and the outcome.
 */
public class LogCell extends ListCell<Log> {

    private static final DateTimeFormatter CLOCK_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss");

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
