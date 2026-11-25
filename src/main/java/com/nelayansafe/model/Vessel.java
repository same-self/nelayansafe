package com.nelayansafe.model;

import com.nelayansafe.exception.MissingSafetyGearException;
import com.nelayansafe.exception.VesselOvercapacityException;

import java.util.List;

public abstract class Vessel {
    public static final double CAUTION_RATIO = 0.75;
    public static final double HIGH_CURRENT_CAUTION_KNOTS = 2.0;

    public record VesselOptions(
            double grossTonnage,
            double maxSafeCurrentKnots,
            double maxCargoTon,
            double currentCargoTon,
            int maxPassengers,
            int currentPassengers,
            int maxContainerTEU
    ) {
        public static VesselOptions defaults() {
            return new VesselOptions(5.0, 2.0, 3000.0, 2000.0, 300, 180, 1200);
        }
    }

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

    public static int defaultMaxCrewFor(String vesselType) {
        String normalized = vesselType == null ? "" : vesselType.trim();
        return switch (normalized) {
            case "SmallFishingBoat" -> 6;
            case "BargeVessel" -> 12;
            case "PassengerFerry" -> 150;
            case "CargoShip" -> 40;
            default -> 6;
        };
    }

    public static Vessel create(String vesselType, String vesselId, String vesselName,
                                int maxCrewCapacity, boolean hasLifeJackets, boolean hasWorkingRadio) {
        return create(vesselType, vesselId, vesselName, maxCrewCapacity,
                hasLifeJackets, hasWorkingRadio, VesselOptions.defaults());
    }

    public static Vessel create(String vesselType, String vesselId, String vesselName,
                                int maxCrewCapacity, boolean hasLifeJackets, boolean hasWorkingRadio,
                                VesselOptions options) {
        VesselOptions opts = options != null ? options : VesselOptions.defaults();
        String normalized = vesselType == null ? "" : vesselType.trim();
        return switch (normalized) {
            case "SmallFishingBoat" ->
                new SmallFishingBoat(vesselId, vesselName, maxCrewCapacity,
                        hasLifeJackets, hasWorkingRadio,
                        opts.grossTonnage(), opts.maxSafeCurrentKnots());
            case "BargeVessel" ->
                new BargeVessel(vesselId, vesselName, maxCrewCapacity,
                        hasLifeJackets, hasWorkingRadio,
                        opts.maxCargoTon(), opts.currentCargoTon());
            case "PassengerFerry" ->
                new PassengerFerry(vesselId, vesselName, maxCrewCapacity,
                        hasLifeJackets, hasWorkingRadio,
                        opts.maxPassengers(), opts.currentPassengers());
            case "CargoShip" ->
                new CargoShip(vesselId, vesselName, maxCrewCapacity,
                        hasLifeJackets, hasWorkingRadio,
                        opts.maxContainerTEU());
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

    /**
     * Template Method risk score (0-100):
     * 85 * max(wind / maxSafeWind, wave / maxSafeWave) + polymorphic vessel modifier (0-15).
     */
    public int calculateRiskScore(MarineForecastSlot slot) {
        double windRatio = slot.getWindSpeed() / maxSafeWindKnots;
        double waveRatio = slot.getWaveHeight() / maxSafeWaveMeters;
        double baseScore = 85.0 * Math.max(windRatio, waveRatio);
        double modifier = Math.max(0.0, Math.min(15.0, calculateVesselModifier(slot)));
        return (int) Math.max(0, Math.min(100, Math.round(baseScore + modifier)));
    }

    /**
     * Shared conservative BMKG evaluation used by all subclasses:
     * DO_NOT_SAIL if wind >= maxSafeWind OR wave >= maxSafeWave.
     * CAUTION if >= 75% of either limit, windGust >= maxSafeWind, or currentSpeed >= 2.0 kt.
     */
    protected SafetyStatus applyStandardBmkgEvaluation(MarineForecastSlot slot,
                                                       List<String> reasons,
                                                       String vesselLabel) {
        SafetyStatus status = SafetyStatus.SAFE;

        if (slot.getWaveHeight() >= maxSafeWaveMeters) {
            status = SafetyStatus.DO_NOT_SAIL;
            reasons.add(String.format("Wave height %.2f m meets/exceeds BMKG %s limit (%.2f m).",
                    slot.getWaveHeight(), vesselLabel, maxSafeWaveMeters));
        }
        if (slot.getWindSpeed() >= maxSafeWindKnots) {
            status = SafetyStatus.DO_NOT_SAIL;
            reasons.add(String.format("Wind speed %.1f kt meets/exceeds BMKG %s limit (%.1f kt).",
                    slot.getWindSpeed(), vesselLabel, maxSafeWindKnots));
        }

        if (status != SafetyStatus.DO_NOT_SAIL) {
            if (slot.getWaveHeight() >= maxSafeWaveMeters * CAUTION_RATIO) {
                status = SafetyStatus.CAUTION;
                reasons.add(String.format("Wave height %.2f m reached >= 75%% of the %.2f m limit.",
                        slot.getWaveHeight(), maxSafeWaveMeters));
            }
            if (slot.getWindSpeed() >= maxSafeWindKnots * CAUTION_RATIO) {
                status = SafetyStatus.CAUTION;
                reasons.add(String.format("Wind speed %.1f kt reached >= 75%% of the %.1f kt limit.",
                        slot.getWindSpeed(), maxSafeWindKnots));
            }
            if (slot.getWindGust() >= maxSafeWindKnots) {
                status = SafetyStatus.CAUTION;
                reasons.add(String.format("Wind gust %.1f kt meets/exceeds the %.1f kt safe wind threshold.",
                        slot.getWindGust(), maxSafeWindKnots));
            }
            if (slot.getCurrentSpeed() >= HIGH_CURRENT_CAUTION_KNOTS) {
                status = SafetyStatus.CAUTION;
                reasons.add(String.format("Surface current %.2f kt meets/exceeds the %.1f kt caution threshold.",
                        slot.getCurrentSpeed(), HIGH_CURRENT_CAUTION_KNOTS));
            }
        }

        return status;
    }

    public abstract SafetyEvaluation evaluateSafety(MarineForecastSlot slot);
    protected abstract double calculateVesselModifier(MarineForecastSlot slot);

    public String getVesselId() { return vesselId; }
    public String getVesselName() { return vesselName; }
    public String getVesselType() { return vesselType; }
    public int getMaxCrewCapacity() { return maxCrewCapacity; }
    public double getMaxSafeWindKnots() { return maxSafeWindKnots; }
    public double getMaxSafeWaveMeters() { return maxSafeWaveMeters; }
    public boolean isHasLifeJackets() { return hasLifeJackets; }
    public boolean isHasWorkingRadio() { return hasWorkingRadio; }
}
