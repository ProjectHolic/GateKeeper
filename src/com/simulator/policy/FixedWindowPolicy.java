package com.simulator.policy;
import com.simulator.model.RequestType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Counts requests inside a fixed grid of consecutive windows.
 *
 * <p>Windows are aligned to whole multiples of the window length counted from
 * the Unix epoch, so every client sees the same boundaries and the boundaries
 * do not drift as requests arrive. A burst that straddles a boundary is split
 * across two windows; that is the inherent trade-off of a fixed window and the
 * reason {@link SlidingWindowPolicy} exists.
 */
public class FixedWindowPolicy extends AbstractWindowPolicy {

    public FixedWindowPolicy(int maxRequests, Duration window,
                             Map<RequestType, Integer> severityMultipliers,
                             int[] violationThresholds,
                             int decayAmount) {
        super(maxRequests, window, severityMultipliers, violationThresholds, decayAmount);
    }

    @Override
    protected Window windowFor(LocalDateTime latest) {
        long windowSeconds = Math.max(1, getWindow().getSeconds());

        // Request timestamps are naive local date-times and are treated as UTC
        // here purely to get a stable grid. The absolute offset is irrelevant;
        // what matters is that the same instant always lands in the same window.
        long epochSecond = latest.toEpochSecond(ZoneOffset.UTC);
        long windowStart = Math.floorDiv(epochSecond, windowSeconds) * windowSeconds;

        LocalDateTime start = LocalDateTime.ofEpochSecond(windowStart, 0, ZoneOffset.UTC);
        return new Window(start, start.plusSeconds(windowSeconds));
    }
}
