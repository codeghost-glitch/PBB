const state = {
  quality: "auto",
  focused: -1,
  pip: false,
  recording: -1,
  pendingRecord: -1,
  sound: 0,
  fullscreen: false,
  orientation: "auto",
};

const players = [];
const cards = [];

let grid;
let backBtn;
let statusText;
let qualityButtons;
let hideControlsTimer = null;

function normalizeQuality(raw) {
  const value = String(raw || "").toLowerCase();
  return value === "hd" || value === "sd" ? value : "auto";
}

function uuidKid(kid) {
  return kid.replace(/(.{8})(.{4})(.{4})(.{4})(.{12})/, "$1-$2-$3-$4-$5");
}

function setStatusText(message) {
  statusText.textContent = message;
}

function setStreamStatus(entry, message, bad) {
  entry.statusEl.textContent = message;
  entry.statusEl.classList.toggle("bad", Boolean(bad));
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
  focus: [
    ["path", { d: "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z" }],
    ["circle", { cx: 12, cy: 12, r: 3 }],
  ],
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
  rotate: [
    ["path", { d: "M21 12a9 9 0 1 1-2.6-6.4" }],
    ["path", { d: "M21 3v6h-6" }],
  ],
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
  if (btn.dataset.icon !== name) {
    btn.dataset.icon = name;
    btn.replaceChildren(iconNode(name));
  }
  if (label) {
    btn.setAttribute("aria-label", label);
    btn.title = label;
  }
}

function makeIconButton(className, role) {
  const btn = el("button", className, role, null);
  btn.setAttribute("type", "button");
  return btn;
}

function createCard(stream, index) {
  const card = el("article", "cam-card card shadow", null, null);
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
  videoWrap.appendChild(video);
  videoWrap.appendChild(statusEl);

  const camMeta = el("div", "cam-meta", null, null);
  const titleWrap = el("div", null, null, null);
  titleWrap.appendChild(el("div", "cam-title", "name", stream.name));
  titleWrap.appendChild(el("div", "cam-sub", "feed", `camera feed 0${index + 1}`));
  camMeta.appendChild(titleWrap);
  camMeta.appendChild(el("span", "live-badge", "badge", "live"));

  const actions = el("div", "cam-actions", null, null);
  const focusBtn = makeIconButton("btn btn-ghost btn-icon", "focus");
  const soundBtn = makeIconButton("btn btn-ghost btn-icon", "sound");
  const pipBtn = makeIconButton("btn btn-ghost btn-icon hidden", "pip");
  const fsBtn = makeIconButton("btn btn-ghost btn-icon hidden", "fs");
  const recordBtn = makeIconButton("btn btn-icon record-btn", "record");
  actions.appendChild(focusBtn);
  actions.appendChild(soundBtn);
  actions.appendChild(pipBtn);
  actions.appendChild(fsBtn);
  actions.appendChild(recordBtn);

  card.appendChild(videoWrap);
  card.appendChild(camMeta);
  card.appendChild(actions);

  card.addEventListener("click", (event) => {
    if (event.target.closest("button")) {
      return;
    }
    toggleControls();
  });

  focusBtn.addEventListener("click", () => focusStream(index));
  soundBtn.addEventListener("click", () => setSound(index));
  pipBtn.addEventListener("click", () => enterPip(index));
  fsBtn.addEventListener("click", () => toggleFullscreen());
  recordBtn.addEventListener("click", () => toggleRecord(index));

  grid.appendChild(card);
  cards.push(card);
  players.push({ video, player: null, stream, statusEl });
}

async function initPlayer(entry, index) {
  const player = new shaka.Player(entry.video);
  entry.player = player;

  const clearKeys = {};
  clearKeys[entry.stream.kid] = entry.stream.key;
  clearKeys[uuidKid(entry.stream.kid)] = entry.stream.key;

  player.configure({
    drm: {
      preferredKeySystems: ["org.w3.clearkey"],
      clearKeys,
    },
    streaming: {
      bufferingGoal: 15,
      rebufferingGoal: 2,
      bufferBehind: 25,
      retryParameters: { maxAttempts: 8, baseDelay: 400, timeout: 20000 },
    },
    manifest: {
      retryParameters: { maxAttempts: 5, baseDelay: 400 },
    },
    abr: { enabled: state.quality === "auto" },
  });

  player.addEventListener("error", (event) => {
    const detail = event.detail;
    const message = detail && (detail.message || detail.code || detail);
    setStreamStatus(entry, `error ${message}`, true);
  });

  player.addEventListener("loaded", () => {
    applyQuality(player, state.quality);
  });

  try {
    await player.load(entry.stream.url);
  } catch {
    setStreamStatus(entry, "load failed", true);
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

  entry.video.muted = state.sound !== index;
  setStreamStatus(entry, "live", false);
  setStatusText("all four feeds ready");
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
  for (const entry of players) {
    applyQuality(entry.player, state.quality);
  }
  if (persist && window.Pbb) {
    Pbb.setDefaultQuality(state.quality);
  }
  setStatusText(`default quality: ${state.quality}`);
}

function updateControls() {
  backBtn.disabled = state.recording >= 0 || state.pip;
  backBtn.classList.toggle("hidden", state.focused < 0 && !state.pip);

  for (const [index, card] of cards.entries()) {
    const pipBtn = card.querySelector('[data-role="pip"]');
    pipBtn.classList.toggle("hidden", state.focused !== index || state.pip || state.fullscreen);
    pipBtn.disabled = state.recording >= 0 || state.pip;

    const fsBtn = card.querySelector('[data-role="fs"]');
    fsBtn.classList.toggle("hidden", state.focused !== index || state.pip || state.fullscreen);
    fsBtn.disabled = state.recording >= 0 || state.pip;

    const recordBtn = card.querySelector('[data-role="record"]');
    recordBtn.classList.toggle("recording", state.recording === index);
    recordBtn.disabled = state.pip && state.recording !== index;

    const focusBtn = card.querySelector('[data-role="focus"]');
    focusBtn.disabled = state.pip || state.recording >= 0;
    focusBtn.classList.toggle("active", state.focused === index);

    const soundBtn = card.querySelector('[data-role="sound"]');
    soundBtn.classList.toggle("active", state.sound === index);

    setIcon(pipBtn, "pip", "picture-in-picture");
    setIcon(fsBtn, "fullscreen", "fullscreen");
    setIcon(recordBtn, state.recording === index ? "stop" : "record", state.recording === index ? "stop recording" : "record");
    setIcon(focusBtn, "focus", state.focused === index ? "focused" : "focus");
    setIcon(soundBtn, state.sound === index ? "soundOn" : "soundOff", state.sound === index ? "mute" : "sound");
  }
}

function ensureSound(index) {
  if (index < 0 || !players[index]) {
    return;
  }
  state.sound = index;
  for (const [i, entry] of players.entries()) {
    entry.video.muted = i !== index;
  }
  players[index].video.play().catch(() => {});
  updateControls();
}

function setSound(index) {
  if (state.sound === index) {
    state.sound = -1;
    for (const entry of players) {
      entry.video.muted = true;
    }
    updateControls();
    return;
  }
  ensureSound(index);
}

function playOnly(index) {
  for (const [i, entry] of players.entries()) {
    if (i === index) {
      entry.video.play().catch(() => {});
    } else {
      entry.video.pause();
    }
  }
}

function focusStream(index) {
  if (state.pip) {
    return;
  }
  if (state.recording >= 0 && state.recording !== index) {
    setStatusText("stop recording first");
    return;
  }
  state.focused = index;
  document.body.classList.add("focus-mode");
  for (const [i, card] of cards.entries()) {
    card.classList.toggle("focused", i === index);
  }
  playOnly(index);
  ensureSound(index);
  if (window.Pbb) {
    Pbb.setFocusedStream(index);
  }
  setStatusText(`focused: ${players[index].stream.name}`);
  updateControls();
}

function backToGrid() {
  if (state.pip) {
    return true;
  }
  if (state.recording >= 0) {
    setStatusText("stop recording first");
    return true;
  }
  state.focused = -1;
  document.body.classList.remove("focus-mode");
  for (const card of cards) {
    card.classList.remove("focused");
  }
  for (const entry of players) {
    entry.video.play().catch(() => {});
  }
  if (window.Pbb) {
    Pbb.clearFocusedStream();
  }
  setStatusText("showing all four feeds");
  updateControls();
  return true;
}

function enterPip(index) {
  if (state.recording >= 0) {
    setStatusText("stop recording before pip");
    return;
  }
  if (state.fullscreen) {
    toggleFullscreen();
  }
  if (state.focused !== index) {
    focusStream(index);
  }
  ensureSound(index);
  setStatusText("requesting picture-in-picture");
  Pbb.enterPip();
}

function toggleFullscreen() {
  if (state.pip) {
    return;
  }
  if (state.recording >= 0) {
    setStatusText("stop recording first");
    return;
  }
  if (state.focused < 0) {
    setStatusText("focus one stream first");
    return;
  }
  state.fullscreen = !state.fullscreen;
  document.body.classList.toggle("fullscreen-mode", state.fullscreen);
  document.body.classList.remove("controls-visible");
  if (hideControlsTimer) {
    clearTimeout(hideControlsTimer);
    hideControlsTimer = null;
  }
  if (window.Pbb) {
    Pbb.setImmersive(state.fullscreen);
    if (state.fullscreen) {
      state.orientation = "landscape";
      Pbb.setOrientation("landscape");
    } else {
      state.orientation = "auto";
      Pbb.setOrientation("auto");
    }
  }
  playOnly(state.focused);
  setStatusText(state.fullscreen ? "fullscreen landscape — rotate or exit" : "back to focus view");
  updateControls();
}

function rotateFullscreen() {
  if (!state.fullscreen) {
    return;
  }
  const portrait = window.matchMedia("(orientation: portrait)").matches;
  state.orientation = portrait ? "landscape" : "portrait";
  if (window.Pbb) {
    Pbb.setOrientation(state.orientation);
  }
  setStatusText(`rotated to ${state.orientation}`);
}

function toggleRecord(index) {
  if (state.pip) {
    setStatusText("exit pip before recording");
    return;
  }
  if (state.recording >= 0) {
    Pbb.stopRecording();
    return;
  }
  if (state.focused !== index) {
    focusStream(index);
  }
  state.pendingRecord = index;
  setStatusText("android will ask for screen access — that is how the video gets recorded, tap start now");
  Pbb.startRecording(index, players[index].stream.name);
}

window.onRecordingState = (active, message) => {
  state.recording = active ? state.pendingRecord : -1;
  if (!active) {
    state.pendingRecord = -1;
  }
  document.body.classList.toggle("recording", active);
  document.body.classList.toggle("recording-mode", active);
  if (active && state.focused >= 0) {
    playOnly(state.focused);
  }
  setStatusText(message || (active ? "recording..." : "recording stopped"));
  updateControls();
};

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
    playOnly(state.focused);
    setStatusText("back from picture-in-picture");
  }
  updateControls();
};

window.handleBack = () => {
  if (state.pip) {
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

function boot() {
  grid = document.getElementById("grid");
  backBtn = document.getElementById("backBtn");
  statusText = document.getElementById("statusText");
  qualityButtons = Array.from(document.querySelectorAll("[data-quality]"));

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
  setIcon(backBtn, "grid", "back to grid");
  const fsExit = document.getElementById("fsExit");
  if (fsExit) {
    setIcon(fsExit, "exit", "exit fullscreen");
    fsExit.addEventListener("click", toggleFullscreen);
  }
  const fsRotate = document.getElementById("fsRotate");
  if (fsRotate) {
    setIcon(fsRotate, "rotate", "rotate");
    fsRotate.addEventListener("click", rotateFullscreen);
  }

  if (Pbb.isInPip()) {
    window.setPipMode(true);
  }
  updateControls();
  if (Pbb.isRecording()) {
    state.recording = 0;
    state.pendingRecord = 0;
    updateControls();
  }
  setStatusText("loading four live feeds...");
}

document.addEventListener("DOMContentLoaded", boot);
