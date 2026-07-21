package com.coresplit.config;

public enum ClientOptimizationMode {

    AUTO("auto", "Auto"),
    LOCAL("local", "Local"),
    ONLINE("online", "Online");

    private final String id;
    private final String label;

    ClientOptimizationMode(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String getId()    { return id; }
    public String getLabel() { return label; }

    @Override
    public String toString() { return label; }
}