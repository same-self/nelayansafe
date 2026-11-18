package com.nelayansafe.model;

import java.util.ArrayList;
import java.util.List;

public class PassengerFerry extends Vessel {

    public PassengerFerry(String vesselId, String vesselName, int maxCrewCapacity,
                          boolean hasLifeJackets, boolean hasWorkingRadio) {
        super(vesselId, vesselName, "PassengerFerry", maxCrewCapacity,
              21.0, 2.50, hasLifeJackets, hasWorkingRadio);
    }

    @Override
    public int calculateRiskScore(MarineForecastSlot slot) {
        double waveRatio = slot.getWaveHeight() / getMaxSafeWaveMeters();
        double windRatio = slot.getWindSpeed() / getMaxSafeWindKnots();
        double gustPenalty = Math.max(0, (slot.getWindGust() - (getMaxSafeWindKnots() * 1.25)) / 12.0) * 10.0;
        double base = Math.max(waveRatio, windRatio) * 58.0 + ((waveRatio + windRatio) * 0.5) * 16.0;
        return (int) Math.min(100, Math.round(base + gustPenalty));
    }

    @Override
    public SafetyEvaluation evaluateSafety(MarineForecastSlot slot) {
        List<String> reasons = new ArrayList<>();
        SafetyStatus status = SafetyStatus.SAFE;

        if (slot.getWaveHeight() > getMaxSafeWaveMeters()) {
            status = SafetyStatus.DO_NOT_SAIL;
            reasons.add(String.format("Wave height %.2f m exceeds BMKG Passenger Ferry limit (%.2f m).",
                slot.getWaveHeight(), getMaxSafeWaveMeters()));
        }
        if (slot.getWindSpeed() > getMaxSafeWindKnots()) {
            status = SafetyStatus.DO_NOT_SAIL;
            reasons.add(String.format("Wind speed %.1f kt exceeds BMKG Passenger Ferry limit (%.1f kt).",
                slot.getWindSpeed(), getMaxSafeWindKnots()));
        }

        if (status != SafetyStatus.DO_NOT_SAIL) {
            if (slot.getWaveHeight() >= getMaxSafeWaveMeters() * 0.80) {
                status = SafetyStatus.CAUTION;
                reasons.add(String.format("Swell of %.2f m may cause heavy vessel rolling for passengers (limit %.2f m).",
                    slot.getWaveHeight(), getMaxSafeWaveMeters()));
            }
            if (slot.getWindSpeed() >= getMaxSafeWindKnots() * 0.80 || slot.getWindGust() >= 27.0) {
                status = SafetyStatus.CAUTION;
                reasons.add(String.format("High winds (%.1f kt, gusts %.1f kt) require passenger cabin briefing.",
                    slot.getWindSpeed(), slot.getWindGust()));
            }
        }

        if (reasons.isEmpty()) {
            reasons.add("Sea conditions meet BMKG Passenger Ferry safety and passenger comfort standards.");
        }

        int score = calculateRiskScore(slot);
        String summary = switch (status) {
            case DO_NOT_SAIL -> "DO NOT SAIL: Sea conditions violate BMKG safety limits for Passenger Ferries.";
            case CAUTION -> "CAUTION: Rough crossing expected. Secure vehicles/cargo and brief passengers before boarding.";
            case SAFE -> "SAFE TO SAIL: Crossing conditions are safe for Passenger Ferries.";
        };

        return new SafetyEvaluation(status, score, getVesselType(), getMaxSafeWindKnots(),
                getMaxSafeWaveMeters(), slot.getTimeWib(), summary, reasons);
    }
}
