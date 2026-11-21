/* global Pbb, shaka */
/* eslint-disable no-undef -- Pbb and shaka are injected before this page loads. */
const Pbb = window.Pbb;
const shaka = window.shaka;
const state = {
  quality: "hd",
  focused: -1,
  pip: false,
  // Stream indices, not a single slot: every camera can record on its own.
  recording: new Set(),
  stopping: new Set(),
  recordStarted: new Map(),
  sound: 0,
  fullscreen: false,
  locked: false,
  volume: 1,
  brightness: 0.5,
  keysStale: false,
  // One automatic re-handshake per rotation; a second failure leaves the
  // message on the strip instead of hammering the site.
  keyRetry: false,
  mini: -1,
};

let manualKeyRefresh = false;
let transientStatusTimer = null;

function refreshKeysFromUi() {
  if (!window.Pbb) {
    return;
  }
  manualKeyRefresh = true;
  setStatusText("Syncing feeds");
  Pbb.refreshKeys();
}

window.onKeysMessage = (message) => {
  // A server switch also refreshes keys. Its failure message must reach the
  // strip; only the manual-refresh flag needs clearing here.
  if (manualKeyRefresh) {
    manualKeyRefresh = false;
  }
  setStatusText(message);
};

const players = [];

let grid;
let backBtn;
let statusText;
let statusMeta;
let qualityButtons;
let recBadge;
let recCount;
let recTime;
let hideControlsTimer = null;
let indicator;
let indicatorTimer = null;
let suppressTap = false;

const gesture = {
  tracking: false,
  active: false,
  mode: null,
  startX: 0,
  startY: 0,
  startValue: 0,
};

const zoom = {
  mode: false,
  scale: 1,
  x: 0,
  y: 0,
  startDist: 0,
  startScale: 1,
  startPanX: 0,
  startPanY: 0,
  touchX: 0,
  touchY: 0,
};

function normalizeQuality(raw) {
  const value = String(raw || "").toLowerCase();
  return value === "hd" || value === "sd" ? value : "hd";
}

function uuidKid(kid) {
  return kid.replace(/(.{8})(.{4})(.{4})(.{4})(.{12})/, "$1-$2-$3-$4-$5");
}

const IDLE_HINT = "Choose a server or refresh the feeds";

function defaultStatus() {
  const count = state.recording.size;
  if (count === 1) {
    return "Recording 1 camera";
  }
  if (count > 1) {
    return `Recording ${count} cameras`;
  }
  return IDLE_HINT;
}

function statusStateFor(message) {
  if (state.recording.size) {
    return "recording";
  }
  if (!message) {
    return "idle";
  }
  if (/^(saved|all feeds ready|feeds are already in sync)/i.test(message)) {
    return "success";
  }
  if (/error|failed|cannot|could not|keys changed|unavailable|invalid|not supported|403|1001|3015/i.test(message)) {
    return "error";
  }
  return "busy";
}

function usingLiveSource() {
  return Boolean(window.Pbb && typeof Pbb.getStreamSource === "function"
    && Pbb.getStreamSource() === "live");
}

function updateStatusMeta() {
  const meta = statusMeta || document.getElementById("statusMeta");
  if (!meta) {
    return;
  }
  const live = usingLiveSource();
  const pbb3 = !live && serverSheet.server === "pbb3";
  const server = live
    ? (LIVE_SERVER_NAMES[liveSheet.server] || "Server 1")
    : (SERVER_NAMES[serverSheet.server] || "Server Jeo");
  const source = live ? "LIVE" : (pbb3 ? "PBB3" : "SITE");
  meta.textContent = `${source} · ${server} · ${state.quality.toUpperCase()}`;
}

function setStatusText(message) {
  // The footer never hides itself: an idle label takes the place of the message.
  statusText.textContent = message || defaultStatus();
  const bar = document.getElementById("statusBar");
  if (bar) {
    bar.classList.remove("hidden");
    bar.dataset.state = statusStateFor(message);
    // A saved recording is the end of the job: give the LED one quiet flash.
    bar.classList.toggle("success", /^saved/i.test(message || ""));
  }
  updateStatusMeta();
  // Fullscreen hides the footer, so mirror the message on the indicator or a
  // control like the refresh icon looks like it did nothing.
  if (message && state.fullscreen) {
    showIndicator(message, 2500);
  }
}

function setStreamStatus(entry, message, bad) {
  entry.statusEl.textContent = message;
  entry.statusEl.classList.toggle("bad", Boolean(bad));
  // The steady "live" pill is noise; keep the pill only while connecting or on error.
  const hidden = !bad && message === "LIVE";
  entry.statusEl.classList.toggle("hidden", hidden);
  // The picture carries the state too: a scan while the signal comes up, a dim
  // wash when it drops. Both clear the moment the feed is live.
  if (entry.card) {
    entry.card.classList.toggle("connecting", !hidden && !bad);
    entry.card.classList.toggle("offline", !hidden && Boolean(bad));
  }
}

// A message that clears itself, so an informational refresh result does not
// sit in the footer until the next action.
function setTransientStatus(message, duration = 4000) {
  setStatusText(message);
  if (transientStatusTimer) {
    clearTimeout(transientStatusTimer);
  }
  transientStatusTimer = setTimeout(() => {
    transientStatusTimer = null;
    if (statusText.textContent === message) {
      setStatusText("");
    }
  }, duration);
}

function showControls() {
  document.body.classList.add("controls-visible");
  if (hideControlsTimer) {
    clearTimeout(hideControlsTimer);
  }
  hideControlsTimer = setTimeout(() => {
    hideControlsTimer = null;
    // A keyboard or D-pad user may still be on a key; keep the deck up until
    // the focus leaves it instead of yanking the key out from under them.
    if (document.body.classList.contains("keyboard-focus")
        && document.activeElement && document.activeElement.closest(".fs-controls")) {
      showControls();
      return;
    }
    document.body.classList.remove("controls-visible");
  }, 3500);
}

function toggleControls() {
  if (!state.fullscreen) {
    return;
  }
  if (document.body.classList.contains("controls-visible")) {
    document.body.classList.remove("controls-visible");
    if (hideControlsTimer) {
      clearTimeout(hideControlsTimer);
      hideControlsTimer = null;
    }
    return;
  }
  showControls();
}

function showIndicator(message, duration = 900) {
  if (!indicator) {
    return;
  }
  indicator.textContent = message;
  indicator.classList.remove("hidden");
  if (indicatorTimer) {
    clearTimeout(indicatorTimer);
  }
  indicatorTimer = setTimeout(() => {
    indicator.classList.add("hidden");
    indicatorTimer = null;
  }, duration);
}

function toggleLock() {
  state.locked = !state.locked;
  document.body.classList.toggle("locked", state.locked);
  showControls();
  updateControls();
}

function applyGestureValue(value) {
  const clamped = Math.min(1, Math.max(0, value));
  if (gesture.mode === "volume") {
    // Drive the media stream so the change is actually audible; the players
    // stay at full volume so nothing attenuates twice.
    state.volume = clamped;
    if (window.Pbb) {
      Pbb.setVolume(clamped, false);
    }
    showIndicator(`volume ${Math.round(clamped * 100)}%`);
  } else {
    state.brightness = clamped;
    if (window.Pbb) {
      Pbb.setBrightness(clamped, false);
    }
    showIndicator(`brightness ${Math.round(clamped * 100)}%`);
  }
}

function touchDistance(touches) {
  const dx = touches[0].clientX - touches[1].clientX;
  const dy = touches[0].clientY - touches[1].clientY;
  return Math.hypot(dx, dy) || 1;
}

function applyZoom() {
  const entry = players[state.focused];
  if (!entry) {
    return;
  }
  if (zoom.scale <= 1) {
    entry.video.style.transform = "";
    zoom.x = 0;
    zoom.y = 0;
    return;
  }
  const limitX = (window.innerWidth * (zoom.scale - 1)) / 2;
  const limitY = (window.innerHeight * (zoom.scale - 1)) / 2;
  zoom.x = Math.max(-limitX, Math.min(limitX, zoom.x));
  zoom.y = Math.max(-limitY, Math.min(limitY, zoom.y));
  entry.video.style.transform = `translate(${zoom.x}px, ${zoom.y}px) scale(${zoom.scale})`;
}

// Clears every tile, not just the focused one: a zoomed video that becomes the
// mini tile keeps its scale and translate and renders black inside the tile.
function resetZoom() {
  zoom.scale = 1;
  zoom.x = 0;
  zoom.y = 0;
  for (const entry of players) {
    entry.video.style.transform = "";
  }
}

function toggleZoom() {
  if (!state.fullscreen) {
    return;
  }
  zoom.mode = !zoom.mode;
  if (!zoom.mode) {
    resetZoom();
  }
  showControls();
  updateControls();
}

function onTouchStart(event) {
  if (state.locked || !state.fullscreen) {
    gesture.tracking = false;
    return;
  }
  if (zoom.mode) {
    gesture.tracking = false;
    if (event.touches.length === 2) {
      zoom.startDist = touchDistance(event.touches);
      zoom.startScale = zoom.scale;
    } else if (event.touches.length === 1) {
      zoom.touchX = event.touches[0].clientX;
      zoom.touchY = event.touches[0].clientY;
      zoom.startPanX = zoom.x;
      zoom.startPanY = zoom.y;
    }
    return;
  }
  if (event.touches.length !== 1) {
    gesture.tracking = false;
    return;
  }
  const touch = event.touches[0];
  gesture.tracking = true;
  gesture.active = false;
  gesture.mode = null;
  gesture.startX = touch.clientX;
  gesture.startY = touch.clientY;
}

function onTouchMove(event) {
  if (state.locked || !state.fullscreen) {
    return;
  }
  if (zoom.mode) {
    if (event.touches.length === 2) {
      event.preventDefault();
      zoom.scale = Math.max(1, Math.min(4, (zoom.startScale * touchDistance(event.touches)) / zoom.startDist));
      applyZoom();
      return;
    }
    if (event.touches.length === 1 && zoom.scale > 1) {
      event.preventDefault();
      zoom.x = zoom.startPanX + (event.touches[0].clientX - zoom.touchX);
      zoom.y = zoom.startPanY + (event.touches[0].clientY - zoom.touchY);
      applyZoom();
    }
    return;
  }
  if (!gesture.tracking || event.touches.length !== 1) {
    return;
  }
  const touch = event.touches[0];
  const dy = gesture.startY - touch.clientY;
  const dx = touch.clientX - gesture.startX;

  if (!gesture.active) {
    if (Math.abs(dy) < 16 || Math.abs(dy) <= Math.abs(dx)) {
      return;
    }
    gesture.active = true;
    const width = window.innerWidth || 1;
    gesture.mode = gesture.startX < width / 2 ? "brightness" : "volume";
    gesture.startValue = gesture.mode === "volume" ? state.volume : state.brightness;
  }

  event.preventDefault();
  const height = window.innerHeight || 1;
  applyGestureValue(gesture.startValue + dy / height);
}

function onTouchEnd() {
  if (gesture.active) {
    suppressTap = true;
    setTimeout(() => {
      suppressTap = false;
    }, 0);
    // The sliders change the level while the finger moves, but the level is
    // only written to disk once, here.
    saveLevel(gesture.mode);
  }
  gesture.tracking = false;
  gesture.active = false;
  gesture.mode = null;
}

// Writes the level the user just picked, so the file on disk is not touched
// on every finger move.
function saveLevel(mode) {
  if (!window.Pbb) {
    return;
  }
  if (mode === "volume") {
    Pbb.setVolume(state.volume, true);
  } else if (mode === "brightness") {
    Pbb.setBrightness(state.brightness, true);
  }
}

function el(tag, className, role, text) {
  const node = document.createElement(tag);
  if (className) {
    node.className = className;
  }
  if (role) {
    node.setAttribute("data-role", role);
  }
  if (text !== undefined && text !== null) {
    node.textContent = text;
  }
  return node;
}

const SVG_NS = "http://www.w3.org/2000/svg";

const ICONS = {
  soundOn: [
    ["path", { d: "M11 5 6 9H2v6h4l5 4V5z" }],
    ["path", { d: "M15.5 8.5a5 5 0 0 1 0 7" }],
    ["path", { d: "M18.5 5.5a9 9 0 0 1 0 13" }],
  ],
  soundOff: [
    ["path", { d: "M11 5 6 9H2v6h4l5 4V5z" }],
    ["path", { d: "m22 9-6 6" }],
    ["path", { d: "m16 9 6 6" }],
  ],
  pip: [
    ["rect", { x: 2, y: 4, width: 20, height: 16, rx: 2 }],
    ["rect", { x: 12, y: 12, width: 8, height: 6, rx: 1 }],
  ],
  fullscreen: [
    ["path", { d: "M8 3H5a2 2 0 0 0-2 2v3" }],
    ["path", { d: "M16 3h3a2 2 0 0 1 2 2v3" }],
    ["path", { d: "M8 21H5a2 2 0 0 1-2-2v-3" }],
    ["path", { d: "M16 21h3a2 2 0 0 0 2-2v-3" }],
  ],
  record: [["circle", { cx: 12, cy: 12, r: 6 }]],
  stop: [["rect", { x: 6, y: 6, width: 12, height: 12, rx: 2 }]],
  exit: [
    ["path", { d: "M8 3v3a2 2 0 0 1-2 2H3" }],
    ["path", { d: "M21 8h-3a2 2 0 0 1-2-2V3" }],
    ["path", { d: "M3 16h3a2 2 0 0 1 2 2v3" }],
    ["path", { d: "M16 21v-3a2 2 0 0 1 2-2h3" }],
  ],
  grid: [
    ["rect", { x: 3, y: 3, width: 7, height: 7, rx: 1 }],
    ["rect", { x: 14, y: 3, width: 7, height: 7, rx: 1 }],
    ["rect", { x: 3, y: 14, width: 7, height: 7, rx: 1 }],
    ["rect", { x: 14, y: 14, width: 7, height: 7, rx: 1 }],
  ],
  zoom: [
    ["circle", { cx: 11, cy: 11, r: 7 }],
    ["path", { d: "m21 21-4.3-4.3" }],
    ["path", { d: "M11 8v6" }],
    ["path", { d: "M8 11h6" }],
  ],
  mini: [
    ["rect", { x: 3, y: 5, width: 18, height: 14, rx: 2 }],
    ["rect", { x: 12, y: 12, width: 7, height: 5, rx: 1 }],
  ],
  next: [
    ["path", { d: "M8 3 4 7l4 4" }],
    ["path", { d: "M4 7h16" }],
    ["path", { d: "m16 21 4-4-4-4" }],
    ["path", { d: "M20 17H4" }],
  ],
  lock: [
    ["rect", { x: 4, y: 11, width: 16, height: 10, rx: 2 }],
    ["path", { d: "M8 11V7a4 4 0 0 1 8 0v4" }],
  ],
  unlock: [
    ["rect", { x: 4, y: 11, width: 16, height: 10, rx: 2 }],
    ["path", { d: "M8 11V7a4 4 0 0 1 7.5-2" }],
  ],
  refresh: [
    ["path", { d: "M21 12a9 9 0 1 1-3-6.7" }],
    ["path", { d: "M21 4v5h-5" }],
  ],
  close: [
    ["path", { d: "M6 6l12 12" }],
    ["path", { d: "M18 6 6 18" }],
  ],
  server: [
    ["rect", { x: 3, y: 4, width: 18, height: 7, rx: 1.5 }],
    ["rect", { x: 3, y: 13, width: 18, height: 7, rx: 1.5 }],
    ["path", { d: "M7 7.5h.01" }],
    ["path", { d: "M7 16.5h.01" }],
  ],
  sliders: [
    ["path", { d: "M4 7h9" }],
    ["path", { d: "M18 7h2" }],
    ["path", { d: "M4 12h3" }],
    ["path", { d: "M12 12h8" }],
    ["path", { d: "M4 17h9" }],
    ["path", { d: "M18 17h2" }],
    ["circle", { cx: 15, cy: 7, r: 2 }],
    ["circle", { cx: 9, cy: 12, r: 2 }],
    ["circle", { cx: 15, cy: 17, r: 2 }],
  ],
  sun: [
    ["circle", { cx: 12, cy: 12, r: 4.2 }],
    ["path", { d: "M12 1.8v2.4" }],
    ["path", { d: "M12 19.8v2.4" }],
    ["path", { d: "M4.6 4.6 6.3 6.3" }],
    ["path", { d: "M17.7 17.7 19.4 19.4" }],
    ["path", { d: "M1.8 12h2.4" }],
    ["path", { d: "M19.8 12h2.4" }],
    ["path", { d: "M4.6 19.4 6.3 17.7" }],
    ["path", { d: "M17.7 6.3 19.4 4.6" }],
  ],
  moon: [
    ["path", { d: "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" }],
  ],
  // Two stacked monitors: the stitched Main-over-Dining recording.
  stitch: [
    ["rect", { x: 3, y: 3, width: 18, height: 8, rx: 1 }],
    ["rect", { x: 3, y: 13, width: 18, height: 8, rx: 1 }],
  ],
  broadcast: [
    ["circle", { cx: 12, cy: 12, r: 2 }],
    ["path", { d: "M7.8 7.8a6 6 0 0 0 0 8.4" }],
    ["path", { d: "M16.2 7.8a6 6 0 0 1 0 8.4" }],
    ["path", { d: "M5 5a10 10 0 0 0 0 14" }],
    ["path", { d: "M19 5a10 10 0 0 1 0 14" }],
  ],
  palette: [
    ["path", { d: "M12 3a9 9 0 1 0 0 18h1.5a2.5 2.5 0 0 0 0-5H12a2 2 0 0 1 0-4h6a3 3 0 0 0 3-3A6 6 0 0 0 12 3z" }],
    ["circle", { cx: 7.5, cy: 10.5, r: 1, fill: "currentColor", stroke: "none" }],
    ["circle", { cx: 10.5, cy: 7.5, r: 1, fill: "currentColor", stroke: "none" }],
    ["circle", { cx: 15, cy: 8, r: 1, fill: "currentColor", stroke: "none" }],
  ],
};

const SVG_ATTRS = {
  viewBox: "0 0 24 24",
  width: "18",
  height: "18",
  fill: "none",
  stroke: "currentColor",
  "stroke-width": "2",
  "stroke-linecap": "round",
  "stroke-linejoin": "round",
  "aria-hidden": "true",
};

function iconNode(name) {
  const svg = document.createElementNS(SVG_NS, "svg");
  for (const [key, value] of Object.entries(SVG_ATTRS)) {
    svg.setAttribute(key, value);
  }
  for (const [tag, attrs] of ICONS[name] || []) {
    const node = document.createElementNS(SVG_NS, tag);
    for (const [key, value] of Object.entries(attrs)) {
      node.setAttribute(key, String(value));
    }
    svg.appendChild(node);
  }
  return svg;
}

function setIcon(btn, name, label) {
  if (!btn) {
    return;
  }
  mountIcon(btn, name);
  if (label) {
    btn.setAttribute("aria-label", label);
    btn.title = label;
  }
}

// A key with a word under its glyph keeps its label when the icon changes.
function setLabelledIcon(btn, slot, name, label) {
  if (!btn) {
    return;
  }
  mountIcon(btn.querySelector(slot), name);
  if (label) {
    btn.setAttribute("aria-label", label);
    btn.title = label;
  }
}

function setDeckIcon(btn, name, label) {
  setLabelledIcon(btn, ".fs-glyph", name, label);
}

function setStatusKey(btn, name, label) {
  setLabelledIcon(btn, ".key-glyph", name, label);
}

// Replaces the children of any node with an icon, so the master record button
// can keep its text label next to a glyph that swaps between record and stop.
function mountIcon(node, name) {
  if (!node || node.dataset.icon === name) {
    return;
  }
  node.dataset.icon = name;
  node.replaceChildren(iconNode(name));
}

function makeIconButton(className, role) {
  const btn = el("button", className, role, null);
  btn.setAttribute("type", "button");
  return btn;
}

function createCard(stream, index) {
  const card = el("article", "cam-card", null, null);
  card.setAttribute("data-index", String(index));
  card.classList.add("connecting");

  const videoWrap = el("div", "video-wrap", null, null);
  const video = el("video", null, "video", null);
  video.setAttribute("playsinline", "");
  video.setAttribute("webkit-playsinline", "");
  // The element stays unmuted for its whole life and is played silent with
  // the level at zero. Toggling "muted" tears the audio renderer down and
  // builds it again, and that is what made the picture stutter every time
  // the sound was switched.
  video.muted = false;
  video.volume = 0;
  video.autoplay = true;
  video.preload = "auto";
  video.controls = false;
  video.setAttribute("aria-label", `${stream.name} live video`);
  const statusEl = el("div", "stream-status", "status", "Connecting");
  // The running time is a burn-in over the picture, the way a recorder puts
  // it there, so the strip on top stays free for the name.
  const tally = el("div", "tally cam-tally", null, null);
  tally.setAttribute("aria-hidden", "true");
  tally.appendChild(el("span", "tally-dot", null, null));
  tally.appendChild(el("span", "tally-label", null, "rec"));
  const tallyTime = el("span", "tally-time", "tally-time", "0:00");
  tally.appendChild(tallyTime);
  videoWrap.appendChild(video);
  videoWrap.appendChild(statusEl);

  const top = el("div", "cam-top", null, null);
  top.appendChild(el("span", "cam-title", "name", stream.name));
  if (index === 0) {
    top.appendChild(el("span", "cam-role", null, "program"));
  }
  // While a feed records, the tally takes the place of the live mark: both
  // would not fit on a preview strip, and the red one says more.
  top.appendChild(tally);
  const liveMark = el("span", "cam-live", null, null);
  liveMark.appendChild(el("span", "cam-live-dot", null, null));
  liveMark.appendChild(document.createTextNode("LIVE"));
  top.appendChild(liveMark);

  const bar = el("div", "cam-bar", null, null);
  const actions = el("div", "cam-actions", null, null);
  const fsBtn = makeIconButton("btn btn-ghost btn-icon", "fs");
  const soundBtn = makeIconButton("btn btn-ghost btn-icon", "sound");
  const pipBtn = makeIconButton("btn btn-ghost btn-icon", "pip");
  const recordBtn = makeIconButton("btn btn-icon record-btn", "record");
  actions.appendChild(fsBtn);
  actions.appendChild(soundBtn);
  actions.appendChild(pipBtn);
  actions.appendChild(recordBtn);

  // The strip sits under the picture, never on it: the feed is the image.
  bar.appendChild(top);
  bar.appendChild(actions);
  card.appendChild(videoWrap);
  card.appendChild(bar);

  card.addEventListener("click", (event) => {
    if (event.target.closest("button") || suppressTap) {
      return;
    }
    if (state.fullscreen && card.classList.contains("mini")) {
      swapMini();
      return;
    }
    toggleControls();
  });

  card.addEventListener("touchstart", onTouchStart, { passive: true });
  card.addEventListener("touchmove", onTouchMove, { passive: false });
  card.addEventListener("touchend", onTouchEnd, { passive: true });

  fsBtn.addEventListener("click", () => fullscreenStream(index));
  soundBtn.addEventListener("click", () => setSound(index));
  pipBtn.addEventListener("click", () => enterPip(index));
  recordBtn.addEventListener("click", () => toggleRecord(index));

  grid.appendChild(card);
  players.push({
    video, player: null, stream, statusEl, card, tally, tallyTime, loaded: false,
    audioBlocked: false,
  });
}

// 1001 BAD_HTTP_STATUS, 1002 HTTP_ERROR, 1003 TIMEOUT and 3015
// MEDIA_SOURCE_OPERATION_THREW are transient on live DASH. Shaka retries
// internally, then gives up and fires a fatal error; reload with a bounded
// backoff instead of leaving the tile dead.
const RECOVERABLE_SHAKA = new Set([1001, 1002, 1003, 3015]);

function scheduleRecover(entry, index, detail) {
  if (entry.retryTimer) {
    return;
  }
  entry.retries = (entry.retries || 0) + 1;
  entry.loaded = false;
  const delay = Math.min(30000, 1000 * Math.pow(2, Math.min(entry.retries - 1, 5)));
  const why = detail ? `[${detail}] ` : "";
  setStreamStatus(entry, `Retry in ${Math.round(delay / 1000)}s · ${why}`, true);
  entry.retryTimer = setTimeout(async () => {
    entry.retryTimer = null;
    const old = entry.player;
    // A config push or another recovery may have replaced this player while
    // the timer was waiting. Do not stack two players on one video element.
    if (entry.player !== old) {
      return;
    }
    entry.player = null;
    if (old) {
      await old.destroy().catch(() => {});
    }
    if (entry.player !== null) {
      return;
    }
    try {
      await initPlayer(entry, index);
    } catch {
      scheduleRecover(entry, index);
    }
  }, delay);
}

let gridGateOpen = false;
let gridStartTimer = null;

function playEntry(entry) {
  entry.video.play().catch(() => {
    // Some devices refuse to start a feed with sound. Fall back to a muted
    // element for that feed and keep the level routing for the rest.
    entry.audioBlocked = true;
    entry.video.muted = true;
    if (state.sound === players.indexOf(entry)) {
      state.sound = -1;
      updateControls();
    }
    entry.video.play().catch(() => {});
  });
}

function startGridTogether() {
  if (gridGateOpen) {
    return;
  }
  gridGateOpen = true;
  if (gridStartTimer) {
    clearTimeout(gridStartTimer);
    gridStartTimer = null;
  }
  for (const entry of players) {
    if (entry.loaded) {
      playEntry(entry);
    }
  }
  applyAudio();
}

function onPlayerReady() {
  if (players.length && players.every((entry) => entry.loaded)) {
    startGridTogether();
  }
}

async function initPlayer(entry, index) {
  const player = new shaka.Player(entry.video);
  entry.player = player;

  const clearKeys = {};
  clearKeys[entry.stream.kid] = entry.stream.key;
  clearKeys[uuidKid(entry.stream.kid)] = entry.stream.key;

  const retry = () => ({
    maxAttempts: 6,
    baseDelay: 400,
    backoffFactor: 2,
    fuzzFactor: 0.5,
    timeout: 20000,
    stallTimeout: 8000,
    connectionTimeout: 10000,
  });

  player.configure({
    drm: {
      preferredKeySystems: ["org.w3.clearkey"],
      clearKeys,
    },
    streaming: {
      // The live window is only ~40s wide. Asking for 30s of buffer and
      // holding 30s behind pushed the readers right up to the edge, where the
      // next segment is not published yet - that was the connect-disconnect
      // loop. Half a window of buffer, a small ring behind, and let the shared
      // presentation delay keep the three feeds together.
      lowLatencyMode: false,
      bufferingGoal: 20,
      rebufferingGoal: 8,
      bufferBehind: 12,
      retryParameters: retry(),
    },
    manifest: {
      // Same delay for every feed: that is what makes three independent
      // players line up, rather than each chasing its own live edge. 14s also
      // absorbs device clock skew, which at 8s had Shaka asking for segments
      // the origin had not written yet.
      defaultPresentationDelay: 14,
      dash: { ignoreSuggestedPresentationDelay: true },
      retryParameters: retry(),
    },
    abr: { enabled: false },
  });

  player.addEventListener("error", (event) => {
    // A destroyed player can still report one late append or read. Its error
    // must not reload the replacement player that now owns the tile.
    if (entry.player !== player) {
      return;
    }
    const detail = event.detail || {};
    const message = Number.isInteger(Number(detail.code))
      ? `Error ${detail.code}` : (detail.message || String(detail));
    if (Number(detail.code) === 4012) {
      state.keysStale = true;
      setStreamStatus(entry, "Keys changed", true);
      // The keys come from the site's handshake, so ask it for fresh ones
      // before asking the operator to do anything.
      if (!state.keyRetry && window.Pbb && typeof Pbb.refreshKeys === "function") {
        state.keyRetry = true;
        refreshKeysFromUi();
        setStatusText("Fetching new keys");
      } else {
        setStatusText("Keys changed. Choose a server");
      }
      return;
    }
    if (RECOVERABLE_SHAKA.has(Number(detail.code))) {
      const http = Array.isArray(detail.data) ? detail.data[1] : null;
      const why = Number.isInteger(http) ? `${detail.code}/${http}` : `${detail.code}`;
      scheduleRecover(entry, index, why);
      setTransientStatus(`Camera ${index + 1}: ${why}`, 4500);
    } else {
      setStreamStatus(entry, `error ${message}`, true);
    }
  });

  player.addEventListener("loaded", () => {
    if (entry.player === player) {
      applyQuality(player, state.quality);
    }
  });

  try {
    await player.load(entry.stream.url);
  } catch {
    if (entry.player === player) {
      scheduleRecover(entry, index);
    }
    return;
  }
  if (entry.player !== player) {
    return;
  }
  entry.loaded = true;
  entry.retries = 0;
  applyAudio();
  setStreamStatus(entry, "LIVE", false);
  if (gridGateOpen) {
    playEntry(entry);
  } else {
    onPlayerReady();
  }
  if (state.focused < 0) {
    setStatusText("All three feeds are live");
  }
}

// Every feed uses the chosen quality all the time. Downgrading hidden tiles
// to sd meant a mini or feed switch played its sd buffer first and only then
// stepped up to hd - visible as a blurry picture for a second or two.
function applyAllQualities() {
  for (const entry of players) {
    applyQuality(entry.player, state.quality);
  }
}

// sd asks for 480 lines. The origin publishes 1080, 720, 360 and 180, so the
// rule takes the smallest rendition that is at least 480 lines - 720p here -
// and never falls to the half-height ones.
const SD_MIN_HEIGHT = 480;

function qualityHeights(tracks) {
  const heights = tracks.filter((track) => track.height > 0).map((track) => track.height);
  heights.sort((a, b) => a - b);
  if (!heights.length) {
    return null;
  }
  return {
    hd: heights[heights.length - 1],
    sd: heights.filter((h) => h >= SD_MIN_HEIGHT)[0] || heights[0],
  };
}

// The buttons say what will actually be played, not what was asked for.
const QUALITY_LABELS = { hd: "HD", sd: "SD" };

function updateQualityLabels(tracks) {
  const heights = qualityHeights(tracks);
  if (!heights) {
    return;
  }
  for (const btn of qualityButtons) {
    const quality = btn.dataset.quality;
    const height = heights[quality];
    if (!height) {
      continue;
    }
    // The word is what the operator chooses; the exact rendition stays in
    // the tooltip and the accessible name.
    btn.textContent = QUALITY_LABELS[quality] || quality;
    const detail = QUALITY_LABELS[quality] + " quality, " + height + "p";
    btn.title = detail;
    btn.setAttribute("aria-label", detail);
  }
}

function applyQuality(player, quality) {
  if (!player) {
    return;
  }
  player.configure({ abr: { enabled: false } });
  const tracks = player.getVariantTracks().filter((track) => track.height > 0);
  if (!tracks.length) {
    return;
  }
  updateQualityLabels(tracks);
  // hd takes the top rendition the feed offers - 1080p when the origin has
  // one - and sd takes the smallest one that is at least 480 lines.
  const heights = qualityHeights(tracks);
  const chosen = quality === "hd"
    ? tracks.filter((t) => t.height <= 1080).sort((a, b) => b.height - a.height)[0]
      || tracks.sort((a, b) => b.height - a.height)[0]
    : tracks.filter((t) => t.height === heights.sd).sort((a, b) => a.bandwidth - b.bandwidth)[0]
      || tracks.sort((a, b) => a.height - b.height)[0];
  // Keep the buffer on the rare case the variant does change (the operator
  // picks a different default quality); clearBuffer = true threw it away and
  // stalled the picture.
  if (chosen.active) {
    return;
  }
  player.selectVariantTrack(chosen, false);
}

function updateQualityButtons() {
  for (const btn of qualityButtons) {
    const active = btn.dataset.quality === state.quality;
    btn.classList.toggle("active", active);
    btn.setAttribute("aria-pressed", active ? "true" : "false");
  }
}

function setQuality(quality, persist) {
  state.quality = normalizeQuality(quality);
  updateQualityButtons();
  applyAllQualities();
  if (persist && window.Pbb) {
    Pbb.setDefaultQuality(state.quality);
  }
  setStatusText(`Default quality: ${state.quality}`);
}

function updateControls() {
  backBtn.disabled = state.pip;
  backBtn.classList.toggle("hidden", state.focused < 0 && !state.pip);

  for (const [index, entry] of players.entries()) {
    const card = entry.card;
    const pipBtn = card.querySelector('[data-role="pip"]');
    pipBtn.classList.toggle("hidden", state.pip || state.fullscreen);
    pipBtn.disabled = state.pip;

    const fsBtn = card.querySelector('[data-role="fs"]');
    fsBtn.classList.toggle("hidden", state.pip || state.fullscreen);
    fsBtn.disabled = state.pip;

    // Recording is per camera now: each card owns its own tally light and stop
    // button, and the set survives whichever camera is focused.
    const armed = state.recording.has(index);
    const recordBtn = card.querySelector('[data-role="record"]');
    recordBtn.classList.toggle("recording", armed);
    recordBtn.setAttribute("aria-pressed", armed ? "true" : "false");
    card.classList.toggle("rec", armed);

    const soundBtn = card.querySelector('[data-role="sound"]');
    soundBtn.classList.toggle("active", state.sound === index);

    setIcon(pipBtn, "pip", `picture-in-picture ${entry.stream.name}`);
    setIcon(fsBtn, "fullscreen", `fullscreen ${entry.stream.name}`);
    setIcon(recordBtn, armed ? "stop" : "record",
      armed ? `stop recording ${entry.stream.name}` : `record ${entry.stream.name}`);
    setIcon(soundBtn, state.sound === index ? "soundOn" : "soundOff",
      state.sound === index ? `mute ${entry.stream.name}` : `sound ${entry.stream.name}`);
  }

  updateDeckControls();
}

// The fullscreen deck is its own little state machine: the keys swap glyphs,
// the lock changes word, and the header follows the focused feed.
function updateDeckControls() {
  const lockBtn = document.getElementById("fsLock");
  setDeckIcon(lockBtn, state.locked ? "lock" : "unlock",
    state.locked ? "unlock controls" : "lock controls");
  const lockLabel = lockBtn && lockBtn.querySelector(".fs-label");
  if (lockLabel) {
    lockLabel.textContent = state.locked ? "unlock" : "lock";
  }

  const miniBtn = document.getElementById("fsMini");
  if (miniBtn) {
    miniBtn.classList.toggle("active", state.mini >= 0);
    setDeckIcon(miniBtn, "mini", state.mini >= 0 ? "hide mini feed" : "mini feed");
  }

  const zoomBtn = document.getElementById("fsZoom");
  if (zoomBtn) {
    zoomBtn.classList.toggle("active", zoom.mode);
    setDeckIcon(zoomBtn, "zoom", zoom.mode ? "zoom off" : "zoom");
  }

  setDeckIcon(document.getElementById("fsSwitch"), "next", "next live feed");
  setDeckIcon(document.getElementById("fsExit"), "exit", "exit fullscreen");

}

// m:ss, or h:mm:ss once a recording passes the hour. Tabular figures in the
// stylesheet keep the tally from jittering as the digits change.
// One grade, after hours. There is no light theme and no toggle: the app is
// a monitor wall and the room stays dark.
function applyDarkShell() {
  document.documentElement.setAttribute("data-theme", "dark");
  const meta = document.getElementById("themeColor");
  if (meta) {
    meta.setAttribute("content", "#0E0E0F");
  }
}

function formatClock(totalSeconds) {
  const total = Math.max(0, Math.floor(Number(totalSeconds) || 0));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  if (hours > 0) {
    return `${hours}:${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
  }
  return `${minutes}:${String(seconds).padStart(2, "0")}`;
}

// One ticker for every tally and the footer badge. It only touches text, so a
// per-second update is cheaper than re-running updateControls.
function updateTimers() {
  const anyRecording = state.recording.size > 0;
  document.body.classList.toggle("recording", anyRecording);
  const now = Date.now();
  let longest = 0;
  for (const [index, entry] of players.entries()) {
    const started = state.recordStarted.get(index);
    const armed = state.recording.has(index) && Number.isFinite(started);
    if (!armed) {
      if (entry.tallyTime.textContent) {
        entry.tallyTime.textContent = "";
      }
      continue;
    }
    const seconds = Math.max(0, (now - started) / 1000);
    longest = Math.max(longest, seconds);
    entry.tallyTime.textContent = formatClock(seconds);
  }
  if (!recBadge || !recCount || !recTime) {
    return;
  }
  recBadge.classList.toggle("hidden", !anyRecording);
  if (!anyRecording) {
    return;
  }
  recCount.textContent = String(state.recording.size);
  recTime.textContent = formatClock(longest);
}

// The focused feed carries the sound, and the mini tile is audible over it, so
// you can hear both cameras at once. Nothing is audible when sound is muted.
//
// The level is what changes, never the muted flag: a browser builds the audio
// renderer when an element is unmuted and drops it again when it is muted, and
// that rebuild is what the picture waited for - the feed visibly stalled
// every time the sound was switched. A feed that could not start with sound
// at all keeps the old way round.
function applyAudio() {
  for (const [i, entry] of players.entries()) {
    const audible = i === state.sound || (i === state.mini && state.sound >= 0);
    if (!entry.audioBlocked) {
      entry.video.muted = false;
    } else {
      entry.video.muted = !audible;
    }
    entry.video.volume = audible ? 1 : 0;
  }
}

function ensureSound(index) {
  if (index < 0 || !players[index]) {
    return;
  }
  state.sound = index;
  applyAudio();
  updateControls();
}

function setSound(index) {
  if (state.sound === index) {
    state.sound = -1;
    applyAudio();
    updateControls();
    return;
  }
  ensureSound(index);
}

// Every feed keeps playing. A paused video stops decoding, so opening the mini
// tile or switching feeds showed a black frame until it buffered again. The
// hidden cards are rendered in a 1px offscreen box (see styles.css), which keeps
// their frames live; audio is still routed by applyAudio().
function playOnly() {
  for (const entry of players) {
    // A second play() call on a feed that is already running makes the
    // picture restart on some devices. PiP announces its state twice, so the
    // guard is what keeps the small window from stuttering.
    if (entry.video.paused) {
      entry.video.play().catch(() => {});
    }
  }
}

function focusStream(index, skipQuality) {
  if (state.pip) {
    return;
  }
  state.focused = index;
  // A card can not be the big feed and the tile at once; drop the tile rather
  // than letting one element carry both layouts.
  if (state.mini === index) {
    for (const entry of players) {
      entry.card.classList.remove("mini");
    }
    state.mini = -1;
  }
  // The arriving feed starts unzoomed, so zoom mode and its highlight go with it.
  zoom.mode = false;
  resetZoom();
  document.body.classList.add("focus-mode");
  for (const [i, entry] of players.entries()) {
    entry.card.classList.toggle("focused", i === index);
  }
  playOnly();
  ensureSound(index);
  if (!skipQuality) {
    applyAllQualities();
  }
  if (window.Pbb) {
    Pbb.setFocusedStream(index);
  }
  updateControls();
}

// The All Access camera is the one carrying the housemates' microphones, so it
// owns the sound whenever no single feed is focused.
function mainStreamIndex() {
  const index = players.findIndex((entry) => /all access/i.test(entry.stream.name));
  return index >= 0 ? index : 0;
}

function backToGrid() {
  if (state.pip) {
    return true;
  }
  state.focused = -1;
  ensureSound(mainStreamIndex());
  document.body.classList.remove("focus-mode");
  for (const entry of players) {
    entry.card.classList.remove("focused");
  }
  applyAllQualities();
  for (const entry of players) {
    entry.video.play().catch(() => {});
  }
  if (window.Pbb) {
    Pbb.clearFocusedStream();
  }
  setStatusText(state.keysStale ? "Keys changed. Choose a server" : "");
  updateControls();
  return true;
}

// One click from the grid: focus the feed and open it fullscreen.
function fullscreenStream(index) {
  if (state.pip) {
    return;
  }
  if (state.focused !== index) {
    focusStream(index);
  }
  if (!state.fullscreen) {
    toggleFullscreen();
  }
}

function feedLabel(index) {
  const entry = players[index];
  return `${String(index + 1).padStart(2, "0")} - ${entry ? entry.stream.name : "No feed"}`;
}

function enterPip(index) {
  if (state.fullscreen) {
    // Keep the focus: the small window shows the same feed as fullscreen.
    toggleFullscreen(true);
  }
  if (state.focused !== index) {
    focusStream(index);
  }
  ensureSound(index);
  setStatusText("Opening picture-in-picture");
  Pbb.enterPip();
}

function toggleFullscreen(keepFocus) {
  if (state.pip) {
    return;
  }
  if (state.focused < 0) {
    setStatusText("Choose a feed first");
    return;
  }
  state.fullscreen = !state.fullscreen;
  document.body.classList.toggle("fullscreen-mode", state.fullscreen);
  if (!state.fullscreen) {
    zoom.mode = false;
    resetZoom();
    state.mini = -1;
    for (const entry of players) {
      entry.card.classList.remove("mini");
    }
    applyAudio();
  }
  document.body.classList.remove("controls-visible");
  if (hideControlsTimer) {
    clearTimeout(hideControlsTimer);
    hideControlsTimer = null;
  }
  if (window.Pbb) {
    // The host owns the orientation: portrait on the grid, landscape for the
    // fullscreen picture.
    Pbb.setImmersive(state.fullscreen);
  }
  if (state.fullscreen && players[state.focused]) {
    // A short chip names the feed as it opens. The keypad stays away until the
    // viewer taps the picture, so fullscreen starts as nothing but the feed.
    showIndicator(feedLabel(state.focused), 1200);
  }
  playOnly();
  updateControls();
  // Leaving fullscreen drops the focus with it, so land back on the grid
  // instead of a single focused feed. PiP asks to keep it.
  if (!state.fullscreen && keepFocus !== true) {
    backToGrid();
  }
}

// A second feed in the corner of fullscreen: the card is the same element,
// only moved and resized by CSS, so its video keeps playing.
function setMini(index) {
  state.mini = index;
  for (const [i, entry] of players.entries()) {
    entry.card.classList.toggle("mini", i === index);
  }
  if (index >= 0) {
    players[index].video.play().catch(() => {});
  } else {
    playOnly();
  }
  applyAudio();
  applyAllQualities();
  updateControls();
}

function toggleMini() {
  if (!state.fullscreen) {
    return;
  }
  if (state.mini >= 0) {
    setMini(-1);
    return;
  }
  setMini((state.focused + 1) % players.length);
}

function swapMini() {
  if (!state.fullscreen || state.mini < 0) {
    return;
  }
  const previous = state.focused;
  // Both states must be in place before quality is re-applied, or the tile that
  // is about to become the mini reselects its variant and clears its buffer twice.
  focusStream(state.mini, true);
  setMini(previous);
}

// Stay in fullscreen and move to the next live feed.
function switchFullscreen() {
  if (!state.fullscreen || state.pip || players.length < 2) {
    return;
  }
  const next = (state.focused + 1) % players.length;
  if (next === state.mini) {
    // The tile is already the next feed: promote it and hand it the old big
    // feed, instead of making one card both the big feed and the tile.
    swapMini();
    showIndicator(feedLabel(state.focused), 1000);
    showControls();
    return;
  }
  focusStream(next);
  showIndicator(feedLabel(next), 1000);
  showControls();
}

function markStopping(index) {
  state.stopping.add(index);
  // A stop can take a few seconds while the pull ends. Never leave the button
  // disabled forever if the service never answers.
  setTimeout(() => {
    if (state.stopping.delete(index)) {
      updateControls();
      updateTimers();
    }
  }, 20000);
}

function toggleRecord(index) {
  if (!window.Pbb) {
    return;
  }
  const entry = players[index];
  if (!entry) {
    return;
  }
  if (state.recording.has(index)) {
    if (!state.stopping.has(index)) {
      markStopping(index);
    }
    // The tally clears at once; the service answers again when the file is
    // actually saved. A stop is idempotent, so the key stays live: another
    // tap re-sends it if a blocked read kept the worker busy.
    state.recording.delete(index);
    setStatusText(`Stopping ${entry.stream.name}`);
    Pbb.stopRecording(index);
  } else if (state.stopping.has(index)) {
    setStatusText(`Saving ${entry.stream.name}`);
  } else if (state.keysStale) {
    // The keys already rotated, so a recording now would be remuxed with the
    // wrong key and land in Movies/PBB as a file that will not play.
    setStatusText("Keys changed. Choose a server before recording");
  } else {
    Pbb.markStackPair(-1, -1);
    setStatusText(`Starting ${entry.stream.name}`);
    Pbb.startRecording(index, entry.stream.name);
  }
  updateControls();
}

// Java is the source of truth: a start is confirmed with the stream index, a
// stop clears it, and the elapsed time comes back so a rebuilt page can show
// the running tally instead of starting at zero.
window.onRecordingState = (payload) => {
  const update = payload && typeof payload === "object" ? payload : {};
  const index = Number(update.index);
  const kind = String(update.kind || "");
  const message = String(update.message || "");
  const elapsed = Math.max(0, Number(update.elapsed) || 0);
  const known = Number.isInteger(index) && index >= 0 && index < players.length;

  if (known) {
    if (update.active) {
      if (!state.recording.has(index)) {
        state.recordStarted.set(index, Date.now() - elapsed * 1000);
      }
      state.recording.add(index);
      state.stopping.delete(index);
    } else {
      state.recording.delete(index);
      state.recordStarted.delete(index);
      if (kind !== "notice") {
        state.stopping.delete(index);
      }
    }
  }

  if (kind === "done" || kind === "error" || kind === "notice") {
    if (message) {
      setTransientStatus(message, kind === "error" ? 6500 : 4500);
    }
  }

  if (stitch.active && STITCH_FEEDS.every((i) => !state.recording.has(i))) {
    stitch.active = false;
    updateStitchButton();
  }
  updateTimers();
  updateControls();
  // The footer copy belongs to the recording count unless a transient message
  // (saved, failed, notice), a key warning or a refresh owns it right now.
  if (!transientStatusTimer && statusBelongsToRecording()) {
    setStatusText("");
  }
};

// True when the footer is showing idle copy, a recording count, or a start/stop
// message: all places that the next recording state should overwrite. Anything
// else (a save result, a key warning, a quality change) stays put.
function statusBelongsToRecording() {
  const text = statusText.textContent;
  return !text
    || text === "All three feeds are live"
    || text === IDLE_HINT
    || /^Recording \d+ camera/.test(text)
    || text.startsWith("Starting ")
    || text.startsWith("Stopping ");
}

window.setPipMode = (enabled) => {
  document.body.classList.toggle("pip-mode", enabled);
  if (enabled) {
    if (state.focused < 0) {
      // focusStream refuses to run while state.pip is set, so the flag goes up
      // after the focus and not before. Otherwise the page comes back from a
      // killed app with no feed focused and reads players[-1].
      focusStream(0);
    }
    state.pip = true;
    ensureSound(state.focused);
    playOnly();
    setStatusText(`Picture-in-picture: ${players[state.focused].stream.name}`);
  } else {
    state.pip = false;
    // The small window is gone, so land on the three-feed grid with the sound
    // back on All Access. There is no "back from PiP" focus view any more.
    backToGrid();
    return;
  }
  updateControls();
};

window.handleBack = () => {
  if (state.pip) {
    return true;
  }
  if (state.locked) {
    showControls();
    return true;
  }
  if (state.fullscreen) {
    toggleFullscreen();
    return true;
  }
  if (state.focused >= 0) {
    return backToGrid();
  }
  return false;
};

async function resyncStreams() {
  const olds = players.map((p) => p.player);
  for (const entry of players) {
    entry.player = null;
    if (entry.retryTimer) {
      clearTimeout(entry.retryTimer);
      entry.retryTimer = null;
    }
    entry.retries = 0;
    setStreamStatus(entry, "Connecting", false);
  }
  await Promise.all(olds.map((p) => (p ? p.destroy().catch(() => {}) : Promise.resolve())));
  for (const [i] of players.entries()) {
    initPlayer(players[i], i);
  }
}

// Pushed by Java after the live stream config is fetched. Rebuild the Shaka
// players so both playback and the recorder use the current clear keys.
function segmentBase(stream) {
  return (stream && (stream.base || stream.url)) || "";
}

window.onStreamConfig = async (raw) => {
  let cfg;
  try {
    cfg = JSON.parse(raw);
  } catch {
    return;
  }
  const streams = cfg && cfg.streams;
  if (!Array.isArray(streams) || !players.length || streams.length !== players.length) {
    return;
  }
  for (const s of streams) {
    if (!s || !s.url || !/^[0-9a-f]{32}$/i.test(s.kid || "") || !/^[0-9a-f]{32}$/i.test(s.key || "")) {
      return;
    }
  }
  let changed = false;
  for (const [i, s] of streams.entries()) {
    const cur = players[i].stream;
    // The site rewrites publishTime on every handshake, so the encoded
    // manifest is different every time even when nothing else is. Identity is
    // the key pair plus where the segments come from.
    if (cur.kid !== s.kid || cur.key !== s.key || segmentBase(cur) !== segmentBase(s)) {
      changed = true;
    }
  }
  if (!changed) {
    if (manualKeyRefresh) {
      manualKeyRefresh = false;
      if (state.keyRetry) {
        // The site still serves the key that just failed, so stop asking.
        state.keyRetry = false;
        setStatusText("Keys changed. Choose a server");
        return;
      }
      // Keys are current. Seeking every feed to the live edge here froze all
      // three tiles for a couple of seconds; the startup gate and shared
      // presentation delay already keep them together, so leave playback be.
      setTransientStatus("Feeds are already in sync");
    }
    return;
  }
  manualKeyRefresh = false;
  state.keysStale = false;
  // A fresh rotation gets its own automatic retry.
  state.keyRetry = false;
  for (const [i, s] of streams.entries()) {
    players[i].stream = s;
  }
  await resyncStreams();
  setStatusText("Keys updated. Reconnecting feeds");
};

// ------------------------------------------------------------------- themes

const THEME_KEY = "pbb_theme";
const THEMES = ["gold", "blue", "green", "violet"];
const THEME_NAMES = {
  gold: "Brand gold",
  blue: "Blue hour",
  green: "Green room",
  violet: "Night violet",
};
const theme = { current: "gold" };

function loadTheme() {
  try {
    const saved = window.localStorage && window.localStorage.getItem(THEME_KEY);
    return THEMES.includes(saved) ? saved : "gold";
  } catch {
    return "gold";
  }
}

function markThemeChoice() {
  const row = document.getElementById("themeOptions");
  if (!row) {
    return;
  }
  for (const option of row.children) {
    const active = option.dataset.theme === theme.current;
    option.classList.toggle("active", active);
    option.setAttribute("aria-checked", active ? "true" : "false");
  }
}

function applyTheme(name, persist = false) {
  const pick = THEMES.includes(name) ? name : "gold";
  theme.current = pick;
  document.documentElement.setAttribute("data-theme", pick);
  markThemeChoice();
  if (persist) {
    try {
      if (window.localStorage) {
        window.localStorage.setItem(THEME_KEY, pick);
      }
    } catch {
      // The theme still applies for this session.
    }
  }
}

function openThemeSheet() {
  const sheet = document.getElementById("themeSheet");
  if (!sheet) {
    return;
  }
  markThemeChoice();
  sheet.classList.remove("hidden");
}

function closeThemeSheet() {
  const sheet = document.getElementById("themeSheet");
  if (sheet) {
    sheet.classList.add("hidden");
  }
}

function wireThemeSheet() {
  const sheet = document.getElementById("themeSheet");
  if (!sheet) {
    return;
  }
  const close = document.getElementById("themeClose");
  if (close) {
    setIcon(close, "close", "close themes");
    close.addEventListener("click", closeThemeSheet);
  }
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      closeThemeSheet();
    }
  });
  const options = document.getElementById("themeOptions");
  if (options) {
    for (const option of options.children) {
      option.addEventListener("click", () => {
        applyTheme(option.dataset.theme, true);
        setTransientStatus(`Theme: ${THEME_NAMES[theme.current]}`);
        closeThemeSheet();
      });
    }
  }
}

applyTheme(loadTheme());

// ------------------------------------------------------------------- servers

// Six names from pbbcollab.com/live, plus the direct route from
// pbb-3.pages.dev. The app stores the pick, so it survives a restart.
const serverSheet = { server: "s1" };
const liveSheet = { server: "s1" };

const SERVERS = ["s1", "pbbtop", "s2", "s3", "s4", "twitch", "pbb3"];

const SERVER_NAMES = {
  s1: "Server Jeo",
  pbbtop: "Server Gehlee",
  s2: "Server Nathan",
  s3: "Server Primo",
  s4: "Server Bela",
  twitch: "Twitch Mirror",
  pbb3: "PBB3.0",
};

const LIVE_SERVERS = ["s1", "s2", "s3", "s4", "s5", "s6"];

const LIVE_SERVER_NAMES = {
  s1: "Server 1",
  s2: "Server 2",
  s3: "Server 3",
  s4: "Server 4",
  s5: "Server 5",
  s6: "Server 6",
};

// Java owns the persisted pick. Read it before the first status line is drawn;
// otherwise a stored PBB3.0 route reads as Server Jeo until the sheet opens.
function loadServerChoice() {
  if (!window.Pbb) {
    return;
  }
  if (typeof Pbb.getStreamServer === "function") {
    const stored = Pbb.getStreamServer();
    serverSheet.server = SERVERS.includes(stored) ? stored : "s1";
  }
  if (typeof Pbb.getLiveServer === "function") {
    const stored = Pbb.getLiveServer();
    liveSheet.server = LIVE_SERVERS.includes(stored) ? stored : "s1";
  }
}

function sourceField(id) {
  return document.getElementById(id);
}

function setSourceNote(message) {
  const note = sourceField("sourceNote");
  if (note) {
    note.textContent = message;
  }
}

function markServerChoice() {
  const row = sourceField("serverChips");
  if (!row) {
    return;
  }
  const siteActive = !usingLiveSource();
  for (const chip of row.children) {
    const active = siteActive && chip.dataset.server === serverSheet.server;
    chip.classList.toggle("active", active);
    chip.setAttribute("aria-checked", active ? "true" : "false");
  }
}

function openSourceSheet() {
  const sheet = sourceField("sourceSheet");
  if (!sheet) {
    return;
  }
  loadServerChoice();
  const live = usingLiveSource();
  markServerChoice();
  markLiveServerChoice();
  setSourceNote(live
    ? ("pbbcollab.live · " + LIVE_SERVER_NAMES[liveSheet.server])
    : (serverSheet.server === "pbb3"
      ? ("pbb-3.pages.dev · " + SERVER_NAMES[serverSheet.server])
      : ("pbbcollab.com · " + SERVER_NAMES[serverSheet.server])));
  sheet.classList.remove("hidden");
}

function closeSourceSheet() {
  const sheet = sourceField("sourceSheet");
  if (sheet) {
    sheet.classList.add("hidden");
  }
}

function selectServer(server) {
  const pick = SERVERS.includes(server) ? server : "s1";
  if (!window.Pbb || typeof Pbb.setStreamServer !== "function") {
    setSourceNote("Android bridge unavailable");
    return;
  }
  const error = Pbb.setStreamServer(pick);
  if (error) {
    setSourceNote(error);
    return;
  }
  serverSheet.server = pick;
  markServerChoice();
  markLiveServerChoice();
  closeSourceSheet();
  setTransientStatus("Switching to " + SERVER_NAMES[pick]);
}

function markLiveServerChoice() {
  const row = sourceField("liveServerChips");
  if (!row) {
    return;
  }
  const liveActive = usingLiveSource();
  for (const chip of row.children) {
    const active = liveActive && chip.dataset.liveServer === liveSheet.server;
    chip.classList.toggle("active", active);
    chip.setAttribute("aria-checked", active ? "true" : "false");
  }
}

function selectLiveServer(server) {
  const pick = LIVE_SERVERS.includes(server) ? server : "s1";
  if (!window.Pbb || typeof Pbb.setLiveServer !== "function") {
    setSourceNote("Android bridge unavailable");
    return;
  }
  const error = Pbb.setLiveServer(pick);
  if (error) {
    setSourceNote(error);
    return;
  }
  liveSheet.server = pick;
  markServerChoice();
  markLiveServerChoice();
  setSourceNote("pbbcollab.live · " + LIVE_SERVER_NAMES[pick]);
  closeSourceSheet();
  setTransientStatus("Switching to " + LIVE_SERVER_NAMES[pick]);
}

// A WebView cannot copy DRM-protected video frames into a canvas: every
// captured frame comes back black. Record both feeds with the native service
// instead; the two tallies and stop buttons are the recording indicators.
const STITCH_FEEDS = [0, 1];
const stitch = { active: false };

function updateStitchButton() {
  const btn = document.getElementById("stitchBtn");
  if (!btn) {
    return;
  }
  btn.classList.toggle("stitch-active", stitch.active);
  setStatusKey(btn, stitch.active ? "stop" : "stitch",
    stitch.active ? "stop both recordings" : "record main and dining together");
}

function toggleStitch() {
  if (!window.Pbb) {
    return;
  }
  if (stitch.active) {
    stitch.active = false;
    for (const index of STITCH_FEEDS) {
      Pbb.stopRecording(index);
    }
    setTransientStatus("Saving stacked video");
  } else if (state.keysStale) {
    setTransientStatus("Keys changed. Choose a server before recording");
  } else {
    stitch.active = true;
    Pbb.markStackPair(STITCH_FEEDS[0], STITCH_FEEDS[1]);
    for (const index of STITCH_FEEDS) {
      Pbb.startRecording(index, players[index].stream.name);
    }
    setTransientStatus("Recording a stacked video");
  }
  updateStitchButton();
}

function wireSourceSheet() {
  const sheet = sourceField("sourceSheet");
  if (!sheet) {
    return;
  }
  sheet.addEventListener("click", (event) => {
    if (event.target === sheet) {
      closeSourceSheet();
    }
  });
  const close = sourceField("sourceClose");
  if (close) {
    setIcon(close, "close", "close servers");
    close.addEventListener("click", closeSourceSheet);
  }
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      closeSourceSheet();
    }
  });
  const row = sourceField("serverChips");
  if (row) {
    for (const chip of Array.from(row.children)) {
      chip.addEventListener("click", () => selectServer(chip.dataset.server));
    }
  }
  const liveRow = sourceField("liveServerChips");
  if (liveRow) {
    for (const chip of Array.from(liveRow.children)) {
      chip.addEventListener("click", () => selectLiveServer(chip.dataset.liveServer));
    }
  }
}

function boot() {
  grid = document.getElementById("grid");
  backBtn = document.getElementById("backBtn");
  statusText = document.getElementById("statusText");
  statusMeta = document.getElementById("statusMeta");
  qualityButtons = Array.from(document.querySelectorAll("[data-quality]"));
  recBadge = document.getElementById("recBadge");
  recCount = document.getElementById("recCount");
  recTime = document.getElementById("recTime");

  document.addEventListener("keydown", () => document.body.classList.add("keyboard-focus"), true);
  document.addEventListener("pointerdown", () => document.body.classList.remove("keyboard-focus"), true);
  applyDarkShell();
  // Mount the chrome before any availability check: even a failed start has
  // to show a legible deck rather than four empty key slots.
  const refreshBtn = document.getElementById("refreshBtn");
  const sourceBtn = document.getElementById("sourceBtn");
  const themeBtn = document.getElementById("themeBtn");
  const stitchBtn = document.getElementById("stitchBtn");
  setStatusKey(refreshBtn, "refresh", "Refresh streams");
  setStatusKey(sourceBtn, "server", "Choose a stream server");
  setIcon(themeBtn, "palette", "Choose a color theme");
  setStatusKey(backBtn, "grid", "Back to grid");
  updateStitchButton();

  if (!window.Pbb) {
    setStatusText("Android bridge unavailable");
    return;
  }
  if (!window.shaka || !shaka.Player.isBrowserSupported()) {
    setStatusText("Playback is not supported");
    return;
  }

  shaka.polyfill.installAll();

  let config;
  try {
    config = JSON.parse(Pbb.getConfig());
  } catch {
    setStatusText("Stream settings are invalid");
    return;
  }

  state.quality = normalizeQuality(Pbb.getDefaultQuality());
  loadServerChoice();
  updateStatusMeta();
  const systemVolume = Number(Pbb.getVolume());
  if (!Number.isNaN(systemVolume)) {
    state.volume = Math.min(1, Math.max(0, systemVolume));
  }
  // -1 means the app has never stored a brightness, so the 0.5 default stands.
  const storedBrightness = Number(Pbb.getBrightness());
  if (!Number.isNaN(storedBrightness) && storedBrightness >= 0) {
    state.brightness = Math.min(1, Math.max(0, storedBrightness));
  }
  for (const [index, stream] of config.streams.entries()) {
    createCard(stream, index);
  }
  updateQualityButtons();

  gridGateOpen = false;
  for (const [index, entry] of players.entries()) {
    initPlayer(entry, index);
  }
  gridStartTimer = setTimeout(startGridTogether, 5000);

  for (const btn of qualityButtons) {
    btn.addEventListener("click", () => setQuality(btn.dataset.quality, true));
  }
  backBtn.addEventListener("click", backToGrid);
  if (refreshBtn) {
    refreshBtn.addEventListener("click", refreshKeysFromUi);
  }
  if (sourceBtn) {
    sourceBtn.addEventListener("click", openSourceSheet);
  }
  if (themeBtn) {
    themeBtn.addEventListener("click", openThemeSheet);
  }
  if (stitchBtn) {
    stitchBtn.addEventListener("click", toggleStitch);
  }
  wireSourceSheet();
  wireThemeSheet();
  indicator = document.getElementById("indicator");
  const fsLock = document.getElementById("fsLock");
  if (fsLock) {
    fsLock.addEventListener("click", toggleLock);
  }
  const fsZoom = document.getElementById("fsZoom");
  if (fsZoom) {
    fsZoom.addEventListener("click", toggleZoom);
  }
  const fsExit = document.getElementById("fsExit");
  if (fsExit) {
    fsExit.addEventListener("click", () => toggleFullscreen());
  }
  const fsSwitch = document.getElementById("fsSwitch");
  if (fsSwitch) {
    fsSwitch.addEventListener("click", switchFullscreen);
  }
  const fsMini = document.getElementById("fsMini");
  if (fsMini) {
    fsMini.addEventListener("click", toggleMini);
  }
  const fsDeck = document.querySelector(".fs-controls");
  if (fsDeck) {
    // Using a key keeps the deck up for another few seconds instead of
    // letting the idle timer pull it out from under the next tap.
    fsDeck.addEventListener("click", () => {
      if (state.fullscreen) {
        showControls();
      }
    });
  }
  restoreRecordingState();
  if (Pbb.isInPip()) {
    window.setPipMode(true);
  }
  updateControls();
  updateTimers();
  setInterval(updateTimers, 1000);
}

// The page can be destroyed while recordings keep running (the service is
// separate), so rebuild the set and the elapsed clocks from the service.
function restoreRecordingState() {
  if (!window.Pbb || typeof Pbb.recordingState !== "function") {
    return;
  }
  let parsed;
  try {
    parsed = JSON.parse(Pbb.recordingState());
  } catch {
    return;
  }
  const list = parsed && parsed.streams;
  if (!Array.isArray(list)) {
    return;
  }
  const now = Date.now();
  for (const item of list) {
    const index = Number(item && item.index);
    if (!Number.isInteger(index) || index < 0 || index >= players.length) {
      continue;
    }
    const elapsed = Math.max(0, Number(item.elapsed) || 0);
    state.recording.add(index);
    state.recordStarted.set(index, now - elapsed * 1000);
  }
}

document.addEventListener("DOMContentLoaded", boot);
