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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class BmkgMarineApiService {
    private static final String BASE_URL = "https://maritim.bmkg.go.id/marine2026-data";
    private static final ZoneId WIB_ZONE = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter BMKG_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");
    private static final DateTimeFormatter WIB_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm 'WIB'");
    private static final DateTimeFormatter WIB_SHORT_FMT = DateTimeFormatter.ofPattern("HH:mm 'WIB'");

    private final HttpClient httpClient;
    private final Path cacheDir;
    private final Map<String, SeaZone> indexByCode = new ConcurrentHashMap<>();

    public BmkgMarineApiService() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.cacheDir = Path.of("cache");
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException ignored) {
        }
    }

    public record ZoneCatalog(String sourceMode, List<ProvinceGroup> provinces) {}
    public record ProvinceGroup(String province, List<SeaZone> areas) {}

    public ZoneCatalog fetchZoneCatalog(boolean forceRefresh) throws BmkgApiUnavailableException {
        String rawJson = null;
        String mode = "LIVE_BMKG";
        Path diskCache = cacheDir.resolve("area_province.json");

        if (forceRefresh || Files.notExists(diskCache)) {
            try {
                rawJson = httpGet(BASE_URL + "/meta/area_province.json");
                Files.writeString(diskCache, rawJson, StandardCharsets.UTF_8);
            } catch (Exception liveEx) {
                rawJson = null;
            }
        } else {
            try {
                rawJson = httpGet(BASE_URL + "/meta/area_province.json");
                Files.writeString(diskCache, rawJson, StandardCharsets.UTF_8);
            } catch (Exception liveEx) {
                rawJson = null;
            }
        }

        if (rawJson == null && Files.exists(diskCache)) {
            try {
                rawJson = Files.readString(diskCache, StandardCharsets.UTF_8);
                mode = "OFFLINE_CACHE";
            } catch (IOException ignored) {
            }
        }

        if (rawJson == null) {
            rawJson = readClasspathResource("/fallback/area_province.json");
            mode = "OFFLINE_CACHE";
        }

        if (rawJson == null) {
            throw new BmkgApiUnavailableException("Unable to load BMKG sea zone catalog online or from cache.");
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

        try {
            if (forceRefresh || Files.notExists(diskCache) || isDiskCacheStale(diskCache)) {
                rawJson = httpGet(BASE_URL + "/perairan/" + safeCode + ".json");
                Files.writeString(diskCache, rawJson, StandardCharsets.UTF_8);
            } else {
                rawJson = Files.readString(diskCache, StandardCharsets.UTF_8);
                mode = "LIVE_BMKG";
            }
        } catch (Exception liveEx) {
            rawJson = null;
        }

        if (rawJson == null && Files.exists(diskCache)) {
            try {
                rawJson = Files.readString(diskCache, StandardCharsets.UTF_8);
                mode = "OFFLINE_CACHE";
            } catch (IOException ignored) {
            }
        }

        if (rawJson == null) {
            rawJson = readClasspathResource("/fallback/" + safeCode + ".json");
            mode = "OFFLINE_CACHE";
        }

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
        MarineForecastSlot best = slots.get(0);
        long smallestDiff = Long.MAX_VALUE;
        for (MarineForecastSlot slot : slots) {
            try {
                LocalDateTime ldt = LocalDateTime.parse(slot.getTimeUtc(), BMKG_TIME_FMT);
                Instant slotInstant = ldt.toInstant(ZoneOffset.UTC);
                long diff = Math.abs(Duration.between(now, slotInstant).toMinutes());
                if (diff < smallestDiff) {
                    smallestDiff = diff;
                    best = slot;
                }
            } catch (Exception ignored) {
            }
        }
        return best;
    }

    private SeaZone parseZoneForecast(String code, String rawJson, String mode) {
        JsonObject root = JsonParser.parseString(rawJson).getAsJsonObject();
        JsonObject payload = root;
        if (!root.has("forecast_day1")) {
            if (root.has(code) && root.get(code).isJsonObject()) {
                payload = root.getAsJsonObject(code);
            } else {
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    if (entry.getValue().isJsonObject()) {
                        payload = entry.getValue().getAsJsonObject();
                        break;
                    }
                }
            }
        }

        SeaZone meta = indexByCode.get(code);
        String name = getString(payload, "name", meta != null ? meta.getAreaName() : code);
        String province = meta != null ? meta.getProvince() : "Indonesia";

        SeaZone zone = new SeaZone(code, name, province);
        zone.setStationName(getString(payload, "nama_stasiun", "BMKG Maritime Meteorological Station"));
        zone.setIssuedUtc(getString(payload, "issued", "N/A"));
        zone.setValidFromUtc(getString(payload, "valid_from", "N/A"));
        zone.setValidToUtc(getString(payload, "valid_to", "N/A"));
        zone.setDataSourceMode(mode);
        zone.setFetchedAtWib(ZonedDateTime.now(WIB_ZONE).format(WIB_FMT));

        List<MarineForecastSlot> slots = new ArrayList<>();
        JsonArray arr = payload.getAsJsonArray("forecast_day1");
        if (arr != null) {
            for (JsonElement item : arr) {
                JsonObject obj = item.getAsJsonObject();
                String timeUtc = getString(obj, "time", "");
                slots.add(new MarineForecastSlot(
                        timeUtc,
                        convertUtcToWib(timeUtc),
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
        String name = meta != null ? meta.getAreaName() : "Perairan Kepulauan Natuna (Simulated Storm)";
        String province = meta != null ? meta.getProvince() : "Kepulauan Riau";

        SeaZone zone = new SeaZone(code, name, province);
        zone.setStationName("SIMULATED BMKG ADVISORY (NOV 2026 NATUNA PROFILE)");
        zone.setIssuedUtc(ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00 'UTC'")));
        zone.setValidFromUtc("24h Simulation Window");
        zone.setValidToUtc("+24h");
        zone.setDataSourceMode("SIMULATED_STORM");
        zone.setFetchedAtWib(ZonedDateTime.now(WIB_ZONE).format(WIB_FMT));

        List<MarineForecastSlot> slots = new ArrayList<>();
        ZonedDateTime baseUtc = ZonedDateTime.now(ZoneOffset.UTC).withMinute(0).withSecond(0).withNano(0);
        for (int h = 0; h < 24; h++) {
            ZonedDateTime slotUtc = baseUtc.plusHours(h);
            String utcStr = slotUtc.format(BMKG_TIME_FMT);
            String wibStr = slotUtc.withZoneSameInstant(WIB_ZONE).format(WIB_SHORT_FMT);

            double wave;
            double wind;
            double gust;
            double current;
            String weather;
            String waveCat;

            if (h >= 3 && h <= 14) {
                wave = 1.85 + Math.sin((h - 3) / 11.0 * Math.PI) * 0.60; // 1.85 - 2.45 m
                wind = 17.0 + Math.sin((h - 3) / 11.0 * Math.PI) * 5.0;  // 17 - 22 kt
                gust = wind + 7.0;
                current = 1.65;
                weather = "Hujan Lebat & Angin Kencang";
                waveCat = "Sedang";
            } else {
                wave = 1.05;
                wind = 11.0;
                gust = 16.0;
                current = 0.95;
                weather = "Berawan";
                waveCat = "Rendah";
            }

            slots.add(new MarineForecastSlot(
                    utcStr,
                    wibStr,
                    weather,
                    26.5,
                    88.0,
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
                .header("User-Agent", "NelayanSafe-MaritimeSafety/1.0")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300 || resp.body() == null || resp.body().isBlank()) {
            throw new IOException("BMKG HTTP status " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    private boolean isDiskCacheStale(Path file) {
        try {
            Instant modified = Files.getLastModifiedTime(file).toInstant();
            return Duration.between(modified, Instant.now()).toMinutes() > 15;
        } catch (IOException e) {
            return true;
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

    private String convertUtcToWib(String utcStr) {
        try {
            LocalDateTime ldt = LocalDateTime.parse(utcStr, BMKG_TIME_FMT);
            return ldt.atOffset(ZoneOffset.UTC).atZoneSameInstant(WIB_ZONE).format(WIB_SHORT_FMT);
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
