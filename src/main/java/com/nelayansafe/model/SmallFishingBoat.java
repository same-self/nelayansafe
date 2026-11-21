package com.nelayansafe.model;

import java.util.ArrayList;
import java.util.List;

public class SmallFishingBoat extends Vessel {
    private double grossTonnage;
    private double maxSafeCurrentKnots;

    public SmallFishingBoat(String vesselId, String vesselName, int maxCrewCapacity,
                            boolean hasLifeJackets, boolean hasWorkingRadio) {
        this(vesselId, vesselName, maxCrewCapacity, hasLifeJackets, hasWorkingRadio, 5.0, 2.0);
    }

    public SmallFishingBoat(String vesselId, String vesselName, int maxCrewCapacity,
                            boolean hasLifeJackets, boolean hasWorkingRadio,
                            double grossTonnage, double maxSafeCurrentKnots) {
        super(vesselId, vesselName, "SmallFishingBoat", maxCrewCapacity,
              15.0, 1.25, hasLifeJackets, hasWorkingRadio);
        if (grossTonnage <= 0) {
            throw new IllegalArgumentException("Gross tonnage (GT) must be positive.");
        }
        if (maxSafeCurrentKnots <= 0) {
            throw new IllegalArgumentException("Max safe current speed must be positive.");
        }
        this.grossTonnage = grossTonnage;
        this.maxSafeCurrentKnots = maxSafeCurrentKnots;
    }

    @Override
    protected double calculateVesselModifier(MarineForecastSlot slot) {
        double maxRatio = Math.min(1.0, Math.max(
                slot.getWindSpeed() / getMaxSafeWindKnots(),
                slot.getWaveHeight() / getMaxSafeWaveMeters()
        ));
        double gustBonus = Math.min(8.0,
                Math.max(0.0, (slot.getWindGust() - getMaxSafeWindKnots()) / getMaxSafeWindKnots()) * 15.0);
        double currentBonus = Math.min(1.0, slot.getCurrentSpeed() / maxSafeCurrentKnots) * 5.0;
        double smallHullPenalty = grossTonnage < 10.0
                ? Math.min(2.0, ((10.0 - grossTonnage) / 10.0) * 4.0) * maxRatio
                : 0.0;
        return Math.min(15.0, gustBonus + currentBonus + smallHullPenalty);
    }

    @Override
    public SafetyEvaluation evaluateSafety(MarineForecastSlot slot) {
        List<String> reasons = new ArrayList<>();
        SafetyStatus status = applyStandardBmkgEvaluation(slot, reasons, "Small Fishing Boat");

        if (status != SafetyStatus.DO_NOT_SAIL
                && maxSafeCurrentKnots < HIGH_CURRENT_CAUTION_KNOTS
                && slot.getCurrentSpeed() >= maxSafeCurrentKnots) {
            status = SafetyStatus.CAUTION;
            reasons.add(String.format("Surface current %.2f kt meets/exceeds this boat's %.2f kt drift limit (%.1f GT).",
                    slot.getCurrentSpeed(), maxSafeCurrentKnots, grossTonnage));
        }

        if (reasons.isEmpty()) {
            reasons.add(String.format(
                    "Wave height, wind, gusts, and currents are comfortably within BMKG limits for this %.1f GT fishing boat.",
                    grossTonnage));
        }

        int score = calculateRiskScore(slot);
        String summary = switch (status) {
            case DO_NOT_SAIL -> "DO NOT SAIL: Sea conditions meet or exceed official BMKG safety limits for Small Fishing Boats.";
            case CAUTION -> "CAUTION: Marginal conditions for Small Fishing Boats. Extra vigilance and gear checks required.";
            case SAFE -> "SAFE TO SAIL: Conditions are favorable for Small Fishing Boats.";
        };

        return new SafetyEvaluation(status, score, getVesselType(), getMaxSafeWindKnots(),
                getMaxSafeWaveMeters(), slot.getTimeWib(), summary, reasons);
    }

    public double getGrossTonnage() { return grossTonnage; }
    public double getMaxSafeCurrentKnots() { return maxSafeCurrentKnots; }
}
