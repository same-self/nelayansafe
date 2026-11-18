package com.nelayansafe.model;

import java.util.ArrayList;
import java.util.List;

public class SmallFishingBoat extends Vessel {

    public SmallFishingBoat(String vesselId, String vesselName, int maxCrewCapacity,
                            boolean hasLifeJackets, boolean hasWorkingRadio) {
        super(vesselId, vesselName, "SmallFishingBoat", maxCrewCapacity,
              15.0, 1.25, hasLifeJackets, hasWorkingRadio);
    }

    @Override
    public int calculateRiskScore(MarineForecastSlot slot) {
        double waveRatio = slot.getWaveHeight() / getMaxSafeWaveMeters();
        double windRatio = slot.getWindSpeed() / getMaxSafeWindKnots();
        double gustBonus = Math.max(0, (slot.getWindGust() - (getMaxSafeWindKnots() * 1.25)) / 10.0) * 15.0;
        double currentBonus = Math.max(0, (slot.getCurrentSpeed() - 1.2) / 1.5) * 14.0;
        double base = Math.max(waveRatio, windRatio) * 58.0 + ((waveRatio + windRatio) * 0.5) * 16.0;
        return (int) Math.min(100, Math.round(base + gustBonus + currentBonus));
    }

    @Override
    public SafetyEvaluation evaluateSafety(MarineForecastSlot slot) {
        List<String> reasons = new ArrayList<>();
        SafetyStatus status = SafetyStatus.SAFE;

        if (slot.getWaveHeight() > getMaxSafeWaveMeters()) {
            status = SafetyStatus.DO_NOT_SAIL;
            reasons.add(String.format("Wave height %.2f m exceeds BMKG Small Fishing Boat limit (%.2f m).",
                slot.getWaveHeight(), getMaxSafeWaveMeters()));
        }
        if (slot.getWindSpeed() > getMaxSafeWindKnots()) {
            status = SafetyStatus.DO_NOT_SAIL;
            reasons.add(String.format("Wind speed %.1f kt exceeds BMKG Small Fishing Boat limit (%.1f kt).",
                slot.getWindSpeed(), getMaxSafeWindKnots()));
        }
        if (slot.getWindGust() >= getMaxSafeWindKnots() * 1.55) {
            status = SafetyStatus.DO_NOT_SAIL;
            reasons.add(String.format("Severe wind gusts (%.1f kt) create capsize risk for small boats.",
                slot.getWindGust()));
        }

        if (status != SafetyStatus.DO_NOT_SAIL) {
            if (slot.getWaveHeight() >= getMaxSafeWaveMeters() * 0.80) {
                status = SafetyStatus.CAUTION;
                reasons.add(String.format("Wave height %.2f m is approaching the %.2f m BMKG threshold.",
                    slot.getWaveHeight(), getMaxSafeWaveMeters()));
            }
            if (slot.getWindSpeed() >= getMaxSafeWindKnots() * 0.80) {
                status = SafetyStatus.CAUTION;
                reasons.add(String.format("Wind speed %.1f kt is approaching the %.1f kt BMKG threshold.",
                    slot.getWindSpeed(), getMaxSafeWindKnots()));
            }
            if (slot.getWindGust() > getMaxSafeWindKnots() * 1.30) {
                status = SafetyStatus.CAUTION;
                reasons.add(String.format("Strong wind gusts (%.1f kt) exceed 1.3x safe wind threshold.",
                    slot.getWindGust()));
            }
            if (slot.getCurrentSpeed() >= 1.50) {
                status = SafetyStatus.CAUTION;
                reasons.add(String.format("Strong surface current (%.2f kt) increases drift hazard for small craft.",
                    slot.getCurrentSpeed()));
            }
        }

        if (reasons.isEmpty()) {
            reasons.add("Wave height, wind speed, gusts, and currents are comfortably within BMKG limits for small fishing boats.");
        }

        int score = calculateRiskScore(slot);
        String summary = switch (status) {
            case DO_NOT_SAIL -> "DO NOT SAIL: Sea conditions exceed official BMKG safety thresholds for Small Fishing Boats.";
            case CAUTION -> "CAUTION: Marginal conditions for Small Fishing Boats. Extra vigilance and safety checks required.";
            case SAFE -> "SAFE TO SAIL: Conditions are favorable for Small Fishing Boats.";
        };

        return new SafetyEvaluation(status, score, getVesselType(), getMaxSafeWindKnots(),
                getMaxSafeWaveMeters(), slot.getTimeWib(), summary, reasons);
    }
}
