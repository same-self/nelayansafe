package com.nelayansafe.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SailingClearance {
    private String clearanceId;
    private String vesselId;
    private String vesselName;
    private String vesselType;
    private int crewCount;
    private int maxCrewCapacity;
    private boolean hasLifeJackets;
    private boolean hasWorkingRadio;
    private List<String> routeZoneCodes;
    private List<String> routeZoneNames;
    private int riskScore;
    private SafetyStatus safetyStatus;
    private String recommendedWindow;
    private boolean cautionAcknowledged;

    @SerializedName(value = "issuedTimestampLocal", alternate = {"issuedTimestampWib"})
    private String issuedTimestampLocal;
    private String departureTimezone;

    public SailingClearance(String clearanceId, Vessel vessel, int crewCount,
                            List<String> routeZoneCodes, List<String> routeZoneNames,
                            int riskScore, SafetyStatus safetyStatus,
                            String recommendedWindow, boolean cautionAcknowledged,
                            String issuedTimestampLocal) {
        this(clearanceId, vessel, crewCount, routeZoneCodes, routeZoneNames,
                riskScore, safetyStatus, recommendedWindow, cautionAcknowledged,
                issuedTimestampLocal, null);
    }

    public SailingClearance(String clearanceId, Vessel vessel, int crewCount,
                            List<String> routeZoneCodes, List<String> routeZoneNames,
                            int riskScore, SafetyStatus safetyStatus,
                            String recommendedWindow, boolean cautionAcknowledged,
                            String issuedTimestampLocal, String departureTimezone) {
        this.clearanceId = clearanceId;
        this.vesselId = vessel.getVesselId();
        this.vesselName = vessel.getVesselName();
        this.vesselType = vessel.getVesselType();
        this.crewCount = crewCount;
        this.maxCrewCapacity = vessel.getMaxCrewCapacity();
        this.hasLifeJackets = vessel.isHasLifeJackets();
        this.hasWorkingRadio = vessel.isHasWorkingRadio();
        this.routeZoneCodes = new ArrayList<>(routeZoneCodes);
        this.routeZoneNames = new ArrayList<>(routeZoneNames);
        this.riskScore = riskScore;
        this.safetyStatus = safetyStatus;
        this.recommendedWindow = recommendedWindow;
        this.cautionAcknowledged = cautionAcknowledged;
        this.issuedTimestampLocal = issuedTimestampLocal;
        this.departureTimezone = departureTimezone;
    }

    public String getClearanceId() { return clearanceId; }
    public String getVesselId() { return vesselId; }
    public String getVesselName() { return vesselName; }
    public String getVesselType() { return vesselType; }
    public int getCrewCount() { return crewCount; }
    public int getMaxCrewCapacity() { return maxCrewCapacity; }
    public boolean isHasLifeJackets() { return hasLifeJackets; }
    public boolean isHasWorkingRadio() { return hasWorkingRadio; }
    public List<String> getRouteZoneCodes() { return Collections.unmodifiableList(routeZoneCodes); }
    public List<String> getRouteZoneNames() { return Collections.unmodifiableList(routeZoneNames); }
    public int getRiskScore() { return riskScore; }
    public SafetyStatus getSafetyStatus() { return safetyStatus; }
    public String getRecommendedWindow() { return recommendedWindow; }
    public boolean isCautionAcknowledged() { return cautionAcknowledged; }
    public String getIssuedTimestampLocal() { return issuedTimestampLocal; }
    public String getDepartureTimezone() { return departureTimezone; }
}
