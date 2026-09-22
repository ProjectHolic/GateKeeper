package com.simulator.model;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;

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

        appendToCSV(); //store each log in csv
    }

    private void appendToCSV(){
        File file = new File("src/com/simulator/model/logs.csv");

        try(BufferedWriter writer = new BufferedWriter(new FileWriter(file, true))){
            String line = client.getName() + ","+ type+","+time+","+status;

            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            System.out.println("Failed to write logs.csv {NOOB} : "+e.getMessage());
        }
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
