package com.simulator.io;

import com.simulator.model.Client;
import com.simulator.model.Log;
import com.simulator.model.RequestType;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the files the dashboard can export and import.
 *
 * <p>Two formats, for two different needs:
 *
 * <ul>
 *   <li><b>Scenario</b> (JSON) — the settings plus the client registry and each
 *       client's telemetry, so a configuration or a hand-built scenario can be
 *       shared and resumed. Small, and hand-editable.
 *   <li><b>Request log</b> (CSV) — the log history, in exactly the schema of the
 *       bundled {@code logs.csv}. Write-only.
 * </ul>
 *
 * <p>The log is deliberately not part of the scenario file. Half a million rows
 * of timestamps from the past would be multi-megabyte, and re-importing them
 * would feed the "busiest day this client ever had" calculation that derives
 * every rate limit — so a round trip could silently change the limits it was
 * supposed to preserve.
 */
public final class ScenarioIO {

    public static final String FORMAT_ID = "gatekeeper-scenario";
    public static final int FORMAT_VERSION = 1;

    public static final String CSV_HEADER = "client,type,time,status";
    private static final DateTimeFormatter CSV_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private ScenarioIO() {
    }

    // -------------------------------------------------------------- scenario

    public static void writeScenario(File file, Map<String, Object> scenario) throws IOException {
        Files.writeString(file.toPath(), Json.write(scenario), StandardCharsets.UTF_8);
    }

    /**
     * Reads a scenario file and checks that it is one of ours and of a version
     * this build understands.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readScenario(File file) throws IOException {
        String text = Files.readString(file.toPath(), StandardCharsets.UTF_8);

        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (Json.JsonException e) {
            throw new IOException("Not valid JSON: " + e.getMessage());
        }

        if (!(parsed instanceof Map)) {
            throw new IOException("Expected a JSON object at the top level.");
        }
        Map<String, Object> doc = (Map<String, Object>) parsed;

        Object format = doc.get("format");
        if (!FORMAT_ID.equals(format)) {
            throw new IOException("Not a GateKeeper scenario file"
                    + " (expected \"format\": \"" + FORMAT_ID + "\").");
        }

        Object version = doc.get("version");
        if (!(version instanceof Double d)) {
            throw new IOException("Scenario file has no numeric \"version\".");
        }
        if (d.intValue() != FORMAT_VERSION) {
            throw new IOException("Scenario version " + d.intValue()
                    + " is not supported by this build (expected " + FORMAT_VERSION + ").");
        }
        return doc;
    }

    // ------------------------------------------------------------------- csv

    /**
     * Writes the log history in the same schema as the bundled history, so the
     * output can be inspected or fed to other tooling.
     */
    public static void writeLogCsv(File file, List<Log> logs) throws IOException {
        try (Writer out = new BufferedWriter(
                Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))) {
            out.write(CSV_HEADER);
            out.write('\n');
            for (Log log : logs) {
                RequestType type = log.getType();
                out.write(csvField(log.getClient().getName()));
                out.write(',');
                out.write(type == null ? "" : type.name());
                out.write(',');
                out.write(log.getTime().format(CSV_TIME));
                out.write(',');
                out.write(csvField(log.getStatus()));
                out.write('\n');
            }
        }
    }

    private static String csvField(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    // ------------------------------------------------------------- utilities

    /** A new map that keeps the key order the writer produced, for readable output. */
    public static Map<String, Object> orderedMap() {
        return new LinkedHashMap<>();
    }

    /** Renders a client as the four fields that are worth persisting. */
    public static Map<String, Object> describe(Client client) {
        Map<String, Object> map = orderedMap();
        map.put("name", client.getName());
        map.put("totalRequest", client.getTotalRequest());
        map.put("violationCount", client.getViolationCount());
        map.put("violationScore", client.getViolationScore());
        return map;
    }
}
