package com.nelayansafe.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SafetyEvaluation {
    private SafetyStatus status;
    private int riskScore;
    private String vesselType;
    private double maxSafeWindKnots;
    private double maxSafeWaveMeters;
    private String evaluatedTimeWib;
    private String summary;
    private List<String> reasons;

    public SafetyEvaluation(SafetyStatus status, int riskScore, String vesselType,
                            double maxSafeWindKnots, double maxSafeWaveMeters,
                            String evaluatedTimeWib, String summary, List<String> reasons) {
        this.status = status;
        this.riskScore = Math.max(0, Math.min(100, riskScore));
        this.vesselType = vesselType;
        this.maxSafeWindKnots = maxSafeWindKnots;
        this.maxSafeWaveMeters = maxSafeWaveMeters;
        this.evaluatedTimeWib = evaluatedTimeWib;
        this.summary = summary;
        this.reasons = new ArrayList<>(reasons);
    }

    public SafetyStatus getStatus() { return status; }
    public int getRiskScore() { return riskScore; }
    public String getVesselType() { return vesselType; }
    public double getMaxSafeWindKnots() { return maxSafeWindKnots; }
    public double getMaxSafeWaveMeters() { return maxSafeWaveMeters; }
    public String getEvaluatedTimeWib() { return evaluatedTimeWib; }
    public String getSummary() { return summary; }
    public List<String> getReasons() { return Collections.unmodifiableList(reasons); }
}
