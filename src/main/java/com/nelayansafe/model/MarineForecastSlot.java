package com.nelayansafe.model;

public class MarineForecastSlot {
    private String timeUtc;
    private String timeLocal;
    private String weather;
    private double tempAvg;
    private double rhAvg;
    private String windFrom;
    private double windSpeed;
    private double windGust;
    private String waveCat;
    private double waveHeight;
    private String currentTo;
    private double currentSpeed;

    public MarineForecastSlot(String timeUtc, String timeLocal, String weather,
                              double tempAvg, double rhAvg, String windFrom,
                              double windSpeed, double windGust, String waveCat,
                              double waveHeight, String currentTo, double currentSpeed) {
        if (waveHeight < 0 || windSpeed < 0 || windGust < 0 || currentSpeed < 0) {
            throw new IllegalArgumentException("Weather measurements cannot be negative.");
        }
        this.timeUtc = timeUtc;
        this.timeLocal = timeLocal;
        this.weather = weather;
        this.tempAvg = tempAvg;
        this.rhAvg = rhAvg;
        this.windFrom = windFrom;
        this.windSpeed = windSpeed;
        this.windGust = windGust;
        this.waveCat = waveCat;
        this.waveHeight = waveHeight;
        this.currentTo = currentTo;
        this.currentSpeed = currentSpeed;
    }

    public String getTimeUtc() { return timeUtc; }
    public String getTimeLocal() { return timeLocal; }
    public String getWeather() { return weather; }
    public double getTempAvg() { return tempAvg; }
    public double getRhAvg() { return rhAvg; }
    public String getWindFrom() { return windFrom; }
    public double getWindSpeed() { return windSpeed; }
    public double getWindGust() { return windGust; }
    public String getWaveCat() { return waveCat; }
    public double getWaveHeight() { return waveHeight; }
    public String getCurrentTo() { return currentTo; }
    public double getCurrentSpeed() { return currentSpeed; }
}
