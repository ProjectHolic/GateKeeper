package com.simulator.policy;
import com.simulator.model.Client;
import com.simulator.model.Request;
import com.simulator.model.ViolationLevel;
import javafx.collections.ObservableList;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

public class FixedWindowPolicy implements RatePolicy {

    private final int maxRequests;
    private final Duration window;

    public FixedWindowPolicy(int maxRequests, Duration window) {
        this.maxRequests = maxRequests;
        this.window = window;
    }

    @Override
    public ViolationLevel evaluate(
            Client client,
            ObservableList<Request> requests) {

        if(client.getViolation() >= 50) return ViolationLevel.CRITICAL;

        List<Request> clientRequests = requests.stream()
                .filter(request ->
                        request.getClient().equals(client))
                .sorted((a, b) ->
                        a.getTime().compareTo(b.getTime()))
                .toList();

        if (clientRequests.isEmpty()) {
            return ViolationLevel.NONE;
        }

        LocalDateTime latest =  clientRequests.get(clientRequests.size() - 1).getTime();

        LocalDateTime windowStart = latest.minusSeconds(latest.getSecond()%window.getSeconds()).withNano(0);

        LocalDateTime windowEnd =
                windowStart.plus(window);

        long count = clientRequests.stream()
                .filter(request ->
                        !request.getTime().isBefore(windowStart)
                                && request.getTime().isBefore(windowEnd))
                .count();


        if (count > maxRequests * 3L) {
            client.increaseViolation();
            return ViolationLevel.CRITICAL;
        }

        if (count > maxRequests) {
            client.increaseViolation();
            return ViolationLevel.HIGH;
        }

        return ViolationLevel.NONE;
    }
}