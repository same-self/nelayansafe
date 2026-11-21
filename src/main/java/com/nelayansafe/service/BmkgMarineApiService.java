package com.nelayansafe.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nelayansafe.exception.BmkgApiUnavailableException;
import com.nelayansafe.model.MarineForecastSlot;
import com.nelayansafe.model.SeaZone;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BmkgMarineApiService {
    public static final String DEFAULT_BASE_URL = "https://maritim.bmkg.go.id/marine2026-data";
    private static final long CATALOG_CACHE_TTL_MINUTES = 360L; // 6 hours
    private static final long FORECAST_CACHE_TTL_MINUTES = 15L; // 15 minutes
    private static final DateTimeFormatter BMKG_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    private final HttpClient httpClient;
    private final String baseUrl;
    private final Path cacheDir;
    private final boolean allowClasspathFallback;
    private final Map<String, SeaZone> indexByCode = new ConcurrentHashMap<>();

    public BmkgMarineApiService() {
        this(DEFAULT_BASE_URL, Path.of("cache"), true);
    }

    public BmkgMarineApiService(String baseUrl, Path cacheDir, boolean allowClasspathFallback) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.replaceAll("/+$", "");
        this.cacheDir = cacheDir != null ? cacheDir : Path.of("cache");
        this.allowClasspathFallback = allowClasspathFallback;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try {
            Files.createDirectories(this.cacheDir);
        } catch (IOException ignored) {
        }
    }

    public record ZoneCatalog(String sourceMode, List<ProvinceGroup> provinces) {}
    public record ProvinceGroup(String province, List<SeaZone> areas) {}

    public ZoneCatalog fetchZoneCatalog(boolean forceRefresh) throws BmkgApiUnavailableException {
        Path diskCache = cacheDir.resolve("area_province.json");
        String rawJson = null;
        String mode = "LIVE_BMKG";

        // 1. Fresh disk cache (skipped when forceRefresh = true)
        if (!forceRefresh && Files.exists(diskCache) && !isDiskCacheStale(diskCache, CATALOG_CACHE_TTL_MINUTES)) {
            String cached = readDiskFileSafe(diskCache);
            if (cached != null && isValidCatalogJson(cached)) {
                rawJson = cached;
                mode = "LIVE_BMKG";
            }
        }

        // 2. Live BMKG fetch (validated before writing to disk cache)
        if (rawJson == null) {
            try {
                String live = httpGet(baseUrl + "/meta/area_province.json");
                if (isValidCatalogJson(live)) {
                    rawJson = live;
                    mode = "LIVE_BMKG";
                    writeDiskFileSafe(diskCache, live);
                }
            } catch (Exception ignored) {
            }
        }

        // 3. Stale disk cache fallback
        if (rawJson == null && Files.exists(diskCache)) {
            String cached = readDiskFileSafe(diskCache);
            if (cached != null && isValidCatalogJson(cached)) {
                rawJson = cached;
                mode = "OFFLINE_CACHE";
            }
        }

        // 4. Bundled classpath fallback
        if (rawJson == null && allowClasspathFallback) {
            String bundled = readClasspathResource("/fallback/area_province.json");
            if (bundled != null && isValidCatalogJson(bundled)) {
                rawJson = bundled;
                mode = "OFFLINE_CACHE";
            }
        }

        // 5. All sources failed
        if (rawJson == null) {
            throw new BmkgApiUnavailableException("Unable to load BMKG sea zone catalog online or from offline cache.");
        }

        JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
        JsonArray dataArray = root.getAsJsonArray("data");
        List<ProvinceGroup> groups = new ArrayList<>();
        indexByCode.clear();

        for (JsonElement elem : dataArray) {
            JsonObject provObj = elem.getAsJsonObject();
            String province = getString(provObj, "province", "Indonesia");
            JsonArray areas = provObj.getAsJsonArray("areas");
            List<SeaZone> zones = new ArrayList<>();
            if (areas != null) {
                for (JsonElement areaElem : areas) {
                    JsonObject areaObj = areaElem.getAsJsonObject();
                    String id = getString(areaObj, "id", "");
                    String name = getString(areaObj, "name", id);
                    if (!id.isBlank()) {
                        SeaZone z = new SeaZone(id, name, province);
                        zones.add(z);
                        indexByCode.put(id, z);
                    }
                }
            }
            if (!zones.isEmpty()) {
                groups.add(new ProvinceGroup(province, zones));
            }
        }
        return new ZoneCatalog(mode, groups);
    }

    public SeaZone fetchZoneForecast(String rawCode, boolean forceRefresh, boolean simulateStorm)
            throws BmkgApiUnavailableException {
        if (indexByCode.isEmpty()) {
            try {
                fetchZoneCatalog(false);
            } catch (BmkgApiUnavailableException ignored) {
            }
        }

        String safeCode = rawCode == null ? "" : rawCode.replaceAll("[^A-Za-z0-9._-]", "");
        if (safeCode.isBlank()) {
            throw new IllegalArgumentException("Sea zone code is required.");
        }

        if (simulateStorm) {
            return buildSimulatedStormZone(safeCode);
        }

        Path diskCache = cacheDir.resolve("perairan_" + safeCode + ".json");
        String rawJson = null;
        String mode = "LIVE_BMKG";

        // 1. Fresh disk cache (skipped when forceRefresh = true)
        if (!forceRefresh && Files.exists(diskCache) && !isDiskCacheStale(diskCache, FORECAST_CACHE_TTL_MINUTES)) {
            String cached = readDiskFileSafe(diskCache);
            if (cached != null && isValidZoneJson(safeCode, cached)) {
                rawJson = cached;
                mode = "LIVE_BMKG";
            }
        }

        // 2. Live BMKG fetch (validated before writing to disk cache)
        if (rawJson == null) {
            try {
                String live = httpGet(baseUrl + "/perairan/" + safeCode + ".json");
                if (isValidZoneJson(safeCode, live)) {
                    rawJson = live;
                    mode = "LIVE_BMKG";
                    writeDiskFileSafe(diskCache, live);
                }
            } catch (Exception ignored) {
            }
        }

        // 3. Stale disk cache fallback
        if (rawJson == null && Files.exists(diskCache)) {
            String cached = readDiskFileSafe(diskCache);
            if (cached != null && isValidZoneJson(safeCode, cached)) {
                rawJson = cached;
                mode = "OFFLINE_CACHE";
            }
        }

        // 4. Bundled classpath fallback
        if (rawJson == null && allowClasspathFallback) {
            String bundled = readClasspathResource("/fallback/" + safeCode + ".json");
            if (bundled != null && isValidZoneJson(safeCode, bundled)) {
                rawJson = bundled;
                mode = "OFFLINE_CACHE";
            }
        }

        // 5. All sources failed
        if (rawJson == null) {
            throw new BmkgApiUnavailableException(
                "BMKG API is unreachable and no offline cache exists for zone '" + safeCode + "'.");
        }

        return parseZoneForecast(safeCode, rawJson, mode);
    }

    public MarineForecastSlot pickCurrentSlot(SeaZone zone) {
        List<MarineForecastSlot> slots = zone.getDay1Slots();
        if (slots.isEmpty()) {
            return null;
        }

        Instant now = Instant.now();
        MarineForecastSlot bestByTimestamp = slots.get(0);
        long smallestDiffMinutes = Long.MAX_VALUE;

        for (MarineForecastSlot slot : slots) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(slot.getTimeUtc(), BMKG_TIME_FMT);
                Instant slotInstant = ldt.toInstant(ZoneOffset.UTC);
                long diff = Math.abs(Duration.between(now, slotInstant).toMinutes());
                if (diff < smallestDiffMinutes) {
                    smallestDiffMinutes = diff;
                    bestByTimestamp = slot;
                }
            } catch (Exception ignored) {
            }
        }

        // Live BMKG and simulated forecasts match exact timestamp (within 24 hours of now)
        if (smallestDiffMinutes <= 24L * 60L) {
            return bestByTimestamp;
        }

        // Outdated offline fallback files match closest UTC time-of-day instead of clamping to the final slot
        ZonedDateTime nowUtc = now.atZone(ZoneOffset.UTC);
        int nowMinuteOfDay = nowUtc.getHour() * 60 + nowUtc.getMinute();
        MarineForecastSlot bestByTimeOfDay = slots.get(0);
        int smallestCircularDiff = Integer.MAX_VALUE;

        for (MarineForecastSlot slot : slots) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(slot.getTimeUtc(), BMKG_TIME_FMT);
                int slotMinuteOfDay = ldt.getHour() * 60 + ldt.getMinute();
                int rawDiff = Math.abs(nowMinuteOfDay - slotMinuteOfDay);
                int circularDiff = Math.min(rawDiff, (24 * 60) - rawDiff);
                if (circularDiff < smallestCircularDiff) {
                    smallestCircularDiff = circularDiff;
                    bestByTimeOfDay = slot;
                }
            } catch (Exception ignored) {
            }
        }
        return bestByTimeOfDay;
    }

    private JsonObject extractZonePayload(String code, JsonObject root) {
        if (root == null) return null;
        if (root.has("forecast_day1")) {
            return root;
        }
        if (root.has(code) && root.get(code).isJsonObject()) {
            return root.getAsJsonObject(code);
        }
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            if (entry.getValue().isJsonObject()) {
                return entry.getValue().getAsJsonObject();
            }
        }
        return null;
    }

    private boolean isValidCatalogJson(String rawJson) {
        try {
            JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
            return root.has("data")
                    && root.get("data").isJsonArray()
                    && !root.getAsJsonArray("data").isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isValidZoneJson(String code, String rawJson) {
        try {
            JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
            JsonObject payload = extractZonePayload(code, root);
            return payload != null
                    && payload.has("forecast_day1")
                    && payload.get("forecast_day1").isJsonArray()
                    && !payload.getAsJsonArray("forecast_day1").isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private SeaZone parseZoneForecast(String code, String rawJson, String mode) {
        JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
        JsonObject payload = extractZonePayload(code, root);
        if (payload == null) {
            payload = root;
        }

        SeaZone meta = indexByCode.get(code);
        String name = getString(payload, "name", meta != null ? meta.getAreaName() : code);
        String province = meta != null ? meta.getProvince() : "Indonesia";
        IndonesianTimezoneResolver.TimezoneInfo tz = IndonesianTimezoneResolver.resolve(province, name);

        SeaZone zone = new SeaZone(code, name, province);
        zone.setStationName(getString(payload, "nama_stasiun", "BMKG Maritime Meteorological Station"));
        zone.setIssuedUtc(getString(payload, "issued", "N/A"));
        zone.setValidFromUtc(getString(payload, "valid_from", "N/A"));
        zone.setValidToUtc(getString(payload, "valid_to", "N/A"));
        zone.setDataSourceMode(mode);
        zone.setTimezoneLabel(tz.displayLabel());
        zone.setFetchedAtLocal(IndonesianTimezoneResolver.formatFullNow(tz));

        List<MarineForecastSlot> slots = new ArrayList<>();
        JsonArray arr = payload.getAsJsonArray("forecast_day1");
        if (arr != null) {
            for (JsonElement item : arr) {
                JsonObject obj = item.getAsJsonObject();
                String timeUtc = getString(obj, "time", "");
                slots.add(new MarineForecastSlot(
                        timeUtc,
                        convertUtcToLocal(timeUtc, tz),
                        getString(obj, "weather", "N/A"),
                        getDouble(obj, "temp_avg", 28.0),
                        getDouble(obj, "rh_avg", 80.0),
                        getString(obj, "wind_from", "-"),
                        getDouble(obj, "wind_speed", 0.0),
                        getDouble(obj, "wind_gust", 0.0),
                        getString(obj, "wave_cat", "Tenang"),
                        getDouble(obj, "wave_height", 0.0),
                        getString(obj, "current_to", "-"),
                        getDouble(obj, "current_speed", 0.0)
                ));
            }
        }
        zone.setDay1Slots(slots);
        return zone;
    }

    private SeaZone buildSimulatedStormZone(String code) {
        SeaZone meta = indexByCode.get(code);
        String name = meta != null ? meta.getAreaName() : "Simulated High-Wave Sea Zone";
        String province = meta != null ? meta.getProvince() : "Indonesia";
        IndonesianTimezoneResolver.TimezoneInfo tz = IndonesianTimezoneResolver.resolve(province, name);

        SeaZone zone = new SeaZone(code, name, province);
        zone.setStationName("SIMULATED HIGH-WAVE ADVISORY (DEMO TEST PROFILE)");
        zone.setIssuedUtc(ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00 'UTC'")));
        zone.setValidFromUtc("24h Simulation Window");
        zone.setValidToUtc("+24h");
        zone.setDataSourceMode("SIMULATED_STORM");
        zone.setTimezoneLabel(tz.displayLabel());
        zone.setFetchedAtLocal(IndonesianTimezoneResolver.formatFullNow(tz));

        List<MarineForecastSlot> slots = new ArrayList<>();
        ZonedDateTime baseUtc = ZonedDateTime.now(ZoneOffset.UTC).withMinute(0).withSecond(0).withNano(0);
        for (int h = 0; h < 24; h++) {
            ZonedDateTime slotUtc = baseUtc.plusHours(h);
            String utcStr = slotUtc.format(BMKG_TIME_FMT);
            String localStr = IndonesianTimezoneResolver.formatShort(slotUtc, tz);

            double wave;
            double wind;
            double gust;
            double current;
            double tempAvg;
            double rhAvg;
            String weather;
            String waveCat;

            if (h <= 8) {
                double progress = h / 8.0;
                wave = 1.95 + Math.sin(progress * Math.PI) * 0.50; // 1.95 - 2.45 m
                wind = 18.0 + Math.sin(progress * Math.PI) * 4.0;  // 18.0 - 22.0 kt
                gust = wind + 6.5;                                 // 24.5 - 28.5 kt
                current = 1.65;
                tempAvg = 26.5;
                rhAvg = 88.0;
                weather = "Hujan Lebat & Angin Kencang";
                waveCat = "Sedang";
            } else if (h <= 11) {
                int step = h - 9;
                wave = 1.15 - (step * 0.05); // 1.15, 1.10, 1.05 m
                wind = 13.0 - (step * 0.5);  // 13.0, 12.5, 12.0 kt
                gust = wind + 4.5;           // 17.5, 17.0, 16.5 kt
                current = 1.25;
                tempAvg = 27.5;
                rhAvg = 83.0;
                weather = "Berawan Tebal";
                waveCat = "Rendah";
            } else {
                wave = 0.70;
                wind = 9.0;
                gust = 13.0;
                current = 0.85;
                tempAvg = 28.5;
                rhAvg = 78.0;
                weather = "Cerah Berawan";
                waveCat = "Rendah";
            }

            slots.add(new MarineForecastSlot(
                    utcStr,
                    localStr,
                    weather,
                    tempAvg,
                    rhAvg,
                    "Timur Laut",
                    Math.round(wind * 10.0) / 10.0,
                    Math.round(gust * 10.0) / 10.0,
                    waveCat,
                    Math.round(wave * 100.0) / 100.0,
                    "Barat Daya",
                    current
            ));
        }
        zone.setDay1Slots(slots);
        return zone;
    }

    private String httpGet(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .header("User-Agent", "MaritimeSafetySystem/1.0")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300 || resp.body() == null || resp.body().isBlank()) {
            throw new IOException("BMKG HTTP status " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    private boolean isDiskCacheStale(Path file, long maxAgeMinutes) {
        try {
            Instant modified = Files.getLastModifiedTime(file).toInstant();
            return Duration.between(modified, Instant.now()).toMinutes() > maxAgeMinutes;
        } catch (IOException e) {
            return true;
        }
    }

    private String readDiskFileSafe(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private void writeDiskFileSafe(Path file, String content) {
        try {
            Files.createDirectories(cacheDir);
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private String readClasspathResource(String path) {
        try (InputStream is = BmkgMarineApiService.class.getResourceAsStream(path)) {
            if (is == null) return null;
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private String convertUtcToLocal(String utcStr, IndonesianTimezoneResolver.TimezoneInfo tz) {
        try {
            LocalDateTime ldt = LocalDateTime.parse(utcStr, BMKG_TIME_FMT);
            return IndonesianTimezoneResolver.formatShort(ldt.atZone(ZoneOffset.UTC), tz);
        } catch (Exception e) {
            return utcStr;
        }
    }

    private String getString(JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        return obj.get(key).getAsString();
    }

    private double getDouble(JsonObject obj, String key, double def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        try {
            return obj.get(key).getAsDouble();
        } catch (Exception e) {
            return def;
        }
    }

    public Map<String, Object> getVesselThresholdsReference() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("SmallFishingBoat", Map.of("label", "Small Fishing Boat (Perahu Nelayan)", "maxWindKt", 15.0, "maxWaveM", 1.25, "defaultCrew", 6));
        map.put("BargeVessel", Map.of("label", "Barge Vessel (Kapal Tongkang)", "maxWindKt", 16.0, "maxWaveM", 1.50, "defaultCrew", 12));
        map.put("PassengerFerry", Map.of("label", "Passenger Ferry (Kapal Ferry)", "maxWindKt", 21.0, "maxWaveM", 2.50, "defaultCrew", 150));
        map.put("CargoShip", Map.of("label", "Cargo / Cruise Ship (Kapal Kargo)", "maxWindKt", 27.0, "maxWaveM", 4.00, "defaultCrew", 40));
        return map;
    }
}
