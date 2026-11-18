package com.nelayansafe.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RouteEvaluation {
    public static class ZoneSegment {
        private String areaCode;
        private String areaName;
        private String province;
        private String dataSourceMode;
        private MarineForecastSlot currentSlot;
        private SafetyEvaluation evaluation;

        public ZoneSegment(String areaCode, String areaName, String province, String dataSourceMode,
                           MarineForecastSlot currentSlot, SafetyEvaluation evaluation) {
            this.areaCode = areaCode;
            this.areaName = areaName;
            this.province = province;
            this.dataSourceMode = dataSourceMode;
            this.currentSlot = currentSlot;
            this.evaluation = evaluation;
        }

        public String getAreaCode() { return areaCode; }
        public String getAreaName() { return areaName; }
        public String getProvince() { return province; }
        public String getDataSourceMode() { return dataSourceMode; }
        public MarineForecastSlot getCurrentSlot() { return currentSlot; }
        public SafetyEvaluation getEvaluation() { return evaluation; }
    }

    private SafetyStatus overallStatus;
    private int highestRiskScore;
    private String bottleneckAreaCode;
    private String bottleneckAreaName;
    private String summary;
    private List<ZoneSegment> segments;

    public RouteEvaluation(SafetyStatus overallStatus, int highestRiskScore,
                           String bottleneckAreaCode, String bottleneckAreaName,
                           String summary, List<ZoneSegment> segments) {
        this.overallStatus = overallStatus;
        this.highestRiskScore = highestRiskScore;
        this.bottleneckAreaCode = bottleneckAreaCode;
        this.bottleneckAreaName = bottleneckAreaName;
        this.summary = summary;
        this.segments = new ArrayList<>(segments);
    }

    public SafetyStatus getOverallStatus() { return overallStatus; }
    public int getHighestRiskScore() { return highestRiskScore; }
    public String getBottleneckAreaCode() { return bottleneckAreaCode; }
    public String getBottleneckAreaName() { return bottleneckAreaName; }
    public String getSummary() { return summary; }
    public List<ZoneSegment> getSegments() { return Collections.unmodifiableList(segments); }
}
