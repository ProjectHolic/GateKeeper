package com.simulator.io;

import com.simulator.model.Client;
import com.simulator.model.RequestType;
import com.simulator.model.SimulationSettings;
import com.simulator.policy.PolicyType;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the live settings and client registry into a scenario document, and
 * validates a scenario document back into live settings and clients.
 *
 * <p>Purely a codec. It reads and writes {@link SimulationSettings} and builds
 * fresh {@link Client} objects, but it never touches observable lists, the
 * request log, or the rate limiter — those belong to the controller and are
 * reset by it after a successful parse.
 */
public final class ScenarioService {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private ScenarioService() {
    }

    /** A document that is not a valid scenario. */
    public static final class ScenarioFormatException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ScenarioFormatException(String message) {
            super(message);
        }
    }

    /** Everything a scenario file claims, already validated. */
    public record ImportedScenario(PolicyType policy,
                                    int windowSeconds,
                                    int warningThreshold,
                                    int highThreshold,
                                    int criticalThreshold,
                                    int decayAmount,
                                    Map<RequestType, Integer> severityMultipliers,
                                    Map<RequestType, Integer> customThresholds,
                                    List<Client> clients) {}

    /**
     * Snapshots the current settings and clients as a scenario document.
     *
     * <p>Deliberately excludes the log history: re-importing old timestamps
     * would feed the busiest-day calculation that derives every rate limit, so a
     * round trip could silently change the very limits it meant to preserve.
     */
    public static Map<String, Object> build(SimulationSettings settings, List<Client> clients) {
        Map<String, Object> severity = ScenarioIO.orderedMap();
        for (RequestType type : RequestType.values()) {
            severity.put(type.name(), settings.getSeverityMultipliers().getOrDefault(type, 1));
        }
        Map<String, Object> custom = ScenarioIO.orderedMap();
        for (RequestType type : RequestType.values()) {
            custom.put(type.name(), settings.getCustomThresholds().getOrDefault(type, 0));
        }

        Map<String, Object> settingsBlock = ScenarioIO.orderedMap();
        settingsBlock.put("policy", settings.getPolicy().name());
        settingsBlock.put("windowSeconds", settings.getWindowSeconds());
        settingsBlock.put("warningThreshold", settings.getWarningThreshold());
        settingsBlock.put("highThreshold", settings.getHighThreshold());
        settingsBlock.put("criticalThreshold", settings.getCriticalThreshold());
        settingsBlock.put("decayAmount", settings.getDecayAmount());
        settingsBlock.put("severityMultipliers", severity);
        settingsBlock.put("customThresholds", custom);

        List<Object> clientList = new ArrayList<>();
        for (Client client : clients) {
            clientList.add(ScenarioIO.describe(client));
        }

        Map<String, Object> document = ScenarioIO.orderedMap();
        document.put("format", ScenarioIO.FORMAT_ID);
        document.put("version", ScenarioIO.FORMAT_VERSION);
        document.put("exportedAt", LocalDateTime.now().format(TIME_FORMAT));
        document.put("settings", settingsBlock);
        document.put("clients", clientList);
        return document;
    }

    /**
     * Validates a parsed document completely before any live state is touched,
     * so a bad file can never leave the application half-imported.
     *
     * @throws ScenarioFormatException with a message naming the offending field
     */
    @SuppressWarnings("unchecked")
    public static ImportedScenario parse(Map<String, Object> document) {
        Map<String, Object> block = requireObject(document, "settings");

        String policyName = requireString(block, "policy");
        PolicyType policy;
        try {
            policy = PolicyType.valueOf(policyName);
        } catch (IllegalArgumentException e) {
            throw new ScenarioFormatException(
                    "\"policy\" is \"" + policyName + "\"; expected FIXED_WINDOW or SLIDING_WINDOW.");
        }

        int window = requireInt(block, "windowSeconds", 1, 3600);
        int warning = requireInt(block, "warningThreshold", 1, 1000);
        int high = requireInt(block, "highThreshold", 1, 1000);
        int critical = requireInt(block, "criticalThreshold", 1, 1000);
        if (warning > high || high > critical) {
            throw new ScenarioFormatException(
                    "thresholds must satisfy WARNING <= HIGH <= CRITICAL, but were "
                            + warning + " / " + high + " / " + critical + ".");
        }
        int decay = requireInt(block, "decayAmount", 0, 50);

        Map<RequestType, Integer> severity = requireEnumIntMap(block, "severityMultipliers",
                1, 10, SimulationSettings.DEFAULT_SEVERITY_MULTIPLIERS);
        Map<RequestType, Integer> custom =
                requireEnumIntMap(block, "customThresholds", 0, 10000, Map.of());

        Object rawClients = document.get("clients");
        if (!(rawClients instanceof List<?> list)) {
            throw new ScenarioFormatException("\"clients\" must be an array.");
        }
        if (list.isEmpty()) {
            throw new ScenarioFormatException("\"clients\" is empty; a scenario needs at least one client.");
        }

        List<Client> imported = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        for (Object element : list) {
            if (!(element instanceof Map)) {
                throw new ScenarioFormatException("every entry in \"clients\" must be an object.");
            }
            Map<String, Object> entry = (Map<String, Object>) element;
            String name = requireString(entry, "name").trim();
            if (name.isEmpty()) {
                throw new ScenarioFormatException("a client has an empty \"name\".");
            }
            if (!seenNames.add(name.toLowerCase(Locale.ROOT))) {
                throw new ScenarioFormatException("duplicate client name \"" + name + "\".");
            }

            Client client = new Client(name);
            client.restore(requireInt(entry, "totalRequest", 0, Integer.MAX_VALUE),
                    requireInt(entry, "violationCount", 0, Integer.MAX_VALUE),
                    requireInt(entry, "violationScore", 0, Integer.MAX_VALUE));
            imported.add(client);
        }

        return new ImportedScenario(policy, window, warning, high, critical, decay,
                severity, custom, imported);
    }

    /**
     * Writes a validated scenario into the live settings and re-derives each
     * client's level.
     *
     * <p>Telemetry arrives without a last-violation timestamp, so a restored
     * client that was locked out gets a fresh chance rather than staying blocked
     * forever. Its level is derived from its score, never set directly.
     */
    public static void applySettings(ImportedScenario scenario, SimulationSettings settings) {
        settings.setPolicy(scenario.policy());
        settings.setWindowSeconds(scenario.windowSeconds());
        settings.setWarningThreshold(scenario.warningThreshold());
        settings.setHighThreshold(scenario.highThreshold());
        settings.setCriticalThreshold(scenario.criticalThreshold());
        settings.setDecayAmount(scenario.decayAmount());
        settings.getSeverityMultipliers().clear();
        settings.getSeverityMultipliers().putAll(scenario.severityMultipliers());
        settings.getCustomThresholds().clear();
        settings.getCustomThresholds().putAll(scenario.customThresholds());

        for (Client client : scenario.clients()) {
            client.updateLevelFromScore(settings.getWarningThreshold(),
                    settings.getHighThreshold(), settings.getCriticalThreshold());
        }
    }

    // ---------------------------------------------------- strict field access

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof Map)) {
            throw new ScenarioFormatException("\"" + key + "\" must be an object.");
        }
        return (Map<String, Object>) value;
    }

    private static String requireString(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new ScenarioFormatException("\"" + key + "\" must be a non-empty string.");
        }
        return s;
    }

    private static int requireInt(Map<String, Object> parent, String key, int min, int max) {
        Object value = parent.get(key);
        if (value == null) {
            // Telemetry is optional so a hand-written file can list just names.
            if (key.equals("totalRequest") || key.equals("violationCount") || key.equals("violationScore")) {
                return 0;
            }
            throw new ScenarioFormatException("\"" + key + "\" is missing.");
        }
        if (!(value instanceof Double d) || !isWholeNumber(d)) {
            throw new ScenarioFormatException("\"" + key + "\" must be a whole number.");
        }
        long asLong = d.longValue();
        if (asLong < min || asLong > max) {
            throw new ScenarioFormatException(
                    "\"" + key + "\" is " + asLong + "; expected between " + min + " and " + max + ".");
        }
        return (int) asLong;
    }

    private static boolean isWholeNumber(Double d) {
        return !d.isNaN() && !d.isInfinite() && d == Math.rint(d);
    }

    @SuppressWarnings("unchecked")
    private static Map<RequestType, Integer> requireEnumIntMap(Map<String, Object> parent,
                                                                String key,
                                                                int min,
                                                                int max,
                                                                Map<RequestType, Integer> defaults) {
        // Constructed from the class rather than a sample map: an empty default
        // map cannot be used to infer the key type.
        Map<RequestType, Integer> result = new EnumMap<>(RequestType.class);
        result.putAll(defaults);

        Object value = parent.get(key);
        if (value == null) {
            return result;
        }
        if (!(value instanceof Map)) {
            throw new ScenarioFormatException("\"" + key + "\" must be an object.");
        }
        Map<String, Object> raw = (Map<String, Object>) value;

        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            RequestType type;
            try {
                type = RequestType.valueOf(entry.getKey());
            } catch (IllegalArgumentException e) {
                throw new ScenarioFormatException(
                        "\"" + key + "\" has unknown request type \"" + entry.getKey()
                                + "\"; expected one of " + Arrays.toString(RequestType.values()) + ".");
            }
            Object number = entry.getValue();
            if (!(number instanceof Double d) || !isWholeNumber(d)) {
                throw new ScenarioFormatException(
                        "\"" + key + "." + entry.getKey() + "\" must be a whole number.");
            }
            long asLong = d.longValue();
            if (asLong < min || asLong > max) {
                throw new ScenarioFormatException("\"" + key + "." + entry.getKey() + "\" is "
                        + asLong + "; expected between " + min + " and " + max + ".");
            }
            result.put(type, (int) asLong);
        }
        return result;
    }
}
