package com.nelayansafe;

import com.nelayansafe.exception.BmkgApiUnavailableException;
import com.nelayansafe.exception.MissingSafetyGearException;
import com.nelayansafe.exception.UnsafeSeaConditionException;
import com.nelayansafe.exception.VesselOvercapacityException;
import com.nelayansafe.model.BargeVessel;
import com.nelayansafe.model.MarineForecastSlot;
import com.nelayansafe.model.PassengerFerry;
import com.nelayansafe.model.RouteEvaluation;
import com.nelayansafe.model.SafetyEvaluation;
import com.nelayansafe.model.SafetyStatus;
import com.nelayansafe.model.SailingClearance;
import com.nelayansafe.model.SmallFishingBoat;
import com.nelayansafe.model.Vessel;
import com.nelayansafe.service.BmkgMarineApiService;
import com.nelayansafe.service.ClearanceService;
import com.nelayansafe.service.RouteAndWindowOptimizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CustomExceptionsAndClearanceTest {

    private RouteEvaluation buildSingleZoneRoute(String code, String name, String province,
                                                 Vessel vessel, double windKt, double waveM,
                                                 double gustKt, double currentKt) {
        MarineForecastSlot slot = new MarineForecastSlot(
                "2026-11-21 04:00 UTC", "12:00 WITA", "Berawan",
                28.0, 80.0, "Tenggara", windKt, gustKt, "Rendah", waveM, "Barat", currentKt
        );
        SafetyEvaluation eval = vessel.evaluateSafety(slot);
        RouteEvaluation.ZoneSegment seg = new RouteEvaluation.ZoneSegment(
                code, name, province, "OFFLINE_CACHE", slot, eval
        );
        return new RouteEvaluation(eval.getStatus(), eval.getRiskScore(), code, name, eval.getSummary(), List.of(seg));
    }

    @Test
    void throwsVesselOvercapacityExceptionForCrewCargoAndPassengerOverload(@TempDir Path tempDir) {
        ClearanceService service = new ClearanceService(tempDir);

        // 1. Crew overcapacity on SmallFishingBoat
        Vessel boat = new SmallFishingBoat("V-1", "KM Harapan Jaya", 6, true, true);
        RouteEvaluation safeRoute = buildSingleZoneRoute("P.AE.09", "Perairan Sinjai", "Sulawesi Selatan",
                boat, 7.0, 0.40, 10.0, 0.70);
        assertThrows(VesselOvercapacityException.class,
                () -> service.requestClearance(boat, 11, safeRoute, "08:00 WITA to 12:00 WITA", false));

        // 2. Polymorphic cargo overload on BargeVessel (crew OK, cargo 3500 > 3000 tons)
        Vessel overloadedBarge = new BargeVessel("V-2", "TB Cargo Max", 12, true, true, 3000.0, 3500.0);
        RouteEvaluation safeBargeRoute = buildSingleZoneRoute("P.AE.09", "Perairan Sinjai", "Sulawesi Selatan",
                overloadedBarge, 7.0, 0.40, 10.0, 0.70);
        assertThrows(VesselOvercapacityException.class,
                () -> service.requestClearance(overloadedBarge, 8, safeBargeRoute, "Immediate", false));

        // 3. Polymorphic passenger overload on PassengerFerry (crew OK, passengers 350 > 300)
        Vessel overloadedFerry = new PassengerFerry("V-3", "KMP Penuh", 150, true, true, 300, 350);
        RouteEvaluation safeFerryRoute = buildSingleZoneRoute("P.AE.09", "Perairan Sinjai", "Sulawesi Selatan",
                overloadedFerry, 7.0, 0.40, 10.0, 0.70);
        assertThrows(VesselOvercapacityException.class,
                () -> service.requestClearance(overloadedFerry, 20, safeFerryRoute, "Immediate", false));
    }

    @Test
    void throwsMissingSafetyGearExceptionForKmMaritimAndRadioChecks(@TempDir Path tempDir) {
        ClearanceService service = new ClearanceService(tempDir);

        // KM Maritim case: 5 crew onboard, missing life jackets
        Vessel noJackets = new SmallFishingBoat("V-KM", "KM Maritim (Safety Case)", 6, false, true);
        RouteEvaluation safeRoute = buildSingleZoneRoute("P.AE.09", "Perairan Sinjai", "Sulawesi Selatan",
                noJackets, 7.0, 0.40, 10.0, 0.70);
        MissingSafetyGearException ex1 = assertThrows(MissingSafetyGearException.class,
                () -> service.requestClearance(noJackets, 5, safeRoute, "Immediate", false));
        assertTrue(ex1.getMessage().contains("Life Jackets"));

        // Missing radio only
        Vessel noRadio = new SmallFishingBoat("V-RAD", "KM Tanpa Radio", 6, true, false);
        MissingSafetyGearException ex2 = assertThrows(MissingSafetyGearException.class,
                () -> service.requestClearance(noRadio, 4, safeRoute, "Immediate", false));
        assertTrue(ex2.getMessage().contains("Radio"));

        // Missing both
        Vessel noGear = new SmallFishingBoat("V-NONE", "KM Tanpa Alat", 6, false, false);
        assertThrows(MissingSafetyGearException.class,
                () -> service.requestClearance(noGear, 4, safeRoute, "Immediate", false));
    }

    @Test
    void throwsUnsafeSeaConditionExceptionOnDoNotSailAndUnacknowledgedCaution(@TempDir Path tempDir) throws Exception {
        ClearanceService service = new ClearanceService(tempDir);
        BmkgMarineApiService apiService = new BmkgMarineApiService("http://127.0.0.1:1", tempDir.resolve("cache"), true);
        RouteAndWindowOptimizer optimizer = new RouteAndWindowOptimizer(apiService);

        Vessel boat = new SmallFishingBoat("V-1", "KM Nelayan Storm Test", 6, true, true);

        // Storm simulation is immediately DO_NOT_SAIL -> must throw even if cautionAcknowledged = true
        RouteEvaluation stormRoute = optimizer.evaluateMultiZoneRoute(boat, List.of("P.AE.09"), false, true);
        assertEquals(SafetyStatus.DO_NOT_SAIL, stormRoute.getOverallStatus());
        assertThrows(UnsafeSeaConditionException.class,
                () -> service.requestClearance(boat, 4, stormRoute, "Postpone", true));

        // CAUTION route without acknowledgment -> throws UnsafeSeaConditionException
        RouteEvaluation cautionRoute = buildSingleZoneRoute("P.AE.09", "Perairan Sinjai", "Sulawesi Selatan",
                boat, 12.0, 1.00, 16.0, 1.10);
        assertEquals(SafetyStatus.CAUTION, cautionRoute.getOverallStatus());
        assertThrows(UnsafeSeaConditionException.class,
                () -> service.requestClearance(boat, 4, cautionRoute, "17:00 WITA to 23:00 WITA", false));

        // Same CAUTION route WITH Captain Acknowledgment -> succeeds!
        SailingClearance issued = service.requestClearance(boat, 4, cautionRoute, "17:00 WITA to 23:00 WITA", true);
        assertEquals(SafetyStatus.CAUTION, issued.getSafetyStatus());
        assertTrue(issued.isCautionAcknowledged());
    }

    @Test
    void throwsBmkgApiUnavailableExceptionWhenOfflineWithoutCacheOrFallback(@TempDir Path tempDir) {
        BmkgMarineApiService isolatedApi = new BmkgMarineApiService(
                "http://127.0.0.1:1",
                tempDir.resolve("empty-cache"),
                false
        );

        assertThrows(BmkgApiUnavailableException.class, () -> isolatedApi.fetchZoneCatalog(true));
        assertThrows(BmkgApiUnavailableException.class, () -> isolatedApi.fetchZoneForecast("P.INVALID.99", true, false));
    }

    @Test
    void issuesClearanceInOriginTimezoneAndPersistsToJsonAndCsv(@TempDir Path tempDir) throws Exception {
        ClearanceService service = new ClearanceService(tempDir);
        Vessel boat = new SmallFishingBoat("V-1", "KM Sinjai Jaya", 6, true, true);
        RouteEvaluation safeWitaRoute = buildSingleZoneRoute("P.AE.09", "Perairan Sinjai", "Sulawesi Selatan",
                boat, 7.0, 0.40, 10.0, 0.70);

        SailingClearance issued = service.requestClearance(boat, 4, safeWitaRoute, "08:00 WITA to 12:00 WITA", false);
        assertNotNull(issued.getClearanceId());
        assertTrue(issued.getIssuedTimestampLocal().endsWith("WITA"),
                "Expected Sulawesi Selatan departure clearance timestamp in WITA, got: " + issued.getIssuedTimestampLocal());

        // Reload fresh ClearanceService from disk to verify JSON persistence
        ClearanceService reloaded = new ClearanceService(tempDir);
        assertEquals(1, reloaded.getAllClearances().size());
        assertEquals(issued.getClearanceId(), reloaded.getAllClearances().get(0).getClearanceId());

        // Verify CSV export headers and contents
        String csv = reloaded.generateCsvContent();
        assertTrue(csv.startsWith("clearance_id,vessel_id,vessel_name"));
        assertTrue(csv.contains("issued_local_time"));
        assertTrue(csv.contains("KM Sinjai Jaya"));
        assertTrue(csv.contains("WITA"));
    }
}
