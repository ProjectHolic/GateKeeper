package com.simulator.model;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.UUID;

public class Client {

    private final String name;
    private final String id;

    private final IntegerProperty totalRequest =
            new SimpleIntegerProperty(0);

    private final IntegerProperty violation =
            new SimpleIntegerProperty(0);

    private final ObjectProperty<ViolationLevel> level =
            new SimpleObjectProperty<>(ViolationLevel.NONE);

    public Client(String name) {
        this.name = name;
        this.id = UUID.randomUUID()
                .toString()
                .replace("-", "");
    }

    public String getName() {
        return name;
    }

    public String getId() {
        return id;
    }


    public int getTotalRequest() {
        return totalRequest.get();
    }

    public IntegerProperty totalRequestProperty() {
        return totalRequest;
    }

    public void increaseTotalRequest() {
        totalRequest.set(totalRequest.get() + 1);
    }


    public int getViolation() {
        return violation.get();
    }

    public IntegerProperty violationProperty() {
        return violation;
    }

    public void increaseViolation() {
        violation.set(violation.get() + 1);
    }


    public ViolationLevel getLevel() {
        return level.get();
    }

    public ObjectProperty<ViolationLevel> levelProperty() {
        return level;
    }

    public void setLevel(ViolationLevel level) {
        this.level.set(level);
    }

  @Override
    public String toString() {
        return name;
    }
}