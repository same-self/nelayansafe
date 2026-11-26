const state = {
  provinces: [],
  allZones: [],
  routeCodes: ["P.AE.09", "P.A.05"],
  zoneNamesByCode: {},
  zoneProvinceByCode: {},
  clearanceScope: "activeZone",
  activePreset: null,
  profileCollapsed: false,
  lastRecommendedWindow: "Immediate window checked",
  lastEvalData: null,
  lastRouteData: null,
  routeDirty: true,
  evalRequestSeq: 0,
  routeRequestSeq: 0,
  evalInFlightPromise: null,
  isSubmittingClearance: false
};

const VESSEL_DEFAULTS = {
  SmallFishingBoat: 6,
  BargeVessel: 12,
  PassengerFerry: 150,
  CargoShip: 40
};

const METRIC_ICONS = {
  wave: `<svg class="metric-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M2 12c2-2.5 4-2.5 6 0s4 2.5 6 0 4-2.5 6 0"/><path d="M2 17c2-2.5 4-2.5 6 0s4 2.5 6 0 4-2.5 6 0"/></svg>`,
  wind: `<svg class="metric-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M17.7 7.7a2.5 2.5 0 1 1 1.8 4.3H2"/><path d="M9.6 4.6A2 2 0 1 1 11 8H2"/><path d="M12.6 19.4A2 2 0 1 0 14 16H2"/></svg>`,
  gust: `<svg class="metric-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M13 2L3 14h9l-1 8 10-12h-9l1-8z"/></svg>`,
  current: `<svg class="metric-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><polygon points="16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88 16.24 7.76"/></svg>`,
  weather: `<svg class="metric-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M18 10h-1.26A8 8 0 1 0 9 20h9a5 5 0 0 0 0-10z"/></svg>`,
  temp: `<svg class="metric-icon" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M14 14.76V3.5a2.5 2.5 0 0 0-5 0v11.26a4.5 4.5 0 1 0 5 0z"/></svg>`
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

function hideClearanceResult() {
  const box = el("clearanceResultBox");
  if (box) box.classList.add("hidden");
}

function inferTimezoneFromLocalStamp(stamp) {
  const s = String(stamp || "").trim();
  if (s.endsWith("WIB")) return "WIB (UTC+7)";
  if (s.endsWith("WITA")) return "WITA (UTC+8)";
  if (s.endsWith("WIT")) return "WIT (UTC+9)";
  return "Local Time";
}

function renderRiskGaugeHtml(score, status, scoreId, label) {
  const numeric = typeof score === "number" && !Number.isNaN(score)
    ? Math.max(0, Math.min(100, score))
    : null;
  const radius = 34;
  const circumference = 2 * Math.PI * radius;
  const dashOffset = numeric === null ? circumference : circumference * (1 - numeric / 100);
  const strokeClass = (status === "SAFE" || status === "CAUTION" || status === "DO_NOT_SAIL")
    ? `gauge-arc-${status}`
    : "gauge-arc-NEUTRAL";

  return `
    <div class="risk-gauge">
      <svg class="risk-gauge-svg" viewBox="0 0 84 84" aria-hidden="true">
        <circle class="gauge-track" cx="42" cy="42" r="${radius}"></circle>
        <circle class="gauge-arc ${strokeClass}" cx="42" cy="42" r="${radius}"
          stroke-dasharray="${circumference.toFixed(2)}"
          stroke-dashoffset="${dashOffset.toFixed(2)}"></circle>
      </svg>
      <div class="risk-gauge-center">
        <strong id="${escapeHtml(scoreId)}">${numeric === null ? "--" : escapeHtml(numeric)}</strong>
        <small>/100</small>
      </div>
    </div>
    <span class="risk-label">${escapeHtml(label)}</span>
  `;
}

document.addEventListener("DOMContentLoaded", async () => {
  bindTabs();
  bindEvents();
  toggleSubclassSpecFields(el("vesselType").value);
  updateStormSimulationBanner();
  updateReadinessAndCompactSummary();
  await loadZoneCatalog(false);
  await evaluateActiveZone(false);
  await loadClearanceLogs();
  updateClearanceSummary();
});

function activateTab(tabId, focusTab = false) {
  const tabButtons = Array.from(document.querySelectorAll(".tab-btn"));
  tabButtons.forEach((b) => {
    const isSelected = b.dataset.tab === tabId;
    b.classList.toggle("active", isSelected);
    b.setAttribute("aria-selected", String(isSelected));
    b.setAttribute("tabindex", isSelected ? "0" : "-1");
    if (isSelected && focusTab) b.focus();
  });
  document.querySelectorAll(".tab-content").forEach((c) => {
    const isActive = c.id === tabId;
    c.classList.toggle("active", isActive);
    c.hidden = !isActive;
  });

  if (tabId === "routeTab") {
    if (state.routeCodes.length === 0) {
      renderEmptyRouteState();
    } else if (state.routeDirty || !state.lastRouteData) {
      analyzeRoute(false);
    }
  } else if (tabId === "clearanceTab") {
    if (state.clearanceScope === "multiZone" && state.routeCodes.length > 0 && (state.routeDirty || !state.lastRouteData)) {
      analyzeRoute(false);
    }
    updateClearanceSummary();
  }
}

function bindTabs() {
  const tabButtons = Array.from(document.querySelectorAll(".tab-btn"));
  tabButtons.forEach((btn, idx) => {
    btn.addEventListener("click", () => activateTab(btn.dataset.tab, false));
    btn.addEventListener("keydown", (e) => {
      let targetIdx = -1;
      if (e.key === "ArrowRight" || e.key === "ArrowDown") {
        targetIdx = (idx + 1) % tabButtons.length;
      } else if (e.key === "ArrowLeft" || e.key === "ArrowUp") {
        targetIdx = (idx - 1 + tabButtons.length) % tabButtons.length;
      } else if (e.key === "Home") {
        targetIdx = 0;
      } else if (e.key === "End") {
        targetIdx = tabButtons.length - 1;
      }
      if (targetIdx !== -1) {
        e.preventDefault();
        activateTab(tabButtons[targetIdx].dataset.tab, true);
      }
    });
  });
}

function updateRulesTabHighlight(vesselType) {
  const badge = el("rulesActiveVesselBadge");
  if (badge) badge.textContent = `Active: ${vesselType}`;

  document.querySelectorAll("tr[data-vessel-row]").forEach((row) => {
    const isActive = row.dataset.vesselRow === vesselType;
    row.classList.toggle("row-active-vessel", isActive);
    const pill = row.querySelector(".active-class-pill");
    if (pill) pill.classList.toggle("hidden", !isActive);
  });

  document.querySelectorAll(".oop-child-box[data-oop-class]").forEach((box) => {
    const isActive = box.dataset.oopClass === vesselType;
    box.classList.toggle("child-active-vessel", isActive);
    const pill = box.querySelector(".active-class-pill");
    if (pill) pill.classList.toggle("hidden", !isActive);
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
  updateRulesTabHighlight(vesselType);
}

function setActivePreset(presetName) {
  state.activePreset = presetName;
  document.querySelectorAll(".btn-chip").forEach((btn) => {
    const isActive = Boolean(presetName) && btn.dataset.preset === presetName;
    btn.classList.toggle("active-preset", isActive);
    btn.setAttribute("aria-pressed", String(isActive));
  });
}

function updateStormSimulationBanner() {
  const isSim = el("simulateStormToggle").checked;
  el("stormSimBanner").classList.toggle("hidden", !isSim);
}

function getProfileValidationIssues(v) {
  const crewInvalid = v.crewCount <= 0;
  const maxCrewInvalid = v.maxCrew <= 0;
  const crewOverloaded = !crewInvalid && !maxCrewInvalid && v.crewCount > v.maxCrew;
  const cargoOverloaded = v.vesselType === "BargeVessel" && v.maxCargoTon > 0 && v.currentCargoTon > v.maxCargoTon;
  const paxOverloaded = v.vesselType === "PassengerFerry" && v.maxPassengers > 0 && v.currentPassengers > v.maxPassengers;

  let subclassInvalidMsg = "";
  if (v.vesselType === "SmallFishingBoat") {
    if (v.grossTonnage <= 0) subclassInvalidMsg = "Gross Tonnage (GT) must be positive (triggers IllegalArgumentException).";
    else if (v.maxSafeCurrentKnots <= 0) subclassInvalidMsg = "Max Safe Current (kt) must be positive (triggers IllegalArgumentException).";
  } else if (v.vesselType === "BargeVessel") {
    if (v.maxCargoTon <= 0) subclassInvalidMsg = "Max Cargo (Tons) must be positive (triggers IllegalArgumentException).";
    else if (v.currentCargoTon < 0) subclassInvalidMsg = "Current Cargo (Tons) cannot be negative (triggers IllegalArgumentException).";
  } else if (v.vesselType === "PassengerFerry") {
    if (v.maxPassengers <= 0) subclassInvalidMsg = "Max Passengers must be positive (triggers IllegalArgumentException).";
    else if (v.currentPassengers < 0) subclassInvalidMsg = "Passengers Onboard cannot be negative (triggers IllegalArgumentException).";
  } else if (v.vesselType === "CargoShip") {
    if (v.maxContainerTEU <= 0) subclassInvalidMsg = "Max Containers (TEU) must be positive (triggers IllegalArgumentException).";
  }

  return {
    crewInvalid,
    maxCrewInvalid,
    crewOverloaded,
    cargoOverloaded,
    paxOverloaded,
    subclassInvalidMsg,
    grossTonnageInvalid: v.vesselType === "SmallFishingBoat" && v.grossTonnage <= 0,
    currentLimitInvalid: v.vesselType === "SmallFishingBoat" && v.maxSafeCurrentKnots <= 0,
    maxCargoInvalid: v.vesselType === "BargeVessel" && v.maxCargoTon <= 0,
    currentCargoInvalid: v.vesselType === "BargeVessel" && v.currentCargoTon < 0,
    maxPaxInvalid: v.vesselType === "PassengerFerry" && v.maxPassengers <= 0,
    currentPaxInvalid: v.vesselType === "PassengerFerry" && v.currentPassengers < 0,
    teuInvalid: v.vesselType === "CargoShip" && v.maxContainerTEU <= 0
  };
}

function updateReadinessAndCompactSummary() {
  const v = getVesselParams();
  const issues = getProfileValidationIssues(v);
  const warnings = [];

  if (issues.maxCrewInvalid) {
    warnings.push("Max Crew must be at least 1 (triggers IllegalArgumentException)");
  }
  if (issues.crewInvalid) {
    warnings.push("Crew Onboard must be at least 1 (triggers IllegalArgumentException)");
  } else if (issues.crewOverloaded) {
    warnings.push(`Crew Overcapacity: ${v.crewCount}/${v.maxCrew} (VesselOvercapacityException)`);
  }
  if (issues.subclassInvalidMsg) {
    warnings.push(issues.subclassInvalidMsg);
  }
  if (issues.cargoOverloaded) {
    warnings.push(`Cargo Overcapacity: ${v.currentCargoTon}/${v.maxCargoTon} t (VesselOvercapacityException)`);
  }
  if (issues.paxOverloaded) {
    warnings.push(`Passenger Overcapacity: ${v.currentPassengers}/${v.maxPassengers} (VesselOvercapacityException)`);
  }
  if (!v.lifeJackets && !v.radio) {
    warnings.push("Missing Life Jackets and Radio (MissingSafetyGearException)");
  } else if (!v.lifeJackets) {
    warnings.push("Missing Life Jackets (MissingSafetyGearException)");
  } else if (!v.radio) {
    warnings.push("Missing Marine Radio (MissingSafetyGearException)");
  }

  // Non-blocking field warning highlights
  el("crewCount").classList.toggle("input-warning", issues.crewInvalid || issues.crewOverloaded);
  el("maxCrew").classList.toggle("input-warning", issues.maxCrewInvalid);
  el("grossTonnage").classList.toggle("input-warning", issues.grossTonnageInvalid);
  el("maxSafeCurrentKnots").classList.toggle("input-warning", issues.currentLimitInvalid);
  el("maxCargoTon").classList.toggle("input-warning", issues.maxCargoInvalid);
  el("currentCargoTon").classList.toggle("input-warning", issues.cargoOverloaded || issues.currentCargoInvalid);
  el("maxPassengers").classList.toggle("input-warning", issues.maxPaxInvalid);
  el("currentPassengers").classList.toggle("input-warning", issues.paxOverloaded || issues.currentPaxInvalid);
  el("maxContainerTEU").classList.toggle("input-warning", issues.teuInvalid);
  el("lifeJacketLabel").classList.toggle("label-warning", !v.lifeJackets);
  el("radioLabel").classList.toggle("label-warning", !v.radio);

  const strip = el("readinessStrip");
  if (warnings.length === 0) {
    strip.innerHTML = `<span class="readiness-pill readiness-ok">Capacity and Gear Ready</span>`;
  } else {
    strip.innerHTML = warnings
      .map((w) => `<span class="readiness-pill readiness-warn">⚠ ${escapeHtml(w)}</span>`)
      .join("");
  }

  el("vesselCompactSummary").innerHTML = `
    <span><strong>${escapeHtml(v.vesselName)}</strong> (${escapeHtml(v.vesselType)}, ${escapeHtml(v.vesselId)})</span>
    <span>Crew: <strong>${escapeHtml(v.crewCount)}/${escapeHtml(v.maxCrew)}</strong></span>
    <span>${escapeHtml(formatSubclassDetail(v))}</span>
    <span>Life Jackets: <strong>${v.lifeJackets ? "OK" : "MISSING"}</strong></span>
    <span>Radio: <strong>${v.radio ? "OK" : "MISSING"}</strong></span>
  `;
}

function handleProfileEvaluationChange() {
  setActivePreset(null);
  state.routeDirty = true;
  hideClearanceResult();
  updateStormSimulationBanner();
  updateReadinessAndCompactSummary();
  evaluateActiveZone(false);
  const needsRouteNow = el("routeTab").classList.contains("active")
    || (el("clearanceTab").classList.contains("active") && state.clearanceScope === "multiZone");
  if (needsRouteNow && state.routeCodes.length > 0) {
    analyzeRoute(false);
  }
  updateClearanceSummary();
}

function bindEvents() {
  el("toggleProfileBtn").addEventListener("click", () => {
    state.profileCollapsed = !state.profileCollapsed;
    el("vesselCollapsibleBody").classList.toggle("hidden", state.profileCollapsed);
    el("vesselCompactSummary").classList.toggle("hidden", !state.profileCollapsed);
    el("toggleProfileBtn").textContent = state.profileCollapsed ? "Edit Profile" : "Hide Inputs";
    el("toggleProfileBtn").setAttribute("aria-expanded", String(!state.profileCollapsed));
  });

  el("exitStormSimBtn").addEventListener("click", () => {
    el("simulateStormToggle").checked = false;
    handleProfileEvaluationChange();
  });

  el("vesselType").addEventListener("change", () => {
    const type = el("vesselType").value;
    el("maxCrew").value = VESSEL_DEFAULTS[type] || 6;
    toggleSubclassSpecFields(type);
    handleProfileEvaluationChange();
  });

  ["vesselName", "vesselId", "crewCount"].forEach((id) => {
    el(id).addEventListener("input", () => {
      setActivePreset(null);
      hideClearanceResult();
      updateReadinessAndCompactSummary();
      updateClearanceSummary();
    });
    el(id).addEventListener("change", () => {
      hideClearanceResult();
      updateReadinessAndCompactSummary();
      updateClearanceSummary();
    });
  });

  el("maxCrew").addEventListener("input", () => {
    setActivePreset(null);
    hideClearanceResult();
    updateReadinessAndCompactSummary();
    updateClearanceSummary();
  });
  el("maxCrew").addEventListener("change", handleProfileEvaluationChange);

  ["hasLifeJackets", "hasWorkingRadio"].forEach((id) => {
    el(id).addEventListener("change", () => {
      setActivePreset(null);
      hideClearanceResult();
      updateReadinessAndCompactSummary();
      updateClearanceSummary();
    });
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
    el(id).addEventListener("input", () => {
      setActivePreset(null);
      hideClearanceResult();
      updateReadinessAndCompactSummary();
      updateClearanceSummary();
    });
    el(id).addEventListener("change", handleProfileEvaluationChange);
  });

  el("provinceSelect").addEventListener("change", () => {
    populateZoneDropdown(el("provinceSelect").value);
    hideClearanceResult();
    updateClearanceSummary();
    evaluateActiveZone(false);
  });

  el("zoneSelect").addEventListener("change", () => {
    hideClearanceResult();
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
      const needsRouteRefresh = el("routeTab").classList.contains("active")
        || (el("clearanceTab").classList.contains("active") && state.clearanceScope === "multiZone");
      if (needsRouteRefresh && state.routeCodes.length > 0) {
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
    hideClearanceResult();
    renderRouteChips();
    updateClearanceSummary();
    analyzeRoute(false);
  });

  el("clearRouteBtn").addEventListener("click", () => {
    state.routeCodes = [];
    state.lastRouteData = null;
    state.routeDirty = true;
    hideClearanceResult();
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
      hideClearanceResult();
      if (state.clearanceScope === "multiZone" && state.routeCodes.length > 0 && (state.routeDirty || !state.lastRouteData)) {
        analyzeRoute(false);
      }
      updateClearanceSummary();
    });
  });

  el("cautionAckCheckbox").addEventListener("change", () => {
    hideClearanceResult();
    updateClearanceSummary();
  });

  el("requestClearanceBtn").addEventListener("click", submitClearance);

  document.querySelectorAll(".btn-chip").forEach((btn) => {
    btn.addEventListener("click", () => applyScenarioPreset(btn.dataset.preset));
    btn.addEventListener("keydown", (e) => {
      if (e.key === "Escape" && state.activePreset) {
        setActivePreset(null);
      }
    });
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
  el("maxCargoTon").value = 3000;
  el("currentCargoTon").value = 2000;
  el("maxPassengers").value = 300;
  el("currentPassengers").value = 180;
  el("maxContainerTEU").value = 1200;

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

  setActivePreset(preset);
  toggleSubclassSpecFields(el("vesselType").value);
  updateStormSimulationBanner();
  updateReadinessAndCompactSummary();
  el("cautionAckCheckbox").checked = false;
  el("clearanceResultBox").classList.add("hidden");
  state.routeDirty = true;

  await evaluateActiveZone(false);
  const needsRouteNow = el("routeTab").classList.contains("active")
    || (el("clearanceTab").classList.contains("active") && state.clearanceScope === "multiZone");
  if (needsRouteNow && state.routeCodes.length > 0) {
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

  const runPromise = (async () => {
    try {
      const res = await fetch(`/api/evaluate?${qs.toString()}`);
      const data = await res.json();
      if (reqId !== state.evalRequestSeq) return;
      if (!data.success) {
        const apiErr = new Error(data.message || "Evaluation failed.");
        apiErr.errorType = data.errorType || "";
        throw apiErr;
      }

      state.lastEvalData = data;
      updateConnectionBadge(data.zone.dataSourceMode);
      renderZoneVerdict(data.zone, data.currentSlot, data.currentEvaluation);
      render24HourChart(data.windowAnalysis, data.zone, data.currentSlot);
      updateClearanceSummary();
    } catch (err) {
      if (reqId !== state.evalRequestSeq) return;
      state.lastEvalData = null;
      state.lastRecommendedWindow = "Forecast unavailable";
      if (err.errorType === "BmkgApiUnavailableException" && !el("simulateStormToggle").checked) {
        updateConnectionBadge("OFFLINE_CACHE");
      }
      const banner = el("zoneVerdictBanner");
      banner.className = "verdict-banner verdict-DO_NOT_SAIL";
      el("verdictBadge").className = "status-pill pill-DO_NOT_SAIL";
      el("verdictBadge").textContent = "ERROR";
      el("verdictTitle").textContent = `Unable to load forecast: ${err.message}`;
      el("verdictMeta").innerHTML = `<span class="meta-chip">Check BMKG API availability or vessel input values.</span>`;
      el("verdictGaugeWrap").innerHTML = renderRiskGaugeHtml(null, "DO_NOT_SAIL", "verdictScore", "Risk Score");
      el("verdictReasons").innerHTML = "";
      el("metricsGrid").innerHTML = "";
      const tzBadge = el("chartTimezoneBadge");
      if (tzBadge) tzBadge.textContent = "Local Time";
      const recBox = el("windowRecommendationBox");
      recBox.className = "callout callout-danger";
      recBox.textContent = err.errorType === "BmkgApiUnavailableException"
        ? "BMKG forecast is unavailable offline for this zone."
        : "24-hour window unavailable due to invalid profile parameters or forecast error.";
      el("hourlyChart").innerHTML = "";
      renderHourlyInspector(null, false, false);
      updateClearanceSummary();
    } finally {
      if (reqId === state.evalRequestSeq) {
        evalBtn.disabled = false;
        evalBtn.textContent = "Evaluate Sea Safety";
        el("zoneVerdictBanner").classList.remove("is-loading");
        el("metricsGrid").classList.remove("is-loading");
        if (state.evalInFlightPromise === runPromise) {
          state.evalInFlightPromise = null;
        }
      }
    }
  })();

  state.evalInFlightPromise = runPromise;
  return runPromise;
}

function updateConnectionBadge(mode) {
  const badge = el("connectionBadge");
  badge.className = "badge";
  if (mode === "SIMULATED_STORM") {
    badge.classList.add("badge-sim");
    badge.textContent = "HIGH-WAVE STORM SIMULATION";
    badge.title = "High-Wave Storm Simulation active (live BMKG feed is paused for demo testing).";
  } else if (mode === "LIVE_BMKG") {
    badge.classList.add("badge-live");
    badge.textContent = "● LIVE BMKG API";
    badge.title = "Connected to live BMKG Marine Meteorology API (38 Indonesian provinces).";
  } else {
    badge.classList.add("badge-cache");
    badge.textContent = "● OFFLINE CACHE MODE";
    badge.title = "Offline fallback active: 3 bundled regional zones covering WIB (Aceh), WITA (Sulawesi Selatan), and WIT (Papua). Run locally for live 38-province BMKG access.";
  }
}

function metricStateTagText(level) {
  if (level === "DO_NOT_SAIL") return "LIMIT EXCEEDED";
  if (level === "CAUTION") return "CAUTION";
  return "OK";
}

function renderZoneVerdict(zone, slot, evalObj) {
  const banner = el("zoneVerdictBanner");
  banner.className = `verdict-banner verdict-${evalObj.status}`;

  const badge = el("verdictBadge");
  badge.className = statusPillClass(evalObj.status);
  badge.textContent = formatStatus(evalObj.status);

  el("verdictTitle").textContent = `${zone.areaName} (${zone.areaCode}): ${evalObj.summary}`;

  el("verdictMeta").innerHTML = `
    <span class="meta-chip"><strong>Station:</strong> ${escapeHtml(zone.stationName)}</span>
    <span class="meta-chip"><strong>Timezone:</strong> ${escapeHtml(zone.timezoneLabel || "Local")}</span>
    <span class="meta-chip"><strong>Slot:</strong> ${escapeHtml(slot.timeLocal)} (${escapeHtml(slot.timeUtc)})</span>
    <span class="meta-chip"><strong>BMKG Limits:</strong> Wind &lt; ${escapeHtml(evalObj.maxSafeWindKnots)} kt, Wave &lt; ${escapeHtml(evalObj.maxSafeWaveMeters)} m</span>
  `;

  el("verdictGaugeWrap").innerHTML = renderRiskGaugeHtml(
    Number(evalObj.riskScore),
    evalObj.status,
    "verdictScore",
    "Risk Score"
  );

  const list = el("verdictReasons");
  list.innerHTML = "";
  (evalObj.reasons || []).forEach((r) => {
    const li = document.createElement("li");
    li.textContent = r;
    list.appendChild(li);
  });

  renderMetricCards(zone, slot, evalObj);
}

function renderMetricCards(zone, slot, evalObj) {
  const v = getVesselParams();
  const wave = Number(slot.waveHeight);
  const wind = Number(slot.windSpeed);
  const gust = Number(slot.windGust);
  const current = Number(slot.currentSpeed);
  const maxWave = Number(evalObj.maxSafeWaveMeters) || 1.25;
  const maxWind = Number(evalObj.maxSafeWindKnots) || 15.0;
  const boatCurrentLimit = v.maxSafeCurrentKnots > 0 ? v.maxSafeCurrentKnots : 2.0;
  const currentLimit = evalObj.vesselType === "SmallFishingBoat" ? Math.min(2.0, boatCurrentLimit) : 2.0;

  const wavePct = Math.round((wave / maxWave) * 100);
  const waveLevel = wave >= maxWave ? "DO_NOT_SAIL" : (wave >= maxWave * 0.75 ? "CAUTION" : "SAFE");

  const windPct = Math.round((wind / maxWind) * 100);
  const windLevel = wind >= maxWind ? "DO_NOT_SAIL" : (wind >= maxWind * 0.75 ? "CAUTION" : "SAFE");

  const gustPct = Math.round((gust / maxWind) * 100);
  const gustLevel = gust >= maxWind ? "CAUTION" : "SAFE";

  const currentPct = Math.round((current / currentLimit) * 100);
  const currentLevel = current >= currentLimit ? "CAUTION" : "SAFE";

  el("metricsGrid").innerHTML = `
    <div class="metric-card metric-${waveLevel}">
      <div class="metric-head">
        <span class="metric-title">${METRIC_ICONS.wave} Wave Height</span>
        <span class="metric-state-tag metric-tag-${waveLevel}">${metricStateTagText(waveLevel)}</span>
      </div>
      <strong class="metric-value">${wave.toFixed(2)} m</strong>
      <div class="metric-bar-wrap" title="${wavePct}% of ${maxWave.toFixed(2)} m vessel limit">
        <div class="metric-bar-fill fill-${waveLevel}" style="width: ${Math.min(100, wavePct)}%"></div>
        <div class="threshold-mark" title="75% Caution threshold"></div>
      </div>
      <small class="metric-sub">${wavePct}% of ${maxWave.toFixed(2)} m limit • Category: ${escapeHtml(slot.waveCat)}</small>
    </div>

    <div class="metric-card metric-${windLevel}">
      <div class="metric-head">
        <span class="metric-title">${METRIC_ICONS.wind} Wind Speed</span>
        <span class="metric-state-tag metric-tag-${windLevel}">${metricStateTagText(windLevel)}</span>
      </div>
      <strong class="metric-value">${wind.toFixed(1)} kt</strong>
      <div class="metric-bar-wrap" title="${windPct}% of ${maxWind.toFixed(1)} kt vessel limit">
        <div class="metric-bar-fill fill-${windLevel}" style="width: ${Math.min(100, windPct)}%"></div>
        <div class="threshold-mark" title="75% Caution threshold"></div>
      </div>
      <small class="metric-sub">${windPct}% of ${maxWind.toFixed(1)} kt limit • From: ${escapeHtml(slot.windFrom)}</small>
    </div>

    <div class="metric-card metric-${gustLevel}">
      <div class="metric-head">
        <span class="metric-title">${METRIC_ICONS.gust} Wind Gust</span>
        <span class="metric-state-tag metric-tag-${gustLevel}">${metricStateTagText(gustLevel)}</span>
      </div>
      <strong class="metric-value">${gust.toFixed(1)} kt</strong>
      <div class="metric-bar-wrap" title="${gustPct}% of ${maxWind.toFixed(1)} kt gust caution limit">
        <div class="metric-bar-fill fill-${gustLevel}" style="width: ${Math.min(100, gustPct)}%"></div>
      </div>
      <small class="metric-sub">Caution at &ge; ${maxWind.toFixed(1)} kt • Peak sudden gust</small>
    </div>

    <div class="metric-card metric-${currentLevel}">
      <div class="metric-head">
        <span class="metric-title">${METRIC_ICONS.current} Surface Current</span>
        <span class="metric-state-tag metric-tag-${currentLevel}">${metricStateTagText(currentLevel)}</span>
      </div>
      <strong class="metric-value">${current.toFixed(2)} kt</strong>
      <div class="metric-bar-wrap" title="${currentPct}% of ${currentLimit.toFixed(2)} kt current caution limit">
        <div class="metric-bar-fill fill-${currentLevel}" style="width: ${Math.min(100, currentPct)}%"></div>
      </div>
      <small class="metric-sub">Caution at &ge; ${currentLimit.toFixed(2)} kt • Heading: ${escapeHtml(slot.currentTo)}</small>
    </div>

    <div class="metric-card metric-INFO">
      <div class="metric-head">
        <span class="metric-title">${METRIC_ICONS.weather} Weather Condition</span>
      </div>
      <strong class="metric-value">${escapeHtml(slot.weather)}</strong>
      <small class="metric-sub">BMKG Hourly Forecast Slot</small>
    </div>

    <div class="metric-card metric-INFO">
      <div class="metric-head">
        <span class="metric-title">${METRIC_ICONS.temp} Air Temp and Humidity</span>
      </div>
      <strong class="metric-value">${escapeHtml(slot.tempAvg)}°C / ${escapeHtml(slot.rhAvg)}%</strong>
      <small class="metric-sub">Updated: ${escapeHtml(zone.fetchedAtLocal)}</small>
    </div>
  `;
}

function findRecommendedWindowRange(points, recWindow) {
  if (!recWindow || !Array.isArray(points) || points.length === 0) return null;
  const len = Number(recWindow.durationHours) || 0;
  if (len <= 0) return null;

  for (let i = 0; i <= points.length - len; i++) {
    const startPt = points[i];
    const endPt = points[i + len - 1];
    if (startPt.slot.timeLocal === recWindow.startTimeLocal
        && endPt.slot.timeLocal === recWindow.endTimeLocal) {
      let allMatch = true;
      for (let j = i; j < i + len; j++) {
        if (points[j].evaluation.status !== recWindow.windowStatus) {
          allMatch = false;
          break;
        }
      }
      if (allMatch) return { start: i, end: i + len - 1 };
    }
  }
  return null;
}

function renderHourlyInspector(pt, isNow, inWindow) {
  const inspector = el("hourlyInspector");
  if (!pt) {
    inspector.className = "hourly-inspector muted";
    inspector.textContent = "Select any hour column above to inspect exact hourly wind, wave, gust, and current metrics.";
    return;
  }

  inspector.className = "hourly-inspector";
  inspector.innerHTML =
    `<strong>${escapeHtml(pt.slot.timeLocal)} (${escapeHtml(pt.slot.timeUtc)}):</strong>` +
    `<span class="${statusPillClass(pt.evaluation.status)}">${escapeHtml(formatStatus(pt.evaluation.status))}</span>` +
    (isNow ? `<span class="inspector-tag">ACTIVE HOUR</span>` : "") +
    (inWindow ? `<span class="inspector-tag">RECOMMENDED WINDOW</span>` : "") +
    `<span>Risk <strong>${escapeHtml(pt.evaluation.riskScore)}/100</strong> | ` +
    `Wave <strong>${Number(pt.slot.waveHeight).toFixed(2)} m</strong> (${escapeHtml(pt.slot.waveCat)}), ` +
    `Wind <strong>${Number(pt.slot.windSpeed).toFixed(1)} kt</strong> (Gust ${Number(pt.slot.windGust).toFixed(1)} kt), ` +
    `Current <strong>${Number(pt.slot.currentSpeed).toFixed(2)} kt</strong>, ` +
    `Weather: ${escapeHtml(pt.slot.weather)}</span>`;
}

function render24HourChart(analysis, zone, currentSlot) {
  const tzBadge = el("chartTimezoneBadge");
  if (tzBadge) {
    tzBadge.textContent = zone?.timezoneLabel ? `Times in ${zone.timezoneLabel}` : "Local Time";
  }

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

  const points = analysis.hourlyPoints || [];
  if (points.length === 0) {
    renderHourlyInspector(null, false, false);
    return;
  }

  const recRange = findRecommendedWindowRange(points, analysis.recommendedWindow);
  let defaultSelectIdx = 0;
  const colButtons = [];

  function selectHourColumn(targetIdx, moveFocus = false) {
    if (!colButtons[targetIdx]) return;
    colButtons.forEach((btn, i) => {
      const isSelected = i === targetIdx;
      btn.classList.toggle("hour-col-selected", isSelected);
      btn.setAttribute("aria-pressed", String(isSelected));
      btn.setAttribute("tabindex", isSelected ? "0" : "-1");
    });
    if (moveFocus) colButtons[targetIdx].focus();
    const pt = points[targetIdx];
    const isNow = Boolean(currentSlot && pt.slot.timeUtc === currentSlot.timeUtc);
    const inWindow = Boolean(recRange && targetIdx >= recRange.start && targetIdx <= recRange.end);
    renderHourlyInspector(pt, isNow, inWindow);
  }

  points.forEach((pt, idx) => {
    const isNow = Boolean(currentSlot && pt.slot.timeUtc === currentSlot.timeUtc);
    const inWindow = Boolean(recRange && idx >= recRange.start && idx <= recRange.end);
    if (isNow) defaultSelectIdx = idx;

    const col = document.createElement("button");
    col.type = "button";
    col.className = "hour-col"
      + (inWindow ? " hour-col-window" : "")
      + (isNow ? " hour-col-now" : "");

    const scoreVal = Math.max(0, Math.min(100, Number(pt.evaluation.riskScore) || 0));
    const barPct = Math.max(6, scoreVal);
    const shortHour = pt.slot.timeLocal.replace(/\s+(WIB|WITA|WIT)$/, "");

    col.setAttribute("tabindex", idx === defaultSelectIdx ? "0" : "-1");
    col.setAttribute("aria-pressed", "false");
    col.setAttribute(
      "aria-label",
      `${pt.slot.timeLocal}, ${formatStatus(pt.evaluation.status)}, Risk ${scoreVal} out of 100${isNow ? ", Active Hour" : ""}${inWindow ? ", Recommended Window" : ""}`
    );

    col.innerHTML = `
      <span class="hour-now-slot">${isNow ? `<span class="hour-now-pill">NOW</span>` : ""}</span>
      <span class="hour-score">${escapeHtml(scoreVal)}</span>
      <div class="hour-bar-track">
        <div class="hour-bar bar-${escapeHtml(pt.evaluation.status)}" style="height: ${barPct}%"></div>
      </div>
      <span class="hour-label">${escapeHtml(shortHour)}</span>
    `;

    col.addEventListener("click", () => selectHourColumn(idx, false));
    col.addEventListener("keydown", (e) => {
      let nextIdx = -1;
      if (e.key === "ArrowRight" || e.key === "ArrowDown") {
        nextIdx = (idx + 1) % points.length;
      } else if (e.key === "ArrowLeft" || e.key === "ArrowUp") {
        nextIdx = (idx - 1 + points.length) % points.length;
      } else if (e.key === "Home") {
        nextIdx = 0;
      } else if (e.key === "End") {
        nextIdx = points.length - 1;
      }
      if (nextIdx !== -1) {
        e.preventDefault();
        selectHourColumn(nextIdx, true);
      }
    });

    colButtons.push(col);
    chart.appendChild(col);
  });

  // Pre-select the active forecast hour (NOW) on render
  selectHourColumn(defaultSelectIdx, false);
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
    removeBtn.setAttribute("aria-label", `Remove ${state.zoneNamesByCode[code] || code} from route`);
    removeBtn.textContent = "×";
    removeBtn.addEventListener("click", () => {
      state.routeCodes.splice(idx, 1);
      state.routeDirty = true;
      hideClearanceResult();
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
    updateClearanceSummary();

    box.className = `verdict-banner verdict-${re.overallStatus}`;
    box.classList.remove("hidden");

    const pill = el("routeStatusPill");
    pill.className = statusPillClass(re.overallStatus);
    pill.textContent = formatStatus(re.overallStatus);

    el("routeSummaryText").textContent = re.summary;
    el("routeBottleneckMeta").textContent = `Bottleneck Zone: ${re.bottleneckAreaName} (${re.bottleneckAreaCode})`;
    el("routeGaugeWrap").innerHTML = renderRiskGaugeHtml(
      Number(re.highestRiskScore),
      re.overallStatus,
      "routePeakScore",
      "Bottleneck Risk"
    );

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
    state.lastRouteData = null;
    grid.innerHTML = "";
    box.className = "verdict-banner verdict-DO_NOT_SAIL";
    box.classList.remove("hidden");
    el("routeStatusPill").className = "status-pill pill-DO_NOT_SAIL";
    el("routeStatusPill").textContent = "ERROR";
    el("routeSummaryText").textContent = `Route evaluation failed: ${err.message}`;
    el("routeBottleneckMeta").textContent = "";
    el("routeGaugeWrap").innerHTML = renderRiskGaugeHtml(null, "DO_NOT_SAIL", "routePeakScore", "Bottleneck Risk");
    updateClearanceSummary();
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

function getExceptionGuidance(errorType) {
  switch (errorType) {
    case "IllegalArgumentException":
      return "Ensure Max Crew, Crew Onboard, and active subclass specifications (tonnage, cargo, passengers, TEU) are valid positive numbers and Vessel Name is not blank.";
    case "VesselOvercapacityException":
      return "Adjust crew count, cargo tons, or passenger count in the Vessel Profile above so it does not exceed maximum vessel capacity.";
    case "MissingSafetyGearException":
      return "Check both Mandatory Life Jackets and Working Marine Radio in the Vessel Profile above before requesting clearance.";
    case "UnsafeSeaConditionException":
      return "Severe weather detected (DO NOT SAIL), or conditions are CAUTION without Captain Acknowledgment. If conditions are CAUTION, tick Captain Acknowledgment above or wait for a calmer departure window.";
    case "BmkgApiUnavailableException":
      return "The BMKG maritime service is currently unreachable and no offline cache is available. Verify internet connectivity or click Refresh Live BMKG.";
    default:
      return "Review vessel inputs, safety gear, and sea conditions before resubmitting.";
  }
}

function renderClearanceGuardrails(v, targetCodes) {
  const wrap = el("clearanceGuardrails");
  if (!wrap) return;

  // 1. Capacity & Profile Input Check
  const issues = getProfileValidationIssues(v);

  let capStatus = "PASS";
  let capClass = "guardrail-pass";
  let capPillClass = "pill-pass";
  let capTitle = "Capacity Gate Passed";
  let capDetail = `Crew ${v.crewCount}/${v.maxCrew} within limits.`;

  if (issues.maxCrewInvalid) {
    capStatus = "BLOCK";
    capClass = "guardrail-block";
    capPillClass = "pill-block";
    capTitle = "Invalid Max Crew";
    capDetail = "Max Crew must be at least 1 (triggers IllegalArgumentException).";
  } else if (issues.crewInvalid) {
    capStatus = "BLOCK";
    capClass = "guardrail-block";
    capPillClass = "pill-block";
    capTitle = "Invalid Crew Count";
    capDetail = "Crew Onboard must be at least 1 (triggers IllegalArgumentException).";
  } else if (issues.subclassInvalidMsg) {
    capStatus = "BLOCK";
    capClass = "guardrail-block";
    capPillClass = "pill-block";
    capTitle = "Invalid Vessel Spec";
    capDetail = issues.subclassInvalidMsg;
  } else if (issues.crewOverloaded) {
    capStatus = "BLOCK";
    capClass = "guardrail-block";
    capPillClass = "pill-block";
    capTitle = "Crew Overcapacity";
    capDetail = `Crew count (${v.crewCount}) exceeds maximum vessel capacity (${v.maxCrew}) (triggers VesselOvercapacityException).`;
  } else if (issues.cargoOverloaded) {
    capStatus = "BLOCK";
    capClass = "guardrail-block";
    capPillClass = "pill-block";
    capTitle = "Cargo Overcapacity";
    capDetail = `Cargo weight (${v.currentCargoTon} t) exceeds maximum payload (${v.maxCargoTon} t) (triggers VesselOvercapacityException).`;
  } else if (issues.paxOverloaded) {
    capStatus = "BLOCK";
    capClass = "guardrail-block";
    capPillClass = "pill-block";
    capTitle = "Passenger Overcapacity";
    capDetail = `Passenger count (${v.currentPassengers}) exceeds certified capacity (${v.maxPassengers}) (triggers VesselOvercapacityException).`;
  } else if (v.vesselType === "BargeVessel") {
    capDetail = `Crew ${v.crewCount}/${v.maxCrew} & Cargo ${v.currentCargoTon}/${v.maxCargoTon} t within safe limits.`;
  } else if (v.vesselType === "PassengerFerry") {
    capDetail = `Crew ${v.crewCount}/${v.maxCrew} & Passengers ${v.currentPassengers}/${v.maxPassengers} within safe limits.`;
  }

  // 2. Mandatory Safety Gear Check
  let gearStatus = "PASS";
  let gearClass = "guardrail-pass";
  let gearPillClass = "pill-pass";
  let gearTitle = "Mandatory Gear Verified";
  let gearDetail = "Both Life Jackets and Working Marine Radio are onboard.";

  if (!v.lifeJackets && !v.radio) {
    gearStatus = "BLOCK";
    gearClass = "guardrail-block";
    gearPillClass = "pill-block";
    gearTitle = "Missing Life Jackets and Radio";
    gearDetail = "Mandatory safety gear missing (triggers MissingSafetyGearException).";
  } else if (!v.lifeJackets) {
    gearStatus = "BLOCK";
    gearClass = "guardrail-block";
    gearPillClass = "pill-block";
    gearTitle = "Missing Life Jackets";
    gearDetail = "Vessel cannot sail without approved life jackets (triggers MissingSafetyGearException).";
  } else if (!v.radio) {
    gearStatus = "BLOCK";
    gearClass = "guardrail-block";
    gearPillClass = "pill-block";
    gearTitle = "Missing Marine Radio";
    gearDetail = "Vessel cannot sail without two-way marine radio (triggers MissingSafetyGearException).";
  }

  // 3. Weather and Caution Gate
  let weatherStatus = "PENDING";
  let weatherClass = "guardrail-pending";
  let weatherPillClass = "pill-pending";
  let weatherTitle = "Evaluating Weather...";
  let weatherDetail = "Checking BMKG sea forecast for selected zone(s).";

  const isSim = el("simulateStormToggle").checked;
  const cautionAck = el("cautionAckCheckbox").checked;

  let activeConditionStatus = null;
  if (state.clearanceScope === "multiZone") {
    if (targetCodes.length === 0) {
      weatherStatus = "BLOCK";
      weatherClass = "guardrail-block";
      weatherPillClass = "pill-block";
      weatherTitle = "No Route Zones";
      weatherDetail = "Add at least one sea zone in Tab 2 or switch to Current Sea Zone.";
    } else if (state.routeDirty || !state.lastRouteData) {
      weatherTitle = "Updating Route Weather...";
      weatherDetail = "Refreshing multi-zone forecast evaluation for current vessel profile.";
    } else {
      activeConditionStatus = state.lastRouteData.overallStatus;
    }
  } else {
    if (state.lastEvalData?.currentEvaluation) {
      activeConditionStatus = state.lastEvalData.currentEvaluation.status;
    }
  }

  if (activeConditionStatus) {
    if (activeConditionStatus === "DO_NOT_SAIL") {
      weatherStatus = "BLOCK";
      weatherClass = "guardrail-block";
      weatherPillClass = "pill-block";
      weatherTitle = "Severe Sea Conditions";
      weatherDetail = isSim
        ? "Simulated tropical storm triggers mandatory DO NOT SAIL (UnsafeSeaConditionException)."
        : "BMKG limits exceeded (wind or wave >= limit). Sailing strictly prohibited (UnsafeSeaConditionException).";
    } else if (activeConditionStatus === "CAUTION") {
      if (cautionAck) {
        weatherStatus = "PASS";
        weatherClass = "guardrail-pass";
        weatherPillClass = "pill-pass";
        weatherTitle = "Caution Acknowledged";
        weatherDetail = "Caution conditions present (>=75% buffer/gust/current). Captain acknowledgment verified.";
      } else {
        weatherStatus = "WARN";
        weatherClass = "guardrail-warn";
        weatherPillClass = "pill-warn";
        weatherTitle = "Captain Ack Required";
        weatherDetail = "Caution conditions present. Must check Captain Acknowledgment before clearance (UnsafeSeaConditionException).";
      }
    } else if (activeConditionStatus === "SAFE") {
      weatherStatus = "PASS";
      weatherClass = "guardrail-pass";
      weatherPillClass = "pill-pass";
      weatherTitle = "Sea Conditions Safe";
      weatherDetail = "All wave, wind, and current parameters remain safely below caution buffers.";
    }
  }

  wrap.innerHTML = `
    <div class="guardrail-card ${capClass}">
      <div class="guardrail-header">
        <span class="guardrail-step">Step 1: Capacity</span>
        <span class="guardrail-status-pill ${capPillClass}">${capStatus}</span>
      </div>
      <h4 class="guardrail-title">${escapeHtml(capTitle)}</h4>
      <p class="guardrail-detail">${escapeHtml(capDetail)}</p>
    </div>

    <div class="guardrail-card ${gearClass}">
      <div class="guardrail-header">
        <span class="guardrail-step">Step 2: Safety Gear</span>
        <span class="guardrail-status-pill ${gearPillClass}">${gearStatus}</span>
      </div>
      <h4 class="guardrail-title">${escapeHtml(gearTitle)}</h4>
      <p class="guardrail-detail">${escapeHtml(gearDetail)}</p>
    </div>

    <div class="guardrail-card ${weatherClass}">
      <div class="guardrail-header">
        <span class="guardrail-step">Step 3: Weather Gate</span>
        <span class="guardrail-status-pill ${weatherPillClass}">${weatherStatus}</span>
      </div>
      <h4 class="guardrail-title">${escapeHtml(weatherTitle)}</h4>
      <p class="guardrail-detail">${escapeHtml(weatherDetail)}</p>
    </div>
  `;
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
  renderClearanceGuardrails(v, targetCodes);
}

async function submitClearance() {
  if (state.isSubmittingClearance) return;

  const box = el("clearanceResultBox");
  const btn = el("requestClearanceBtn");
  if (state.evalInFlightPromise) {
    state.isSubmittingClearance = true;
    btn.disabled = true;
    btn.textContent = "Syncing Forecast...";
    try {
      await state.evalInFlightPromise;
    } catch (_) {
    } finally {
      state.isSubmittingClearance = false;
    }
  }

  const areaCodes = getClearanceTargetCodes();
  if (areaCodes.length === 0) {
    btn.disabled = false;
    btn.textContent = "Request Sailing Clearance";
    box.classList.remove("hidden");
    box.innerHTML = `
      <div class="clearance-result clearance-result-REJECTED">
        <div class="clearance-result-header">
          <span class="status-pill pill-DO_NOT_SAIL">NO ROUTE SELECTED</span>
          <h3>Departure Clearance Not Submitted</h3>
        </div>
        <div class="clearance-result-body">
          <p>Add at least one sea zone in Multi-Zone Route (Tab 2) or switch Clearance Scope to Current Sea Zone (Tab 1).</p>
        </div>
      </div>
    `;
    return;
  }

  const v = getVesselParams();
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
      box.innerHTML = `
        <div class="clearance-result clearance-result-REJECTED">
          <div class="clearance-result-header">
            <span class="status-pill pill-DO_NOT_SAIL">CLEARANCE REJECTED</span>
            <h3>Departure Blocked by Port Authority (Syahbandar)</h3>
          </div>
          <div class="clearance-result-body">
            <div class="exception-box">
              <div class="exception-header">
                <span class="exception-code-badge">${escapeHtml(data.errorType || "DepartureException")}</span>
              </div>
              <p class="exception-message">${escapeHtml(data.message)}</p>
              <p class="exception-guidance"><strong>Recommended Action:</strong> ${escapeHtml(getExceptionGuidance(data.errorType))}</p>
            </div>
          </div>
        </div>
      `;
      return;
    }

    const c = data.clearance;
    const depTz = c.departureTimezone || inferTimezoneFromLocalStamp(c.issuedTimestampLocal);
    box.innerHTML = `
      <div class="clearance-result clearance-result-APPROVED">
        <div class="clearance-result-header">
          <span class="status-pill pill-SAFE">CLEARANCE ISSUED</span>
          <h3>Departure Approved (${escapeHtml(c.clearanceId)})</h3>
        </div>
        <div class="clearance-result-body">
          <p><strong>${escapeHtml(c.vesselName)}</strong> (${escapeHtml(c.vesselType)}) cleared for voyage along <strong>${escapeHtml((c.routeZoneCodes || []).join(" → "))}</strong>.</p>
          <p>Status: <span class="${statusPillClass(c.safetyStatus)}">${escapeHtml(formatStatus(c.safetyStatus))}</span> &bull; Risk Score: <strong>${escapeHtml(c.riskScore)}/100</strong> &bull; Departure Timezone: <strong>${escapeHtml(depTz)}</strong> &bull; Issued: <strong>${escapeHtml(c.issuedTimestampLocal)}</strong></p>
        </div>
      </div>
    `;
    await loadClearanceLogs();
  } catch (err) {
    box.classList.remove("hidden");
    box.innerHTML = `
      <div class="clearance-result clearance-result-ERROR">
        <div class="clearance-result-header">
          <span class="status-pill pill-DO_NOT_SAIL">SERVICE ERROR</span>
          <h3>Clearance Service Unavailable</h3>
        </div>
        <div class="clearance-result-body">
          <p>Unable to reach the clearance service (${escapeHtml(err.message)}).</p>
        </div>
      </div>
    `;
  } finally {
    state.isSubmittingClearance = false;
    btn.disabled = false;
    btn.textContent = "Request Sailing Clearance";
  }
}

async function loadClearanceLogs() {
  const tbody = el("clearanceLogBody");
  const countBadge = el("clearanceCountBadge");
  try {
    const res = await fetch("/api/clearances");
    const data = await res.json();
    const clearances = data.clearances || [];
    if (countBadge) {
      countBadge.textContent = `${clearances.length} record${clearances.length === 1 ? "" : "s"}`;
    }
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
    if (countBadge) countBadge.textContent = "0 records";
    tbody.innerHTML = `
      <tr class="empty-state-row">
        <td colspan="7">Unable to load clearance logs: ${escapeHtml(err.message)}</td>
      </tr>
    `;
  }
}