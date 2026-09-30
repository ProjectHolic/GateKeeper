package com.simulator.model;

/**
 * How many clients the auto simulation puts on the wire per tick.
 *
 * <p>Both modes issue the same requests through the same limiter, so the rate
 * limits behave identically either way. The only difference is the shape of the
 * traffic, which is exactly what the modes are for.
 */
public enum SimulationMode {

    /**
     * One client, one request, per tick: clients are served round-robin, so
     * each is quiet for as many ticks as there are clients.
     *
     * <p>Good for watching a single client's score climb towards its lockout
     * without other traffic getting in the way.
     */
    SINGLE_CLIENT("Single Client"),

    /**
     * Every registered client issues one request of its own per tick.
     *
     * <p>Good for watching several clients cross their limits at once, and for
     * separating the clients that misbehave from those that do not. A tick
     * fires as many requests as there are clients, so a large registry
     * generates proportionally more traffic.
     */
    MULTI_CLIENT("Multiple Clients");

    private final String displayName;

    SimulationMode(String displayName) {
        this.displayName = displayName;
    }

    public String toString() {
        return displayName;
    }
}
