package com.nelayansafe.model;

public class DepartureWindow {
    private SafetyStatus windowStatus;
    private String startTimeWib;
    private String endTimeWib;
    private int durationHours;
    private double avgWaveHeight;
    private double avgWindSpeed;
    private int avgRiskScore;

    public DepartureWindow(SafetyStatus windowStatus, String startTimeWib, String endTimeWib,
                           int durationHours, double avgWaveHeight, double avgWindSpeed, int avgRiskScore) {
        this.windowStatus = windowStatus;
        this.startTimeWib = startTimeWib;
        this.endTimeWib = endTimeWib;
        this.durationHours = durationHours;
        this.avgWaveHeight = Math.round(avgWaveHeight * 100.0) / 100.0;
        this.avgWindSpeed = Math.round(avgWindSpeed * 10.0) / 10.0;
        this.avgRiskScore = avgRiskScore;
    }

    public SafetyStatus getWindowStatus() { return windowStatus; }
    public String getStartTimeWib() { return startTimeWib; }
    public String getEndTimeWib() { return endTimeWib; }
    public int getDurationHours() { return durationHours; }
    public double getAvgWaveHeight() { return avgWaveHeight; }
    public double getAvgWindSpeed() { return avgWindSpeed; }
    public int getAvgRiskScore() { return avgRiskScore; }
}
