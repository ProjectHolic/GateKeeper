package com.simulator.model;

import com.simulator.policy.PolicyType;

import java.util.EnumMap;
import java.util.Map;

/**
 * Everything the settings dialog edits, and the one place a rate limit is
 * decided.
 *
 * <p>Deliberately free of JavaFX so the rules can be exercised without a
 * toolkit. The controller owns an instance and hands it to the dialog; the
 * dialog edits it through {@link #setPolicy}, the setters below, and
 * {@link #resetToDefaults()}.
 */
public final class SimulationSettings {

    /** A client may exceed its busiest day by this factor before it is limited. */
    public static final double THRESHOLD_HEADROOM = 1.5;

    /** Upper bound on a history derived limit, however busy the client has been. */
    public static final int THRESHOLD_CEILING = 200;

    public static final PolicyType DEFAULT_POLICY = PolicyType.FIXED_WINDOW;
    public static final int DEFAULT_WINDOW_SECONDS = 10;
    public static final int DEFAULT_WARNING_THRESHOLD = 20;
    public static final int DEFAULT_HIGH_THRESHOLD = 50;
    public static final int DEFAULT_CRITICAL_THRESHOLD = 100;
    public static final int DEFAULT_DECAY_AMOUNT = 1;

    /** Custom thresholds default to 0, meaning "derive from the client's history". */
    public static final int DEFAULT_CUSTOM_THRESHOLD = 0;

    public static final Map<RequestType, Integer> DEFAULT_SEVERITY_MULTIPLIERS;

    static {
        Map<RequestType, Integer> severity = new EnumMap<>(RequestType.class);
        severity.put(RequestType.READ, 1);
        severity.put(RequestType.WRITE, 2);
        severity.put(RequestType.LOGIN, 3);
        severity.put(RequestType.PAYMENT, 2);
        DEFAULT_SEVERITY_MULTIPLIERS = Map.copyOf(severity);
    }

    private PolicyType policy = DEFAULT_POLICY;
    private int windowSeconds = DEFAULT_WINDOW_SECONDS;
    private int warningThreshold = DEFAULT_WARNING_THRESHOLD;
    private int highThreshold = DEFAULT_HIGH_THRESHOLD;
    private int criticalThreshold = DEFAULT_CRITICAL_THRESHOLD;
    private int decayAmount = DEFAULT_DECAY_AMOUNT;

    private final Map<RequestType, Integer> severityMultipliers = new EnumMap<>(RequestType.class);
    private final Map<RequestType, Integer> customThresholds = new EnumMap<>(RequestType.class);

    public SimulationSettings() {
        resetToDefaults();
    }

    public void resetToDefaults() {
        policy = DEFAULT_POLICY;
        windowSeconds = DEFAULT_WINDOW_SECONDS;
        warningThreshold = DEFAULT_WARNING_THRESHOLD;
        highThreshold = DEFAULT_HIGH_THRESHOLD;
        criticalThreshold = DEFAULT_CRITICAL_THRESHOLD;
        decayAmount = DEFAULT_DECAY_AMOUNT;
        for (RequestType type : RequestType.values()) {
            customThresholds.put(type, DEFAULT_CUSTOM_THRESHOLD);
        }
        severityMultipliers.clear();
        severityMultipliers.putAll(DEFAULT_SEVERITY_MULTIPLIERS);
    }

    // ------------------------------------------------------------- accessors

    public PolicyType getPolicy() {
        return policy;
    }

    public void setPolicy(PolicyType policy) {
        this.policy = policy;
    }

    public int getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(int windowSeconds) {
        this.windowSeconds = windowSeconds;
    }

    public int getWarningThreshold() {
        return warningThreshold;
    }

    public void setWarningThreshold(int warningThreshold) {
        this.warningThreshold = warningThreshold;
    }

    public int getHighThreshold() {
        return highThreshold;
    }

    public void setHighThreshold(int highThreshold) {
        this.highThreshold = highThreshold;
    }

    public int getCriticalThreshold() {
        return criticalThreshold;
    }

    public void setCriticalThreshold(int criticalThreshold) {
        this.criticalThreshold = criticalThreshold;
    }

    public int getDecayAmount() {
        return decayAmount;
    }

    public void setDecayAmount(int decayAmount) {
        this.decayAmount = decayAmount;
    }

    /** Live view; the dialog edits these maps in place through the spinners. */
    public Map<RequestType, Integer> getSeverityMultipliers() {
        return severityMultipliers;
    }

    public Map<RequestType, Integer> getCustomThresholds() {
        return customThresholds;
    }

    public int getSeverity(RequestType type) {
        return severityMultipliers.getOrDefault(type, 1);
    }

    public void setSeverity(RequestType type, int value) {
        severityMultipliers.put(type, value);
    }

    public int getCustomThreshold(RequestType type) {
        return customThresholds.getOrDefault(type, DEFAULT_CUSTOM_THRESHOLD);
    }

    public void setCustomThreshold(RequestType type, int value) {
        customThresholds.put(type, value);
    }

    // ------------------------------------------------------------ the rules

    /**
     * The built-in floor for a request type, used when nothing better is known.
     */
    public static int defaultThreshold(RequestType type) {
        return switch (type) {
            case LOGIN -> 5;
            case PAYMENT -> 10;
            case WRITE -> 20;
            case READ -> 50;
        };
    }

    /**
     * The one place a request limit is decided.
     *
     * <p>The dashboard label and the enforcing policy both go through here, so
     * what is advertised is necessarily what is enforced. A custom threshold
     * above zero wins; otherwise the limit is derived from the client's own
     * history.
     *
     * @param busiestDayCount how many accepted requests of this type the client
     *                        made on its busiest day, or {@code 0} when unknown
     */
    public int resolveThreshold(RequestType type, int busiestDayCount) {
        int custom = customThresholds.getOrDefault(type, DEFAULT_CUSTOM_THRESHOLD);
        if (custom > 0) {
            return custom;
        }
        if (busiestDayCount <= 0) {
            return defaultThreshold(type);
        }
        int headroom = (int) Math.ceil(busiestDayCount * THRESHOLD_HEADROOM);
        return Math.max(defaultThreshold(type), Math.min(headroom, THRESHOLD_CEILING));
    }

    /** True when the three score thresholds are in a usable order. */
    public boolean thresholdsAreOrdered() {
        return warningThreshold <= highThreshold && highThreshold <= criticalThreshold;
    }
}
