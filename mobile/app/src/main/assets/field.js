"use strict";
const $ = (id) => document.getElementById(id),
  main = $("main"),
  phone = typeof Field !== "undefined";
let records = [],
  previous = null,
  current = "",
  currentPage = "",
  timer,
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
function row(v) {
  const b = button(main, "", () => detail(v.id), "row"),
    s = el("span");
  s.append(el("b", patchName(v) + " · " + v.phase), el("small", time(v)));
  b.append(s, el("span", Math.round(v.cover) + "%", "metric"));
}
async function home() {
  page("journal");
  previous = null;
  const hero = el("section", undefined, "hero");
  hero.append(
    el("h1", "Lantana field journal"),
    el(
      "span",
      phone ? "Surveys saved on this phone" : "Survey monitoring dashboard",
    ),
  );
  main.append(hero);
  if (phone) {
    const a = el("div", undefined, "actions");
    button(a, "Scan a patch", () => Field.scan("", "Baseline"), "primary");
    button(a, "Import photo", () => Field.pick());
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
function openScan() {
  page("scan");
  $("nav").hidden = true;
  const box = el("section", undefined, "screen");
  box.innerHTML =
    '<h1>Scan the patch</h1><p id="hint">Keep the base and canopy in view.</p><div id="live"></div><div class="progress"><i id="bar"></i></div>';
  button(box, "Cancel scan", () => Field.cancel());
  main.append(box);
}
function preview(frame, mask, percent, hint) {
  if (currentPage !== "scan") return;
  const p = photo(frame, mask);
  if (previous) {
    const ghost = el("img", undefined, "ghost");
    ghost.src = "data:image/jpeg;base64," + previous.photo;
    ghost.alt = "Previous visit framing guide";
    p.append(ghost);
  }
  $("live").replaceChildren(p);
  $("bar").style.width = percent + "%";
  $("hint").textContent = hint;
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
    main.append(
      el("h1", patchName(v)),
      el("p", v.phase + " · " + time(v), "sub"),
    );
    const view = photo(v.photo, v.mask);
    main.append(view);
    const a = el("div", undefined, "actions");
    const toggle = button(a, "Hide outline", () => {
      const m = view.querySelector(".mask");
      m.style.display = m.style.display === "none" ? "block" : "none";
      toggle.textContent =
        m.style.display === "none" ? "Show outline" : "Hide outline";
    });
    main.append(a);
    const numbers = el("div", undefined, "numbers");
    for (const [label, value] of [
      ["Visible Lantana cover", v.cover.toFixed(1) + "%"],
      [
        "Canopy edge height",
        v.height == null ? "Unavailable" : v.height.toFixed(2) + " m",
      ],
    ]) {
      const n = el("div");
      n.append(el("small", label), el("b", value));
      numbers.append(n);
    }
    main.append(numbers);
    main.append(
      el(
        "p",
        v.review
          ? "Low-quality or inconsistent view. Review the outline and repeat the scan if needed."
          : "Experimental species prediction. Review the outline before field use.",
        "hint",
      ),
    );
    main.append(
      el(
        "p",
        v.lat == null
          ? "Location unavailable"
          : v.lat.toFixed(5) +
              ", " +
              v.lon.toFixed(5) +
              " · GPS ±" +
              Math.round(v.accuracy) +
              " m" +
              (v.heading == null
                ? ""
                : " · " + Math.round(v.heading) + "° magnetic"),
        "sub",
      ),
      el(
        "small",
        v.source +
          " · " +
          v.frames +
          " frame" +
          (v.frames === 1 ? "" : "s") +
          " · " +
          v.model,
      ),
    );
    if (v.biomass)
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
          (v.cover - base.cover).toFixed(1) +
            " percentage points of visible cover",
        ),
      );
    }
    if (phone) {
      const stages = el("select");
      stages.setAttribute("aria-label", "Revisit stage");
      for (const s of ["After clearance", "Regrowth", "Baseline"])
        stages.append(el("option", s));
      const actions = el("div", undefined, "actions");
      actions.append(stages);
      button(
        actions,
        "Rescan this patch",
        () => {
          previous = v;
          Field.scan(v.patch, stages.value);
        },
        "primary",
      );
      main.append(actions);
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
  main.append(el("h1", "Dashboard connection"));
  if (phone) {
    main.append(
      el(
        "p",
        "Visits stay on this phone and upload when a configured dashboard is reachable.",
      ),
    );
    button(main, "Configure sync", () => Field.setup());
    button(main, "Test model on sample photo", () => Field.example());
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
      "Pilot model trained on sparse labels from ten public photographs. Local field accuracy and biomass calibration are pending.",
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
    el("p", cover.toFixed(1) + "% predicted visible cover"),
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
if (phone) home();
else settings();
