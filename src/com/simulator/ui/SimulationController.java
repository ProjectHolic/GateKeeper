package com.simulator.ui;

import javafx.fxml.FXML;
import javafx.scene.control.Button;

public class SimulationController {

    @FXML
    private Button AddButton;

    @FXML
    private void onAddClient(){
        System.out.println("Client added!");
    }

}
