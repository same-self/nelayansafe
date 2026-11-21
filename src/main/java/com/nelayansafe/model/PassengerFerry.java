package com.nelayansafe.model;

import com.nelayansafe.exception.MissingSafetyGearException;
import com.nelayansafe.exception.VesselOvercapacityException;

import java.util.ArrayList;
import java.util.List;

public class PassengerFerry extends Vessel {
    private int maxPassengers;
    private int currentPassengers;

    public PassengerFerry(String vesselId, String vesselName, int maxCrewCapacity,
                          boolean hasLifeJackets, boolean hasWorkingRadio) {
        this(vesselId, vesselName, maxCrewCapacity, hasLifeJackets, hasWorkingRadio, 300, 180);
    }

    public PassengerFerry(String vesselId, String vesselName, int maxCrewCapacity,
                          boolean hasLifeJackets, boolean hasWorkingRadio,
                          int maxPassengers, int currentPassengers) {
        super(vesselId, vesselName, "PassengerFerry", maxCrewCapacity,
              21.0, 2.50, hasLifeJackets, hasWorkingRadio);
        if (maxPassengers <= 0) {
            throw new IllegalArgumentException("Max passenger capacity must be positive.");
        }
        if (currentPassengers < 0) {
            throw new IllegalArgumentException("Current passenger count cannot be negative.");
        }
        this.maxPassengers = maxPassengers;
        this.currentPassengers = currentPassengers;
    }

    @Override
    public void validateEquipmentAndCapacity(int crewCount)
            throws VesselOvercapacityException, MissingSafetyGearException {
        super.validateEquipmentAndCapacity(crewCount);
        if (currentPassengers > maxPassengers) {
            throw new VesselOvercapacityException(
                String.format("PassengerFerry '%s' is overloaded: %d passengers exceed maximum capacity of %d.",
                    getVesselName(), currentPassengers, maxPassengers));
        }
    }

    @Override
    protected double calculateVesselModifier(MarineForecastSlot slot) {
        double occupancyRatio = Math.min(1.0, (double) currentPassengers / maxPassengers);
        double rollFactor = Math.min(1.0, slot.getWaveHeight() / getMaxSafeWaveMeters())
                * (4.0 + (4.0 * occupancyRatio));
        double gustFactor = Math.min(7.0,
                Math.max(0.0, (slot.getWindGust() - getMaxSafeWindKnots()) / getMaxSafeWindKnots()) * 14.0);
        return Math.min(15.0, rollFactor + gustFactor);
    }

    @Override
    public SafetyEvaluation evaluateSafety(MarineForecastSlot slot) {
        List<String> reasons = new ArrayList<>();
        SafetyStatus status = applyStandardBmkgEvaluation(slot, reasons, "Passenger Ferry");

        if (status == SafetyStatus.CAUTION && ((double) currentPassengers / maxPassengers) >= 0.80) {
            reasons.add(String.format("High passenger load (%d / %d onboard) requires mandatory cabin safety briefing.",
                    currentPassengers, maxPassengers));
        }

        if (reasons.isEmpty()) {
            reasons.add(String.format(
                    "Sea conditions meet BMKG Passenger Ferry safety standards (%d / %d passengers onboard).",
                    currentPassengers, maxPassengers));
        }

        int score = calculateRiskScore(slot);
        String summary = switch (status) {
            case DO_NOT_SAIL -> "DO NOT SAIL: Sea conditions meet or exceed BMKG safety limits for Passenger Ferries.";
            case CAUTION -> "CAUTION: Rough crossing expected. Secure vehicles/cargo and brief passengers before boarding.";
            case SAFE -> "SAFE TO SAIL: Crossing conditions are safe for Passenger Ferries.";
        };

        return new SafetyEvaluation(status, score, getVesselType(), getMaxSafeWindKnots(),
                getMaxSafeWaveMeters(), slot.getTimeWib(), summary, reasons);
    }

    public int getMaxPassengers() { return maxPassengers; }
    public int getCurrentPassengers() { return currentPassengers; }
}
