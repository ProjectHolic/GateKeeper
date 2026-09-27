package com.simulator.policy;
import com.simulator.model.RequestType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Counts requests inside a window that slides with the traffic.
 *
 * <p>The window always ends at the newest matching request, so a burst is
 * counted in full even when it spans what would be two fixed windows.
 */
public class SlidingWindowPolicy extends AbstractWindowPolicy {

    public SlidingWindowPolicy(int maxRequests, Duration window,
                               Map<RequestType, Integer> severityMultipliers,
                               int[] violationThresholds,
                               int decayAmount) {
        super(maxRequests, window, severityMultipliers, violationThresholds, decayAmount);
    }

    @Override
    protected Window windowFor(LocalDateTime latest) {
        // plusNanos(1) makes the range half-open like every other window.
        return new Window(latest.minus(getWindow()), latest.plusNanos(1));
    }
}
