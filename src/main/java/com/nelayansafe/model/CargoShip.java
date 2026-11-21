package com.nelayansafe.model;

import java.util.ArrayList;
import java.util.List;

public class CargoShip extends Vessel {
    private int maxContainerTEU;

    public CargoShip(String vesselId, String vesselName, int maxCrewCapacity,
                     boolean hasLifeJackets, boolean hasWorkingRadio) {
        this(vesselId, vesselName, maxCrewCapacity, hasLifeJackets, hasWorkingRadio, 1200);
    }

    public CargoShip(String vesselId, String vesselName, int maxCrewCapacity,
                     boolean hasLifeJackets, boolean hasWorkingRadio,
                     int maxContainerTEU) {
        super(vesselId, vesselName, "CargoShip", maxCrewCapacity,
              27.0, 4.00, hasLifeJackets, hasWorkingRadio);
        if (maxContainerTEU <= 0) {
            throw new IllegalArgumentException("Max container capacity (TEU) must be positive.");
        }
        this.maxContainerTEU = maxContainerTEU;
    }

    @Override
    protected double calculateVesselModifier(MarineForecastSlot slot) {
        double windageScale = Math.min(1.0, maxContainerTEU / 3000.0);
        double windExposure = Math.min(1.0, slot.getWindSpeed() / getMaxSafeWindKnots())
                * (3.0 + (4.0 * windageScale));
        double gustFactor = Math.min(8.0,
                Math.max(0.0, (slot.getWindGust() - getMaxSafeWindKnots()) / getMaxSafeWindKnots()) * 16.0);
        return Math.min(15.0, windExposure + gustFactor);
    }

    @Override
    public SafetyEvaluation evaluateSafety(MarineForecastSlot slot) {
        List<String> reasons = new ArrayList<>();
        SafetyStatus status = applyStandardBmkgEvaluation(slot, reasons, "Cargo / Cruise Ship");

        if (status == SafetyStatus.CAUTION && maxContainerTEU >= 1000) {
            reasons.add(String.format("High container stack capacity (%d TEU) requires deck lashing inspection.",
                    maxContainerTEU));
        }

        if (reasons.isEmpty()) {
            reasons.add(String.format(
                    "Ocean conditions are within safe limits for Cargo / Cruise Ship transit (%d TEU).",
                    maxContainerTEU));
        }

        int score = calculateRiskScore(slot);
        String summary = switch (status) {
            case DO_NOT_SAIL -> "DO NOT SAIL: Extreme winds or high seas meet or exceed BMKG limits for Cargo Ships.";
            case CAUTION -> "CAUTION: Heavy sea state. Verify deck cargo lashing and navigation watch.";
            case SAFE -> "SAFE TO SAIL: Conditions are favorable for Cargo Ship transit.";
        };

        return new SafetyEvaluation(status, score, getVesselType(), getMaxSafeWindKnots(),
                getMaxSafeWaveMeters(), slot.getTimeLocal(), summary, reasons);
    }

    public int getMaxContainerTEU() { return maxContainerTEU; }
}
