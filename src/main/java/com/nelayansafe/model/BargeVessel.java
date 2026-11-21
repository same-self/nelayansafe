package com.nelayansafe.model;

import com.nelayansafe.exception.MissingSafetyGearException;
import com.nelayansafe.exception.VesselOvercapacityException;

import java.util.ArrayList;
import java.util.List;

public class BargeVessel extends Vessel {
    private double maxCargoTon;
    private double currentCargoTon;

    public BargeVessel(String vesselId, String vesselName, int maxCrewCapacity,
                       boolean hasLifeJackets, boolean hasWorkingRadio) {
        this(vesselId, vesselName, maxCrewCapacity, hasLifeJackets, hasWorkingRadio, 3000.0, 2000.0);
    }

    public BargeVessel(String vesselId, String vesselName, int maxCrewCapacity,
                       boolean hasLifeJackets, boolean hasWorkingRadio,
                       double maxCargoTon, double currentCargoTon) {
        super(vesselId, vesselName, "BargeVessel", maxCrewCapacity,
              16.0, 1.50, hasLifeJackets, hasWorkingRadio);
        if (maxCargoTon <= 0) {
            throw new IllegalArgumentException("Max cargo capacity (tons) must be positive.");
        }
        if (currentCargoTon < 0) {
            throw new IllegalArgumentException("Current cargo load (tons) cannot be negative.");
        }
        this.maxCargoTon = maxCargoTon;
        this.currentCargoTon = currentCargoTon;
    }

    @Override
    public void validateEquipmentAndCapacity(int crewCount)
            throws VesselOvercapacityException, MissingSafetyGearException {
        super.validateEquipmentAndCapacity(crewCount);
        if (currentCargoTon > maxCargoTon) {
            throw new VesselOvercapacityException(
                String.format("BargeVessel '%s' cargo is overloaded: %.1f tons exceeds maximum capacity of %.1f tons.",
                    getVesselName(), currentCargoTon, maxCargoTon));
        }
    }

    @Override
    protected double calculateVesselModifier(MarineForecastSlot slot) {
        double maxRatio = Math.min(1.0, Math.max(
                slot.getWindSpeed() / getMaxSafeWindKnots(),
                slot.getWaveHeight() / getMaxSafeWaveMeters()
        ));
        double loadRatio = Math.min(1.0, currentCargoTon / maxCargoTon);
        double currentDrag = Math.min(1.0, slot.getCurrentSpeed() / 2.5) * 6.0;
        double gustFactor = Math.min(5.0,
                Math.max(0.0, (slot.getWindGust() - getMaxSafeWindKnots()) / getMaxSafeWindKnots()) * 12.0);
        double loadPenalty = loadRatio * 4.0 * maxRatio;
        return Math.min(15.0, currentDrag + gustFactor + loadPenalty);
    }

    @Override
    public SafetyEvaluation evaluateSafety(MarineForecastSlot slot) {
        List<String> reasons = new ArrayList<>();
        SafetyStatus status = applyStandardBmkgEvaluation(slot, reasons, "Barge Vessel");

        if (status == SafetyStatus.CAUTION && (currentCargoTon / maxCargoTon) >= 0.85) {
            reasons.add(String.format("Heavy cargo load (%.0f / %.0f tons) requires reduced towing speed and towline inspection.",
                    currentCargoTon, maxCargoTon));
        }

        if (reasons.isEmpty()) {
            reasons.add(String.format(
                    "Wave height, wind speed, and currents are safe for barge operations (Cargo: %.0f / %.0f tons).",
                    currentCargoTon, maxCargoTon));
        }

        int score = calculateRiskScore(slot);
        String summary = switch (status) {
            case DO_NOT_SAIL -> "DO NOT SAIL: Conditions meet or exceed official BMKG safety limits for Barge Vessels.";
            case CAUTION -> "CAUTION: Approaching BMKG thresholds for Barge Vessels. Inspect towlines before departure.";
            case SAFE -> "SAFE TO SAIL: Sea state is within safe operating thresholds for Barge Vessels.";
        };

        return new SafetyEvaluation(status, score, getVesselType(), getMaxSafeWindKnots(),
                getMaxSafeWaveMeters(), slot.getTimeWib(), summary, reasons);
    }

    public double getMaxCargoTon() { return maxCargoTon; }
    public double getCurrentCargoTon() { return currentCargoTon; }
}
