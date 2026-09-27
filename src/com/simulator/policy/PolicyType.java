package com.simulator.policy;

/** The rate limiting algorithms the simulator can run. */
public enum PolicyType {

    FIXED_WINDOW("Fixed Window"),
    SLIDING_WINDOW("Sliding Window");

    private final String displayName;

    PolicyType(String displayName) {
        this.displayName = displayName;
    }

    public String toString() {
        return displayName;
    }
}
