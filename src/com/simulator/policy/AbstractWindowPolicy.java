package com.simulator.policy;

import com.simulator.model.Client;
import com.simulator.model.Request;
import com.simulator.model.RequestType;
import javafx.collections.ObservableList;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Scoring, decay and lockout logic shared by every window based policy.
 *
 * <p>Subclasses only decide where the window starts and ends; everything else
 * is identical, and keeping it in one place stops the two policies from
 * drifting apart.
 */
abstract class AbstractWindowPolicy implements RatePolicy {

    /** A half-open time range {@code [start, end)}. */
    protected record Window(LocalDateTime start, LocalDateTime end) {}

    protected static final int WARNING_PENALTY = 5;
    protected static final int HIGH_PENALTY = 15;
    protected static final int CRITICAL_PENALTY = 30;

    private final int maxRequests;
    private final Duration window;
    private final Map<RequestType, Integer> severityMultipliers;
    private final int warningThreshold;
    private final int highThreshold;
    private final int criticalThreshold;
    private final int decayAmount;

    protected AbstractWindowPolicy(int maxRequests, Duration window,
                                   Map<RequestType, Integer> severityMultipliers,
                                   int[] violationThresholds,
                                   int decayAmount) {
        if (violationThresholds == null || violationThresholds.length < 3) {
            throw new IllegalArgumentException("violationThresholds must hold warning, high and critical");
        }
        this.maxRequests = maxRequests;
        this.window = window;
        this.severityMultipliers = severityMultipliers;
        this.warningThreshold = violationThresholds[0];
        this.highThreshold = violationThresholds[1];
        this.criticalThreshold = violationThresholds[2];
        this.decayAmount = decayAmount;
    }

    /** The half-open range that a request stamped {@code latest} is counted against. */
    protected abstract Window windowFor(LocalDateTime latest);

    @Override
    public Decision evaluate(Client client, RequestType type, ObservableList<Request> requests) {
        refreshLevel(client);

        // A client that has hit the critical threshold is locked out, but only
        // until one full window has passed without a fresh violation. Without
        // that the first critical score would block the client for good.
        if (client.getViolationScore() >= criticalThreshold && isCoolingDown(client)) {
            return Decision.blocked(client.getLevel());
        }

        List<Request> matching = requests.stream()
                .filter(request -> request.getClient() == client)
                .filter(request -> request.getType() == type)
                .sorted(Comparator.comparing(Request::getTime))
                .toList();

        if (matching.isEmpty()) {
            return Decision.allowed(client.getLevel());
        }

        LocalDateTime latest = matching.get(matching.size() - 1).getTime();
        Window bounds = windowFor(latest);

        long count = matching.stream()
                .filter(request -> !request.getTime().isBefore(bounds.start())
                        && request.getTime().isBefore(bounds.end()))
                .count();

        int severity = severityMultipliers.getOrDefault(type, 1);

        if (count > maxRequests * 3L) {
            return penalise(client, CRITICAL_PENALTY * severity);
        }
        if (count > maxRequests * 2L) {
            return penalise(client, HIGH_PENALTY * severity);
        }
        if (count > maxRequests) {
            return penalise(client, WARNING_PENALTY * severity);
        }

        if (client.getViolationScore() > 0) {
            client.decayViolationScore(decayAmount);
        }
        refreshLevel(client);
        return Decision.allowed(client.getLevel());
    }

    private Decision penalise(Client client, int penalty) {
        client.addViolationScore(penalty);
        client.increaseViolationCount();
        refreshLevel(client);
        return Decision.blocked(client.getLevel());
    }

    private void refreshLevel(Client client) {
        client.updateLevelFromScore(warningThreshold, highThreshold, criticalThreshold);
    }

    private boolean isCoolingDown(Client client) {
        LocalDateTime lastViolation = client.getLastViolationTime();
        return lastViolation != null
                && Duration.between(lastViolation, LocalDateTime.now()).compareTo(window) < 0;
    }

    public int getMaxRequests() {
        return maxRequests;
    }

    protected Duration getWindow() {
        return window;
    }
}
