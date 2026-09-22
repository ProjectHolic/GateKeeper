package com.simulator.policy;

import com.simulator.model.Client;
import com.simulator.model.Request;
import com.simulator.model.ViolationLevel;
import javafx.collections.ObservableList;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;


//This policy isn't implemented with UI till now --> 6:51AM

public class PrivatePolicy implements RatePolicy {

    private static final int DAYS_IN_6_MONTHS = 180;

    private static final double WARNING_LIMIT = 100.0;
    private static final double BLOCKED_LIMIT = 150.0;

    private static final int MAX_REQUESTS = 10;
    private static final Duration WINDOW = Duration.ofSeconds(10);

    //below the map stores total request counts from the logs dor each user (180 days logs)
    //I think there is a bug, because the size of log history changes over time but average doesnt.
    private static final Map<String, Integer> clientCounts = loadLogs();

    @Override
    public ViolationLevel evaluate(Client client, ObservableList<Request> requests) {
        if (client == null) {
            return ViolationLevel.NONE;
        }

        //daily average for the last six months, maybe needs a fix
        double dailyAverage = getDailyAverage(client.getName());

        if (dailyAverage >= BLOCKED_LIMIT) {
            client.increaseViolation();
            return ViolationLevel.CRITICAL; //blocked
        }

        if (dailyAverage >= WARNING_LIMIT) {
            client.increaseViolation();
            return ViolationLevel.HIGH; //warning
        }

        //requests in the 10-second window NEW user (noob)
        int recentRequests = 0;
        LocalDateTime now = LocalDateTime.now();

        for (Request req : requests) {
            if (req.getClient().equals(client)) {
                if (Duration.between(req.getTime(), now).compareTo(WINDOW) <= 0) {
                    recentRequests++;
                }
            }
        }

        if (recentRequests > MAX_REQUESTS) {
            client.increaseViolation();
            return ViolationLevel.HIGH; // Warning for spamming
        }

        recordRequest(client.getName()); //storing new requests
        return ViolationLevel.NONE;
    }

    //counts new requests and changes the request count at runtime for better precision
    public static void recordRequest(String clientName) {
        if (clientName != null) {
            String key = clientName.trim();
            clientCounts.put(key, clientCounts.getOrDefault(key, 0) + 1);
        }
    }

    //daily average of 180 days
    public static double getDailyAverage(String clientName) {
        if (clientName == null) {
            return 0.0;
        }

        int totalRequests = clientCounts.getOrDefault(clientName.trim(),0);
        return (double) totalRequests/DAYS_IN_6_MONTHS;
    }

    // Reads logs.csv and counts how many requests each client made
    private static Map<String, Integer> loadLogs() {
        Map<String, Integer> counts = new HashMap<>();

        File file = new File("src/com/simulator/model/logs.csv");

        if (!file.exists()) {
            System.out.println("logs.csv file not found.");
            return counts;
        }

        try(BufferedReader reader = new BufferedReader(new FileReader(file))){
            String line = reader.readLine(); //kicks the header line of CSV

            while((line = reader.readLine())!= null){
                if (line.isBlank()) continue;
                String[] segments = line.split(",");
                if (segments.length > 0){
                    String clientName = segments[0].trim();
                    int currentCount = counts.getOrDefault(clientName, 0);
                    counts.put(clientName, currentCount+1);
                }
            }
        } catch (Exception e) {
            System.out.println("Error reading logs.csv" + e.getMessage());
        }

        return counts;
    }
}
