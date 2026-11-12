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
const ACTIVITY = path.join(HERE, "..", "java", "com", "pbb", "live", "MainActivity.java");

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
  };

  const statusText = makeElement("span");
  const backBtn = makeElement("button");
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
    getElementById: (id) => (id === "statusText" ? statusText : null),
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

const tests = [
  pipFocusesAFeedWhenThereIsNone,
  pipKeepsTheFeedThatWasAlreadyFocused,
  pipLeavesTheGridWhenItCloses,
  recordingIsRefusedWhenTheKeysRotated,
  recordingStartsWhenTheKeysAreGood,
  sliderLevelIsSavedOnce,
  bridgeContract,
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
