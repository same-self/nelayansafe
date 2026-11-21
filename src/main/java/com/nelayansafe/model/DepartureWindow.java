package com.nelayansafe.model;

public class DepartureWindow {
    private SafetyStatus windowStatus;
    private String startTimeLocal;
    private String endTimeLocal;
    private int durationHours;
    private double avgWaveHeight;
    private double avgWindSpeed;
    private int avgRiskScore;

    public DepartureWindow(SafetyStatus windowStatus, String startTimeLocal, String endTimeLocal,
                           int durationHours, double avgWaveHeight, double avgWindSpeed, int avgRiskScore) {
        this.windowStatus = windowStatus;
        this.startTimeLocal = startTimeLocal;
        this.endTimeLocal = endTimeLocal;
        this.durationHours = durationHours;
        this.avgWaveHeight = Math.round(avgWaveHeight * 100.0) / 100.0;
        this.avgWindSpeed = Math.round(avgWindSpeed * 10.0) / 10.0;
        this.avgRiskScore = avgRiskScore;
    }

    public SafetyStatus getWindowStatus() { return windowStatus; }
    public String getStartTimeLocal() { return startTimeLocal; }
    public String getEndTimeLocal() { return endTimeLocal; }
    public int getDurationHours() { return durationHours; }
    public double getAvgWaveHeight() { return avgWaveHeight; }
    public double getAvgWindSpeed() { return avgWindSpeed; }
    public int getAvgRiskScore() { return avgRiskScore; }
}
