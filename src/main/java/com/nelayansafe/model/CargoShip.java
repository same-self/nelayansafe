package com.nelayansafe.model;

import java.util.ArrayList;
import java.util.List;

public class CargoShip extends Vessel {

    public CargoShip(String vesselId, String vesselName, int maxCrewCapacity,
                     boolean hasLifeJackets, boolean hasWorkingRadio) {
        super(vesselId, vesselName, "CargoShip", maxCrewCapacity,
              27.0, 4.00, hasLifeJackets, hasWorkingRadio);
    }

    @Override
    public int calculateRiskScore(MarineForecastSlot slot) {
        double waveRatio = slot.getWaveHeight() / getMaxSafeWaveMeters();
        double windRatio = slot.getWindSpeed() / getMaxSafeWindKnots();
        double base = Math.max(waveRatio, windRatio) * 58.0 + ((waveRatio + windRatio) * 0.5) * 16.0;
        return (int) Math.min(100, Math.round(base));
    }

    @Override
    public SafetyEvaluation evaluateSafety(MarineForecastSlot slot) {
        List<String> reasons = new ArrayList<>();
        SafetyStatus status = SafetyStatus.SAFE;

        if (slot.getWaveHeight() > getMaxSafeWaveMeters()) {
            status = SafetyStatus.DO_NOT_SAIL;
            reasons.add(String.format("Wave height %.2f m exceeds BMKG Cargo/Cruise Ship limit (%.2f m).",
                slot.getWaveHeight(), getMaxSafeWaveMeters()));
        }
        if (slot.getWindSpeed() > getMaxSafeWindKnots()) {
            status = SafetyStatus.DO_NOT_SAIL;
            reasons.add(String.format("Wind speed %.1f kt exceeds BMKG Cargo/Cruise Ship limit (%.1f kt).",
                slot.getWindSpeed(), getMaxSafeWindKnots()));
        }

        if (status != SafetyStatus.DO_NOT_SAIL) {
            if (slot.getWaveHeight() >= getMaxSafeWaveMeters() * 0.80) {
                status = SafetyStatus.CAUTION;
                reasons.add(String.format("High swell %.2f m is nearing %.2f m cargo ship threshold.",
                    slot.getWaveHeight(), getMaxSafeWaveMeters()));
            }
            if (slot.getWindSpeed() >= getMaxSafeWindKnots() * 0.80 || slot.getWindGust() >= 35.0) {
                status = SafetyStatus.CAUTION;
                reasons.add(String.format("Gale-force gusts (%.1f kt) require container lashing inspection.",
                    slot.getWindGust()));
            }
        }

        if (reasons.isEmpty()) {
            reasons.add("Ocean conditions are within safe limits for Cargo / Cruise Ship navigation.");
        }

        int score = calculateRiskScore(slot);
        String summary = switch (status) {
            case DO_NOT_SAIL -> "DO NOT SAIL: Extreme winds or high seas exceed BMKG limits for Cargo Ships.";
            case CAUTION -> "CAUTION: Heavy sea state. Verify deck cargo lashing and navigation watch.";
            case SAFE -> "SAFE TO SAIL: Conditions are favorable for Cargo Ship transit.";
        };

        return new SafetyEvaluation(status, score, getVesselType(), getMaxSafeWindKnots(),
                getMaxSafeWaveMeters(), slot.getTimeWib(), summary, reasons);
    }
}
