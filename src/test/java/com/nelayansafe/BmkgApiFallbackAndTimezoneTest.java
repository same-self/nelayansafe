package com.nelayansafe;

import com.nelayansafe.model.MarineForecastSlot;
import com.nelayansafe.model.SeaZone;
import com.nelayansafe.service.BmkgMarineApiService;
import com.nelayansafe.service.IndonesianTimezoneResolver;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class BmkgApiFallbackAndTimezoneTest {

    private static final DateTimeFormatter BMKG_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    @Test
    void resolvesAllThreeIndonesianTimezonesAccuratelyWithoutSubstringCollisions() {
        // WIB (UTC+7)
        assertEquals("WIB", IndonesianTimezoneResolver.resolve("Aceh").abbreviation());
        assertEquals("WIB", IndonesianTimezoneResolver.resolve("Jawa Timur").abbreviation());
        assertEquals("WIB", IndonesianTimezoneResolver.resolve("Kepulauan Riau").abbreviation());
        assertEquals("WIB", IndonesianTimezoneResolver.resolve("Kalimantan Barat").abbreviation());

        // WITA (UTC+8)
        assertEquals("WITA", IndonesianTimezoneResolver.resolve("Sulawesi Selatan").abbreviation());
        assertEquals("WITA", IndonesianTimezoneResolver.resolve("Bali").abbreviation());
        assertEquals("WITA", IndonesianTimezoneResolver.resolve("Nusa Tenggara Timur").abbreviation());
        assertEquals("WITA", IndonesianTimezoneResolver.resolve("Kalimantan Timur").abbreviation());
        assertEquals("WITA", IndonesianTimezoneResolver.resolve("Gorontalo").abbreviation());

        // WIT (UTC+9)
        assertEquals("WIT", IndonesianTimezoneResolver.resolve("Maluku").abbreviation());
        assertEquals("WIT", IndonesianTimezoneResolver.resolve("Maluku Utara").abbreviation());
        assertEquals("WIT", IndonesianTimezoneResolver.resolve("Papua").abbreviation());
        assertEquals("WIT", IndonesianTimezoneResolver.resolve("Papua Barat Daya").abbreviation());

        // Area fallback & regression check: Kotabaru ("aru" substring) stays WITA, Garut stays WIB
        assertEquals("WITA", IndonesianTimezoneResolver.resolve("Indonesia", "Perairan Kotabaru").abbreviation());
        assertEquals("WIB", IndonesianTimezoneResolver.resolve("Jawa Barat", "Perairan Garut").abbreviation());
        assertEquals("WIT", IndonesianTimezoneResolver.resolve("Indonesia", "Perairan Kepulauan Aru").abbreviation());
    }

    @Test
    void offlineClasspathFallbackProvidesFull24HourForecastsWithAccurateWibAndWita(@TempDir Path tempDir) throws Exception {
        BmkgMarineApiService api = new BmkgMarineApiService("http://127.0.0.1:1", tempDir, true);

        BmkgMarineApiService.ZoneCatalog catalog = api.fetchZoneCatalog(false);
        assertEquals("OFFLINE_CACHE", catalog.sourceMode());
        assertEquals(3, catalog.provinces().size());

        // Perairan Sinjai (Sulawesi Selatan -> WITA)
        SeaZone sinjai = api.fetchZoneForecast("P.AE.09", false, false);
        assertEquals("OFFLINE_CACHE", sinjai.getDataSourceMode());
        assertEquals("WITA (UTC+8)", sinjai.getTimezoneLabel());
        assertEquals(24, sinjai.getDay1Slots().size());
        assertEquals("08:00 WITA", sinjai.getDay1Slots().get(0).getTimeLocal());

        // Perairan Aceh Besar - Meulaboh (Aceh -> WIB)
        SeaZone aceh = api.fetchZoneForecast("P.A.05", false, false);
        assertEquals("OFFLINE_CACHE", aceh.getDataSourceMode());
        assertEquals("WIB (UTC+7)", aceh.getTimezoneLabel());
        assertEquals(24, aceh.getDay1Slots().size());
        assertEquals("07:00 WIB", aceh.getDay1Slots().get(0).getTimeLocal());

        // Perairan Selatan P. Biak (Papua -> WIT)
        SeaZone biak = api.fetchZoneForecast("P.AL.04", false, false);
        assertEquals("OFFLINE_CACHE", biak.getDataSourceMode());
        assertEquals("WIT (UTC+9)", biak.getTimezoneLabel());
        assertEquals(24, biak.getDay1Slots().size());
        assertEquals("09:00 WIT", biak.getDay1Slots().get(0).getTimeLocal());

        // Stale offline fallback picks closest UTC time-of-day (within 1 hour of current UTC clock)
        MarineForecastSlot picked = api.pickCurrentSlot(sinjai);
        assertNotNull(picked);
        int pickedHour = LocalDateTime.parse(picked.getTimeUtc(), BMKG_FMT).getHour();
        int nowHour = ZonedDateTime.now(ZoneOffset.UTC).getHour();
        int rawDiff = Math.abs(nowHour - pickedHour);
        int circularDiff = Math.min(rawDiff, 24 - rawDiff);
        assertTrue(circularDiff <= 1, "Expected time-of-day match within 1h of UTC " + nowHour + ", got " + pickedHour);
    }

    @Test
    void freshDiskCacheReusesLiveDataAndCorruptRefreshFallsBackCleanly(@TempDir Path tempDir) throws Exception {
        AtomicInteger zoneHits = new AtomicInteger(0);
        AtomicBoolean returnCorruptResponse = new AtomicBoolean(false);

        String mockCatalogJson = """
                {
                  "data": [
                    {
                      "province": "Papua",
                      "areas": [{ "id": "P.TEST.01", "name": "Perairan Jayapura" }]
                    }
                  ]
                }
                """;
        String nowUtc = ZonedDateTime.now(ZoneOffset.UTC).withMinute(0).withSecond(0).withNano(0).format(BMKG_FMT);
        String mockZoneJson = """
                {
                  "P.TEST.01": {
                    "code": "P.TEST.01",
                    "name": "Perairan Jayapura",
                    "nama_stasiun": "STASIUN METEOROLOGI MARITIM JAYAPURA",
                    "issued": "%s",
                    "valid_from": "%s",
                    "valid_to": "%s",
                    "forecast_day1": [
                      { "time": "%s", "weather": "Cerah Berawan", "temp_avg": 29, "rh_avg": 78, "wind_from": "Timur", "wind_speed": 8, "wind_gust": 12, "wave_cat": "Rendah", "wave_height": 0.60, "current_to": "Barat", "current_speed": 0.70 }
                    ]
                  }
                }
                """.formatted(nowUtc, nowUtc, nowUtc, nowUtc);

        HttpServer mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mockServer.createContext("/meta/area_province.json", ex -> {
            byte[] bytes = mockCatalogJson.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        mockServer.createContext("/perairan/P.TEST.01.json", ex -> {
            zoneHits.incrementAndGet();
            String body = returnCorruptResponse.get() ? "<html>502 Bad Gateway</html>" : mockZoneJson;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        mockServer.start();

        try {
            String baseUrl = "http://127.0.0.1:" + mockServer.getAddress().getPort();
            BmkgMarineApiService api = new BmkgMarineApiService(baseUrl, tempDir, false);

            // 1. Live fetch hits mock server once and resolves WIT (Papua)
            SeaZone first = api.fetchZoneForecast("P.TEST.01", false, false);
            assertEquals("LIVE_BMKG", first.getDataSourceMode());
            assertEquals("WIT (UTC+9)", first.getTimezoneLabel());
            assertEquals(1, zoneHits.get());

            // 2. Second non-refresh call reuses fresh disk cache without hitting mock server again
            SeaZone second = api.fetchZoneForecast("P.TEST.01", false, false);
            assertEquals("LIVE_BMKG", second.getDataSourceMode());
            assertEquals(1, zoneHits.get());

            // 3. Force refresh when server returns corrupt HTML -> keeps good disk cache & marks OFFLINE_CACHE
            returnCorruptResponse.set(true);
            SeaZone fallback = api.fetchZoneForecast("P.TEST.01", true, false);
            assertEquals("OFFLINE_CACHE", fallback.getDataSourceMode());
            assertEquals(2, zoneHits.get());
            assertEquals(1, fallback.getDay1Slots().size());
        } finally {
            mockServer.stop(0);
        }
    }
}
