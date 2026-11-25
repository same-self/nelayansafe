package com.nelayansafe.service;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.nelayansafe.exception.MissingSafetyGearException;
import com.nelayansafe.exception.UnsafeSeaConditionException;
import com.nelayansafe.exception.VesselOvercapacityException;
import com.nelayansafe.model.RouteEvaluation;
import com.nelayansafe.model.SafetyStatus;
import com.nelayansafe.model.SailingClearance;
import com.nelayansafe.model.Vessel;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ClearanceService {
    private static final DateTimeFormatter ID_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path dataDir;
    private final Path jsonFile;
    private final Path csvFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final List<SailingClearance> logs = new ArrayList<>();

    public ClearanceService() {
        this(Path.of("data"));
    }

    public ClearanceService(Path dataDir) {
        this.dataDir = dataDir != null ? dataDir : Path.of("data");
        this.jsonFile = this.dataDir.resolve("clearances.json");
        this.csvFile = this.dataDir.resolve("clearances.csv");
        try {
            Files.createDirectories(this.dataDir);
            if (Files.exists(this.jsonFile)) {
                String json = Files.readString(this.jsonFile, StandardCharsets.UTF_8);
                Type listType = new TypeToken<List<SailingClearance>>() {}.getType();
                List<SailingClearance> loaded = gson.fromJson(json, listType);
                if (loaded != null) {
                    logs.addAll(loaded);
                }
            }
        } catch (IOException ignored) {
        }
    }

    public synchronized SailingClearance requestClearance(
            Vessel vessel,
            int crewCount,
            RouteEvaluation routeEval,
            String recommendedWindow,
            boolean cautionAcknowledged)
            throws VesselOvercapacityException, MissingSafetyGearException, UnsafeSeaConditionException {

        // 1. Capacity & mandatory safety gear guardrails
        vessel.validateEquipmentAndCapacity(crewCount);

        // 2. Weather guardrail
        if (routeEval.getOverallStatus() == SafetyStatus.DO_NOT_SAIL) {
            throw new UnsafeSeaConditionException(
                String.format("Clearance DENIED due to DO_NOT_SAIL weather at %s (%s). Risk Score: %d/100. %s",
                    routeEval.getBottleneckAreaName(),
                    routeEval.getBottleneckAreaCode(),
                    routeEval.getHighestRiskScore(),
                    routeEval.getSummary()));
        }

        if (routeEval.getOverallStatus() == SafetyStatus.CAUTION && !cautionAcknowledged) {
            throw new UnsafeSeaConditionException(
                String.format("Clearance paused at CAUTION gate (%s, Risk Score %d/100). Captain must tick caution acknowledgment to proceed.",
                    routeEval.getBottleneckAreaName(), routeEval.getHighestRiskScore()));
        }

        List<String> codes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (RouteEvaluation.ZoneSegment seg : routeEval.getSegments()) {
            codes.add(seg.getAreaCode());
            names.add(seg.getAreaName());
        }

        IndonesianTimezoneResolver.TimezoneInfo tz = IndonesianTimezoneResolver.WIB;
        if (!routeEval.getSegments().isEmpty()) {
            RouteEvaluation.ZoneSegment origin = routeEval.getSegments().get(0);
            tz = IndonesianTimezoneResolver.resolve(origin.getProvince(), origin.getAreaName());
        }

        ZonedDateTime nowLocal = ZonedDateTime.now(tz.zoneId());
        String clearanceId = String.format("CLR-%s-%03d", nowLocal.format(ID_DATE_FMT), logs.size() + 1);
        SailingClearance clearance = new SailingClearance(
                clearanceId,
                vessel,
                crewCount,
                codes,
                names,
                routeEval.getHighestRiskScore(),
                routeEval.getOverallStatus(),
                recommendedWindow == null || recommendedWindow.isBlank() ? "Immediate window checked" : recommendedWindow,
                cautionAcknowledged,
                IndonesianTimezoneResolver.formatLogNow(tz),
                tz.displayLabel()
        );

        logs.add(0, clearance);
        persistAll();
        return clearance;
    }

    public synchronized List<SailingClearance> getAllClearances() {
        return Collections.unmodifiableList(new ArrayList<>(logs));
    }

    public synchronized String generateCsvContent() {
        StringBuilder sb = new StringBuilder();
        sb.append("clearance_id,vessel_id,vessel_name,vessel_type,crew_count,max_crew,life_jackets,working_radio,route_zones,risk_score,safety_status,caution_acknowledged,recommended_window,issued_local_time\n");
        for (SailingClearance c : logs) {
            sb.append(csv(c.getClearanceId())).append(',')
              .append(csv(c.getVesselId())).append(',')
              .append(csv(c.getVesselName())).append(',')
              .append(csv(c.getVesselType())).append(',')
              .append(c.getCrewCount()).append(',')
              .append(c.getMaxCrewCapacity()).append(',')
              .append(c.isHasLifeJackets()).append(',')
              .append(c.isHasWorkingRadio()).append(',')
              .append(csv(String.join(" -> ", c.getRouteZoneCodes()))).append(',')
              .append(c.getRiskScore()).append(',')
              .append(c.getSafetyStatus()).append(',')
              .append(c.isCautionAcknowledged()).append(',')
              .append(csv(c.getRecommendedWindow())).append(',')
              .append(csv(c.getIssuedTimestampLocal())).append('\n');
        }
        return sb.toString();
    }

    private void persistAll() {
        try {
            Files.createDirectories(dataDir);
            Files.writeString(jsonFile, gson.toJson(logs), StandardCharsets.UTF_8);
            Files.writeString(csvFile, generateCsvContent(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private String csv(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
