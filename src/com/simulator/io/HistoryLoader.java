package com.simulator.io;

import com.simulator.model.RequestType;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the bundled request history.
 *
 * <p>The background half of history loading: file I/O, field splitting and
 * timestamp parsing, with no JavaFX and no knowledge of clients. It runs off
 * the application thread, so it must not touch observable state.
 */
public final class HistoryLoader {

    public static final String HISTORY_RESOURCE = "/com/simulator/files/logs.csv";

    private static final int READ_BUFFER = 1 << 16;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private HistoryLoader() {
    }

    /** One parsed history row, before it is turned into a {@code Log}. */
    public record HistoryRow(String clientName, RequestType type,
                             LocalDateTime time, String status) {}

    /**
     * Reads every well formed row from the bundled history.
     *
     * @throws IOException if the resource is missing or cannot be read
     */
    public static List<HistoryRow> readRows() throws IOException {
        List<HistoryRow> rows = new ArrayList<>();

        try (InputStream is = HistoryLoader.class.getResourceAsStream(HISTORY_RESOURCE)) {
            if (is == null) {
                throw new FileNotFoundException("Missing classpath resource " + HISTORY_RESOURCE);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8), READ_BUFFER)) {

                reader.readLine();

                String line;
                while ((line = reader.readLine()) != null) {
                    HistoryRow row = parseRow(line);
                    if (row != null) {
                        rows.add(row);
                    }
                }
            }
        }

        return rows;
    }

    /**
     * Returns {@code null} for a row that cannot be understood, so one bad line
     * among half a million cannot abort the whole load.
     */
    public static HistoryRow parseRow(String line) {
        String[] parts = line.split(",");
        if (parts.length < 4) {
            return null;
        }

        String clientName = parts[0].trim();
        String typeStr = parts[1].trim();
        String timeStr = parts[2].trim();
        String status = parts[3].trim();

        if (clientName.isEmpty() || typeStr.isEmpty() || timeStr.isEmpty()) {
            return null;
        }

        RequestType type;
        try {
            type = RequestType.valueOf(typeStr);
        } catch (IllegalArgumentException unknownType) {
            return null;
        }

        LocalDateTime time;
        try {
            time = LocalDateTime.parse(timeStr, TIME_FORMAT);
        } catch (DateTimeParseException unparseableTime) {
            return null;
        }

        return new HistoryRow(clientName, type, time, status);
    }
}
