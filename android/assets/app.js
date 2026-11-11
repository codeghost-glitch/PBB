const state = {
  quality: "auto",
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
  mini: -1,
};

let manualKeyRefresh = false;
let transientStatusTimer = null;

function refreshKeysFromUi() {
  if (!window.Pbb) {
    return;
  }
  manualKeyRefresh = true;
  setStatusText("refreshing streams...");
  Pbb.refreshKeys();
}

window.onKeysMessage = (message) => {
  if (manualKeyRefresh) {
    manualKeyRefresh = false;
    setStatusText(message);
  }
};

const players = [];

let grid;
let backBtn;
let statusText;
let qualityButtons;
let recAllBtn;
let recAllGlyph;
let recAllLabel;
let recAllCount;
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
  return value === "hd" || value === "sd" ? value : "auto";
}

function uuidKid(kid) {
  return kid.replace(/(.{8})(.{4})(.{4})(.{4})(.{12})/, "$1-$2-$3-$4-$5");
}

const IDLE_HINT = "tap refresh to reload the stream keys";

function defaultStatus() {
  const count = state.recording.size;
  if (count === 1) {
    return "recording 1 camera";
  }
  if (count > 1) {
    return `recording ${count} cameras`;
  }
  return IDLE_HINT;
}

function setStatusText(message) {
  // The footer never hides itself: an idle label takes the place of the message.
  statusText.textContent = message || defaultStatus();
  const bar = document.getElementById("statusBar");
  if (bar) {
    bar.classList.remove("hidden");
  }
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
  const hidden = !bad && message === "live";
  entry.statusEl.classList.toggle("hidden", hidden);
  // Lets the short-landscape layout hide the buttons the status would cover.
  if (entry.card) {
    entry.card.classList.toggle("status-on", !hidden);
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
    document.body.classList.remove("controls-visible");
    hideControlsTimer = null;
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
      Pbb.setVolume(clamped);
    }
    showIndicator(`volume ${Math.round(clamped * 100)}%`);
  } else {
    state.brightness = clamped;
    if (window.Pbb) {
      Pbb.setBrightness(clamped);
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
  }
  gesture.tracking = false;
  gesture.active = false;
  gesture.mode = null;
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

  const videoWrap = el("div", "video-wrap", null, null);
  const video = el("video", null, "video", null);
  video.setAttribute("playsinline", "");
  video.setAttribute("webkit-playsinline", "");
  video.muted = true;
  video.autoplay = true;
  video.preload = "auto";
  video.controls = false;
  video.setAttribute("aria-label", `${stream.name} live video`);
  const statusEl = el("div", "stream-status", "status", "connecting...");
  // The tally light: the card that is recording says so on the picture, with
  // the running time, instead of hiding it in a toolbar.
  const tally = el("div", "tally", "tally", null);
  tally.setAttribute("aria-hidden", "true");
  tally.appendChild(el("span", "tally-dot", null, null));
  tally.appendChild(el("span", "tally-label", null, "rec"));
  const tallyTime = el("span", "tally-time", "tally-time", "0:00");
  tally.appendChild(tallyTime);
  videoWrap.appendChild(video);
  videoWrap.appendChild(statusEl);
  videoWrap.appendChild(tally);

  const rack = el("div", "cam-rack", null, null);
  const camId = el("div", "cam-id", null, null);
  camId.appendChild(el("span", "cam-num", null, `0${index + 1}`));
  camId.appendChild(el("span", "cam-title", "name", stream.name));
  const liveBadge = el("span", "live-badge", "badge", null);
  liveBadge.appendChild(el("span", "live-pip", null, null));
  liveBadge.appendChild(document.createTextNode("live"));

  const actions = el("div", "cam-actions", null, null);
  const fsBtn = makeIconButton("btn btn-ghost btn-icon", "fs");
  const soundBtn = makeIconButton("btn btn-ghost btn-icon", "sound");
  const pipBtn = makeIconButton("btn btn-ghost btn-icon", "pip");
  const recordBtn = makeIconButton("btn btn-icon record-btn", "record");
  actions.appendChild(fsBtn);
  actions.appendChild(soundBtn);
  actions.appendChild(pipBtn);
  actions.appendChild(recordBtn);

  rack.appendChild(camId);
  rack.appendChild(liveBadge);
  rack.appendChild(actions);

  card.appendChild(videoWrap);
  card.appendChild(rack);

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

  // Every card starts on "connecting...", so the short-landscape rule that
  // hides the buttons behind the status pill applies from the first frame.
  card.classList.add("status-on");

  grid.appendChild(card);
  players.push({ video, player: null, stream, statusEl, card, tally, tallyTime });
}

// 1001 BAD_HTTP_STATUS, 1002 HTTP_ERROR, 1003 TIMEOUT are transient on live
// DASH. Shaka retries internally, then gives up and fires a fatal error; reload
// with a bounded backoff instead of leaving the tile dead.
const RECOVERABLE_SHAKA = new Set([1001, 1002, 1003]);

function scheduleRecover(entry, index) {
  if (entry.retryTimer) {
    return;
  }
  entry.retries = (entry.retries || 0) + 1;
  const delay = Math.min(30000, 1000 * Math.pow(2, Math.min(entry.retries - 1, 5)));
  setStreamStatus(entry, `reconnecting ${Math.round(delay / 1000)}s`, true);
  entry.retryTimer = setTimeout(async () => {
    entry.retryTimer = null;
    try {
      await entry.player.load(entry.stream.url);
    } catch {
      scheduleRecover(entry, index);
      return;
    }
    entry.retries = 0;
    applyAudio();
    if (state.focused >= 0 && state.focused !== index) {
      entry.video.pause();
    } else {
      entry.video.play().catch(() => {});
    }
    setStreamStatus(entry, "live", false);
  }, delay);
}

async function initPlayer(entry, index) {
  const player = new shaka.Player(entry.video);
  entry.player = player;

  const clearKeys = {};
  clearKeys[entry.stream.kid] = entry.stream.key;
  clearKeys[uuidKid(entry.stream.kid)] = entry.stream.key;

  const retry = () => ({
    maxAttempts: 8,
    baseDelay: 400,
    backoffFactor: 2,
    fuzzFactor: 0.5,
    timeout: 20000,
  });

  player.configure({
    drm: {
      preferredKeySystems: ["org.w3.clearkey"],
      clearKeys,
    },
    streaming: {
      bufferingGoal: 15,
      rebufferingGoal: 2,
      bufferBehind: 25,
      retryParameters: retry(),
    },
    manifest: {
      retryParameters: retry(),
    },
    abr: { enabled: state.quality === "auto" },
  });

  player.addEventListener("error", (event) => {
    const detail = event.detail || {};
    const message = detail.message || detail.code || detail;
    if (Number(detail.code) === 4012) {
      state.keysStale = true;
      setStreamStatus(entry, "keys rotated - tap refresh", true);
      setStatusText("keys rotated - tap refresh to reload");
      return;
    }
    if (RECOVERABLE_SHAKA.has(Number(detail.code))) {
      scheduleRecover(entry, index);
    } else {
      setStreamStatus(entry, `error ${message}`, true);
    }
  });

  player.addEventListener("loaded", () => {
    applyQuality(player, qualityFor(index));
  });

  try {
    await player.load(entry.stream.url);
  } catch {
    scheduleRecover(entry, index);
    return;
  }

  try {
    await entry.video.play();
  } catch {
    entry.video.muted = true;
    if (state.sound === index) {
      state.sound = -1;
      updateControls();
    }
    entry.video.play().catch(() => {});
  }

  applyAudio();
  if (state.focused >= 0 && state.focused !== index) {
    entry.video.pause();
  }
  setStreamStatus(entry, "live", false);
  if (state.focused < 0) {
    setStatusText("all three feeds ready");
  }
}

// hd is 1080p: only the feed being watched needs that many pixels at once, so
// the other tiles drop to sd while one feed is focused.
function qualityFor(index) {
  if (state.quality !== "hd" || state.focused < 0) {
    return state.quality;
  }
  // The big feed and the mini tile are both watched, so they keep hd; only the
  // feeds hidden behind fullscreen drop to sd until they are shown again.
  if (index === state.focused || index === state.mini) {
    return "hd";
  }
  return "sd";
}

function applyAllQualities() {
  for (const [index, entry] of players.entries()) {
    applyQuality(entry.player, qualityFor(index));
  }
}

function applyQuality(player, quality) {
  if (!player) {
    return;
  }
  player.configure({ abr: { enabled: quality === "auto" } });
  if (quality === "auto") {
    return;
  }
  const tracks = player.getVariantTracks().filter((track) => track.height > 0);
  if (!tracks.length) {
    return;
  }
  tracks.sort((a, b) => a.height - b.height);
  const chosen = quality === "hd" ? tracks[tracks.length - 1] : tracks[0];
  // selectVariantTrack(clearBuffer = true) throws the buffer away, which stalls
  // the picture. Swapping feeds re-applies quality to every tile, so skip the
  // ones that already play the variant we would pick.
  if (chosen.active) {
    return;
  }
  player.selectVariantTrack(chosen, true);
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
  setStatusText(`default quality: ${state.quality}`);
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
    const stopping = state.stopping.has(index);
    const recordBtn = card.querySelector('[data-role="record"]');
    recordBtn.classList.toggle("recording", armed);
    recordBtn.disabled = stopping;
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

  const lockBtn = document.getElementById("fsLock");
  setIcon(lockBtn, state.locked ? "lock" : "unlock", state.locked ? "unlock controls" : "lock controls");

  const miniBtn = document.getElementById("fsMini");
  if (miniBtn) {
    miniBtn.classList.toggle("active", state.mini >= 0);
    setIcon(miniBtn, "mini", state.mini >= 0 ? "hide mini feed" : "mini feed");
  }

  const zoomBtn = document.getElementById("fsZoom");
  if (zoomBtn) {
    zoomBtn.classList.toggle("active", zoom.mode);
    setIcon(zoomBtn, "zoom", zoom.mode ? "zoom off" : "zoom");
  }

  if (recAllBtn) {
    const anyRecording = state.recording.size > 0;
    recAllBtn.disabled = state.pip || state.stopping.size > 0;
    recAllBtn.classList.toggle("armed", anyRecording);
    recAllBtn.setAttribute("aria-pressed", anyRecording ? "true" : "false");
    recAllBtn.setAttribute("aria-label",
      anyRecording ? "stop all recordings" : "record all cameras");
    mountIcon(recAllGlyph, anyRecording ? "stop" : "record");
    if (recAllLabel) {
      recAllLabel.textContent = anyRecording ? "stop all" : "record all";
    }
    if (recAllCount) {
      recAllCount.textContent = String(state.recording.size);
      recAllCount.classList.toggle("hidden", !anyRecording);
    }
  }
}

// m:ss, or h:mm:ss once a recording passes the hour. Tabular figures in the
// stylesheet keep the tally from jittering as the digits change.
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
function applyAudio() {
  for (const [i, entry] of players.entries()) {
    entry.video.muted = !(i === state.sound || (i === state.mini && state.sound >= 0));
  }
}

function ensureSound(index) {
  if (index < 0 || !players[index]) {
    return;
  }
  state.sound = index;
  applyAudio();
  players[index].video.volume = 1;
  players[index].video.play().catch(() => {});
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

// The big feed plays, and so does the mini tile: pausing it here is what froze
// the tile whenever the focused feed changed.
function playOnly(index) {
  for (const [i, entry] of players.entries()) {
    if (i === index || i === state.mini) {
      entry.video.play().catch(() => {});
    } else {
      entry.video.pause();
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
  playOnly(index);
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
  setStatusText(state.keysStale ? "keys rotated - tap refresh to reload" : "");
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

function enterPip(index) {
  if (state.fullscreen) {
    // Keep the focus: the small window shows the same feed as fullscreen.
    toggleFullscreen(true);
  }
  if (state.focused !== index) {
    focusStream(index);
  }
  ensureSound(index);
  setStatusText("requesting picture-in-picture");
  Pbb.enterPip();
}

function toggleFullscreen(keepFocus) {
  if (state.pip) {
    return;
  }
  if (state.focused < 0) {
    setStatusText("focus one stream first");
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
    Pbb.setImmersive(state.fullscreen);
    Pbb.setOrientation(state.fullscreen ? "landscape" : "auto");
  }
  playOnly(state.focused);
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
    playOnly(state.focused);
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
  // is about to become the mini drops to sd and immediately back to hd, which
  // clears its buffer twice.
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
    showControls();
    return;
  }
  focusStream(next);
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
  if (!window.Pbb || state.stopping.has(index)) {
    return;
  }
  const entry = players[index];
  if (!entry) {
    return;
  }
  if (state.recording.has(index)) {
    markStopping(index);
    setStatusText(`stopping ${entry.stream.name}`);
    Pbb.stopRecording(index);
  } else {
    setStatusText(`starting ${entry.stream.name}`);
    Pbb.startRecording(index, entry.stream.name);
  }
  updateControls();
}

// The master control records every camera, or stops every camera that is still
// running. A camera already recording is left alone when the batch starts.
function toggleRecordAll() {
  if (!window.Pbb) {
    return;
  }
  if (state.recording.size > 0) {
    for (const index of state.recording) {
      markStopping(index);
    }
    setStatusText("stopping every recording");
    Pbb.stopRecording(-1);
  } else if (state.stopping.size === 0) {
    setStatusText("starting every camera");
    Pbb.recordAll();
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
      state.stopping.delete(index);
    }
  }

  if (kind === "done" || kind === "error" || kind === "notice") {
    if (message) {
      setTransientStatus(message, kind === "error" ? 6500 : 4500);
    }
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
    || text === "all three feeds ready"
    || text === IDLE_HINT
    || /^recording \d+ camera/.test(text)
    || text.startsWith("starting ")
    || text.startsWith("stopping ");
}

window.setPipMode = (enabled) => {
  state.pip = enabled;
  document.body.classList.toggle("pip-mode", enabled);
  if (enabled) {
    if (state.focused < 0) {
      focusStream(0);
    }
    ensureSound(state.focused);
    playOnly(state.focused);
    setStatusText(`picture-in-picture: ${players[state.focused].stream.name}`);
  } else {
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

// Pushed by Java after the live stream config is fetched. Rebuild the Shaka
// players so both playback and the recorder use the current clear keys.
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
    if (cur.url !== s.url || cur.kid !== s.kid || cur.key !== s.key) {
      changed = true;
    }
  }
  if (!changed) {
    if (manualKeyRefresh) {
      manualKeyRefresh = false;
      setTransientStatus("streams already up to date");
    }
    return;
  }
  manualKeyRefresh = false;
  state.keysStale = false;
  const olds = players.map((p) => p.player);
  for (const [i, s] of streams.entries()) {
    players[i].stream = s;
    players[i].player = null;
    if (players[i].retryTimer) {
      clearTimeout(players[i].retryTimer);
      players[i].retryTimer = null;
    }
    players[i].retries = 0;
  }
  await Promise.all(olds.map((p) => (p ? p.destroy().catch(() => {}) : Promise.resolve())));
  for (const [i] of players.entries()) {
    initPlayer(players[i], i);
  }
  setStatusText("keys refreshed - reconnecting streams");
};

function boot() {
  grid = document.getElementById("grid");
  backBtn = document.getElementById("backBtn");
  statusText = document.getElementById("statusText");
  qualityButtons = Array.from(document.querySelectorAll("[data-quality]"));
  recAllBtn = document.getElementById("recAll");
  recAllGlyph = document.querySelector('[data-role="recglyph"]');
  recAllLabel = document.getElementById("recAllLabel");
  recAllCount = document.getElementById("recAllCount");
  recBadge = document.getElementById("recBadge");
  recCount = document.getElementById("recCount");
  recTime = document.getElementById("recTime");

  document.addEventListener("keydown", () => document.body.classList.add("keyboard-focus"), true);
  document.addEventListener("pointerdown", () => document.body.classList.remove("keyboard-focus"), true);

  if (!window.Pbb) {
    setStatusText("android bridge missing");
    return;
  }
  if (!window.shaka || !shaka.Player.isBrowserSupported()) {
    setStatusText("shaka player unsupported");
    return;
  }

  shaka.polyfill.installAll();

  let config;
  try {
    config = JSON.parse(Pbb.getConfig());
  } catch {
    setStatusText("stream config invalid");
    return;
  }

  state.quality = normalizeQuality(Pbb.getDefaultQuality());
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

  for (const [index, entry] of players.entries()) {
    initPlayer(entry, index);
  }

  for (const btn of qualityButtons) {
    btn.addEventListener("click", () => setQuality(btn.dataset.quality, true));
  }
  backBtn.addEventListener("click", backToGrid);
  const refreshBtn = document.getElementById("refreshBtn");
  if (refreshBtn) {
    setIcon(refreshBtn, "refresh", "refresh stream keys");
    refreshBtn.addEventListener("click", refreshKeysFromUi);
  }
  if (recAllBtn) {
    recAllBtn.addEventListener("click", toggleRecordAll);
  }
  indicator = document.getElementById("indicator");
  setIcon(backBtn, "grid", "back to grid");
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
    setIcon(fsExit, "exit", "exit fullscreen");
    fsExit.addEventListener("click", () => toggleFullscreen());
  }
  const fsSwitch = document.getElementById("fsSwitch");
  if (fsSwitch) {
    setIcon(fsSwitch, "next", "next live feed");
    fsSwitch.addEventListener("click", switchFullscreen);
  }
  const fsMini = document.getElementById("fsMini");
  if (fsMini) {
    setIcon(fsMini, "mini", "mini feed");
    fsMini.addEventListener("click", toggleMini);
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
