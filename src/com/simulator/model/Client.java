package com.simulator.model;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.time.LocalDateTime;

public class Client {

    private final String name;

    private final IntegerProperty totalRequest =
            new SimpleIntegerProperty(0);

    private final IntegerProperty violationScore =
            new SimpleIntegerProperty(0);

    private final IntegerProperty violationCount =
            new SimpleIntegerProperty(0);

    private final ObjectProperty<ViolationLevel> level =
            new SimpleObjectProperty<>(ViolationLevel.NONE);

    private LocalDateTime lastViolationTime;

    public Client(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
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


    public int getViolationScore() {
        return violationScore.get();
    }

    public IntegerProperty violationScoreProperty() {
        return violationScore;
    }

    public void addViolationScore(int score) {
        violationScore.set(violationScore.get() + score);
        lastViolationTime = LocalDateTime.now();
    }

    public void decayViolationScore(int decayAmount) {
        int newScore = Math.max(0, violationScore.get() - decayAmount);
        violationScore.set(newScore);
    }

    public int getViolationCount() {
        return violationCount.get();
    }

    public IntegerProperty violationCountProperty() {
        return violationCount;
    }

    public void increaseViolationCount() {
        violationCount.set(violationCount.get() + 1);
    }


    public ViolationLevel getLevel() {
        return level.get();
    }

    public ObjectProperty<ViolationLevel> levelProperty() {
        return level;
    }

    public LocalDateTime getLastViolationTime() {
        return lastViolationTime;
    }

    /** Returns the client to a pristine state, as if it had just been registered. */
    public void reset() {
        totalRequest.set(0);
        violationScore.set(0);
        violationCount.set(0);
        level.set(ViolationLevel.NONE);
        lastViolationTime = null;
    }

    /**
     * The single place {@link #level} is derived. Nothing else may set it
     * directly, otherwise the badge can disagree with the score beside it.
     */
    public void updateLevelFromScore(int warningThreshold, int highThreshold, int criticalThreshold) {
        int score = violationScore.get();
        if (score >= criticalThreshold) {
            this.level.set(ViolationLevel.CRITICAL);
        } else if (score >= highThreshold) {
            this.level.set(ViolationLevel.HIGH);
        } else if (score >= warningThreshold) {
            this.level.set(ViolationLevel.WARNING);
        } else {
            this.level.set(ViolationLevel.NONE);
        }
    }

  @Override
    public String toString() {
        return name;
    }
}