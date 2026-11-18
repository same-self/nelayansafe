package com.nelayansafe.model;

import java.util.ArrayList;
import java.util.List;

public class BargeVessel extends Vessel {

    public BargeVessel(String vesselId, String vesselName, int maxCrewCapacity,
                       boolean hasLifeJackets, boolean hasWorkingRadio) {
        super(vesselId, vesselName, "BargeVessel", maxCrewCapacity,
              16.0, 1.50, hasLifeJackets, hasWorkingRadio);
    }

    @Override
    public int calculateRiskScore(MarineForecastSlot slot) {
        double waveRatio = slot.getWaveHeight() / getMaxSafeWaveMeters();
        double windRatio = slot.getWindSpeed() / getMaxSafeWindKnots();
        double currentPenalty = Math.max(0, (slot.getCurrentSpeed() - 1.5) / 1.5) * 12.0;
        double base = Math.max(waveRatio, windRatio) * 58.0 + ((waveRatio + windRatio) * 0.5) * 16.0;
        return (int) Math.min(100, Math.round(base + currentPenalty));
    }

    @Override
    public SafetyEvaluation evaluateSafety(MarineForecastSlot slot) {
        List<String> reasons = new ArrayList<>();
        SafetyStatus status = SafetyStatus.SAFE;

        if (slot.getWaveHeight() > getMaxSafeWaveMeters()) {
            status = SafetyStatus.DO_NOT_SAIL;
            reasons.add(String.format("Wave height %.2f m exceeds BMKG Barge Vessel limit (%.2f m).",
                slot.getWaveHeight(), getMaxSafeWaveMeters()));
        }
        if (slot.getWindSpeed() > getMaxSafeWindKnots()) {
            status = SafetyStatus.DO_NOT_SAIL;
            reasons.add(String.format("Wind speed %.1f kt exceeds BMKG Barge Vessel limit (%.1f kt).",
                slot.getWindSpeed(), getMaxSafeWindKnots()));
        }

        if (status != SafetyStatus.DO_NOT_SAIL) {
            if (slot.getWaveHeight() >= getMaxSafeWaveMeters() * 0.80) {
                status = SafetyStatus.CAUTION;
                reasons.add(String.format("Wave height %.2f m is nearing the %.2f m barge stability threshold.",
                    slot.getWaveHeight(), getMaxSafeWaveMeters()));
            }
            if (slot.getWindSpeed() >= getMaxSafeWindKnots() * 0.80) {
                status = SafetyStatus.CAUTION;
                reasons.add(String.format("Wind speed %.1f kt is nearing the %.1f kt towing limit.",
                    slot.getWindSpeed(), getMaxSafeWindKnots()));
            }
            if (slot.getWindGust() > getMaxSafeWindKnots() * 1.35 || slot.getCurrentSpeed() >= 2.0) {
                status = SafetyStatus.CAUTION;
                reasons.add(String.format("Cross-gusts (%.1f kt) or strong current (%.2f kt) require reduced barge speed.",
                    slot.getWindGust(), slot.getCurrentSpeed()));
            }
        }

        if (reasons.isEmpty()) {
            reasons.add("Wave height, wind speed, and currents are safe for barge operations.");
        }

        int score = calculateRiskScore(slot);
        String summary = switch (status) {
            case DO_NOT_SAIL -> "DO NOT SAIL: Conditions exceed official BMKG safety limits for Barge Vessels.";
            case CAUTION -> "CAUTION: Approaching BMKG thresholds for Barge Vessels. Inspect towlines before departure.";
            case SAFE -> "SAFE TO SAIL: Sea state is within safe operating thresholds for Barge Vessels.";
        };

        return new SafetyEvaluation(status, score, getVesselType(), getMaxSafeWindKnots(),
                getMaxSafeWaveMeters(), slot.getTimeWib(), summary, reasons);
    }
}
