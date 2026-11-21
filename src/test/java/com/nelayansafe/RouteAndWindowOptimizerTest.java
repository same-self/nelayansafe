package com.nelayansafe;

import com.nelayansafe.exception.BmkgApiUnavailableException;
import com.nelayansafe.model.BargeVessel;
import com.nelayansafe.model.CargoShip;
import com.nelayansafe.model.MarineForecastSlot;
import com.nelayansafe.model.PassengerFerry;
import com.nelayansafe.model.RouteEvaluation;
import com.nelayansafe.model.SafetyStatus;
import com.nelayansafe.model.SeaZone;
import com.nelayansafe.model.SmallFishingBoat;
import com.nelayansafe.model.Vessel;
import com.nelayansafe.service.BmkgMarineApiService;
import com.nelayansafe.service.RouteAndWindowOptimizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RouteAndWindowOptimizerTest {

    private static final DateTimeFormatter BMKG_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    @Test
    void stormSimulationIsImmediatelyDoNotSailAndProvides12HourSafeRecoveryWindow(@TempDir Path tempDir) throws Exception {
        BmkgMarineApiService api = new BmkgMarineApiService("http://127.0.0.1:1", tempDir, true);
        RouteAndWindowOptimizer optimizer = new RouteAndWindowOptimizer(api);

        SeaZone stormZone = api.fetchZoneForecast("P.AE.09", false, true);
        assertEquals("SIMULATED_STORM", stormZone.getDataSourceMode());
        assertEquals("WITA (UTC+8)", stormZone.getTimezoneLabel());
        assertEquals(24, stormZone.getDay1Slots().size());

        MarineForecastSlot nowSlot = api.pickCurrentSlot(stormZone);
        assertNotNull(nowSlot);

        // Live polymorphism during active storm hour
        assertEquals(SafetyStatus.DO_NOT_SAIL, new SmallFishingBoat("V-1", "Boat", 6, true, true).evaluateSafety(nowSlot).getStatus());
        assertEquals(SafetyStatus.DO_NOT_SAIL, new BargeVessel("V-2", "Barge", 12, true, true).evaluateSafety(nowSlot).getStatus());
        assertEquals(SafetyStatus.CAUTION, new PassengerFerry("V-3", "Ferry", 150, true, true).evaluateSafety(nowSlot).getStatus());
        assertEquals(SafetyStatus.SAFE, new CargoShip("V-4", "Cargo", 40, true, true).evaluateSafety(nowSlot).getStatus());

        // 24h optimizer finds the 12-hour calm recovery window in WITA
        RouteAndWindowOptimizer.WindowOptimizationResult result =
                optimizer.analyze24HourWindows(new SmallFishingBoat("V-1", "Boat", 6, true, true), stormZone);
        assertNotNull(result.recommendedWindow());
        assertEquals(SafetyStatus.SAFE, result.recommendedWindow().getWindowStatus());
        assertEquals(12, result.recommendedWindow().getDurationHours());
        assertTrue(result.recommendedWindow().getStartTimeLocal().endsWith("WITA"));
    }

    @Test
    void windowOptimizerPrefersLongestSafeWindowThenLowestAverageRisk(@TempDir Path tempDir) {
        BmkgMarineApiService api = new BmkgMarineApiService("http://127.0.0.1:1", tempDir, true);
        RouteAndWindowOptimizer optimizer = new RouteAndWindowOptimizer(api);
        Vessel boat = new SmallFishingBoat("V-1", "Boat", 6, true, true);

        // Two equal 2-hour SAFE windows separated by DO_NOT_SAIL; second window is calmer (lower avg risk)
        SeaZone zone = new SeaZone("P.TEST", "Test Sea", "Jawa Timur");
        zone.setDay1Slots(List.of(
                slotAtHour(0, 10.0, 0.80, 12.0, 0.8), // SAFE (higher risk)
                slotAtHour(1, 10.0, 0.80, 12.0, 0.8), // SAFE
                slotAtHour(2, 18.0, 1.80, 24.0, 1.5), // DO_NOT_SAIL
                slotAtHour(3, 5.0, 0.30, 8.0, 0.4),   // SAFE (calmer!)
                slotAtHour(4, 5.0, 0.30, 8.0, 0.4)    // SAFE
        ));

        RouteAndWindowOptimizer.WindowOptimizationResult res = optimizer.analyze24HourWindows(boat, zone);
        assertNotNull(res.recommendedWindow());
        assertEquals(2, res.recommendedWindow().getDurationHours());
        assertEquals("10:00 WIB", res.recommendedWindow().getStartTimeLocal());
        assertEquals("11:00 WIB", res.recommendedWindow().getEndTimeLocal());

        // All DO_NOT_SAIL zone returns null recommended window
        SeaZone allStorm = new SeaZone("P.STORM", "Storm Sea", "Aceh");
        allStorm.setDay1Slots(List.of(
                slotAtHour(0, 20.0, 2.0, 26.0, 1.8),
                slotAtHour(1, 21.0, 2.1, 27.0, 1.9)
        ));
        RouteAndWindowOptimizer.WindowOptimizationResult blocked = optimizer.analyze24HourWindows(boat, allStorm);
        assertNull(blocked.recommendedWindow());
        assertTrue(blocked.recommendationMessage().contains("Postpone sailing"));
    }

    @Test
    void multiZoneBottleneckPrioritizesDoNotSailOverHigherScoreCaution(@TempDir Path tempDir) throws Exception {
        // Zone A: CAUTION with high risk score (97)
        SeaZone cautionZone = new SeaZone("ZONE.A", "Perairan Caution HighScore", "Aceh");
        cautionZone.setDataSourceMode("OFFLINE_CACHE");
        cautionZone.setDay1Slots(List.of(currentSlot(14.5, 1.15, 24.0, 1.90)));

        // Zone B: DO_NOT_SAIL with lower risk score (87)
        SeaZone blockedZone = new SeaZone("ZONE.B", "Perairan Blocked Limit", "Sulawesi Selatan");
        blockedZone.setDataSourceMode("OFFLINE_CACHE");
        blockedZone.setDay1Slots(List.of(currentSlot(15.0, 0.50, 15.0, 0.00)));

        Map<String, SeaZone> stubZones = Map.of("ZONE.A", cautionZone, "ZONE.B", blockedZone);
        BmkgMarineApiService stubApi = new BmkgMarineApiService("http://127.0.0.1:1", tempDir, false) {
            @Override
            public SeaZone fetchZoneForecast(String rawCode, boolean forceRefresh, boolean simulateStorm)
                    throws BmkgApiUnavailableException {
                SeaZone z = stubZones.get(rawCode);
                if (z == null) throw new BmkgApiUnavailableException("Not found: " + rawCode);
                return z;
            }
        };

        RouteAndWindowOptimizer optimizer = new RouteAndWindowOptimizer(stubApi);
        Vessel boat = new SmallFishingBoat("V-1", "KM Uji", 6, true, true, 5.0, 2.0);
        RouteEvaluation eval = optimizer.evaluateMultiZoneRoute(boat, List.of("ZONE.A", "ZONE.B"), false, false);

        assertEquals(SafetyStatus.DO_NOT_SAIL, eval.getOverallStatus());
        assertEquals("ZONE.B", eval.getBottleneckAreaCode());
        assertEquals("Perairan Blocked Limit", eval.getBottleneckAreaName());
        assertEquals(87, eval.getHighestRiskScore());
    }

    private MarineForecastSlot slotAtHour(int utcHour, double wind, double wave, double gust, double current) {
        String utc = String.format("2026-11-21 %02d:00 UTC", utcHour);
        String wib = String.format("%02d:00 WIB", (utcHour + 7) % 24);
        return new MarineForecastSlot(utc, wib, "Berawan", 28.0, 80.0, "Timur", wind, gust, "Rendah", wave, "Barat", current);
    }

    private MarineForecastSlot currentSlot(double wind, double wave, double gust, double current) {
        String nowUtc = ZonedDateTime.now(ZoneOffset.UTC).withMinute(0).withSecond(0).withNano(0).format(BMKG_FMT);
        return new MarineForecastSlot(nowUtc, "12:00 WIB", "Berawan", 28.0, 80.0, "Timur", wind, gust, "Sedang", wave, "Barat", current);
    }
}
