package com.simulator.model;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

//Below 2 are generic templates from javaFX
import javafx.beans.property.ObjectProperty;
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
        /*
        generating random unique id for each user as string.
        The id will contain dashes like "6c846d28-eb03-4bee-947c-1f0b4b9b3ab8"
        Also removing the dashes with .replace(str1, str2) method.
        */
    }

    public String getName() {
        return name;
    }

    //request segment
    public int getTotalRequest() {
        return totalRequest.get();
    }
    public IntegerProperty totalRequestProperty() {
        return totalRequest;
    }
    public void increaseTotalRequest() {
        totalRequest.set(totalRequest.get() + 1);
    }

    //violation segment
    public int getViolation() {
        return violation.get();
    }
    public IntegerProperty violationProperty() {
        return violation;
    }
    public void increaseViolation() {
        violation.set(violation.get() + 1);
    }

    //Violation level segment
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