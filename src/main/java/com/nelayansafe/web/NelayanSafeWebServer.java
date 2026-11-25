package com.nelayansafe.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.nelayansafe.exception.BmkgApiUnavailableException;
import com.nelayansafe.exception.MissingSafetyGearException;
import com.nelayansafe.exception.UnsafeSeaConditionException;
import com.nelayansafe.exception.VesselOvercapacityException;
import com.nelayansafe.model.MarineForecastSlot;
import com.nelayansafe.model.RouteEvaluation;
import com.nelayansafe.model.SafetyEvaluation;
import com.nelayansafe.model.SailingClearance;
import com.nelayansafe.model.SeaZone;
import com.nelayansafe.model.Vessel;
import com.nelayansafe.service.BmkgMarineApiService;
import com.nelayansafe.service.ClearanceService;
import com.nelayansafe.service.RouteAndWindowOptimizer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

public class NelayanSafeWebServer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final BmkgMarineApiService apiService = new BmkgMarineApiService();
    private final RouteAndWindowOptimizer optimizer = new RouteAndWindowOptimizer(apiService);
    private final ClearanceService clearanceService = new ClearanceService();

    public static void main(String[] args) throws IOException {
        new NelayanSafeWebServer().start();
    }

    public void start() throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));

        server.createContext("/api/zones", this::handleZones);
        server.createContext("/api/vessels/rules", this::handleVesselRules);
        server.createContext("/api/evaluate", this::handleEvaluate);
        server.createContext("/api/route", this::handleRoute);
        server.createContext("/api/clearance", this::handleClearancePost);
        server.createContext("/api/clearances/export", this::handleClearancesExport);
        server.createContext("/api/clearances", this::handleClearancesList);
        server.createContext("/", this::handleStatic);

        server.start();
        System.out.println("===================================================================");
        System.out.println(" Maritime Sailing Safety and Departure Window System on port " + port);
        System.out.println(" Open locally: http://localhost:" + port);
        System.out.println("===================================================================");
    }

    private void handleZones(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        try {
            Map<String, String> q = parseQuery(ex);
            boolean refresh = Boolean.parseBoolean(q.getOrDefault("refresh", "false"));
            BmkgMarineApiService.ZoneCatalog catalog = apiService.fetchZoneCatalog(refresh);
            sendJson(ex, 200, Map.of("success", true, "catalog", catalog));
        } catch (BmkgApiUnavailableException e) {
            sendError(ex, 503, e.getClass().getSimpleName(), e.getMessage());
        } catch (Exception e) {
            sendError(ex, 500, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private void handleVesselRules(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        sendJson(ex, 200, Map.of("success", true, "rules", apiService.getVesselThresholdsReference()));
    }

    private void handleEvaluate(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        try {
            Map<String, String> q = parseQuery(ex);
            String vesselType = q.getOrDefault("vesselType", "SmallFishingBoat");
            int defaultMaxCrew = Vessel.defaultMaxCrewFor(vesselType);
            Vessel.VesselOptions opts = new Vessel.VesselOptions(
                    parseDouble(q.get("grossTonnage"), 5.0),
                    parseDouble(q.get("maxSafeCurrentKnots"), 2.0),
                    parseDouble(q.get("maxCargoTon"), 3000.0),
                    parseDouble(q.get("currentCargoTon"), 2000.0),
                    parseInt(q.get("maxPassengers"), 300),
                    parseInt(q.get("currentPassengers"), 180),
                    parseInt(q.get("maxContainerTEU"), 1200)
            );
            Vessel vessel = Vessel.create(
                    vesselType,
                    q.getOrDefault("vesselId", "VSL-001"),
                    q.getOrDefault("vesselName", "KM Nelayan Sejahtera"),
                    parseInt(q.get("maxCrew"), defaultMaxCrew),
                    Boolean.parseBoolean(q.getOrDefault("lifeJackets", "true")),
                    Boolean.parseBoolean(q.getOrDefault("radio", "true")),
                    opts
            );

            String areaCode = q.getOrDefault("areaCode", "P.AE.09");
            boolean refresh = Boolean.parseBoolean(q.getOrDefault("refresh", "false"));
            boolean simulate = Boolean.parseBoolean(q.getOrDefault("simulate", "false"));

            SeaZone zone = apiService.fetchZoneForecast(areaCode, refresh, simulate);
            MarineForecastSlot currentSlot = apiService.pickCurrentSlot(zone);
            if (currentSlot == null) {
                throw new BmkgApiUnavailableException("Zone forecast contains no hourly slots.");
            }
            SafetyEvaluation currentEval = vessel.evaluateSafety(currentSlot);
            RouteAndWindowOptimizer.WindowOptimizationResult windowResult =
                    optimizer.analyze24HourWindows(vessel, zone);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("success", true);
            payload.put("zone", zone);
            payload.put("currentSlot", currentSlot);
            payload.put("currentEvaluation", currentEval);
            payload.put("windowAnalysis", windowResult);
            sendJson(ex, 200, payload);
        } catch (BmkgApiUnavailableException e) {
            sendError(ex, 503, e.getClass().getSimpleName(), e.getMessage());
        } catch (IllegalArgumentException e) {
            sendError(ex, 400, e.getClass().getSimpleName(), e.getMessage());
        } catch (Exception e) {
            sendError(ex, 500, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private void handleRoute(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "MethodNotAllowed", "Use POST for /api/route");
            return;
        }
        try {
            JsonObject body = JsonParser.parseString(readBody(ex)).getAsJsonObject();
            Vessel vessel = vesselFromJson(body);
            List<String> codes = readAreaCodes(body);
            boolean refresh = getBool(body, "refresh", false);
            boolean simulate = getBool(body, "simulate", false);

            RouteEvaluation routeEval = optimizer.evaluateMultiZoneRoute(vessel, codes, refresh, simulate);
            sendJson(ex, 200, Map.of("success", true, "routeEvaluation", routeEval));
        } catch (BmkgApiUnavailableException e) {
            sendError(ex, 503, e.getClass().getSimpleName(), e.getMessage());
        } catch (IllegalArgumentException | JsonParseException | IllegalStateException e) {
            sendError(ex, 400, e.getClass().getSimpleName(), e.getMessage());
        } catch (Exception e) {
            sendError(ex, 500, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private void handleClearancePost(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendError(ex, 405, "MethodNotAllowed", "Use POST for /api/clearance");
            return;
        }
        try {
            JsonObject body = JsonParser.parseString(readBody(ex)).getAsJsonObject();
            Vessel vessel = vesselFromJson(body);
            int crewCount = parseRequiredIntOrDefault(body, "crewCount", 1);
            List<String> codes = readAreaCodes(body);
            boolean simulate = getBool(body, "simulate", false);
            boolean cautionAck = getBool(body, "cautionAcknowledged", false);
            String recWindow = body.has("recommendedWindow") && !body.get("recommendedWindow").isJsonNull()
                    ? body.get("recommendedWindow").getAsString()
                    : "";

            RouteEvaluation routeEval = optimizer.evaluateMultiZoneRoute(vessel, codes, false, simulate);
            SailingClearance clearance = clearanceService.requestClearance(
                    vessel, crewCount, routeEval, recWindow, cautionAck);

            sendJson(ex, 201, Map.of(
                    "success", true,
                    "clearance", clearance,
                    "routeEvaluation", routeEval
            ));
        } catch (VesselOvercapacityException | MissingSafetyGearException | UnsafeSeaConditionException e) {
            sendError(ex, 422, e.getClass().getSimpleName(), e.getMessage());
        } catch (BmkgApiUnavailableException e) {
            sendError(ex, 503, e.getClass().getSimpleName(), e.getMessage());
        } catch (IllegalArgumentException | JsonParseException | IllegalStateException e) {
            sendError(ex, 400, e.getClass().getSimpleName(), e.getMessage());
        } catch (Exception e) {
            sendError(ex, 500, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    private void handleClearancesList(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        sendJson(ex, 200, Map.of("success", true, "clearances", clearanceService.getAllClearances()));
    }

    private void handleClearancesExport(HttpExchange ex) throws IOException {
        if (handleCorsPreflight(ex)) return;
        byte[] bytes = clearanceService.generateCsvContent().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/csv; charset=utf-8");
        ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"maritime_safety_clearances.csv\"");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path == null || path.equals("/")) {
            path = "/index.html";
        }
        String resourcePath = "/public" + path;
        try (InputStream is = NelayanSafeWebServer.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                byte[] notFound = "404 Not Found".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(404, notFound.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(notFound);
                }
                return;
            }
            byte[] bytes = is.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", guessMimeType(path));
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private Vessel vesselFromJson(JsonObject body) {
        String type = body.has("vesselType") && !body.get("vesselType").isJsonNull()
                ? body.get("vesselType").getAsString() : "SmallFishingBoat";
        String id = body.has("vesselId") && !body.get("vesselId").isJsonNull()
                ? body.get("vesselId").getAsString() : "VSL-001";
        String name = body.has("vesselName") && !body.get("vesselName").isJsonNull()
                ? body.get("vesselName").getAsString() : "KM Nelayan Sejahtera";
        int maxCrew = getInt(body, "maxCrew", Vessel.defaultMaxCrewFor(type));
        boolean lifeJackets = getBool(body, "lifeJackets", true);
        boolean radio = getBool(body, "radio", true);

        Vessel.VesselOptions opts = new Vessel.VesselOptions(
                getDouble(body, "grossTonnage", 5.0),
                getDouble(body, "maxSafeCurrentKnots", 2.0),
                getDouble(body, "maxCargoTon", 3000.0),
                getDouble(body, "currentCargoTon", 2000.0),
                getInt(body, "maxPassengers", 300),
                getInt(body, "currentPassengers", 180),
                getInt(body, "maxContainerTEU", 1200)
        );
        return Vessel.create(type, id, name, maxCrew, lifeJackets, radio, opts);
    }

    private List<String> readAreaCodes(JsonObject body) {
        List<String> codes = new ArrayList<>();
        if (body.has("areaCodes") && body.get("areaCodes").isJsonArray()) {
            JsonArray arr = body.getAsJsonArray("areaCodes");
            for (JsonElement el : arr) {
                String c = el.getAsString().trim();
                if (!c.isBlank()) codes.add(c);
            }
        }
        return codes;
    }

    private boolean getBool(JsonObject obj, String key, boolean def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsBoolean() : def;
    }

    private int parseInt(String val, int def) {
        if (val == null || val.isBlank()) return def;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private double parseDouble(String val, double def) {
        if (val == null || val.isBlank()) return def;
        try {
            return Double.parseDouble(val.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private int parseRequiredIntOrDefault(JsonObject obj, String key, int def) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return def;
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            throw new IllegalArgumentException("Field '" + key + "' must be a valid integer.");
        }
    }

    private int getInt(JsonObject obj, String key, int def) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return def;
        try {
            return obj.get(key).getAsInt();
        } catch (Exception e) {
            return def;
        }
    }

    private double getDouble(JsonObject obj, String key, double def) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return def;
        try {
            return obj.get(key).getAsDouble();
        } catch (Exception e) {
            return def;
        }
    }

    private Map<String, String> parseQuery(HttpExchange ex) {
        Map<String, String> map = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) return map;
        for (String pair : raw.split("&")) {
            String[] kv = pair.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(k, v);
        }
        return map;
    }

    private String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private boolean handleCorsPreflight(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            ex.sendResponseHeaders(204, -1);
            return true;
        }
        return false;
    }

    private void sendError(HttpExchange ex, int status, String errorType, String message) throws IOException {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("success", false);
        err.put("errorType", errorType);
        err.put("message", message);
        sendJson(ex, status, err);
    }

    private void sendJson(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String guessMimeType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }
}
