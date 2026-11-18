package com.nelayansafe.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SeaZone {
    private String areaCode;
    private String areaName;
    private String province;
    private String stationName;
    private String issuedUtc;
    private String validFromUtc;
    private String validToUtc;
    private String dataSourceMode;
    private String fetchedAtWib;
    private List<MarineForecastSlot> day1Slots;

    public SeaZone(String areaCode, String areaName, String province) {
        this.areaCode = areaCode;
        this.areaName = areaName;
        this.province = province;
        this.day1Slots = new ArrayList<>();
    }

    public String getAreaCode() { return areaCode; }
    public String getAreaName() { return areaName; }
    public void setAreaName(String areaName) { this.areaName = areaName; }
    public String getProvince() { return province; }
    public void setProvince(String province) { this.province = province; }
    public String getStationName() { return stationName; }
    public void setStationName(String stationName) { this.stationName = stationName; }
    public String getIssuedUtc() { return issuedUtc; }
    public void setIssuedUtc(String issuedUtc) { this.issuedUtc = issuedUtc; }
    public String getValidFromUtc() { return validFromUtc; }
    public void setValidFromUtc(String validFromUtc) { this.validFromUtc = validFromUtc; }
    public String getValidToUtc() { return validToUtc; }
    public void setValidToUtc(String validToUtc) { this.validToUtc = validToUtc; }
    public String getDataSourceMode() { return dataSourceMode; }
    public void setDataSourceMode(String dataSourceMode) { this.dataSourceMode = dataSourceMode; }
    public String getFetchedAtWib() { return fetchedAtWib; }
    public void setFetchedAtWib(String fetchedAtWib) { this.fetchedAtWib = fetchedAtWib; }

    public List<MarineForecastSlot> getDay1Slots() {
        return Collections.unmodifiableList(day1Slots);
    }

    public void setDay1Slots(List<MarineForecastSlot> slots) {
        this.day1Slots = new ArrayList<>(slots);
    }
}
