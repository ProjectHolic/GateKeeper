package com.simulator.ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;

public class MainApp extends Application {

    private SimulationController controller;

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(MainApp.class.getResource("dashboard.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1000, 600);
        controller = fxmlLoader.getController();
        stage.setTitle("GateKeeper Rate Limiter");
        stage.setScene(scene);
        stage.setMaximized(false);
        stage.setResizable(false);
        stage.show();
    }

    /**
     * Called when the application is shutting down, which happens as soon as
     * the last window closes. Without this the traffic sampler and the auto
     * simulation timelines are only ever ended by the JVM exiting.
     */
    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown();
        }
    }

    public static void main(String[] args) {
        launch();
    }
}
