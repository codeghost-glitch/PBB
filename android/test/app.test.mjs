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
const HTML = path.join(HERE, "..", "assets", "index.html");
const ACTIVITY = path.join(HERE, "..", "java", "com", "pbb", "live", "MainActivity.java");
const SNAPSHOT = path.join(HERE, "..", "..", "keys", "keys.json");
const ICON_BG = path.join(HERE, "..", "res", "values", "colors.xml");
const ICON_FG = path.join(HERE, "..", "res", "drawable", "ic_launcher_foreground.xml");

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
    add: (...c) => { for (const x of c) names.add(x); },
    remove: (...c) => { for (const x of c) names.delete(x); },
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
  const serverPick = { server: "s1" };
  const Pbb = {
    setVolume: (value, persist) => calls.push(["setVolume", value, persist]),
    setBrightness: (value, persist) => calls.push(["setBrightness", value, persist]),
    markStackPair: (top, bottom) => calls.push(["markStackPair", top, bottom]),
    startRecording: (index, label) => calls.push(["startRecording", index, label]),
    stopRecording: (index) => calls.push(["stopRecording", index]),
    setFocusedStream: (index) => calls.push(["setFocusedStream", index]),
    clearFocusedStream: () => calls.push(["clearFocusedStream"]),
    setImmersive: (on) => calls.push(["setImmersive", on]),
    setOrientation: (mode) => calls.push(["setOrientation", mode]),
    setTheme: (name) => calls.push(["setTheme", name]),
    enterPip: () => calls.push(["enterPip"]),
    getStreamServer: () => serverPick.server,
    setStreamServer: (server) => {
      calls.push(["setStreamServer", server]);
      serverPick.server = server;
      return "";
    },
  };

  const statusText = makeElement("span");
  const backBtn = makeElement("button");
  const sourceNote = makeElement("p");
  const sourceSheet = makeElement("div");
  const serverChips = makeElement("div");
  for (const id of ["s1", "s2", "s3"]) {
    const chip = makeElement("button");
    chip.dataset.server = id;
    serverChips.appendChild(chip);
  }
  const fields = {
    statusText,
    sourceNote,
    sourceSheet,
    serverChips,
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
  serverSheet,
  selectServer,
  openSourceSheet,
  focusStream,
  toggleRecord,
  applyGestureValue,
  applyQuality,
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
  while (true) {
    match = declaration.exec(java);
    if (match === null) break;
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
  while (true) {
    match = call.exec(page);
    if (match === null) break;
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
  check("the old channel list servers are gone", false,
    /gist\.githubusercontent\.com\/codeghost-glitch|pbbliveaccess\.lovable\.app/.test(java));

  const snapshot = JSON.parse(fs.readFileSync(SNAPSHOT, "utf8"));
  const streams = snapshot.streams;
  check("the snapshot holds a streams array", true, Array.isArray(streams));
  if (!Array.isArray(streams)) {
    return;
  }
  const count = /CHANNEL_COUNT\s*=\s*(\d+)/.exec(java);
  check("the app and the snapshot agree on the channel count", streams.length, Number(count[1]));
  for (const stream of streams) {
    check(`manifest URL of ${stream.name} is an inlined pbb3 MPD`, true,
      stream.url.startsWith("data:application/dash+xml;base64,")
      && Buffer.from(stream.url.split(",")[1], "base64").toString("utf8").includes("<BaseURL>https://"));
    check(`KID of ${stream.name} is 32 hex`, true, /^[0-9a-f]{32}$/i.test(stream.kid));
    check(`key of ${stream.name} is 32 hex`, true, /^[0-9a-f]{32}$/i.test(stream.key));
  }
}

function serverSwitchFailuresReachTheStrip() {
  const page = loadPage();
  page.window.onKeysMessage("could not reach the streams - tap refresh to retry");
  check("a Java refresh failure updates the status", 
    "could not reach the streams - tap refresh to retry",
    page.statusText.textContent);
}

function serverPickIsStored() {
  const page = loadPage();
  page.selectServer("s2");
  const call = page.calls.find((entry) => entry[0] === "setStreamServer");
  check("the pick reaches the app", "s2", call && call[1]);
  check("the sheet closes after a pick", true, page.fields.sourceSheet.classList.contains("hidden"));
  check("the sheet remembers the pick", "s2", page.serverSheet.server);
}

function serverSheetFollowsTheApp() {
  const page = loadPage();
  page.selectServer("s2");
  page.openSourceSheet();
  check("the sheet opens on the stored server", "s2", page.serverSheet.server);
  check("the active chip is marked", true,
    page.fields.serverChips.children[1].classList.contains("active")
    && !page.fields.serverChips.children[0].classList.contains("active"));
  check("the note names the server", true,
    /server 2 is on/.test(page.fields.sourceNote.textContent));
  page.selectServer("s3");
  page.openSourceSheet();
  check("the sheet opens on the third server", "s3", page.serverSheet.server);
  check("the note names the server", true,
    /server 3 is on/.test(page.fields.sourceNote.textContent));
  check("the third chip is the marked one", true,
    page.fields.serverChips.children[2].classList.contains("active"));
}

function fullscreenKeepsThePictureWhole() {
  const app = fs.readFileSync(APP, "utf8");
  const css = fs.readFileSync(CSS, "utf8");
  const html = fs.readFileSync(HTML, "utf8");
  check("the controls sit in the top-right corner", true,
    /\.fs-controls \{[^}]*top: max\(12px, env\(safe-area-inset-top\)\)/s.test(css)
    && /\.fs-controls \{[^}]*right: max\(12px, env\(safe-area-inset-right\)\)/s.test(css)
    && /\.fs-deck \{[^}]*flex-direction: column/s.test(css));
  check("the mini monitor sits bottom-right", true,
    /body\.fullscreen-mode \.cam-card\.mini \{[^}]*bottom: max\(12px, env\(safe-area-inset-bottom\)\)/s.test(css));
  check("the picture never gives up a lane", true,
    /pip-mode \.video-wrap,\s*body\.fullscreen-mode \.video-wrap \{[^}]*inset: 0;/s.test(css));
  check("no dock or reserved lane is left behind", false,
    css.includes("--fs-rail") || css.includes("--fs-dock") || css.includes(".fs-dock"));
  // Fullscreen chrome follows the desk theme, stays compact, and still speaks
  // when a feed is connecting or has dropped.
  check("the deck is a quiet solid panel", true,
    /\.fs-deck \{[^}]*background: var\(--surface\)/s.test(css)
    && !css.includes("backdrop-filter"));
  check("the app is dark only", true,
    html.includes('data-theme="dark"')
    && /color-scheme: dark/.test(css)
    && !/color-scheme: dark light/.test(html)
    && !/themeBtn/.test(html));
  check("keys stay compact but keep a 44px target", true,
    /\.fs-keys \{[^}]*grid-template-columns: repeat\(2, var\(--tap\)\)/s.test(css)
    && /\.fs-key \{[^}]*min-height: var\(--tap\)/s.test(css));
  check("the redundant controls button is gone", false,
    css.includes(".fs-launcher") || app.includes("fsLauncher"));
  check("the mini number badge is gone", false,
    css.includes("data-cam") || app.includes("data-cam"));
  check("fullscreen still shows connection states", true,
    /body\.fullscreen-mode \.cam-card\.focused \.stream-status \{/s.test(css));
  check("the old blanket status hide is gone", false,
    css.includes("body.fullscreen-mode .stream-status { display: none"));
  check("the feed name is a corner channel bug", true,
    /body\.fullscreen-mode \.indicator \{[^}]*top: max\(12px, env\(safe-area-inset-top\)\)/s.test(css));
  check("fullscreen does not open the keypad on entry", false,
    /if \(state\.fullscreen && players\[state\.focused\]\) \{[^}]*showControls\(\)/s.test(app));
}

function stopNoticeClearsTheTallyButKeepsTheKey() {
  const app = fs.readFileSync(APP, "utf8");
  const page = loadPage();
  page.state.recording.add(0);
  page.state.recordStarted.set(0, Date.now() - 5000);
  page.state.stopping.add(0);
  // "notice" is the immediate stop acknowledgement, sent before the remux and
  // the MediaStore copy finish. The key stays live so another stop can be sent.
  page.window.onRecordingState({
    active: false, index: 0, kind: "notice", message: "", elapsed: 5,
  });
  check("the tally clears on the stop notice", false, page.state.recording.has(0));
  check("the stop key stays usable while saving", true,
    !/recordBtn\.disabled = stopping/.test(app));
  page.window.onRecordingState({
    active: false, index: 0, kind: "done", message: "", elapsed: 5,
  });
  check("done clears the saving state", false, page.state.stopping.has(0));
}

function everyStopTapReachesTheService() {
  const page = loadPage();
  page.state.recording.add(0);
  page.state.stopping.add(0);
  page.toggleRecord(0);
  const stops = page.calls.filter((call) => call[0] === "stopRecording");
  check("a second stop tap is sent again", 1, stops.length);
  check("the tally is already cleared", false, page.state.recording.has(0));
}

function pipDoesNotRestartTheFeed() {
  const app = fs.readFileSync(APP, "utf8");
  const java = fs.readFileSync(ACTIVITY, "utf8");
  check("PiP state is announced once", true,
    !/if \(entered\) \{[\s\S]{0,140}notifyPipState\(true\)/.test(java)
    && /onPictureInPictureModeChanged[\s\S]{0,300}notifyPipState/.test(java));
  check("play only starts paused feeds", true,
    /if \(entry\.video\.paused\) \{[\s\S]{0,80}entry\.video\.play\(\)/.test(app));
}

function refreshResyncsAllStreams() {
  const app = fs.readFileSync(APP, "utf8");
  check("refresh still asks for fresh keys", true,
    /function refreshKeysFromUi\(\) \{[\s\S]{0,220}Pbb\.refreshKeys\(\)/s.test(app));
  check("a manual refresh rebuilds the players", true,
    /manualKeyRefresh[\s\S]{0,320}await resyncStreams\(\)/s.test(app));
  check("all players reload in the same tick", true,
    /for \(const \[i\] of players\.entries\(\)\) \{[\s\S]{0,120}initPlayer\(players\[i\], i\)/s.test(app));
  check("a rotated key is re-fetched once, then explained", true,
    /=== 4012[\s\S]{0,500}state\.keyRetry = true;[\s\S]{0,120}refreshKeysFromUi\(\)/.test(app)
    && /state\.keyRetry = false;/.test(app));
  check("an unchanged-key refresh never seeks the live feeds", true,
    !/\.currentTime = /s.test(app) && !/goToLive\(\)/s.test(app) &&
    /setTransientStatus\("streams already in sync"\)/s.test(app));
}

function channelSourceContract() {
  const app = fs.readFileSync(APP, "utf8");
  const java = fs.readFileSync(ACTIVITY, "utf8");
  const http = fs.readFileSync(path.join(HERE, "..", "java", "com", "pbb", "live", "Http.java"), "utf8");
  check("the pbbcollab.live site is the source", true,
    java.includes('SITE = "https://www.pbbcollab.live"')
    && java.includes('/api/streams/session'));
  check("all three cameras have an id", true,
    ['"all"', '"din"', '"ext"'].every((id) => java.includes(id)));
  check("the handshake is the randomised payload the site posts", true,
    java.includes('SecureRandom') && java.includes('0x3c')
    && java.includes('postJson(SITE + "/api/streams/session"'));
  check("a camera token is unsealed with the handshake nonce", true,
    java.includes('sessionToken') && java.includes('^ 0x5a ^'));
  check("the manifests are read with the session cookie", true,
    java.includes('handshake.cookie') && http.includes('X-PBB-Client')
    && http.includes('setRequestProperty("Cookie"'));
  check("the site's MPD is inlined as a data URL", true,
    java.includes('DATA_MPD = "data:application/dash+xml;base64,"')
    && /Base64\.encodeToString\(\s*siteMpd\(xml,/.test(java));
  check("the site's segment base is hoisted into BaseURL", true,
    java.includes('"<BaseURL>" + base + "</BaseURL>"'));
  check("Http decodes inlined MPDs and reads Last-Modified", true,
    /Base64\.getDecoder\(\)\.decode/.test(http)
    && /getLastModified\(\)/.test(http));
  check("recoveries show the Shaka error code", true,
    /setTransientStatus\(`camera \$\{index \+ 1\}: \$\{why\}`/.test(app)
    && /setStreamStatus\(entry, `\$\{why\}retrying/.test(app));
  const recorder = fs.readFileSync(
    path.join(HERE, "..", "java", "com", "pbb", "live", "RecordService.java"), "utf8");
  check("recording uses the generated MPD's BaseURL", true,
    /plan\.baseUrl = baseEnd > baseStart/.test(recorder));
  check("the dead pbb3 bundle reader is gone", false,
    /PBB3_M3U|api\.Config|SEALED_VAULT|live-stream4/.test(java));
  check("no reachability gate rejects the generated manifests", false,
    /reachable\(/.test(java));
  check("the sheet is a server picker, not a source editor", true,
    /Pbb\.getStreamServer\(\)/.test(app) && /Pbb\.setStreamServer\(pick\)/.test(app)
    && !/parseSourceText|applySource|selectSourceChannel/.test(app));
  check("the web-player channels keep their names", true,
    java.includes("CHANNEL_NAMES"));
  check("the old JSON shapes are gone", false, /mpdUrl|servers\.getJSONObject/.test(java));
}

function stitchAndServerContract() {
  const app = fs.readFileSync(APP, "utf8");
  const java = fs.readFileSync(ACTIVITY, "utf8");
  check("the double record uses the native recorder, not a WebView canvas", true,
    !/captureStream|MediaRecorder/s.test(app) &&
    /STITCH_FEEDS = \[0, 1\]/s.test(app) &&
    /Pbb\.startRecording\(index, players\[index\]\.stream\.name\)/s.test(app));
  check("the two feeds are marked as a stack pair before recording", true,
    /Pbb\.markStackPair\(STITCH_FEEDS\[0\], STITCH_FEEDS\[1\]\)/s.test(app));
  check("a single recording clears any pending stack pair", true,
    /Pbb\.markStackPair\(-1, -1\)/s.test(app));
  check("the double record has a visible tally state", true,
    /stitch-active/s.test(app) && /updateStitchButton\(\)/s.test(app));
  const html = fs.readFileSync(path.join(HERE, "..", "assets", "index.html"), "utf8");
  check("the sheet lists the three servers", true,
    /id="serverChips"/.test(html) && /data-server="s1"/.test(html)
    && /data-server="s2"/.test(html) && /data-server="s3"/.test(html)
    && /selectServer/.test(app));
  check("the third server is the mirror route", true,
    /MIRROR = "https:\/\/pbb3collablive\.xyz\/api\/proxy\/stream\//.test(java)
    && /CAM_MIRROR/.test(java));
  check("the bridge stores the pick", true,
    /setStreamServer/.test(java) && /getStreamServer/.test(java));
  check("the manifest and key boxes are gone", false,
    /sourceUrl|sourceKid|sourceKey|sourceDetect|sourceApply/.test(html + app));
}

function playbackAndRecorderFixes() {
  const app = fs.readFileSync(APP, "utf8");
  const java = fs.readFileSync(
    path.join(HERE, "..", "java", "com", "pbb", "live", "RecordService.java"), "utf8");
  const delays = app.match(/defaultPresentationDelay: \d+/g) || [];
  check("all players use one shared presentation delay", true,
    delays.length === 1 && /ignoreSuggestedPresentationDelay: true/s.test(app));
  check("live low-latency mode is off", true, /lowLatencyMode: false/s.test(app));
  const goal = Number((/bufferingGoal: (\d+)/.exec(app) || [])[1]);
  check("buffering has real headroom", true,
    goal >= 12 && goal <= 30 && /rebufferingGoal: 8/s.test(app));
  check("startup waits for all three loads", true,
    /setTimeout\(startGridTogether, 5000\)/s.test(app));
  check("no per-load live-edge seek", false,
    /await player\.load[\s\S]{0,220}alignPlayer/s.test(app));
  check("quality switches keep the buffer so mini and feed swaps stay instant", true,
    /selectVariantTrack\(chosen, false\)/s.test(app) &&
    !/selectVariantTrack\(chosen, true\)/s.test(app));
  check("hidden feeds are never downgraded to sd", true,
    !/qualityFor/s.test(app) && /applyQuality\(entry\.player, state\.quality\)/s.test(app));
  const html = fs.readFileSync(path.join(HERE, "..", "assets", "index.html"), "utf8");
  check("only hd and sd quality choices are shown", true,
    !html.includes('data-quality="auto"') &&
    ["hd", "sd"].every((q) => html.includes(`data-quality="${q}"`)));
  check("saved auto quality migrates to hd", true,
    /return value === "hd" \|\| value === "sd" \? value : "hd"/.test(app));
  check("adaptive bitrate is off", true, /abr: \{ enabled: false \}/s.test(app));

  const page = loadPage();
  const heights = [180, 360, 720, 1080];
  const fakePlayer = {
    configure() {},
    getVariantTracks: () => heights.map((h) => ({ height: h, active: false })),
    selectVariantTrack(track) { fakePlayer.picked = track.height; },
  };
  page.applyQuality(fakePlayer, "hd");
  check("hd takes the 1080p rendition", 1080, fakePlayer.picked);
  page.applyQuality(fakePlayer, "sd");
  // sd asks for 480 lines; the origin's ladder tops out below that, so the
  // rule takes the smallest rendition of at least 480 lines - 720p here.
  check("sd takes the smallest rendition of at least 480 lines", 720, fakePlayer.picked);
  check("sd never falls to the half-height renditions", true,
    /SD_MIN_HEIGHT = 480/.test(app)
    && /heights\.filter\(\(h\) => h >= SD_MIN_HEIGHT\)\[0\]/.test(app));
  check("the buttons read HD and SD with the rendition in the tooltip", true,
    /QUALITY_LABELS = \{ hd: "HD", sd: "SD" \}/.test(app)
    && /btn\.title = detail/.test(app)
    && /data-quality="hd">HD/.test(html));
  const css = fs.readFileSync(path.join(HERE, "..", "assets", "styles.css"), "utf8");
  check("every feed keeps decoding so mini opens on a live frame", true,
    !/\.pause\(\)/s.test(app) &&
    /for \(const entry of players\) \{[\s\S]{0,80}entry\.video\.play\(\)/s.test(app));
  check("hidden feeds render offscreen instead of display:none", true,
    !/body\.fullscreen-mode \.cam-card:not\(\.focused\) \{ display: none/s.test(css) &&
    /opacity: 0\.01/s.test(css) && /body\.fullscreen-mode \.cam-card\.mini \{[\s\S]{0,400}opacity: 1/s.test(css));
  check("the stop interrupt is cleared before saving", true,
    /Thread\.interrupted\(\)/s.test(java));
  check("empty segment bodies count as misses", true,
    /v\.length < 128/s.test(java));
  check("a header-only remux is never published", true,
    /outTmp\.length\(\) < 4096/s.test(java));
  check("a MediaStore failure still saves a copy", true,
    /publishFallback/s.test(java) && /IS_PENDING, 0\)/s.test(java));
  check("a stop during the edge probe still saves a file", true,
    /nothing recorded - stopped before the first segment/s.test(java) &&
    /while \(session\.running \|\| segmentsThisPart == 0\)/s.test(java));
  check("the live-edge probe keeps real network slack", true,
    /Http\.open\(url, 4000, 6000\)/s.test(java));
  check("a transient probe failure retries instead of ending the recording", true,
    /probeRetries > MAX_PROBE_RETRIES/s.test(java) &&
    /Thread\.sleep\(3000\)/s.test(java));
  check("the stop interrupt is cleared on every post-stop fetch", true,
    /if \(!session\.running\) \{[\s\S]{0,500}Thread\.interrupted\(\)[\s\S]{0,150}stopFetches/s.test(java));
  const plugin = fs.readFileSync(path.join(HERE, "..", "java", "com", "pbb", "live", "RecordService.java"), "utf8");
  const c = fs.readFileSync(path.join(HERE, "..", "..", "ffmpeg", "pbb_ffmpeg.c"), "utf8");
  check("a stitch pair is joined by native code into one file", true,
    /nativeStack\(top\.getAbsolutePath\(\), bottom\.getAbsolutePath\(\)/s.test(plugin) &&
    /int pbb_stack\(/s.test(c) &&
    /stack_planes\(frame, pt, pb, w, h\)/s.test(c));
  check("the stack keeps each source's full height", true,
    /int rows_y = h;/s.test(c) && /int rows_c = h \/ 2;/s.test(c));
}

function controlRoomStructure() {
  const app = fs.readFileSync(APP, "utf8");
  const css = fs.readFileSync(CSS, "utf8");
  check("each card has a numbered top strip", true, app.includes('"cam-top"'));
  check("each card has a key strip", true, app.includes('"cam-actions"'));
  check("the old floating rack is gone", false,
    app.includes('"cam-rack"') || css.includes(".cam-rack"));
  check("the header and the status deck are bars", true,
    /\.nav \{[^}]*border-bottom: 1px solid var\(--line\)/s.test(css)
    && /\.status \{[^}]*border-top: 1px solid var\(--line\)/s.test(css));
  check("camera chrome sits in a strip under the picture", true,
    /\.cam-bar \{[^}]*border-top: 1px solid var\(--line\)/s.test(css)
    && /bar\.appendChild\(top\)/.test(app)
    && /bar\.appendChild\(actions\)/.test(app)
    && !/--on-video-top/.test(css));
  check("no words or scrims sit on the video", true,
    !/\.cam-card::before/.test(css) && !/\.cam-card::after/.test(css)
    && !/video-wrap::before/.test(css));
  check("a preview gives its name the whole strip", true,
    /\.grid \.cam-card:not\(:first-child\) \.cam-bar \{[^}]*flex-direction: column/s.test(css));
}

function designSystemHoldsTheLine() {
  const css = fs.readFileSync(CSS, "utf8");
  const html = fs.readFileSync(HTML, "utf8");
  const app = fs.readFileSync(APP, "utf8");
  // The taste skill's rules, kept as a build-time guard: one type scale, two
  // weights, 4px-grid spacing, a neutral interface and one filled control.
  check("one type scale, two weights", true,
    (css.match(/--t-(?:label|body|card|lead|h1|h2|stat):/g) || []).length === 7
    && (css.match(/--w-(?:reg|bold):/g) || []).length === 2
    && !/--t-xs|--t-md|--t-lg/.test(css));
  check("every spacing token is on the 4px grid", true,
    Array.from(css.matchAll(/--s-[0-9]+: (\d+)px/g))
      .every((match) => Number(match[1]) % 4 === 0));
  check("the picker lists routes as rows", true,
    /\.chip \{[^}]*min-height: 64px/s.test(css)
    && /\.chip-name \{[^}]*--w-bold/s.test(css)
    && (html.match(/data-server="/g) || []).length === 3
    && !html.includes("chip-desc"));
  check("the chosen route is marked with a filled dot", true,
    /\.chip\.active \.chip-key \{[^}]*background: var\(--ink\)/s.test(css));
  check("the program feed is marked by a hairline, not a number", true,
    /\.cam-card:first-child \{ border-color: var\(--line-strong\)/s.test(css)
    && !/\.cam-num/.test(css));
  check("the one filled control is the chosen quality", true,
    /\.seg-btn\.active \{[^}]*background: var\(--ink\)/s.test(css));
  check("the interface stays neutral", true,
    !css.includes("backdrop-filter")
    && !/\.cam-card \{[^}]*box-shadow/s.test(css)
    && !/\.fs-deck \{[^}]*box-shadow/s.test(css)
    && !/\.sheet-panel \{[^}]*box-shadow/s.test(css));
  check("the page declares itself as software", true,
    html.includes('data-type="software"') && html.includes('name="page-type" content="software"'));
  check("the running time sits in the top strip", true,
    /\.cam-card\.rec \.cam-live \{ display: none; \}/.test(css)
    && /top\.appendChild\(tally\)/.test(app)
    && !/\.cam-num/.test(css));
  const titleRule = /\.cam-title \{[^}]*\}/s.exec(css);
  const statusRule = /#statusText \{[^}]*\}/s.exec(css);
  check("feed names and status wrap instead of being cut", true,
    Boolean(titleRule) && /white-space: normal/.test(titleRule[0])
    && !/text-overflow/.test(titleRule[0])
    && Boolean(statusRule) && /white-space: normal/.test(statusRule[0])
    && !/text-overflow/.test(statusRule[0]));
}

function liveWindowAndBackupRoute() {
  const app = fs.readFileSync(APP, "utf8");
  const java = fs.readFileSync(ACTIVITY, "utf8");
  // The site's proxy answers its own Origin only, so the page cannot fetch
  // server 2's segments itself. The app makes that hop and hands them over.
  check("every media hop goes through the app", true,
    /shouldInterceptRequest\(/.test(java)
    && java.includes('url.startsWith(SITE + "/stream/")')
    && java.includes('url.startsWith("https://absp-live.akamaized.net/")')
    && java.includes('url.startsWith(MIRROR)')
    && java.includes('setRequestProperty("Origin", SITE)')
    && java.includes('setRequestProperty("Cookie", sessionCookie)')
    && java.includes('"Access-Control-Allow-Origin", "*"'));
  check("the page waits for the launch handshake before it builds tiles", true,
    /public String getConfig\(\) \{\s*waitForFreshConfig\(\d+\);/.test(java)
    && java.includes("markConfigFresh();"));
  // A 40 second window, three players: the delay plus the ring behind has to
  // stay inside it or the readers ask for segments that were never written.
  const delay = Number((/defaultPresentationDelay: (\d+)/.exec(app) || [])[1]);
  const behind = Number((/bufferBehind: (\d+)/.exec(app) || [])[1]);
  check("the live buffer stays inside the 40s window", true,
    delay > 8 && delay + behind <= 40);
}

function startupDoesNotReloadTheGrid() {
  const app = fs.readFileSync(APP, "utf8");
  const java = fs.readFileSync(ACTIVITY, "utf8");
  // Each handshake rewrites publishTime, so the encoded manifest differs even
  // when nothing the player cares about has moved. Keying identity off it made
  // every tile reload right after boot.
  check("identity is the key pair plus the segment base", true,
    /cur\.kid !== s\.kid \|\| cur\.key !== s\.key \|\| segmentBase\(cur\) !== segmentBase\(s\)/.test(app)
    && /function segmentBase\(stream\)/.test(app));
  check("the site hands the base over once", true,
    java.includes('stream.put("base", base)'));
  check("the launch handshake is not repeated", true,
    /if \(!configFresh\) \{\s*refreshKeys\(\);/.test(java));
  check("a baked manifest is not polled every 30s", true,
    java.includes("minimumUpdatePeriod"));
}

function soundSwitchDoesNotRebuildTheAudioRenderer() {
  const app = fs.readFileSync(APP, "utf8");
  // A browser builds the audio renderer when an element is unmuted and drops
  // it again when it is muted; that rebuild is what the picture waited for.
  check("the feeds are unmuted from the start", true,
    /video\.muted = false;\s*video\.volume = 0;/.test(app));
  check("the level decides what is audible", true,
    /entry\.video\.volume = audible \? 1 : 0;/.test(app)
    && !/entry\.video\.muted = !\(i === state\.sound/.test(app));
  check("switching the sound does not touch playback", true,
    !/function ensureSound[\s\S]{0,400}video\.play\(/.test(app));
}

function launcherIconFollowsTheSystem() {
  const bg = fs.readFileSync(ICON_BG, "utf8");
  const fg = fs.readFileSync(ICON_FG, "utf8");
  // The icon keeps the eye, drops the tally: red means recording inside the
  // app, so the mark uses one accent over a neutral ground.
  check("the icon uses the neutral ground and one accent", true,
    bg.includes('#101214')
    && fg.includes('#F1F1EE')
    && fg.includes('#E8B44A')
    && !/#E93B2F|#E8635A/.test(fg));
  check("the themed icon is one colour", true,
    fs.readFileSync(path.join(HERE, "..", "res", "drawable", "ic_launcher_monochrome.xml"), "utf8")
      .split("fillColor").length === 3);
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
  serverPickIsStored,
  serverSwitchFailuresReachTheStrip,
  serverSheetFollowsTheApp,
  fullscreenKeepsThePictureWhole,
  controlRoomStructure,
  everyStopTapReachesTheService,
  pipDoesNotRestartTheFeed,
  stopNoticeClearsTheTallyButKeepsTheKey,
  refreshResyncsAllStreams,
  channelSourceContract,
  stitchAndServerContract,
  playbackAndRecorderFixes,
  designSystemHoldsTheLine,
  liveWindowAndBackupRoute,
  startupDoesNotReloadTheGrid,
  soundSwitchDoesNotRebuildTheAudioRenderer,
  launcherIconFollowsTheSystem,
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
