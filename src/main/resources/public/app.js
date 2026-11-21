const state = {
  provinces: [],
  routeCodes: ["P.AE.09", "P.A.05"],
  zoneNamesByCode: {},
  lastRecommendedWindow: "Immediate window checked",
  lastEvalData: null
};

const VESSEL_DEFAULTS = {
  SmallFishingBoat: 6,
  BargeVessel: 12,
  PassengerFerry: 150,
  CargoShip: 40
};

const el = (id) => document.getElementById(id);

document.addEventListener("DOMContentLoaded", async () => {
  bindTabs();
  bindEvents();
  toggleSubclassSpecFields(el("vesselType").value);
  await loadZoneCatalog(false);
  await evaluateActiveZone(false);
  await loadClearanceLogs();
  updateClearanceSummary();
});

function bindTabs() {
  document.querySelectorAll(".tab-btn").forEach((btn) => {
    btn.addEventListener("click", () => {
      document.querySelectorAll(".tab-btn").forEach((b) => b.classList.remove("active"));
      document.querySelectorAll(".tab-content").forEach((c) => c.classList.remove("active"));
      btn.classList.add("active");
      el(btn.dataset.tab).classList.add("active");
      if (btn.dataset.tab === "clearanceTab") updateClearanceSummary();
    });
  });
}

function toggleSubclassSpecFields(vesselType) {
  const map = {
    SmallFishingBoat: "specsSmallFishingBoat",
    BargeVessel: "specsBargeVessel",
    PassengerFerry: "specsPassengerFerry",
    CargoShip: "specsCargoShip"
  };
  Object.values(map).forEach((id) => el(id)?.classList.add("hidden"));
  const activeId = map[vesselType];
  if (activeId) el(activeId)?.classList.remove("hidden");
}

function bindEvents() {
  el("vesselType").addEventListener("change", () => {
    const type = el("vesselType").value;
    el("maxCrew").value = VESSEL_DEFAULTS[type] || 6;
    toggleSubclassSpecFields(type);
    evaluateActiveZone(false);
    if (el("routeTab").classList.contains("active") || !el("routeSummaryBox").classList.contains("hidden")) {
      analyzeRoute();
    }
    updateClearanceSummary();
  });

  ["vesselName", "vesselId", "maxCrew", "crewCount", "hasLifeJackets", "hasWorkingRadio"].forEach((id) => {
    el(id).addEventListener("change", updateClearanceSummary);
  });

  [
    "grossTonnage",
    "maxSafeCurrentKnots",
    "maxCargoTon",
    "currentCargoTon",
    "maxPassengers",
    "currentPassengers",
    "maxContainerTEU"
  ].forEach((id) => {
    el(id).addEventListener("change", () => {
      evaluateActiveZone(false);
      if (el("routeTab").classList.contains("active") || !el("routeSummaryBox").classList.contains("hidden")) {
        analyzeRoute();
      }
      updateClearanceSummary();
    });
  });

  el("provinceSelect").addEventListener("change", () => {
    populateZoneDropdown(el("provinceSelect").value);
    evaluateActiveZone(false);
  });

  el("zoneSelect").addEventListener("change", () => evaluateActiveZone(false));
  el("evaluateZoneBtn").addEventListener("click", () => evaluateActiveZone(false));
  el("refreshBtn").addEventListener("click", async () => {
    await loadZoneCatalog(true);
    await evaluateActiveZone(true);
  });

  el("simulateStormToggle").addEventListener("change", () => {
    evaluateActiveZone(false);
    if (el("routeTab").classList.contains("active") || !el("routeSummaryBox").classList.contains("hidden")) {
      analyzeRoute();
    }
  });

  el("addRouteZoneBtn").addEventListener("click", () => {
    const code = el("routeZoneAdder").value;
    if (code && !state.routeCodes.includes(code)) {
      state.routeCodes.push(code);
      renderRouteChips();
      updateClearanceSummary();
    }
  });

  el("analyzeRouteBtn").addEventListener("click", analyzeRoute);
  el("requestClearanceBtn").addEventListener("click", submitClearance);

  document.querySelectorAll(".btn-chip").forEach((btn) => {
    btn.addEventListener("click", () => applyScenarioPreset(btn.dataset.preset));
  });
}

function getVesselParams() {
  return {
    vesselType: el("vesselType").value,
    vesselId: el("vesselId").value.trim() || "VSL-001",
    vesselName: el("vesselName").value.trim() || "KM Nelayan Sejahtera",
    maxCrew: Number(el("maxCrew").value) || 6,
    crewCount: Number(el("crewCount").value) || 1,
    lifeJackets: el("hasLifeJackets").checked,
    radio: el("hasWorkingRadio").checked,
    grossTonnage: Number(el("grossTonnage").value) || 5.0,
    maxSafeCurrentKnots: Number(el("maxSafeCurrentKnots").value) || 2.0,
    maxCargoTon: Number(el("maxCargoTon").value) || 3000,
    currentCargoTon: Number(el("currentCargoTon").value) || 0,
    maxPassengers: Number(el("maxPassengers").value) || 300,
    currentPassengers: Number(el("currentPassengers").value) || 0,
    maxContainerTEU: Number(el("maxContainerTEU").value) || 1200,
    simulate: el("simulateStormToggle").checked
  };
}

async function applyScenarioPreset(preset) {
  el("grossTonnage").value = 5.0;
  el("maxSafeCurrentKnots").value = 2.0;

  if (preset === "safe") {
    el("vesselType").value = "SmallFishingBoat";
    el("vesselName").value = "KM Nelayan Sejahtera";
    el("maxCrew").value = 6;
    el("crewCount").value = 4;
    el("hasLifeJackets").checked = true;
    el("hasWorkingRadio").checked = true;
    el("simulateStormToggle").checked = false;
  } else if (preset === "overcapacity") {
    el("vesselType").value = "SmallFishingBoat";
    el("vesselName").value = "KM Harapan Jaya";
    el("maxCrew").value = 6;
    el("crewCount").value = 11;
    el("hasLifeJackets").checked = true;
    el("hasWorkingRadio").checked = true;
    el("simulateStormToggle").checked = false;
  } else if (preset === "missingGear") {
    el("vesselType").value = "SmallFishingBoat";
    el("vesselName").value = "KM Maritim (Safety Case)";
    el("maxCrew").value = 6;
    el("crewCount").value = 5;
    el("hasLifeJackets").checked = false;
    el("hasWorkingRadio").checked = true;
    el("simulateStormToggle").checked = false;
  } else if (preset === "storm") {
    el("vesselType").value = "SmallFishingBoat";
    el("vesselName").value = "KM Nelayan Storm Test";
    el("maxCrew").value = 6;
    el("crewCount").value = 5;
    el("hasLifeJackets").checked = true;
    el("hasWorkingRadio").checked = true;
    el("simulateStormToggle").checked = true;
  }

  toggleSubclassSpecFields(el("vesselType").value);
  el("cautionAckCheckbox").checked = false;
  el("clearanceResultBox").classList.add("hidden");

  await evaluateActiveZone(false);
  if (el("routeTab").classList.contains("active") || !el("routeSummaryBox").classList.contains("hidden")) {
    await analyzeRoute();
  }
  updateClearanceSummary();
}

async function loadZoneCatalog(refresh) {
  try {
    const res = await fetch(`/api/zones?refresh=${refresh}`);
    const data = await res.json();
    if (!data.success) throw new Error(data.message);

    state.provinces = data.catalog.provinces;
    state.zoneNamesByCode = {};

    const provSel = el("provinceSelect");
    const routeAdder = el("routeZoneAdder");
    provSel.innerHTML = "";
    routeAdder.innerHTML = "";

    state.provinces.forEach((p) => {
      const opt = document.createElement("option");
      opt.value = p.province;
      opt.textContent = p.province;
      provSel.appendChild(opt);

      p.areas.forEach((a) => {
        state.zoneNamesByCode[a.areaCode] = a.areaName;
        const rOpt = document.createElement("option");
        rOpt.value = a.areaCode;
        rOpt.textContent = `${p.province}: ${a.areaName} (${a.areaCode})`;
        routeAdder.appendChild(rOpt);
      });
    });

    if (state.provinces.length > 0) {
      const defaultProv = state.provinces.find((p) => p.areas.some((a) => a.areaCode === "P.AE.09"))
        || state.provinces[0];
      provSel.value = defaultProv.province;
      populateZoneDropdown(defaultProv.province);
      if (state.zoneNamesByCode["P.AE.09"]) {
        el("zoneSelect").value = "P.AE.09";
      }
    }

    state.routeCodes = state.routeCodes.filter((c) => state.zoneNamesByCode[c]);
    if (state.routeCodes.length === 0 && el("zoneSelect").value) {
      state.routeCodes = [el("zoneSelect").value];
    }
    renderRouteChips();
    updateConnectionBadge(data.catalog.sourceMode);
  } catch (err) {
    updateConnectionBadge("OFFLINE_CACHE");
  }
}

function populateZoneDropdown(provinceName) {
  const group = state.provinces.find((p) => p.province === provinceName);
  const zoneSel = el("zoneSelect");
  zoneSel.innerHTML = "";
  if (!group) return;
  group.areas.forEach((a) => {
    const opt = document.createElement("option");
    opt.value = a.areaCode;
    opt.textContent = `${a.areaName} (${a.areaCode})`;
    zoneSel.appendChild(opt);
  });
}

async function evaluateActiveZone(refresh) {
  const areaCode = el("zoneSelect").value || "P.AE.09";
  const v = getVesselParams();
  const qs = new URLSearchParams({
    areaCode,
    vesselType: v.vesselType,
    vesselId: v.vesselId,
    vesselName: v.vesselName,
    maxCrew: String(v.maxCrew),
    lifeJackets: String(v.lifeJackets),
    radio: String(v.radio),
    grossTonnage: String(v.grossTonnage),
    maxSafeCurrentKnots: String(v.maxSafeCurrentKnots),
    maxCargoTon: String(v.maxCargoTon),
    currentCargoTon: String(v.currentCargoTon),
    maxPassengers: String(v.maxPassengers),
    currentPassengers: String(v.currentPassengers),
    maxContainerTEU: String(v.maxContainerTEU),
    refresh: String(refresh),
    simulate: String(v.simulate)
  });

  try {
    const res = await fetch(`/api/evaluate?${qs.toString()}`);
    const data = await res.json();
    if (!data.success) throw new Error(data.message);

    state.lastEvalData = data;
    updateConnectionBadge(data.zone.dataSourceMode);
    renderZoneVerdict(data.zone, data.currentSlot, data.currentEvaluation);
    render24HourChart(data.windowAnalysis);
    updateClearanceSummary();
  } catch (err) {
    const banner = el("zoneVerdictBanner");
    if (banner) {
      banner.className = "verdict-banner verdict-DO_NOT_SAIL";
    }
    el("verdictBadge").textContent = "ERROR";
    el("verdictTitle").textContent = `Unable to load forecast: ${err.message}`;
    el("verdictMeta").textContent = "Check BMKG API availability or vessel input values.";
    el("verdictScore").textContent = "--";
  }
}

function updateConnectionBadge(mode) {
  const badge = el("connectionBadge");
  badge.className = "badge";
  if (mode === "SIMULATED_STORM") {
    badge.classList.add("badge-sim");
    badge.textContent = "HIGH-WAVE STORM SIMULATION";
  } else if (mode === "LIVE_BMKG") {
    badge.classList.add("badge-live");
    badge.textContent = "● LIVE BMKG API";
  } else {
    badge.classList.add("badge-cache");
    badge.textContent = "● OFFLINE CACHE MODE";
  }
}

function renderZoneVerdict(zone, slot, evalObj) {
  const banner = el("zoneVerdictBanner");
  banner.className = `verdict-banner verdict-${evalObj.status}`;
  el("verdictBadge").textContent = evalObj.status.replaceAll("_", " ");
  el("verdictTitle").textContent = `${zone.areaName} (${zone.areaCode}): ${evalObj.summary}`;
  el("verdictMeta").textContent =
    `Station: ${zone.stationName} • Slot: ${slot.timeWib} (${slot.timeUtc}) • Limits: Wind < ${evalObj.maxSafeWindKnots} kt, Wave < ${evalObj.maxSafeWaveMeters} m`;
  el("verdictScore").textContent = evalObj.riskScore;

  const list = el("verdictReasons");
  list.innerHTML = "";
  evalObj.reasons.forEach((r) => {
    const li = document.createElement("li");
    li.textContent = r;
    list.appendChild(li);
  });

  el("metricsGrid").innerHTML = `
    <div class="metric-card"><span>Wave Height</span><strong>${slot.waveHeight.toFixed(2)} m</strong><small>Category: ${slot.waveCat}</small></div>
    <div class="metric-card"><span>Wind Speed</span><strong>${slot.windSpeed.toFixed(1)} kt</strong><small>From: ${slot.windFrom}</small></div>
    <div class="metric-card"><span>Wind Gust</span><strong>${slot.windGust.toFixed(1)} kt</strong><small>Peak sudden gust</small></div>
    <div class="metric-card"><span>Surface Current</span><strong>${slot.currentSpeed.toFixed(2)} kt</strong><small>Heading: ${slot.currentTo}</small></div>
    <div class="metric-card"><span>Weather Condition</span><strong>${slot.weather}</strong><small>BMKG Hourly Slot</small></div>
    <div class="metric-card"><span>Air Temp / Humidity</span><strong>${slot.tempAvg}°C / ${slot.rhAvg}%</strong><small>Updated: ${zone.fetchedAtWib}</small></div>
  `;
}

function render24HourChart(analysis) {
  const recBox = el("windowRecommendationBox");
  recBox.className = "callout " + (analysis.recommendedWindow
    ? (analysis.recommendedWindow.windowStatus === "SAFE" ? "callout-safe" : "callout-info")
    : "callout-danger");
  recBox.textContent = analysis.recommendationMessage;
  state.lastRecommendedWindow = analysis.recommendedWindow
    ? `${analysis.recommendedWindow.startTimeWib} to ${analysis.recommendedWindow.endTimeWib}`
    : "Postpone Departure";

  const chart = el("hourlyChart");
  chart.innerHTML = "";
  analysis.hourlyPoints.forEach((pt) => {
    const col = document.createElement("div");
    col.className = "hour-col";
    const heightPct = Math.max(12, pt.evaluation.riskScore);
    const shortHour = pt.slot.timeWib.replace(/\s+(WIB|WITA|WIT)$/, "");
    col.innerHTML = `
      <span class="hour-score">${pt.evaluation.riskScore}</span>
      <div class="hour-bar bar-${pt.evaluation.status}" style="height: ${heightPct}%"></div>
      <span class="hour-label">${shortHour}</span>
    `;
    col.addEventListener("click", () => {
      el("hourlyInspector").innerHTML =
        `<strong>${pt.slot.timeWib} (${pt.slot.timeUtc}):</strong> Status <strong>${pt.evaluation.status}</strong> (Risk ${pt.evaluation.riskScore}/100) | ` +
        `Wave <strong>${pt.slot.waveHeight} m</strong> (${pt.slot.waveCat}), Wind <strong>${pt.slot.windSpeed} kt</strong> (Gust ${pt.slot.windGust} kt), Current <strong>${pt.slot.currentSpeed} kt</strong>, Weather: ${pt.slot.weather}`;
    });
    chart.appendChild(col);
  });
}

function renderRouteChips() {
  const wrap = el("routeZoneChips");
  wrap.innerHTML = "";
  state.routeCodes.forEach((code, idx) => {
    const chip = document.createElement("span");
    chip.className = "route-chip";
    chip.innerHTML = `${idx + 1}. ${state.zoneNamesByCode[code] || code} (${code}) <button title="Remove">×</button>`;
    chip.querySelector("button").addEventListener("click", () => {
      state.routeCodes.splice(idx, 1);
      renderRouteChips();
      updateClearanceSummary();
    });
    wrap.appendChild(chip);
  });
}

async function analyzeRoute() {
  if (state.routeCodes.length === 0) return;
  const v = getVesselParams();
  const box = el("routeSummaryBox");
  try {
    const res = await fetch("/api/route", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        ...v,
        areaCodes: state.routeCodes
      })
    });
    const data = await res.json();
    if (!data.success) throw new Error(data.message);

    const re = data.routeEvaluation;
    box.className = `verdict-banner verdict-${re.overallStatus}`;
    box.classList.remove("hidden");
    el("routeStatusPill").textContent = re.overallStatus.replaceAll("_", " ");
    el("routeSummaryText").textContent = re.summary;
    el("routeBottleneckMeta").textContent = `Bottleneck Zone: ${re.bottleneckAreaName} (${re.bottleneckAreaCode})`;
    el("routePeakScore").textContent = re.highestRiskScore;

    const grid = el("routeSegmentsGrid");
    grid.innerHTML = "";
    re.segments.forEach((seg, i) => {
      const card = document.createElement("div");
      card.className = "segment-card";
      card.innerHTML = `
        <span class="status-pill" style="margin-bottom:0.4rem;">Segment ${i + 1}: ${seg.evaluation.status}</span>
        <h4 style="margin:0.25rem 0;">${seg.areaName} (${seg.areaCode})</h4>
        <p class="muted" style="margin:0 0 0.5rem 0;">${seg.province} • Risk Score: <strong>${seg.evaluation.riskScore}/100</strong></p>
        <p style="margin:0; font-size:0.85rem;">Wave: <strong>${seg.currentSlot.waveHeight} m</strong> | Wind: <strong>${seg.currentSlot.windSpeed} kt</strong> | Current: <strong>${seg.currentSlot.currentSpeed} kt</strong></p>
      `;
      grid.appendChild(card);
    });
  } catch (err) {
    box.className = "verdict-banner verdict-DO_NOT_SAIL";
    box.classList.remove("hidden");
    el("routeStatusPill").textContent = "ERROR";
    el("routeSummaryText").textContent = `Route evaluation failed: ${err.message}`;
    el("routeBottleneckMeta").textContent = "";
    el("routePeakScore").textContent = "--";
  }
}

function formatSubclassDetail(v) {
  if (v.vesselType === "SmallFishingBoat") {
    return `${v.grossTonnage} GT, Max Current: ${v.maxSafeCurrentKnots} kt`;
  }
  if (v.vesselType === "BargeVessel") {
    return `Cargo: ${v.currentCargoTon}/${v.maxCargoTon} tons`;
  }
  if (v.vesselType === "PassengerFerry") {
    return `Passengers: ${v.currentPassengers}/${v.maxPassengers}`;
  }
  return `Containers: ${v.maxContainerTEU} TEU`;
}

function updateClearanceSummary() {
  const v = getVesselParams();
  const activeZones = state.routeCodes.length > 0 ? state.routeCodes : [el("zoneSelect").value || "P.AE.09"];
  el("clrVesselSummary").textContent =
    `${v.vesselName} (${v.vesselType}) | Crew: ${v.crewCount}/${v.maxCrew} | ${formatSubclassDetail(v)} | Life Jackets: ${v.lifeJackets ? "OK" : "MISSING"} | Radio: ${v.radio ? "OK" : "MISSING"}`;
  el("clrRouteSummary").textContent = activeZones.map((c) => `${state.zoneNamesByCode[c] || c} (${c})`).join(" → ");
  el("clrWindowSummary").textContent = state.lastRecommendedWindow;
}

async function submitClearance() {
  const v = getVesselParams();
  const areaCodes = state.routeCodes.length > 0 ? state.routeCodes : [el("zoneSelect").value || "P.AE.09"];
  const res = await fetch("/api/clearance", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      ...v,
      areaCodes,
      recommendedWindow: state.lastRecommendedWindow,
      cautionAcknowledged: el("cautionAckCheckbox").checked
    })
  });
  const data = await res.json();
  const box = el("clearanceResultBox");
  box.classList.remove("hidden");

  if (!data.success) {
    box.className = "callout callout-danger";
    box.innerHTML = `<strong>⛔ CLEARANCE BLOCKED [${data.errorType}]:</strong> ${data.message}`;
    return;
  }

  const c = data.clearance;
  box.className = "callout callout-safe";
  box.innerHTML = `<strong>✅ CLEARANCE ISSUED (${c.clearanceId}):</strong> ${c.vesselName} cleared for ${c.routeZoneCodes.join(" → ")} (${c.safetyStatus}, Risk ${c.riskScore}/100) at ${c.issuedTimestampWib}.`;
  await loadClearanceLogs();
}

async function loadClearanceLogs() {
  const res = await fetch("/api/clearances");
  const data = await res.json();
  const tbody = el("clearanceLogBody");
  tbody.innerHTML = "";
  (data.clearances || []).forEach((c) => {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td><code>${c.clearanceId}</code></td>
      <td>${c.issuedTimestampWib}</td>
      <td>${c.vesselName} <small class="muted">(${c.vesselType})</small></td>
      <td>${c.crewCount}/${c.maxCrewCapacity}</td>
      <td>${c.routeZoneCodes.join(" → ")}</td>
      <td><strong>${c.riskScore}</strong>/100</td>
      <td><strong>${c.safetyStatus}</strong></td>
    `;
    tbody.appendChild(tr);
  });
}
