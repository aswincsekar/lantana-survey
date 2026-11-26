"use strict";
const $ = (id) => document.getElementById(id),
  main = $("main"),
  phone = typeof Field !== "undefined";
let records = [],
  current = "",
  currentPage = "",
  timer,
  receiveDepth,
  access = "";
function el(tag, text, cls) {
  const n = document.createElement(tag);
  if (text !== undefined) n.textContent = text;
  if (cls) n.className = cls;
  return n;
}
function button(parent, label, action, cls) {
  const b = el("button", label, cls);
  b.onclick = action;
  parent.append(b);
  return b;
}
function notice(text) {
  clearTimeout(timer);
  $("notice").textContent = text;
  timer = setTimeout(() => ($("notice").textContent = ""), 4500);
}
function page(name) {
  currentPage = name;
  receiveDepth = null;
  main.replaceChildren();
  $("nav").hidden = false;
  for (const id of ["journal", "map", "settings"])
    $(id).setAttribute("aria-current", String(id === name));
  window.scrollTo(0, 0);
}
async function api(path) {
  const r = await fetch(path, {
    headers: { Authorization: "Bearer " + access },
    cache: "no-store",
  });
  if (!r.ok) throw Error("Could not read dashboard (" + r.status + ").");
  return r.json();
}
async function list() {
  return phone ? JSON.parse(Field.visits()) : api("/visits");
}
async function read(id) {
  return phone
    ? JSON.parse(Field.visit(id))
    : api("/visits/" + encodeURIComponent(id));
}
function time(v) {
  return new Date(v.at).toLocaleString(undefined, {
    month: "short",
    day: "numeric",
    year: "numeric",
    hour: "numeric",
    minute: "2-digit",
  });
}
function patchName(v) {
  return "Patch " + v.patch.slice(0, 6).toUpperCase();
}
function identityLabel(v) {
  if (v.identity === "likely_lantana") return "Likely Lantana";
  if (v.identity === "closeup_match") return "Possible Lantana";
  if (v.identity === "unidentified") return "Lantana unconfirmed";
  return "Species unchecked";
}
function row(v) {
  const b = button(main, "", () => detail(v.id), "row"),
    s = el("span");
  s.append(el("b", patchName(v) + " · " + v.phase), el("small", time(v)));
  const metric = el("span", undefined, "metric");
  if (v.photoCheck || v.cover == null) {
    metric.textContent = identityLabel(v.photoCheck || v);
  } else {
    if (!v.identity) metric.append(el("small", "Legacy"), " ");
    metric.append(el("span", Math.round(v.cover) + "%", "cover-value"));
  }
  b.append(s, metric);
}
async function home() {
  page("journal");
  const hero = el("section", undefined, "hero");
  hero.append(
    el("h1", "Field journal"),
    el(
      "span",
      phone ? "Surveys saved on this phone" : "Survey monitoring dashboard",
    ),
  );
  main.append(hero);
  if (phone) {
    const a = el("div", undefined, "actions");
    button(a, "Scan plant", () => Field.scan("", "Baseline", ""), "primary");
    main.append(a);
  }
  main.append(el("h2", "Recent visits"));
  try {
    records = await list();
    if (currentPage !== "journal") return;
    if (!records.length)
      main.append(
        el(
          "p",
          phone
            ? "Your first patch scan will appear here."
            : "Synced visits will appear here.",
          "sub",
        ),
      );
    records.forEach(row);
  } catch (e) {
    notice(e.message);
  }
}
function photo(frame, mask) {
  const b = el("div", undefined, "photo");
  const img = el("img");
  img.alt = "Survey overview";
  img.src = "data:image/jpeg;base64," + frame;
  b.append(img);
  if (mask) {
    const m = el("img", undefined, "mask");
    m.alt = "Predicted Lantana mask";
    m.src = "data:image/png;base64," + mask;
    b.append(m);
  }
  return b;
}
function processingSweep() {
  page("processing");
  $("nav").hidden = true;
  main.append(el("h1", "Saving your observation…"));
}

function depthSection(stats, sweep, visit) {
  const section = el("section");
  section.append(el("h2", "Measured sweep depth"));
  const pct = (100 * stats.validCells) / stats.totalCells;
  section.append(
    el(
      "p",
      stats.validCells
        ? pct.toFixed(1) + "% of the saved depth overview has usable depth."
        : "No usable depth in the overview. Move sideways with the patch in view and check AR setup under Connection.",
      "sub",
    ),
  );
  if (stats.sceneMedianM != null)
    section.append(
      el("p", "Median scene depth " + stats.sceneMedianM.toFixed(2) + " m"),
    );
  if (stats.observedProjectedAreaM2 != null)
    section.append(
      el(
        "p",
        "Observed projected Lantana area " +
          stats.observedProjectedAreaM2.toFixed(2) +
          " m² · " +
          Math.round((100 * stats.supportedMaskCells) / stats.maskCells) +
          "% of the predicted shrub has depth.",
      ),
    );
  if (!sweep) return section;
  section.append(
    el(
      "small",
      sweep.depthFrames +
        " of " +
        sweep.count +
        " frames captured depth. Amber is 0.5 m, blue is 5 m. Clear areas have no accepted reading.",
    ),
  );
  const slider = el("input"),
    view = el("div"),
    label = el("small");
  slider.type = "range";
  slider.min = 0;
  slider.max = sweep.count - 1;
  slider.value = stats.frame;
  slider.setAttribute("aria-label", "Depth replay frame");
  function select() {
    const i = Number(slider.value),
      jpeg = Field.sweepFrame(visit.id, i);
    const overlay = Field.sweepDepth(
      visit.id,
      i,
      sweep.gridWidth,
      sweep.gridHeight,
    );
    label.textContent =
      "Frame " +
      (i + 1) +
      " of " +
      sweep.count +
      (overlay ? " · depth recorded" : " · no usable depth");
    view.replaceChildren();
    if (jpeg) {
      const image = photo(jpeg, overlay);
      if (overlay) image.querySelector(".mask").alt = "Measured depth";
      view.append(image);
    }
  }
  slider.oninput = select;
  select();
  section.append(view, label, slider);
  return section;
}

function photoControls(visit, view) {
  const section = el("section"),
    actions = el("div", undefined, "actions");
  const original = view.querySelector("img"),
    mask = view.querySelector(".mask");
  const status = el("small");
  status.setAttribute("role", "status");
  let visible = false,
    loading = false,
    depth = "",
    showMask = true;
  let outline;
  if (mask)
    outline = button(actions, "Hide outline", () => {
      showMask = !showMask;
      mask.hidden = !showMask;
      outline.textContent = showMask ? "Hide outline" : "Show outline";
    });
  if (phone) {
    const toggle = button(actions, "Show depth map", () => {
      visible = !visible;
      render();
      if (visible && !loading && !depth) {
        loading = true;
        status.textContent = "Preparing depth map…";
        Field.photoDepth(visit.id);
      }
    });
    toggle.setAttribute("aria-pressed", "false");
    function render() {
      const show = visible && !!depth;
      original.src = show
        ? "data:image/png;base64," + depth
        : "data:image/jpeg;base64," + visit.photo;
      original.alt = show
        ? "Relative depth of this plant photo"
        : "Plant photo";
      if (mask) {
        mask.hidden = show || !showMask;
        outline.hidden = show;
      }
      toggle.textContent = visible ? "Hide depth map" : "Show depth map";
      toggle.setAttribute("aria-pressed", String(visible));
      status.textContent = visible
        ? show
          ? "Amber is nearer. Blue is farther."
          : "Preparing depth map…"
        : "";
    }
    receiveDepth = (encoded) => {
      loading = false;
      depth = encoded;
      if (!encoded) {
        const wasVisible = visible;
        visible = false;
        render();
        if (wasVisible)
          status.textContent = "Depth map unavailable. Tap to try again.";
      } else if (visible) render();
    };
  }
  section.append(actions, status);
  return section;
}

function photoDepthResult(id, encoded) {
  if (currentPage === "detail" && id === current && receiveDepth)
    receiveDepth(encoded);
}

function identificationHelp(v) {
  if (v.identity === "closeup_match")
    return "The close-up resembles Lantana. A wider view is needed to estimate cover.";
  if (v.identity === "unidentified") {
    if (v.identificationReason === "blur")
      return "The leaves were out of focus. Try again with the phone held steady.";
    if (v.identificationReason === "inconsistent")
      return "Only some views resembled Lantana. Try again with the same plant centred.";
    return "There wasn’t enough visual evidence to identify Lantana. Try a clear view of the leaves or flowers.";
  }
  if (v.identity === "likely_lantana")
    return v.review
      ? "Some views were unclear. Check the highlighted outline."
      : "The outline shows where the app found likely Lantana.";
  return "This older observation has no species check.";
}

function importing() {
  page("import");
  main.append(el("h1", "Analysing photograph…"));
}
async function detail(id) {
  page("detail");
  current = id;
  try {
    const [v, all] = await Promise.all([read(id), list()]);
    if (currentPage !== "detail" || current !== id) return;
    records = all;
    const result = v.photoCheck || v;
    main.append(
      el(
        "h1",
        result.identity === "unidentified"
          ? "Couldn’t confirm Lantana"
          : identityLabel(result),
      ),
      el("p", "Saved · " + time(v), "sub"),
      el("p", identificationHelp(result)),
    );
    const view = photo(
      v.photo,
      v.cover == null || v.photoCheck ? null : v.mask,
    );
    main.append(view, photoControls(v, view));
    const info = el("details");
    info.append(
      el("summary", "Visit details"),
      el("p", patchName(v) + " · " + v.phase),
    );
    if (v.restoredFromUsb)
      info.append(el("small", "Recovered from the local archive"));
    const depth = v.depth || v.sweepTrial?.depth;
    if (depth) {
      info.addEventListener("toggle", () => {
        if (info.open && !info.querySelector(".measured-depth")) {
          const measured = depthSection(depth, v.sweepTrial, v);
          measured.classList.add("measured-depth");
          info.append(measured);
        }
      });
    }
    if (v.photoCheck) {
      const legacy = el("details");
      legacy.append(
        el("summary", "Original pilot estimate"),
        photo(v.photo, v.mask),
        el("small", v.cover.toFixed(1) + "% visible cover · species unchecked"),
      );
      info.append(legacy);
    }
    if (v.closeup) info.append(el("h2", "Close-up"), photo(v.closeup, null));
    const numbers = el("div", undefined, "numbers");
    for (const [label, value] of [
      [
        "Visible Lantana cover",
        result.cover == null ? null : result.cover.toFixed(1) + "%",
      ],
      [
        "Canopy height",
        v.photoCheck || v.height == null ? null : v.height.toFixed(2) + " m",
      ],
    ]) {
      if (value === null) continue;
      const n = el("div");
      n.append(el("small", label), el("b", value));
      numbers.append(n);
    }
    if (numbers.children.length) main.append(numbers);
    info.append(
      el(
        "small",
        v.lat == null
          ? "Location unavailable"
          : v.lat.toFixed(5) +
              ", " +
              v.lon.toFixed(5) +
              " · GPS ±" +
              Math.round(v.accuracy) +
              " m",
      ),
    );
    main.append(info);
    if (!v.photoCheck && v.biomass)
      main.append(
        el(
          "p",
          "Estimated dry biomass " +
            v.biomass.estimate.toFixed(2) +
            " kg/m² (" +
            v.biomass.low.toFixed(2) +
            "–" +
            v.biomass.high.toFixed(2) +
            ")",
        ),
      );
    const past = records
      .filter((o) => o.patch === v.patch && o.at < v.at)
      .sort((a, b) => a.at - b.at);
    if (past.length) {
      const base = await read(past[0].id);
      if (currentPage !== "detail" || current !== id) return;
      const pair = el("div", undefined, "pair");
      for (const item of [base, v]) {
        const side = el("div");
        side.append(photo(item.photo, null), el("small", time(item)));
        pair.append(side);
      }
      main.append(
        el("h2", "Change since first visit"),
        pair,
        el(
          "p",
          !v.photoCheck &&
            !base.photoCheck &&
            v.cover != null &&
            base.cover != null &&
            v.model === base.model
            ? (v.cover - base.cover).toFixed(1) +
                " percentage points of visible cover"
            : "Cover change requires two measured visits using the same model.",
        ),
      );
    }
    if (phone) {
      if (
        result.identity === "unidentified" ||
        result.identity === "closeup_match"
      )
        button(
          main,
          "Try another view",
          () => Field.scan(v.patch, v.phase, ""),
          "primary",
        );
      const stages = el("select");
      stages.setAttribute("aria-label", "Revisit stage");
      for (const s of ["After clearance", "Regrowth", "Baseline"])
        stages.append(el("option", s));
      const actions = el("div", undefined, "actions");
      actions.append(stages);
      button(
        actions,
        "Record a follow-up",
        () => {
          Field.scan(v.patch, stages.value, v.id);
        },
        "primary",
      );
      info.append(actions);
    }
  } catch (e) {
    notice("Unable to open this visit.");
  }
}
async function map() {
  page("map");
  main.append(
    el("h1", "Patch map"),
    el("p", "Survey locations · north up", "sub"),
  );
  try {
    records = await list();
    if (currentPage !== "map") return;
    const pts = records.filter((v) => v.lat != null && v.lon != null);
    if (!pts.length) {
      main.append(el("p", "No geotagged visits yet."));
      return;
    }
    const ns = "http://www.w3.org/2000/svg",
      svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", "0 0 500 320");
    svg.classList.add("map");
    const add = (tag, attrs) => {
      const n = document.createElementNS(ns, tag);
      Object.entries(attrs).forEach(([k, v]) => n.setAttribute(k, v));
      svg.append(n);
      return n;
    };
    const lat0 = pts[0].lat,
      lon0 = pts[0].lon,
      mLon = 111320 * Math.cos((lat0 * Math.PI) / 180),
      xs = pts.map((v) => (v.lon - lon0) * mLon),
      ys = pts.map((v) => (v.lat - lat0) * 111320);
    const xmin = Math.min(...xs),
      xmax = Math.max(...xs),
      ymin = Math.min(...ys),
      ymax = Math.max(...ys),
      scale = Math.max((xmax - xmin) / 430, (ymax - ymin) / 230, 0.5);
    for (let i = 0; i < 500; i += 40)
      add("line", { x1: i, y1: 0, x2: i, y2: 320 });
    for (let i = 0; i < 320; i += 40)
      add("line", { x1: 0, y1: i, x2: 500, y2: i });
    pts.forEach((v, i) => {
      let n = add("circle", {
        cx: 250 + (xs[i] - (xmin + xmax) / 2) / scale,
        cy: 150 - (ys[i] - (ymin + ymax) / 2) / scale,
        r: 8,
      });
      n.onclick = () => detail(v.id);
    });
    add("text", { x: 12, y: 305 }).textContent =
      "Grid spacing " + Math.round(40 * scale) + " m";
    main.append(svg);
    pts.forEach(row);
  } catch (e) {
    notice(e.message);
  }
}
function settings() {
  page("settings");
  main.append(el("h1", phone ? "Settings" : "Dashboard connection"));
  if (phone) {
    button(main, "Choose a saved photo", () => Field.pick());
    main.append(el("h2", "Dashboard connection"));
    main.append(
      el(
        "p",
        "Visits stay on this phone and upload when a configured dashboard is reachable.",
      ),
    );
    button(main, "Configure sync", () => Field.setup());
    const advanced = el("details");
    advanced.append(el("summary", "Advanced"));
    button(advanced, "Test the sample photo", () => Field.example());
    button(advanced, "Set up AR measurements", () => Field.setupDepth());
    main.append(advanced);
  } else {
    const input = el("input");
    input.type = "password";
    input.placeholder = "Dashboard access token";
    main.append(input);
    button(
      main,
      "Open dashboard",
      () => {
        access = input.value;
        home();
      },
      "primary",
    );
  }
  main.append(
    el("h2", "Model and imagery"),
    el(
      "p",
      "Offline species recognition uses Indian plant observations. The experimental mask uses sparse annotations from ten photographs. Forest validation and biomass calibration are pending.",
    ),
    el(
      "small",
      "Journal image by Maina Tudu, CC0. Full image credits accompany the training dataset.",
    ),
  );
}
function exampleResult(frame, mask, cover) {
  page("example");
  main.append(
    el("h1", "Example prediction"),
    photo(frame, mask),
    el(
      "p",
      cover == null
        ? "Species unidentified"
        : cover.toFixed(1) + "% predicted visible cover",
    ),
    el(
      "small",
      "Training image · Maina Tudu, CC0. This example is excluded from survey records.",
    ),
  );
}
function back() {
  if (currentPage === "scan") Field.cancel();
  else home();
}
$("journal").onclick = home;
$("map").onclick = map;
$("settings").onclick = settings;
if (phone) {
  $("settings").textContent = "Settings";
  home();
} else settings();
