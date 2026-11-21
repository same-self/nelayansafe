const state = {
  provinces: [],
  allZones: [],
  routeCodes: ["P.AE.09", "P.A.05"],
  zoneNamesByCode: {},
  zoneProvinceByCode: {},
  clearanceScope: "activeZone",
  lastRecommendedWindow: "Immediate window checked",
  lastEvalData: null,
  lastRouteData: null,
  routeDirty: true,
  evalRequestSeq: 0,
  routeRequestSeq: 0,
  isSubmittingClearance: false
};

const VESSEL_DEFAULTS = {
  SmallFishingBoat: 6,
  BargeVessel: 12,
  PassengerFerry: 150,
  CargoShip: 40
};

const el = (id) => document.getElementById(id);

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll("\"", "&quot;")
    .replaceAll("'", "&#39;");
}

function formatStatus(status) {
  return String(status || "UNKNOWN").replaceAll("_", " ");
}

function statusPillClass(status) {
  if (status === "SAFE" || status === "CAUTION" || status === "DO_NOT_SAIL") {
    return `status-pill pill-${status}`;
  }
  return "status-pill pill-NEUTRAL";
}

function parseNumberInput(id, fallback) {
  const raw = el(id)?.value ?? "";
  if (String(raw).trim() === "") return fallback;
  const parsed = Number(raw);
  return Number.isNaN(parsed) ? fallback : parsed;
}

document.addEventListener("DOMContentLoaded", async () => {
  bindTabs();
  bindEvents();
  toggleSubclassSpecFields(el("vesselType").value);
  await loadZoneCatalog(false);
  await evaluateActiveZone(false);
  await loadClearanceLogs();
  updateClearanceSummary();
});

function activateTab(tabId) {
  document.querySelectorAll(".tab-btn").forEach((b) => {
    b.classList.toggle("active", b.dataset.tab === tabId);
  });
  document.querySelectorAll(".tab-content").forEach((c) => {
    c.classList.toggle("active", c.id === tabId);
  });

  if (tabId === "routeTab") {
    if (state.routeCodes.length === 0) {
      renderEmptyRouteState();
    } else if (state.routeDirty || !state.lastRouteData) {
      analyzeRoute(false);
    }
  } else if (tabId === "clearanceTab") {
    updateClearanceSummary();
  }
}

function bindTabs() {
  document.querySelectorAll(".tab-btn").forEach((btn) => {
    btn.addEventListener("click", () => activateTab(btn.dataset.tab));
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

function handleProfileEvaluationChange() {
  state.routeDirty = true;
  evaluateActiveZone(false);
  if (el("routeTab").classList.contains("active") && state.routeCodes.length > 0) {
    analyzeRoute(false);
  }
  updateClearanceSummary();
}

function bindEvents() {
  el("vesselType").addEventListener("change", () => {
    const type = el("vesselType").value;
    el("maxCrew").value = VESSEL_DEFAULTS[type] || 6;
    toggleSubclassSpecFields(type);
    handleProfileEvaluationChange();
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
    el(id).addEventListener("change", handleProfileEvaluationChange);
  });

  el("provinceSelect").addEventListener("change", () => {
    populateZoneDropdown(el("provinceSelect").value);
    updateClearanceSummary();
    evaluateActiveZone(false);
  });

  el("zoneSelect").addEventListener("change", () => {
    updateClearanceSummary();
    evaluateActiveZone(false);
  });

  el("evaluateZoneBtn").addEventListener("click", () => evaluateActiveZone(false));

  el("refreshBtn").addEventListener("click", async () => {
    const btn = el("refreshBtn");
    btn.disabled = true;
    btn.textContent = "↻ Refreshing...";
    try {
      await loadZoneCatalog(true);
      await evaluateActiveZone(true);
      state.routeDirty = true;
      if (el("routeTab").classList.contains("active") && state.routeCodes.length > 0) {
        await analyzeRoute(true);
      }
      updateClearanceSummary();
    } finally {
      btn.disabled = false;
      btn.textContent = "↻ Refresh Live BMKG";
    }
  });

  el("simulateStormToggle").addEventListener("change", handleProfileEvaluationChange);

  el("routeZoneFilter").addEventListener("input", () => {
    renderRouteZoneOptions(el("routeZoneFilter").value);
  });

  el("addRouteZoneBtn").addEventListener("click", () => {
    const code = el("routeZoneAdder").value;
    if (!code || state.routeCodes.includes(code)) return;
    state.routeCodes.push(code);
    state.routeDirty = true;
    renderRouteChips();
    updateClearanceSummary();
    analyzeRoute(false);
  });

  el("clearRouteBtn").addEventListener("click", () => {
    state.routeCodes = [];
    state.lastRouteData = null;
    state.routeDirty = true;
    renderRouteChips();
    renderEmptyRouteState();
    updateClearanceSummary();
  });

  el("analyzeRouteBtn").addEventListener("click", () => analyzeRoute(false));

  el("useRouteForClearanceBtn").addEventListener("click", () => {
    state.clearanceScope = "multiZone";
    el("scopeMultiZone").checked = true;
    el("clearanceResultBox").classList.add("hidden");
    activateTab("clearanceTab");
  });

  document.querySelectorAll("input[name='clearanceScope']").forEach((radio) => {
    radio.addEventListener("change", () => {
      state.clearanceScope = radio.value;
      el("clearanceResultBox").classList.add("hidden");
      updateClearanceSummary();
    });
  });

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
    maxCrew: parseNumberInput("maxCrew", 6),
    crewCount: parseNumberInput("crewCount", 1),
    lifeJackets: el("hasLifeJackets").checked,
    radio: el("hasWorkingRadio").checked,
    grossTonnage: parseNumberInput("grossTonnage", 5.0),
    maxSafeCurrentKnots: parseNumberInput("maxSafeCurrentKnots", 2.0),
    maxCargoTon: parseNumberInput("maxCargoTon", 3000),
    currentCargoTon: parseNumberInput("currentCargoTon", 0),
    maxPassengers: parseNumberInput("maxPassengers", 300),
    currentPassengers: parseNumberInput("currentPassengers", 0),
    maxContainerTEU: parseNumberInput("maxContainerTEU", 1200),
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
  state.routeDirty = true;

  await evaluateActiveZone(false);
  if (el("routeTab").classList.contains("active") && state.routeCodes.length > 0) {
    await analyzeRoute(false);
  }
  updateClearanceSummary();
}

async function loadZoneCatalog(refresh) {
  const prevProvince = el("provinceSelect").value;
  const prevZoneCode = el("zoneSelect").value;

  try {
    const res = await fetch(`/api/zones?refresh=${refresh}`);
    const data = await res.json();
    if (!data.success) throw new Error(data.message);

    state.provinces = data.catalog.provinces || [];
    state.allZones = [];
    state.zoneNamesByCode = {};
    state.zoneProvinceByCode = {};

    const provSel = el("provinceSelect");
    provSel.innerHTML = "";

    state.provinces.forEach((p) => {
      const opt = document.createElement("option");
      opt.value = p.province;
      opt.textContent = p.province;
      provSel.appendChild(opt);

      p.areas.forEach((a) => {
        state.zoneNamesByCode[a.areaCode] = a.areaName;
        state.zoneProvinceByCode[a.areaCode] = p.province;
        state.allZones.push({
          province: p.province,
          areaCode: a.areaCode,
          areaName: a.areaName
        });
      });
    });

    if (state.provinces.length > 0) {
      if (prevZoneCode && state.zoneProvinceByCode[prevZoneCode]) {
        const preservedProv = state.zoneProvinceByCode[prevZoneCode];
        provSel.value = preservedProv;
        populateZoneDropdown(preservedProv);
        el("zoneSelect").value = prevZoneCode;
      } else if (prevProvince && state.provinces.some((p) => p.province === prevProvince)) {
        provSel.value = prevProvince;
        populateZoneDropdown(prevProvince);
      } else {
        const defaultProv = state.provinces.find((p) => p.areas.some((a) => a.areaCode === "P.AE.09"))
          || state.provinces[0];
        provSel.value = defaultProv.province;
        populateZoneDropdown(defaultProv.province);
        if (state.zoneNamesByCode["P.AE.09"]) {
          el("zoneSelect").value = "P.AE.09";
        }
      }
    }

    renderRouteZoneOptions(el("routeZoneFilter")?.value || "");

    state.routeCodes = state.routeCodes.filter((c) => state.zoneNamesByCode[c]);
    if (state.routeCodes.length === 0 && el("zoneSelect").value) {
      state.routeCodes = [el("zoneSelect").value];
    }
    renderRouteChips();
    updateConnectionBadge(data.catalog.sourceMode);
    updateClearanceSummary();
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

function renderRouteZoneOptions(filterText) {
  const routeAdder = el("routeZoneAdder");
  const prevValue = routeAdder.value;
  const query = (filterText || "").trim().toLowerCase();

  const matches = query
    ? state.allZones.filter((z) =>
        z.province.toLowerCase().includes(query)
        || z.areaName.toLowerCase().includes(query)
        || z.areaCode.toLowerCase().includes(query))
    : state.allZones;

  routeAdder.innerHTML = "";
  if (matches.length === 0) {
    const emptyOpt = document.createElement("option");
    emptyOpt.value = "";
    emptyOpt.textContent = "No matching sea zones found";
    routeAdder.appendChild(emptyOpt);
    el("addRouteZoneBtn").disabled = true;
    return;
  }

  el("addRouteZoneBtn").disabled = false;
  matches.forEach((z) => {
    const opt = document.createElement("option");
    opt.value = z.areaCode;
    opt.textContent = `${z.province}: ${z.areaName} (${z.areaCode})`;
    routeAdder.appendChild(opt);
  });

  if (prevValue && matches.some((z) => z.areaCode === prevValue)) {
    routeAdder.value = prevValue;
  }
}

async function evaluateActiveZone(refresh) {
  const reqId = ++state.evalRequestSeq;
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

  const evalBtn = el("evaluateZoneBtn");
  evalBtn.disabled = true;
  evalBtn.textContent = "Evaluating...";
  el("zoneVerdictBanner").classList.add("is-loading");
  el("metricsGrid").classList.add("is-loading");

  try {
    const res = await fetch(`/api/evaluate?${qs.toString()}`);
    const data = await res.json();
    if (reqId !== state.evalRequestSeq) return;
    if (!data.success) throw new Error(data.message || "Evaluation failed.");

    state.lastEvalData = data;
    updateConnectionBadge(data.zone.dataSourceMode);
    renderZoneVerdict(data.zone, data.currentSlot, data.currentEvaluation);
    render24HourChart(data.windowAnalysis);
    updateClearanceSummary();
  } catch (err) {
    if (reqId !== state.evalRequestSeq) return;
    const banner = el("zoneVerdictBanner");
    banner.className = "verdict-banner verdict-DO_NOT_SAIL";
    el("verdictBadge").className = "status-pill pill-DO_NOT_SAIL";
    el("verdictBadge").textContent = "ERROR";
    el("verdictTitle").textContent = `Unable to load forecast: ${err.message}`;
    el("verdictMeta").textContent = "Check BMKG API availability or vessel input values.";
    el("verdictScore").textContent = "--";
  } finally {
    if (reqId === state.evalRequestSeq) {
      evalBtn.disabled = false;
      evalBtn.textContent = "Evaluate Sea Safety";
      el("zoneVerdictBanner").classList.remove("is-loading");
      el("metricsGrid").classList.remove("is-loading");
    }
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

  const badge = el("verdictBadge");
  badge.className = statusPillClass(evalObj.status);
  badge.textContent = formatStatus(evalObj.status);

  el("verdictTitle").textContent = `${zone.areaName} (${zone.areaCode}): ${evalObj.summary}`;
  el("verdictMeta").textContent =
    `Station: ${zone.stationName} • Timezone: ${zone.timezoneLabel || "Local"} • Slot: ${slot.timeLocal} (${slot.timeUtc}) • Limits: Wind < ${evalObj.maxSafeWindKnots} kt, Wave < ${evalObj.maxSafeWaveMeters} m`;
  el("verdictScore").textContent = evalObj.riskScore;

  const list = el("verdictReasons");
  list.innerHTML = "";
  (evalObj.reasons || []).forEach((r) => {
    const li = document.createElement("li");
    li.textContent = r;
    list.appendChild(li);
  });

  el("metricsGrid").innerHTML = `
    <div class="metric-card"><span>Wave Height</span><strong>${Number(slot.waveHeight).toFixed(2)} m</strong><small>Category: ${escapeHtml(slot.waveCat)}</small></div>
    <div class="metric-card"><span>Wind Speed</span><strong>${Number(slot.windSpeed).toFixed(1)} kt</strong><small>From: ${escapeHtml(slot.windFrom)}</small></div>
    <div class="metric-card"><span>Wind Gust</span><strong>${Number(slot.windGust).toFixed(1)} kt</strong><small>Peak sudden gust</small></div>
    <div class="metric-card"><span>Surface Current</span><strong>${Number(slot.currentSpeed).toFixed(2)} kt</strong><small>Heading: ${escapeHtml(slot.currentTo)}</small></div>
    <div class="metric-card"><span>Weather Condition</span><strong>${escapeHtml(slot.weather)}</strong><small>BMKG Hourly Slot</small></div>
    <div class="metric-card"><span>Air Temp / Humidity</span><strong>${escapeHtml(slot.tempAvg)}°C / ${escapeHtml(slot.rhAvg)}%</strong><small>Updated: ${escapeHtml(zone.fetchedAtLocal)}</small></div>
  `;
}

function render24HourChart(analysis) {
  const recBox = el("windowRecommendationBox");
  recBox.className = "callout " + (analysis.recommendedWindow
    ? (analysis.recommendedWindow.windowStatus === "SAFE" ? "callout-safe" : "callout-info")
    : "callout-danger");
  recBox.textContent = analysis.recommendationMessage;
  state.lastRecommendedWindow = analysis.recommendedWindow
    ? `${analysis.recommendedWindow.startTimeLocal} to ${analysis.recommendedWindow.endTimeLocal}`
    : "Postpone Departure";

  const chart = el("hourlyChart");
  chart.innerHTML = "";
  (analysis.hourlyPoints || []).forEach((pt) => {
    const col = document.createElement("div");
    col.className = "hour-col";
    const heightPct = Math.max(12, pt.evaluation.riskScore);
    const shortHour = pt.slot.timeLocal.replace(/\s+(WIB|WITA|WIT)$/, "");
    col.innerHTML = `
      <span class="hour-score">${escapeHtml(pt.evaluation.riskScore)}</span>
      <div class="hour-bar bar-${escapeHtml(pt.evaluation.status)}" style="height: ${heightPct}%"></div>
      <span class="hour-label">${escapeHtml(shortHour)}</span>
    `;
    col.addEventListener("click", () => {
      el("hourlyInspector").innerHTML =
        `<strong>${escapeHtml(pt.slot.timeLocal)} (${escapeHtml(pt.slot.timeUtc)}):</strong> ` +
        `<span class="${statusPillClass(pt.evaluation.status)}">${escapeHtml(formatStatus(pt.evaluation.status))}</span> ` +
        `<span>Risk <strong>${escapeHtml(pt.evaluation.riskScore)}/100</strong> | ` +
        `Wave <strong>${Number(pt.slot.waveHeight).toFixed(2)} m</strong> (${escapeHtml(pt.slot.waveCat)}), ` +
        `Wind <strong>${Number(pt.slot.windSpeed).toFixed(1)} kt</strong> (Gust ${Number(pt.slot.windGust).toFixed(1)} kt), ` +
        `Current <strong>${Number(pt.slot.currentSpeed).toFixed(2)} kt</strong>, ` +
        `Weather: ${escapeHtml(pt.slot.weather)}</span>`;
    });
    chart.appendChild(col);
  });
}

function renderRouteChips() {
  const wrap = el("routeZoneChips");
  wrap.innerHTML = "";

  const count = state.routeCodes.length;
  el("routeZoneCount").textContent = `${count} zone${count === 1 ? "" : "s"} in route (recommended: 2 to 5)`;
  el("clearRouteBtn").disabled = count === 0;
  el("analyzeRouteBtn").disabled = count === 0;
  el("useRouteForClearanceBtn").disabled = count === 0;

  if (count === 0) {
    const note = document.createElement("span");
    note.className = "muted";
    note.textContent = "No sea zones added yet. Pick a zone above and click + Add Zone.";
    wrap.appendChild(note);
    return;
  }

  state.routeCodes.forEach((code, idx) => {
    const chip = document.createElement("span");
    chip.className = "route-chip";

    const label = document.createElement("span");
    label.textContent = `${idx + 1}. ${state.zoneNamesByCode[code] || code} (${code})`;

    const removeBtn = document.createElement("button");
    removeBtn.type = "button";
    removeBtn.title = "Remove zone from route";
    removeBtn.textContent = "×";
    removeBtn.addEventListener("click", () => {
      state.routeCodes.splice(idx, 1);
      state.routeDirty = true;
      renderRouteChips();
      updateClearanceSummary();
      if (state.routeCodes.length > 0) {
        analyzeRoute(false);
      } else {
        state.lastRouteData = null;
        renderEmptyRouteState();
      }
    });

    chip.appendChild(label);
    chip.appendChild(removeBtn);
    wrap.appendChild(chip);
  });
}

function renderEmptyRouteState() {
  el("routeSummaryBox").classList.add("hidden");
  el("routeSegmentsGrid").innerHTML =
    `<div class="empty-state-box">Add at least one sea zone above to run a multi-zone route bottleneck analysis.</div>`;
}

async function analyzeRoute(forceRefresh = false) {
  if (state.routeCodes.length === 0) {
    renderEmptyRouteState();
    return;
  }

  const reqId = ++state.routeRequestSeq;
  const v = getVesselParams();
  const box = el("routeSummaryBox");
  const grid = el("routeSegmentsGrid");
  const btn = el("analyzeRouteBtn");

  btn.disabled = true;
  btn.textContent = "Analyzing...";
  box.classList.add("is-loading");
  grid.classList.add("is-loading");

  try {
    const res = await fetch("/api/route", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        ...v,
        areaCodes: state.routeCodes,
        refresh: forceRefresh
      })
    });
    const data = await res.json();
    if (reqId !== state.routeRequestSeq) return;
    if (!data.success) throw new Error(data.message || "Route evaluation failed.");

    const re = data.routeEvaluation;
    state.lastRouteData = re;
    state.routeDirty = false;

    box.className = `verdict-banner verdict-${re.overallStatus}`;
    box.classList.remove("hidden");

    const pill = el("routeStatusPill");
    pill.className = statusPillClass(re.overallStatus);
    pill.textContent = formatStatus(re.overallStatus);

    el("routeSummaryText").textContent = re.summary;
    el("routeBottleneckMeta").textContent = `Bottleneck Zone: ${re.bottleneckAreaName} (${re.bottleneckAreaCode})`;
    el("routePeakScore").textContent = re.highestRiskScore;

    grid.innerHTML = "";
    (re.segments || []).forEach((seg, i) => {
      const isBottleneck = seg.areaCode === re.bottleneckAreaCode;
      const card = document.createElement("div");
      card.className = isBottleneck
        ? `segment-card segment-bottleneck segment-bottleneck-${seg.evaluation.status}`
        : "segment-card";

      card.innerHTML = `
        <div class="segment-card-header">
          <span class="${statusPillClass(seg.evaluation.status)}">Segment ${i + 1}: ${escapeHtml(formatStatus(seg.evaluation.status))}</span>
          ${isBottleneck ? `<span class="bottleneck-badge">BOTTLENECK</span>` : ""}
        </div>
        <h4 class="segment-title">${escapeHtml(seg.areaName)} (${escapeHtml(seg.areaCode)})</h4>
        <p class="muted segment-meta">${escapeHtml(seg.province)} • Slot: ${escapeHtml(seg.currentSlot.timeLocal)} • Risk Score: <strong>${escapeHtml(seg.evaluation.riskScore)}/100</strong></p>
        <p class="segment-metrics">
          Wave: <strong>${Number(seg.currentSlot.waveHeight).toFixed(2)} m</strong> |
          Wind: <strong>${Number(seg.currentSlot.windSpeed).toFixed(1)} kt</strong> (Gust ${Number(seg.currentSlot.windGust).toFixed(1)} kt) |
          Current: <strong>${Number(seg.currentSlot.currentSpeed).toFixed(2)} kt</strong>
        </p>
      `;
      grid.appendChild(card);
    });
  } catch (err) {
    if (reqId !== state.routeRequestSeq) return;
    box.className = "verdict-banner verdict-DO_NOT_SAIL";
    box.classList.remove("hidden");
    el("routeStatusPill").className = "status-pill pill-DO_NOT_SAIL";
    el("routeStatusPill").textContent = "ERROR";
    el("routeSummaryText").textContent = `Route evaluation failed: ${err.message}`;
    el("routeBottleneckMeta").textContent = "";
    el("routePeakScore").textContent = "--";
  } finally {
    if (reqId === state.routeRequestSeq) {
      btn.disabled = state.routeCodes.length === 0;
      btn.textContent = "Analyze Route";
      box.classList.remove("is-loading");
      grid.classList.remove("is-loading");
    }
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

function getClearanceTargetCodes() {
  if (state.clearanceScope === "multiZone") {
    return [...state.routeCodes];
  }
  const activeCode = el("zoneSelect").value || "P.AE.09";
  return [activeCode];
}

function getClearanceRecommendedWindow(targetCodes) {
  if (targetCodes.length === 0) {
    return "No sea zone selected";
  }
  if (state.clearanceScope === "activeZone") {
    return state.lastRecommendedWindow;
  }
  const activeCode = el("zoneSelect").value || "P.AE.09";
  if (targetCodes[0] === activeCode) {
    return `${state.lastRecommendedWindow} (Origin zone 24h window)`;
  }
  return `Live multi-zone check across ${targetCodes.length} sea zone${targetCodes.length === 1 ? "" : "s"}`;
}

function updateClearanceSummary() {
  const v = getVesselParams();
  const activeCode = el("zoneSelect").value || "P.AE.09";
  const activeName = state.zoneNamesByCode[activeCode] || activeCode;

  el("scopeActiveLabel").textContent = `Current Sea Zone (Tab 1): ${activeName} (${activeCode})`;
  el("scopeMultiLabel").textContent = `Multi-Zone Route (Tab 2): ${state.routeCodes.length} zone${state.routeCodes.length === 1 ? "" : "s"}`;

  const targetCodes = getClearanceTargetCodes();
  el("clrVesselSummary").textContent =
    `${v.vesselName} (${v.vesselType}) | Crew: ${v.crewCount}/${v.maxCrew} | ${formatSubclassDetail(v)} | Life Jackets: ${v.lifeJackets ? "OK" : "MISSING"} | Radio: ${v.radio ? "OK" : "MISSING"}`;

  el("clrRouteSummary").textContent = targetCodes.length > 0
    ? targetCodes.map((c) => `${state.zoneNamesByCode[c] || c} (${c})`).join(" → ")
    : "No zones in Multi-Zone Route. Add zones in Tab 2 or switch to Current Sea Zone (Tab 1).";

  el("clrWindowSummary").textContent = getClearanceRecommendedWindow(targetCodes);
}

async function submitClearance() {
  if (state.isSubmittingClearance) return;

  const box = el("clearanceResultBox");
  const areaCodes = getClearanceTargetCodes();
  if (areaCodes.length === 0) {
    box.classList.remove("hidden");
    box.className = "callout callout-danger";
    box.innerHTML = `<strong>⛔ CLEARANCE NOT SUBMITTED:</strong> Add at least one sea zone in Multi-Zone Route (Tab 2) or switch Clearance Scope to Current Sea Zone (Tab 1).`;
    return;
  }

  const v = getVesselParams();
  const btn = el("requestClearanceBtn");
  state.isSubmittingClearance = true;
  btn.disabled = true;
  btn.textContent = "Checking Guardrails...";

  try {
    const res = await fetch("/api/clearance", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        ...v,
        areaCodes,
        recommendedWindow: getClearanceRecommendedWindow(areaCodes),
        cautionAcknowledged: el("cautionAckCheckbox").checked
      })
    });
    const data = await res.json();
    box.classList.remove("hidden");

    if (!data.success) {
      box.className = "callout callout-danger";
      box.innerHTML = `<strong>⛔ CLEARANCE BLOCKED [${escapeHtml(data.errorType)}]:</strong> ${escapeHtml(data.message)}`;
      return;
    }

    const c = data.clearance;
    box.className = "callout callout-safe";
    box.innerHTML =
      `<strong>✅ CLEARANCE ISSUED (${escapeHtml(c.clearanceId)}):</strong> ` +
      `${escapeHtml(c.vesselName)} cleared for ${escapeHtml((c.routeZoneCodes || []).join(" → "))} ` +
      `(${escapeHtml(formatStatus(c.safetyStatus))}, Risk ${escapeHtml(c.riskScore)}/100) at ${escapeHtml(c.issuedTimestampLocal)}.`;
    await loadClearanceLogs();
  } catch (err) {
    box.classList.remove("hidden");
    box.className = "callout callout-danger";
    box.innerHTML = `<strong>⛔ REQUEST FAILED:</strong> Unable to reach the clearance service (${escapeHtml(err.message)}).`;
  } finally {
    state.isSubmittingClearance = false;
    btn.disabled = false;
    btn.textContent = "Request Sailing Clearance";
  }
}

async function loadClearanceLogs() {
  const tbody = el("clearanceLogBody");
  try {
    const res = await fetch("/api/clearances");
    const data = await res.json();
    const clearances = data.clearances || [];
    tbody.innerHTML = "";

    if (clearances.length === 0) {
      tbody.innerHTML = `
        <tr class="empty-state-row">
          <td colspan="7">No sailing clearances issued yet. Request a clearance above to save a persistent log entry.</td>
        </tr>
      `;
      return;
    }

    clearances.forEach((c) => {
      const tr = document.createElement("tr");
      tr.innerHTML = `
        <td><code>${escapeHtml(c.clearanceId)}</code></td>
        <td>${escapeHtml(c.issuedTimestampLocal)}</td>
        <td>${escapeHtml(c.vesselName)} <small class="muted">(${escapeHtml(c.vesselType)})</small></td>
        <td>${escapeHtml(c.crewCount)}/${escapeHtml(c.maxCrewCapacity)}</td>
        <td>${escapeHtml((c.routeZoneCodes || []).join(" → "))}</td>
        <td><strong>${escapeHtml(c.riskScore)}</strong>/100</td>
        <td><span class="${statusPillClass(c.safetyStatus)}">${escapeHtml(formatStatus(c.safetyStatus))}</span></td>
      `;
      tbody.appendChild(tr);
    });
  } catch (err) {
    tbody.innerHTML = `
      <tr class="empty-state-row">
        <td colspan="7">Unable to load clearance logs: ${escapeHtml(err.message)}</td>
      </tr>
    `;
  }
}