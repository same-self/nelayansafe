package com.nelayansafe;

import com.nelayansafe.model.BargeVessel;
import com.nelayansafe.model.CargoShip;
import com.nelayansafe.model.MarineForecastSlot;
import com.nelayansafe.model.PassengerFerry;
import com.nelayansafe.model.SafetyEvaluation;
import com.nelayansafe.model.SafetyStatus;
import com.nelayansafe.model.SmallFishingBoat;
import com.nelayansafe.model.Vessel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VesselPolymorphismAndSafetyTest {

    private MarineForecastSlot slot(double windKt, double waveM, double gustKt, double currentKt) {
        return new MarineForecastSlot(
                "2026-11-21 04:00 UTC",
                "12:00 WITA",
                "Berawan",
                28.0,
                80.0,
                "Timur",
                windKt,
                gustKt,
                "Sedang",
                waveM,
                "Barat",
                currentKt
        );
    }

    @Test
    void slide4DryRunEvaluatesPolymorphicallyAcrossAllFourVesselSubclasses() {
        MarineForecastSlot dryRunSlot = slot(16.0, 1.35, 22.0, 1.60);

        List<Vessel> fleet = List.of(
                new SmallFishingBoat("V-1", "KM Nelayan", 6, true, true),
                new BargeVessel("V-2", "TB Tongkang", 12, true, true),
                new PassengerFerry("V-3", "KMP Nusantara", 150, true, true),
                new CargoShip("V-4", "MV Samudra", 40, true, true)
        );

        List<SafetyStatus> statuses = fleet.stream()
                .map(v -> v.evaluateSafety(dryRunSlot).getStatus())
                .toList();

        assertEquals(List.of(
                SafetyStatus.DO_NOT_SAIL, // SmallFishingBoat: 16 >= 15 AND 1.35 >= 1.25
                SafetyStatus.DO_NOT_SAIL, // BargeVessel: 16 >= 16 (conservative OR + >=)
                SafetyStatus.CAUTION,     // PassengerFerry: 16/21 >= 75% and gust 22 >= 21
                SafetyStatus.SAFE         // CargoShip: well within 27 kt / 4.0 m limits
        ), statuses);
    }

    @Test
    void slide4WorkedRiskScoreExampleMatchesExpectedScores() {
        MarineForecastSlot roughSlot = slot(14.0, 1.15, 20.0, 1.80);

        Vessel fishingBoat = new SmallFishingBoat("V-1", "KM Nelayan", 6, true, true, 5.0, 2.0);
        Vessel cargoShip = new CargoShip("V-4", "MV Samudra", 40, true, true, 1200);

        SafetyEvaluation fishingEval = fishingBoat.evaluateSafety(roughSlot);
        SafetyEvaluation cargoEval = cargoShip.evaluateSafety(roughSlot);

        assertEquals(SafetyStatus.CAUTION, fishingEval.getStatus());
        assertEquals(91, fishingEval.getRiskScore());

        assertEquals(SafetyStatus.SAFE, cargoEval.getStatus());
        assertEquals(46, cargoEval.getRiskScore());
    }

    @Test
    void inclusiveBmkgBoundariesTriggerDoNotSailOnExactWindOrWaveLimit() {
        SmallFishingBoat boat = new SmallFishingBoat("V-1", "Boat", 6, true, true);
        BargeVessel barge = new BargeVessel("V-2", "Barge", 12, true, true);
        PassengerFerry ferry = new PassengerFerry("V-3", "Ferry", 150, true, true);
        CargoShip cargo = new CargoShip("V-4", "Cargo", 40, true, true);

        // Exact wind limit with calm waves
        assertEquals(SafetyStatus.DO_NOT_SAIL, boat.evaluateSafety(slot(15.0, 0.30, 15.0, 0.5)).getStatus());
        assertEquals(SafetyStatus.DO_NOT_SAIL, barge.evaluateSafety(slot(16.0, 0.30, 16.0, 0.5)).getStatus());
        assertEquals(SafetyStatus.DO_NOT_SAIL, ferry.evaluateSafety(slot(21.0, 0.30, 21.0, 0.5)).getStatus());
        assertEquals(SafetyStatus.DO_NOT_SAIL, cargo.evaluateSafety(slot(27.0, 0.30, 27.0, 0.5)).getStatus());

        // Exact wave limit with calm wind
        assertEquals(SafetyStatus.DO_NOT_SAIL, boat.evaluateSafety(slot(5.0, 1.25, 8.0, 0.5)).getStatus());
        assertEquals(SafetyStatus.DO_NOT_SAIL, barge.evaluateSafety(slot(5.0, 1.50, 8.0, 0.5)).getStatus());
        assertEquals(SafetyStatus.DO_NOT_SAIL, ferry.evaluateSafety(slot(5.0, 2.50, 8.0, 0.5)).getStatus());
        assertEquals(SafetyStatus.DO_NOT_SAIL, cargo.evaluateSafety(slot(5.0, 4.00, 8.0, 0.5)).getStatus());
    }

    @Test
    void unifiedCautionRulesTriggerAt75PercentGustLimitOrHighCurrent() {
        SmallFishingBoat boat = new SmallFishingBoat("V-1", "Boat", 6, true, true, 5.0, 2.0);
        CargoShip cargo = new CargoShip("V-4", "Cargo", 40, true, true, 1200);

        // >= 75% wave limit (0.94 / 1.25 = 75.2%)
        assertEquals(SafetyStatus.CAUTION, boat.evaluateSafety(slot(6.0, 0.94, 9.0, 0.5)).getStatus());

        // windGust >= maxSafeWindKnots (15.0 kt) even with calm sustained wind
        assertEquals(SafetyStatus.CAUTION, boat.evaluateSafety(slot(8.0, 0.40, 15.0, 0.5)).getStatus());

        // currentSpeed >= 2.0 kt triggers CAUTION even on CargoShip in calm wind/wave
        assertEquals(SafetyStatus.CAUTION, cargo.evaluateSafety(slot(5.0, 0.40, 8.0, 2.0)).getStatus());

        // Boat-specific tighter maxSafeCurrentKnots (1.4 kt)
        SmallFishingBoat lightBoat = new SmallFishingBoat("V-1B", "Small Sampan", 4, true, true, 3.0, 1.4);
        assertEquals(SafetyStatus.CAUTION, lightBoat.evaluateSafety(slot(6.0, 0.40, 9.0, 1.5)).getStatus());
    }

    @Test
    void riskScoreIsBoundedZeroToHundredAndReflectsSubclassAttributes() {
        SmallFishingBoat boat = new SmallFishingBoat("V-1", "Boat", 6, true, true);
        assertEquals(0, boat.calculateRiskScore(slot(0.0, 0.0, 0.0, 0.0)));
        assertEquals(100, boat.calculateRiskScore(slot(45.0, 6.0, 60.0, 3.5)));

        MarineForecastSlot moderateSlot = slot(12.0, 1.00, 15.0, 1.0);
        BargeVessel lightlyLoaded = new BargeVessel("B-1", "Light Barge", 12, true, true, 3000.0, 500.0);
        BargeVessel heavilyLoaded = new BargeVessel("B-2", "Heavy Barge", 12, true, true, 3000.0, 2900.0);
        assertTrue(heavilyLoaded.calculateRiskScore(moderateSlot) > lightlyLoaded.calculateRiskScore(moderateSlot));

        PassengerFerry lowOccupancy = new PassengerFerry("F-1", "Light Ferry", 150, true, true, 300, 20);
        PassengerFerry highOccupancy = new PassengerFerry("F-2", "Full Ferry", 150, true, true, 300, 290);
        assertTrue(highOccupancy.calculateRiskScore(moderateSlot) > lowOccupancy.calculateRiskScore(moderateSlot));
    }

    @Test
    void encapsulationRejectsInvalidVesselAndWeatherInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> new SmallFishingBoat("V-1", "  ", 6, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> new SmallFishingBoat("V-1", "Boat", 0, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> new SmallFishingBoat("V-1", "Boat", 6, true, true, 0.0, 2.0));
        assertThrows(IllegalArgumentException.class,
                () -> new BargeVessel("V-2", "Barge", 12, true, true, 3000.0, -10.0));
        assertThrows(IllegalArgumentException.class,
                () -> new PassengerFerry("V-3", "Ferry", 150, true, true, 300, -5));
        assertThrows(IllegalArgumentException.class,
                () -> new CargoShip("V-4", "Cargo", 40, true, true, 0));
        assertThrows(IllegalArgumentException.class,
                () -> Vessel.create("Submarine", "V-9", "Invalid", 5, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> Vessel.create("SmallFishingBoat", "V-0", "Boat", 0, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> slot(-1.0, 0.5, 5.0, 0.5));
    }
}
