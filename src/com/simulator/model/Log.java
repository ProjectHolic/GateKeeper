package com.simulator.model;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class Log {
    private final Client client;
    private final RequestType type;
    private final LocalDateTime time;
    private  final String status;

    public Log(Request request, String status){
        this.client = request.getClient();
        this.time =  request.getTime();
        this.type = request.getType();
        this.status = status;
    }


    public Client getClient() {
        return this.client;
    }

    public LocalDateTime getTime() {
        return this.time;
    }

    public RequestType getType() {
        return this.type;
    }

    public String getStatus() {
        return status;
    }

    @Override
    public String toString() {
        return client.toString() + " " + type + " " + status;
    }
}
