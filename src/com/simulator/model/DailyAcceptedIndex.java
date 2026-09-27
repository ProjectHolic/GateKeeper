package com.simulator.model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Running index of accepted requests, bucketed by client, request type and local
 * date.
 *
 * <p>Deriving a rate limit needs the peak number of accepted requests a client
 * made on any single day. Scanning the whole log history for that on every
 * request is O(logs), which is far too expensive against a history of half a
 * million entries. This index keeps the daily tallies up to date incrementally
 * and caches the peak per client and type, so the lookup is effectively
 * constant time.
 */
public final class DailyAcceptedIndex {

    private final Map<Client, Map<RequestType, NavigableMap<LocalDate, Integer>>> dailyCounts =
            new IdentityHashMap<>();

    private final Map<Client, Map<RequestType, Integer>> peakDailyCache =
            new IdentityHashMap<>();

    /** Records an accepted request. Blocked entries are ignored. */
    public void add(Log log) {
        if (!Log.STATUS_ACCEPTED.equals(log.getStatus())) {
            return;
        }
        tally(log.getClient(), log.getType(), log.getTime().toLocalDate(), 1);
    }

    /** Undoes a previously recorded entry. */
    public void remove(Log log) {
        if (!Log.STATUS_ACCEPTED.equals(log.getStatus())) {
            return;
        }
        tally(log.getClient(), log.getType(), log.getTime().toLocalDate(), -1);
    }

    public void clear() {
        dailyCounts.clear();
        peakDailyCache.clear();
    }

    /**
     * Highest number of accepted requests of the given type the client made on
     * any single day, or {@code 0} when the client has no accepted history.
     */
    public int maxDailyAccepted(Client client, RequestType type) {
        Map<RequestType, Integer> peaks = peakDailyCache.get(client);
        if (peaks == null) {
            peaks = new EnumMap<>(RequestType.class);
            peakDailyCache.put(client, peaks);
        }

        Integer cached = peaks.get(type);
        if (cached != null) {
            return cached;
        }

        int peak = 0;
        for (Integer count : perType(client, type).values()) {
            if (count != null && count > peak) {
                peak = count;
            }
        }

        peaks.put(type, peak);
        return peak;
    }

    private void tally(Client client, RequestType type, LocalDate day, int delta) {
        dailyCounts
                .computeIfAbsent(client, c -> new EnumMap<>(RequestType.class))
                .computeIfAbsent(type, t -> new TreeMap<>())
                .merge(day, delta, Integer::sum);
        peakDailyCache.remove(client);
    }

    private NavigableMap<LocalDate, Integer> perType(Client client, RequestType type) {
        Map<RequestType, NavigableMap<LocalDate, Integer>> byType = dailyCounts.get(client);
        if (byType == null) {
            return Collections.emptyNavigableMap();
        }
        return byType.getOrDefault(type, Collections.emptyNavigableMap());
    }
}
