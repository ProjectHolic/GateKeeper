module com.simulator {
    requires javafx.controls;
    requires javafx.fxml;

    exports com.simulator.ui;
    opens com.simulator.ui to javafx.fxml;
}