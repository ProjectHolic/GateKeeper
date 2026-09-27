package com.simulator.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * A small undecorated modal carrying one message and one button.
 *
 * <p>Used for validation failures, for results, and for the confirmation that
 * guards an import. In confirm mode the button becomes a real choice and the
 * return value says whether it was taken.
 *
 * <p>Deliberately blocking ({@code showAndWait}): the caller must not carry on
 * as though an import had been confirmed.
 */
public final class NoticeDialog {

    /**
     * Room left around the card for its drop shadow.
     *
     * <p>A node's shadow renders outside its own bounds, so a scene sized exactly
     * to the card clips it. The card is therefore built at its natural size and
     * placed inside a scene this much larger on every side.
     */
    public static final int SHADOW_MARGIN = 32;

    private static final int WIDTH = 420;
    private static final int HEIGHT = 170;
    private static final int HEIGHT_CONFIRM = 250;

    private NoticeDialog() {
    }

    public static void error(String message) {
        show(message, "#ef4444", "#f87171", "OK", false);
    }

    /** Reports the outcome of an export, import or other completed action. */
    public static void success(String message) {
        show(message, "#10b981", "#4ade80", "OK", false);
    }

    public static boolean confirmDestructive(String message) {
        return show(message, "#f59e0b", "#fbbf24", "Import and replace", true);
    }

    private static boolean show(String message, String accent, String textColour,
                                String actionText, boolean confirmMode) {
        Stage notice = new Stage();
        notice.initModality(Modality.WINDOW_MODAL);
        notice.initStyle(StageStyle.UNDECORATED);

        Label text = new Label(message);
        text.setWrapText(true);
        text.setFont(Font.font("System", 12));
        text.setTextFill(Color.web(textColour));
        text.setPadding(new Insets(18, 22, 12, 22));

        Button action = new Button(actionText);
        action.setPrefWidth(confirmMode ? 150 : 90);
        action.setPrefHeight(32);
        action.setFont(Font.font("System", FontWeight.BOLD, 11));
        action.setTextFill(Color.WHITE);
        action.setStyle("-fx-background-color: " + accent
                + "; -fx-background-radius: 8; -fx-cursor: hand;");

        final boolean[] confirmed = {false};
        action.setOnAction(e -> {
            confirmed[0] = confirmMode;
            notice.close();
        });

        HBox actions = new HBox(action);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setPadding(new Insets(0, 22, 18, 22));

        VBox root = new VBox(text, actions);
        root.setStyle(
                "-fx-background-color: #0b1329;"
                        + "-fx-border-color: " + accent + ";"
                        + "-fx-border-width: 1.2;"
                        + "-fx-background-radius: 10;"
        );

        int height = confirmMode ? HEIGHT_CONFIRM : HEIGHT;
        root.setPrefSize(WIDTH, height);
        root.setMaxSize(WIDTH, height);

        StackPane wrapper = new StackPane(root);
        StackPane.setMargin(root, new Insets(SHADOW_MARGIN));
        wrapper.setStyle("-fx-background-color: transparent;");

        Scene scene = new Scene(
                wrapper,
                WIDTH + SHADOW_MARGIN * 2,
                height + SHADOW_MARGIN * 2
        );
        var cssUrl = NoticeDialog.class.getResource("style.css");
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl.toExternalForm());
        }
        notice.setScene(scene);
        notice.showAndWait();
        return confirmed[0];
    }
}
