package com.nelayansafe.model;

import com.nelayansafe.exception.MissingSafetyGearException;
import com.nelayansafe.exception.VesselOvercapacityException;

public abstract class Vessel {
    private String vesselId;
    private String vesselName;
    private String vesselType;
    private int maxCrewCapacity;
    private double maxSafeWindKnots;
    private double maxSafeWaveMeters;
    private boolean hasLifeJackets;
    private boolean hasWorkingRadio;

    protected Vessel(String vesselId, String vesselName, String vesselType,
                     int maxCrewCapacity, double maxSafeWindKnots, double maxSafeWaveMeters,
                     boolean hasLifeJackets, boolean hasWorkingRadio) {
        if (vesselName == null || vesselName.isBlank()) {
            throw new IllegalArgumentException("Vessel name cannot be empty.");
        }
        if (maxCrewCapacity <= 0) {
            throw new IllegalArgumentException("Max crew capacity must be at least 1.");
        }
        if (maxSafeWindKnots <= 0 || maxSafeWaveMeters <= 0) {
            throw new IllegalArgumentException("BMKG safety limits must be positive.");
        }
        this.vesselId = (vesselId == null || vesselId.isBlank()) ? "VSL-001" : vesselId.trim();
        this.vesselName = vesselName.trim();
        this.vesselType = vesselType;
        this.maxCrewCapacity = maxCrewCapacity;
        this.maxSafeWindKnots = maxSafeWindKnots;
        this.maxSafeWaveMeters = maxSafeWaveMeters;
        this.hasLifeJackets = hasLifeJackets;
        this.hasWorkingRadio = hasWorkingRadio;
    }

    public static Vessel create(String vesselType, String vesselId, String vesselName,
                                int maxCrewCapacity, boolean hasLifeJackets, boolean hasWorkingRadio) {
        String normalized = vesselType == null ? "" : vesselType.trim();
        return switch (normalized) {
            case "SmallFishingBoat" ->
                new SmallFishingBoat(vesselId, vesselName, maxCrewCapacity > 0 ? maxCrewCapacity : 6,
                        hasLifeJackets, hasWorkingRadio);
            case "BargeVessel" ->
                new BargeVessel(vesselId, vesselName, maxCrewCapacity > 0 ? maxCrewCapacity : 12,
                        hasLifeJackets, hasWorkingRadio);
            case "PassengerFerry" ->
                new PassengerFerry(vesselId, vesselName, maxCrewCapacity > 0 ? maxCrewCapacity : 150,
                        hasLifeJackets, hasWorkingRadio);
            case "CargoShip" ->
                new CargoShip(vesselId, vesselName, maxCrewCapacity > 0 ? maxCrewCapacity : 40,
                        hasLifeJackets, hasWorkingRadio);
            default -> throw new IllegalArgumentException("Unsupported vessel class: " + vesselType);
        };
    }

    public void validateEquipmentAndCapacity(int crewCount)
            throws VesselOvercapacityException, MissingSafetyGearException {
        if (crewCount <= 0) {
            throw new IllegalArgumentException("Crew count onboard must be at least 1.");
        }
        if (crewCount > maxCrewCapacity) {
            throw new VesselOvercapacityException(
                String.format("%s '%s' is overloaded: %d crew onboard exceeds maximum capacity of %d.",
                    vesselType, vesselName, crewCount, maxCrewCapacity));
        }
        if (!hasLifeJackets && !hasWorkingRadio) {
            throw new MissingSafetyGearException(
                String.format("Mandatory safety gear missing on '%s': both Life Jackets and Marine Radio are absent.",
                    vesselName));
        }
        if (!hasLifeJackets) {
            throw new MissingSafetyGearException(
                String.format("Clearance blocked for '%s': Life Jackets are required for all crew onboard.",
                    vesselName));
        }
        if (!hasWorkingRadio) {
            throw new MissingSafetyGearException(
                String.format("Clearance blocked for '%s': a working VHF/Marine Radio is required for maritime communication.",
                    vesselName));
        }
    }

    public abstract SafetyEvaluation evaluateSafety(MarineForecastSlot slot);
    public abstract int calculateRiskScore(MarineForecastSlot slot);

    public String getVesselId() { return vesselId; }
    public String getVesselName() { return vesselName; }
    public String getVesselType() { return vesselType; }
    public int getMaxCrewCapacity() { return maxCrewCapacity; }
    public double getMaxSafeWindKnots() { return maxSafeWindKnots; }
    public double getMaxSafeWaveMeters() { return maxSafeWaveMeters; }
    public boolean isHasLifeJackets() { return hasLifeJackets; }
    public boolean isHasWorkingRadio() { return hasWorkingRadio; }
}
