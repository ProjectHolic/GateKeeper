package com.simulator.model;

import java.time.LocalDateTime;

public class Request {
    private Client client;
    private LocalDateTime time;
    private  RequestType type;
    public Request(Client client, RequestType type){
        this.client = client;
        this.time = LocalDateTime.now();
        this.type = type;
    }
    public Client getClient(){
        return this.client;
    }
    public LocalDateTime getTime(){
        return this.time;
    }
    public  RequestType getType(){
        return this.type;
    }
}
