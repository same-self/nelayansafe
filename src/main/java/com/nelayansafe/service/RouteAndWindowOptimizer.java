package com.nelayansafe.service;

import com.nelayansafe.exception.BmkgApiUnavailableException;
import com.nelayansafe.model.DepartureWindow;
import com.nelayansafe.model.MarineForecastSlot;
import com.nelayansafe.model.RouteEvaluation;
import com.nelayansafe.model.SafetyEvaluation;
import com.nelayansafe.model.SafetyStatus;
import com.nelayansafe.model.SeaZone;
import com.nelayansafe.model.Vessel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class RouteAndWindowOptimizer {
    private final BmkgMarineApiService apiService;

    public RouteAndWindowOptimizer(BmkgMarineApiService apiService) {
        this.apiService = apiService;
    }

    public record HourlyForecastPoint(MarineForecastSlot slot, SafetyEvaluation evaluation) {}
    public record WindowOptimizationResult(
            DepartureWindow recommendedWindow,
            String recommendationMessage,
            List<DepartureWindow> allWindows,
            List<HourlyForecastPoint> hourlyPoints) {}

    public WindowOptimizationResult analyze24HourWindows(Vessel vessel, SeaZone zone) {
        List<MarineForecastSlot> slots = zone.getDay1Slots();
        List<HourlyForecastPoint> hourlyPoints = new ArrayList<>();
        for (MarineForecastSlot slot : slots) {
            hourlyPoints.add(new HourlyForecastPoint(slot, vessel.evaluateSafety(slot)));
        }

        List<DepartureWindow> safeWindows = groupContiguousWindows(hourlyPoints, SafetyStatus.SAFE);
        List<DepartureWindow> cautionWindows = groupContiguousWindows(hourlyPoints, SafetyStatus.CAUTION);

        Comparator<DepartureWindow> ranker = Comparator
                .comparingInt(DepartureWindow::getDurationHours).reversed()
                .thenComparingInt(DepartureWindow::getAvgRiskScore);

        safeWindows.sort(ranker);
        cautionWindows.sort(ranker);

        if (!safeWindows.isEmpty()) {
            DepartureWindow best = safeWindows.get(0);
            String msg = String.format(
                    "Best Safe Departure Window: %s – %s (%d hr%s) • Avg Wave: %.2f m • Avg Wind: %.1f kt • Risk Score: %d/100",
                    best.getStartTimeWib(), best.getEndTimeWib(), best.getDurationHours(),
                    best.getDurationHours() > 1 ? "s" : "",
                    best.getAvgWaveHeight(), best.getAvgWindSpeed(), best.getAvgRiskScore());
            return new WindowOptimizationResult(best, msg, safeWindows, hourlyPoints);
        }

        if (!cautionWindows.isEmpty()) {
            DepartureWindow bestCaution = cautionWindows.get(0);
            String msg = String.format(
                    "No fully SAFE hours in next 24h. Lowest-risk CAUTION window: %s – %s (%d hr%s, Avg Wave: %.2f m, Risk Score: %d/100).",
                    bestCaution.getStartTimeWib(), bestCaution.getEndTimeWib(), bestCaution.getDurationHours(),
                    bestCaution.getDurationHours() > 1 ? "s" : "",
                    bestCaution.getAvgWaveHeight(), bestCaution.getAvgRiskScore());
            return new WindowOptimizationResult(bestCaution, msg, cautionWindows, hourlyPoints);
        }

        return new WindowOptimizationResult(
                null,
                "All 24 forecast hours exceed official BMKG safety limits for " + vessel.getVesselType() + ". Postpone sailing.",
                List.of(),
                hourlyPoints
        );
    }

    private List<DepartureWindow> groupContiguousWindows(List<HourlyForecastPoint> points, SafetyStatus target) {
        List<DepartureWindow> windows = new ArrayList<>();
        int i = 0;
        while (i < points.size()) {
            if (points.get(i).evaluation().getStatus() == target) {
                int start = i;
                double waveSum = 0;
                double windSum = 0;
                int riskSum = 0;
                while (i < points.size() && points.get(i).evaluation().getStatus() == target) {
                    waveSum += points.get(i).slot().getWaveHeight();
                    windSum += points.get(i).slot().getWindSpeed();
                    riskSum += points.get(i).evaluation().getRiskScore();
                    i++;
                }
                int count = i - start;
                String startTime = points.get(start).slot().getTimeWib();
                String endTime = points.get(i - 1).slot().getTimeWib();
                windows.add(new DepartureWindow(
                        target,
                        startTime,
                        endTime,
                        count,
                        waveSum / count,
                        windSum / count,
                        Math.round((float) riskSum / count)
                ));
            } else {
                i++;
            }
        }
        return windows;
    }

    public RouteEvaluation evaluateMultiZoneRoute(Vessel vessel, List<String> areaCodes,
                                                  boolean forceRefresh, boolean simulateStorm)
            throws BmkgApiUnavailableException {
        if (areaCodes == null || areaCodes.isEmpty()) {
            throw new IllegalArgumentException("At least one sea zone is required to evaluate a route.");
        }

        List<RouteEvaluation.ZoneSegment> segments = new ArrayList<>();
        SafetyStatus worstStatus = null;
        int bottleneckRisk = -1;
        String bottleneckCode = "";
        String bottleneckName = "";

        for (String code : areaCodes) {
            SeaZone zone = apiService.fetchZoneForecast(code, forceRefresh, simulateStorm);
            MarineForecastSlot slot = apiService.pickCurrentSlot(zone);
            if (slot == null) {
                throw new BmkgApiUnavailableException("No hourly forecast data available for zone " + code);
            }
            SafetyEvaluation eval = vessel.evaluateSafety(slot);
            segments.add(new RouteEvaluation.ZoneSegment(
                    zone.getAreaCode(),
                    zone.getAreaName(),
                    zone.getProvince(),
                    zone.getDataSourceMode(),
                    slot,
                    eval
            ));

            boolean isMoreSevere = (worstStatus == null)
                    || (eval.getStatus().ordinal() > worstStatus.ordinal())
                    || (eval.getStatus() == worstStatus && eval.getRiskScore() > bottleneckRisk);

            if (isMoreSevere) {
                worstStatus = eval.getStatus();
                bottleneckRisk = eval.getRiskScore();
                bottleneckCode = zone.getAreaCode();
                bottleneckName = zone.getAreaName();
            }
        }

        String summary = switch (worstStatus) {
            case DO_NOT_SAIL -> String.format(
                    "ROUTE BLOCKED: Bottleneck at %s (%s) with Risk Score %d/100 exceeds BMKG limits for %s.",
                    bottleneckName, bottleneckCode, bottleneckRisk, vessel.getVesselType());
            case CAUTION -> String.format(
                    "ROUTE CAUTION: Highest risk at %s (%s) with Risk Score %d/100. Captain acknowledgment required.",
                    bottleneckName, bottleneckCode, bottleneckRisk);
            case SAFE -> String.format(
                    "ROUTE CLEAR: All %d zone(s) are SAFE for %s (Peak Risk Score: %d/100 at %s).",
                    segments.size(), vessel.getVesselType(), bottleneckRisk, bottleneckName);
        };

        return new RouteEvaluation(worstStatus, bottleneckRisk, bottleneckCode, bottleneckName, summary, segments);
    }
}
