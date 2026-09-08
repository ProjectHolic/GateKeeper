package com.simulator.policy;
import com.simulator.model.Client;
import com.simulator.model.Request;
import com.simulator.model.ViolationLevel;
import javafx.collections.ObservableList;

public interface RatePolicy {

    public ViolationLevel evaluate(Client client, ObservableList<Request> requests);

}