// Host-side checks for the page. It loads the real android/assets/app.js and
// drives it with a stub DOM and a stub Pbb bridge, so the parts that used to
// break silently - the picture-in-picture focus, the record button, the
// sliders - fail the build instead of failing on a phone.
//
// Run: node android/test/app.test.mjs

import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const APP = path.join(HERE, "..", "assets", "app.js");
const CSS = path.join(HERE, "..", "assets", "styles.css");
const ACTIVITY = path.join(HERE, "..", "java", "com", "pbb", "live", "MainActivity.java");
const SNAPSHOT = path.join(HERE, "..", "..", "keys", "keys.json");

let failures = 0;

function check(what, expected, actual) {
  const same = expected === actual
    || (expected !== null && actual !== null && String(expected) === String(actual));
  if (!same) {
    failures++;
    console.error(`${what}: expected ${expected}, got ${actual}`);
  }
}

// ---------------------------------------------------------------- the stub DOM

function makeClassList() {
  const names = new Set();
  return {
    add: (...c) => c.forEach((x) => names.add(x)),
    remove: (...c) => c.forEach((x) => names.delete(x)),
    toggle: (c, force) => {
      const on = force === undefined ? !names.has(c) : Boolean(force);
      if (on) {
        names.add(c);
      } else {
        names.delete(c);
      }
      return on;
    },
    contains: (c) => names.has(c),
  };
}

function makeElement(tag) {
  const el = {
    tagName: tag,
    className: "",
    textContent: "",
    disabled: false,
    dataset: {},
    style: {},
    attributes: {},
    children: [],
    classList: makeClassList(),
    setAttribute(key, value) {
      this.attributes[key] = value;
      if (key === "class") {
        this.className = value;
      }
    },
    getAttribute(key) {
      return key in this.attributes ? this.attributes[key] : null;
    },
    appendChild(child) {
      this.children.push(child);
      return child;
    },
    removeChild(child) {
      this.children = this.children.filter((x) => x !== child);
    },
    replaceChildren(...kids) {
      this.children = kids;
    },
    addEventListener() {},
    removeEventListener() {},
    closest() {
      return null;
    },
    focus() {},
  };
  // A card holds its four keys; any role selector finds one.
  el.querySelector = () => makeElement("button");
  el.querySelectorAll = () => [];
  return el;
}

function makeCard() {
  const card = makeElement("article");
  card.classList.add("cam-card");
  return card;
}

// -------------------------------------------------------------- the stub page

function makePlayer(index, name) {
  const video = makeElement("video");
  video.play = () => Promise.resolve();
  video.pause = () => {};
  return {
    video,
    player: null,
    stream: { name, url: `https://example.invalid/${index}.mpd`, kid: "0".repeat(32), key: "0".repeat(32) },
    statusEl: makeElement("div"),
    card: makeCard(),
    tally: makeElement("div"),
    tallyTime: makeElement("span"),
  };
}

// ------------------------------------------------------------- the page itself

function loadPage() {
  const calls = [];
  const Pbb = {
    setVolume: (value, persist) => calls.push(["setVolume", value, persist]),
    setBrightness: (value, persist) => calls.push(["setBrightness", value, persist]),
    startRecording: (index, label) => calls.push(["startRecording", index, label]),
    stopRecording: (index) => calls.push(["stopRecording", index]),
    setFocusedStream: (index) => calls.push(["setFocusedStream", index]),
    clearFocusedStream: () => calls.push(["clearFocusedStream"]),
    setImmersive: (on) => calls.push(["setImmersive", on]),
    setOrientation: (mode) => calls.push(["setOrientation", mode]),
    setTheme: (name) => calls.push(["setTheme", name]),
    enterPip: () => calls.push(["enterPip"]),
    setSource: (index, url, kid, key) => {
      calls.push(["setSource", index, url, kid, key]);
      return "";
    },
  };

  const statusText = makeElement("span");
  const backBtn = makeElement("button");
  const sourceUrl = makeElement("input");
  const sourceKid = makeElement("input");
  const sourceKey = makeElement("input");
  const sourceNote = makeElement("p");
  const sourceSheet = makeElement("div");
  const sourceChips = makeElement("div");
  const fields = {
    statusText,
    sourceUrl,
    sourceKid,
    sourceKey,
    sourceNote,
    sourceSheet,
    sourceChips,
  };
  const windowStub = {
    Pbb,
    matchMedia: () => ({ matches: false, addEventListener() {}, addListener() {} }),
    localStorage: { getItem: () => null, setItem() {} },
  };

  const documentStub = {
    body: makeElement("body"),
    documentElement: makeElement("html"),
    activeElement: null,
    addEventListener() {},
    removeEventListener() {},
    createElement: (tag) => makeElement(tag),
    createElementNS: (_ns, tag) => makeElement(tag),
    getElementById: (id) => fields[id] || null,
    querySelector: () => null,
    querySelectorAll: () => [],
  };

  const context = vm.createContext({
    window: windowStub,
    document: documentStub,
    // In a browser the bare name Pbb and window.Pbb are the same object.
    Pbb,
    console,
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    Promise,
    Math,
    JSON,
    Date,
    Number,
    String,
    Boolean,
    Array,
    Object,
    Set,
    Map,
    RegExp,
    Error,
  });
  context.globalThis = context;
  context.window.document = documentStub;

  // The page's own source, plus a small door into its script scope so the
  // harness can set the element references boot() would have set.
  const source = fs.readFileSync(APP, "utf8");
  const door = `
;globalThis.__page = {
  state,
  players,
  gesture,
  sourceSheet,
  parseSourceText,
  openSourceSheet,
  applySource,
  focusStream,
  toggleRecord,
  applyGestureValue,
  onTouchEnd,
  setRefs(refs) {
    grid = refs.grid;
    backBtn = refs.backBtn;
    statusText = refs.statusText;
    indicator = null;
    qualityButtons = [];
    recBadge = null;
    recCount = null;
    recTime = null;
  },
};`;
  vm.runInContext(source + door, context, { filename: "app.js" });

  const page = context.__page;
  page.setRefs({
    grid: makeElement("div"),
    backBtn,
    statusText,
  });
  page.players.length = 0;
  page.players.push(makePlayer(0, "PBB All Access"));
  page.players.push(makePlayer(1, "PBB Dining Area"));
  page.players.push(makePlayer(2, "PBB External Den"));
  page.window = windowStub;
  page.statusText = statusText;
  page.calls = calls;
  page.fields = fields;
  return page;
}

// ------------------------------------------------------------------- the tests

function pipFocusesAFeedWhenThereIsNone() {
  const page = loadPage();
  let threw = null;
  try {
    page.window.setPipMode(true);
  } catch (error) {
    threw = error;
  }
  check("pip does not throw", null, threw && threw.message);
  check("pip focuses a feed", 0, page.state.focused);
  check("pip is on", true, page.state.pip);
  check("pip names the feed", true, page.statusText.textContent.includes("All Access"));
}

function pipKeepsTheFeedThatWasAlreadyFocused() {
  const page = loadPage();
  page.focusStream(2);
  page.window.setPipMode(true);
  check("pip keeps the focus", 2, page.state.focused);
}

function pipLeavesTheGridWhenItCloses() {
  const page = loadPage();
  page.focusStream(1);
  page.window.setPipMode(true);
  page.window.setPipMode(false);
  check("pip off clears the focus", -1, page.state.focused);
  check("pip off clears the flag", false, page.state.pip);
}

function recordingIsRefusedWhenTheKeysRotated() {
  const page = loadPage();
  page.state.keysStale = true;
  page.toggleRecord(0);
  const started = page.calls.filter((c) => c[0] === "startRecording");
  check("no recording starts with stale keys", 0, started.length);
  check("the status says so", true, page.statusText.textContent.includes("keys rotated"));
}

function recordingStartsWhenTheKeysAreGood() {
  const page = loadPage();
  page.toggleRecord(0);
  const started = page.calls.filter((c) => c[0] === "startRecording");
  check("one recording starts", 1, started.length);
  check("the right camera", 0, started.length ? started[0][1] : -1);
}

function sliderLevelIsSavedOnce() {
  const page = loadPage();
  page.gesture.mode = "volume";
  page.gesture.active = true;
  page.applyGestureValue(0.5);
  const during = page.calls.filter((c) => c[0] === "setVolume");
  check("the slider moves the volume live", 1, during.length);
  check("the live move is not saved", false, during.length ? during[0][2] : true);
  page.onTouchEnd();
  const after = page.calls.filter((c) => c[0] === "setVolume");
  check("the level is saved once at the end", 2, after.length);
  check("the saved level is written", true, after.length ? after[1][2] : false);
  check("the saved level is the one shown", 0.5, after.length ? after[1][1] : -1);
}

// Counts the arguments of a call that starts at openIndex.
function countCallArgs(page, openIndex) {
  let depth = 1;
  let args = 0;
  let sawValue = false;
  let i = openIndex;
  while (i < page.length && depth > 0) {
    const c = page[i];
    if (c === "(") {
      depth++;
    } else if (c === ")") {
      depth--;
    } else if (c === "," && depth === 1) {
      args++;
    } else if (!/\s/.test(c)) {
      sawValue = true;
    }
    i++;
  }
  return sawValue ? args + 1 : 0;
}

// The bridge methods the Java host exposes to the page.
function javaBridgeMethods(java) {
  const methods = new Map();
  const declaration = /@JavascriptInterface\s+public\s+[\w<>\[\]]+\s+(\w+)\s*\(([^)]*)\)/g;
  let match;
  while ((match = declaration.exec(java)) !== null) {
    const args = match[2].trim();
    methods.set(match[1], args === "" ? 0 : args.split(",").length);
  }
  return methods;
}

// The bridge methods the page calls, with the number of arguments it passes.
function pageBridgeCalls(page) {
  const calls = new Map();
  const call = /Pbb\.(\w+)\s*\(/g;
  let match;
  while ((match = call.exec(page)) !== null) {
    calls.set(match[1], countCallArgs(page, match.index + match[0].length));
  }
  return calls;
}

// The page and the Java host talk through the Pbb bridge. A call that does not
// match the bridge method it calls fails at runtime on a phone, so the two
// sides are checked against each other here.
function bridgeContract() {
  const methods = javaBridgeMethods(fs.readFileSync(ACTIVITY, "utf8"));
  const calls = pageBridgeCalls(fs.readFileSync(APP, "utf8"));

  for (const [name, count] of calls) {
    check(`bridge method ${name} exists`, true, methods.has(name));
    if (methods.has(name)) {
      check(`bridge method ${name} takes ${count} argument(s)`, count, methods.get(name));
    }
  }
  for (const name of methods.keys()) {
    check(`bridge method ${name} is used by the page`, true, calls.has(name));
  }
}

function channelListContract() {
  const java = fs.readFileSync(ACTIVITY, "utf8");
  const block = /CHANNEL_LIST_URLS\s*=\s*\{([^}]*)\}/.exec(java);
  check("the app has channel list addresses", true, Boolean(block));
  if (!block) {
    return;
  }
  const urls = block[1].match(/https?:\/\/[^"]+/g) || [];
  check("the app has at least one channel list address", true, urls.length > 0);
  for (const url of urls) {
    check(`the channel list address ${url.slice(0, 40)} is https`, true, url.startsWith("https://"));
  }

  const snapshot = JSON.parse(fs.readFileSync(SNAPSHOT, "utf8"));
  const streams = snapshot.streams;
  check("the snapshot holds a streams array", true, Array.isArray(streams));
  if (!Array.isArray(streams)) {
    return;
  }
  const count = /CHANNEL_COUNT\s*=\s*(\d+)/.exec(java);
  check("the app and the snapshot agree on the channel count", streams.length, Number(count[1]));
  for (const stream of streams) {
    check(`manifest URL of ${stream.name} is https`, true, stream.url.startsWith("https://"));
    check(`KID of ${stream.name} is 32 hex`, true, /^[0-9a-f]{32}$/i.test(stream.kid));
    check(`key of ${stream.name} is 32 hex`, true, /^[0-9a-f]{32}$/i.test(stream.key));
  }
}

function sourceTextIsRead() {
  const page = loadPage();
  const found = page.parseSourceText(
    "PBB ALL ACCESS\nhttps://absp-live.akamaized.net/dash/live/2099522/x/manifest.mpd\n"
    + "8150b74d-1632-4bb4-b2ff-62e3e202fc7a:0c5ed4f9db9d4ee61bbc91b3a51ef8b5");
  check("the manifest link is read",
    "https://absp-live.akamaized.net/dash/live/2099522/x/manifest.mpd", found.url);
  check("the kid is read without dashes", "8150b74d16324bb4b2ff62e3e202fc7a", found.kid);
  check("the key is read", "0c5ed4f9db9d4ee61bbc91b3a51ef8b5", found.key);
}

function sourceTextPrefersTheManifest() {
  const page = loadPage();
  const found = page.parseSourceText(
    "license https://license.example.invalid/widevine\nstream https://cdn.example.invalid/manifest.mpd?x=1");
  check("the manifest wins over the license link",
    "https://cdn.example.invalid/manifest.mpd?x=1", found.url);
}

function sourceTextDropsTheFullStop() {
  const page = loadPage();
  const found = page.parseSourceText(
    "see https://cdn.example.invalid/manifest.mpd, then the key");
  check("the link keeps no full stop",
    "https://cdn.example.invalid/manifest.mpd", found.url);
}

function sourceJsonIsRead() {
  const page = loadPage();
  const found = page.parseSourceText(
    '{"keys":[{"kid":"8150b74d16324bb4b2ff62e3e202fc7a","k":"0c5ed4f9db9d4ee61bbc91b3a51ef8b5"}]}');
  check("the kid is read from json", "8150b74d16324bb4b2ff62e3e202fc7a", found.kid);
  check("the key is read from json", "0c5ed4f9db9d4ee61bbc91b3a51ef8b5", found.key);
}

function sourceApplyStoresTheChannel() {
  const page = loadPage();
  page.fields.sourceUrl.value = "https://cdn.example.invalid/manifest.mpd";
  page.fields.sourceKid.value = "8150b74d-1632-4bb4-b2ff-62e3e202fc7a";
  page.fields.sourceKey.value = "0c5ed4f9db9d4ee61bbc91b3a51ef8b5";
  page.sourceSheet.index = 1;
  page.applySource();
  const call = page.calls.find((entry) => entry[0] === "setSource");
  check("the source is stored", true, Boolean(call));
  if (call) {
    check("the channel is stored", 1, call[1]);
    check("the manifest is stored", "https://cdn.example.invalid/manifest.mpd", call[2]);
    check("the kid is stored without dashes", "8150b74d16324bb4b2ff62e3e202fc7a", call[3]);
    check("the key is stored", "0c5ed4f9db9d4ee61bbc91b3a51ef8b5", call[4]);
  }
  check("the sheet closes after a store", true, page.fields.sourceSheet.classList.contains("hidden"));
}

function fullscreenKeepsThePictureWhole() {
  const app = fs.readFileSync(APP, "utf8");
  const css = fs.readFileSync(CSS, "utf8");
  check("the keypad sits in the top-right corner", true,
    /\.fs-controls \{[^}]*top: max\(10px, env\(safe-area-inset-top\)\)/s.test(css));
  check("the mini monitor sits in the bottom-right corner", true,
    /body\.fullscreen-mode \.cam-card\.mini \{[^}]*bottom: max\(10px, env\(safe-area-inset-bottom\)\)/s.test(css));
  check("the picture never gives up a lane", true,
    /pip-mode \.video-wrap,\s*body\.fullscreen-mode \.video-wrap \{[^}]*top: 0;[^}]*bottom: 0;/s.test(css));
  check("no dock or reserved lane is left behind", false,
    css.includes("--fs-rail") || css.includes("--fs-dock") || css.includes(".fs-dock"));
  // Fullscreen chrome follows the desk theme, stays compact, and still speaks
  // when a feed is connecting or has dropped.
  check("the deck is translucent over the picture", true,
    /\.fs-deck \{[^}]*backdrop-filter: blur/s.test(css));
  check("fullscreen chrome follows the desk theme", true,
    /html\[data-theme="dark"\] \{[^}]*--fs-glass/s.test(css));
  check("keys stay compact but keep a 44px target", true,
    /\.fs-keys \{[^}]*grid-template-columns: repeat\(2, var\(--tap\)\)/s.test(css));
  check("the redundant controls button is gone", false,
    css.includes(".fs-launcher") || app.includes("fsLauncher"));
  check("the mini number badge is gone", false,
    css.includes("data-cam") || app.includes("data-cam"));
  check("fullscreen still shows connection states", true,
    /body\.fullscreen-mode \.cam-card\.focused \.stream-status \{/s.test(css));
  check("the old blanket status hide is gone", false,
    css.includes("body.fullscreen-mode .stream-status { display: none"));
  check("the feed name is a corner channel bug", true,
    /body\.fullscreen-mode \.indicator \{[^}]*top: max\(10px, env\(safe-area-inset-top\)\)/s.test(css));
  check("fullscreen does not open the keypad on entry", false,
    /if \(state\.fullscreen && players\[state\.focused\]\) \{[^}]*showControls\(\)/s.test(app));
}

function stopNoticeClearsTheTallyButKeepsTheKey() {
  const page = loadPage();
  page.state.recording.add(0);
  page.state.recordStarted.set(0, Date.now() - 5000);
  page.state.stopping.add(0);
  // "notice" is the immediate stop acknowledgement, sent before the remux and
  // the MediaStore copy finish. Keep the key disabled until "done" lands.
  page.window.onRecordingState({
    active: false, index: 0, kind: "notice", message: "", elapsed: 5,
  });
  check("the tally clears on the stop notice", false, page.state.recording.has(0));
  check("the key stays disabled until done", true, page.state.stopping.has(0));
  page.window.onRecordingState({
    active: false, index: 0, kind: "done", message: "", elapsed: 5,
  });
  check("done re-enables the key", false, page.state.stopping.has(0));
}

function refreshResyncsAllStreams() {
  const app = fs.readFileSync(APP, "utf8");
  check("refresh still asks for fresh keys", true,
    /function refreshKeysFromUi\(\) \{[\s\S]{0,220}Pbb\.refreshKeys\(\)/s.test(app));
  check("a manual refresh rebuilds the players", true,
    /manualKeyRefresh[\s\S]{0,320}await resyncStreams\(\)/s.test(app));
  check("all players reload in the same tick", true,
    /for \(const \[i\] of players\.entries\(\)\) \{[\s\S]{0,120}initPlayer\(players\[i\], i\)/s.test(app));
  check("feeds align inside the buffered region, not at the raw edge", true,
    /range\.end - 1/s.test(app) && !/goToLive\(\)/s.test(app));
}

function channelSourceContract() {
  const java = fs.readFileSync(ACTIVITY, "utf8");
  check("the pages.dev config is an alternative source", true,
    java.includes("https://pbb-v2.pages.dev/secrets.json"));
  check("the servers shape is normalised", true,
    /servers[\s\S]{0,500}mpdUrl/s.test(java));
  check("the web-player channels keep their names", true,
    java.includes("CHANNEL_NAMES"));
}

function stitchAndServerContract() {
  const app = fs.readFileSync(APP, "utf8");
  const java = fs.readFileSync(ACTIVITY, "utf8");
  check("stitch stacks two feeds vertically", true,
    /canvas\.height = 720/s.test(app) && /i \* half/s.test(app));
  check("stitch saves one clip", true, /Pbb\.saveStitched\(/s.test(app));
  check("the sources sheet offers a server switch", true,
    /serverChips/s.test(app) && /data-source="3"/s.test(fs.readFileSync(path.join(HERE, "..", "assets", "index.html"), "utf8")));
  check("the chosen channel list is tried first", true,
    /channelSource > 0 \? channelSource - 1 : 0/s.test(java));
  check("the pages.dev server is in the list", true,
    java.includes("https://pbb-v2.pages.dev/secrets.json"));
}

function playbackAndRecorderFixes() {
  const app = fs.readFileSync(APP, "utf8");
  const java = fs.readFileSync(
    path.join(HERE, "..", "java", "com", "pbb", "live", "RecordService.java"), "utf8");
  check("all players use one shared presentation delay", true,
    /defaultPresentationDelay: 8/s.test(app) && /ignoreSuggestedPresentationDelay: true/s.test(app));
  check("live low-latency mode is off", true, /lowLatencyMode: false/s.test(app));
  check("buffering has real headroom", true,
    /bufferingGoal: 30/s.test(app) && /rebufferingGoal: 8/s.test(app));
  check("startup waits for all three loads", true,
    /setTimeout\(startGridTogether, 5000\)/s.test(app));
  check("no per-load live-edge seek", false,
    /await player\.load[\s\S]{0,220}alignPlayer/s.test(app));
  check("the stop interrupt is cleared before saving", true,
    /Thread\.interrupted\(\)/s.test(java));
  check("empty segment bodies count as misses", true,
    /v\.length < 128/s.test(java));
  check("a header-only remux is never published", true,
    /outTmp\.length\(\) < 4096/s.test(java));
  check("a MediaStore failure still saves a copy", true,
    /publishFallback/s.test(java) && /IS_PENDING, 0\)/s.test(java));
  check("a header-only stitch is never saved", true,
    /blob\.size < 8192/s.test(app));
}

function controlRoomStructure() {
  const app = fs.readFileSync(APP, "utf8");
  const css = fs.readFileSync(CSS, "utf8");
  check("each card has a numbered top strip", true, app.includes('"cam-top"'));
  check("each card has a key strip", true, app.includes('"cam-actions"'));
  check("the old floating rack is gone", false,
    app.includes('"cam-rack"') || css.includes(".cam-rack"));
  check("the program rail is a flat strip", true,
    /\.nav \{[^}]*border-bottom: var\(--console-rule\)/s.test(css));
  check("the status ticker is a flat strip", true,
    /\.status \{[^}]*border-top: var\(--console-rule\)/s.test(css));
  check("camera chrome owns its own lanes", true,
    /\.cam-top \{[^}]*border-bottom: var\(--border\)/s.test(css)
    && /\.cam-actions \{[^}]*border-top: var\(--border\)/s.test(css));
}

const tests = [
  pipFocusesAFeedWhenThereIsNone,
  pipKeepsTheFeedThatWasAlreadyFocused,
  pipLeavesTheGridWhenItCloses,
  recordingIsRefusedWhenTheKeysRotated,
  recordingStartsWhenTheKeysAreGood,
  sliderLevelIsSavedOnce,
  bridgeContract,
  channelListContract,
  sourceTextIsRead,
  sourceTextPrefersTheManifest,
  sourceTextDropsTheFullStop,
  sourceJsonIsRead,
  sourceApplyStoresTheChannel,
  fullscreenKeepsThePictureWhole,
  controlRoomStructure,
  stopNoticeClearsTheTallyButKeepsTheKey,
  refreshResyncsAllStreams,
  channelSourceContract,
  stitchAndServerContract,
  playbackAndRecorderFixes,
];

for (const test of tests) {
  try {
    test();
  } catch (error) {
    failures++;
    console.error(`${test.name} threw: ${error && error.stack}`);
  }
}

if (failures > 0) {
  console.error(`${failures} check(s) failed`);
  process.exit(1);
}
console.log(`page checks passed (${tests.length} groups)`);
