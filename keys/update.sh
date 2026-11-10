#!/usr/bin/env bash
# Refresh the checked-in fallback snapshot from pbb-live.vercel.app.
# The runtime app fetches the same API directly; this file is only a local snapshot.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

python3 - "$HERE/keys.json" <<'PY'
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

base = "https://pbb-live.vercel.app/api/stream"
channels = [
    ("PBB All Access", "4f8b2c15-7d9a-4e3b-8c0d-1e3f6a5b9c22"),
    ("PBB Dining Area", "6a1e3f89-2c4d-4b0e-9a1c-5d7e8f3a2b44"),
    ("PBB External Den", "8d2f7c34-1e5f-4a0e-8b1c-6a3e9f2d5b11"),
]
streams = []
for name, channel in channels:
    url = base + "?" + urllib.parse.urlencode({"channel": channel})
    request = urllib.request.Request(url, headers={"Cache-Control": "no-cache", "User-Agent": "PBB/1.0"})
    with urllib.request.urlopen(request, timeout=20) as response:
        data = json.load(response)
    if not data.get("requiresDrm") or data.get("drmMode") != "clearkey":
        raise RuntimeError("unexpected DRM mode for " + name)
    pair = data.get("ClearKey") or {}
    kid = pair.get("k1", "")
    key = pair.get("k2", "")
    mpd = data.get("directManifestUri", "")
    if not re.fullmatch(r"[0-9a-fA-F]{32}", kid) or not re.fullmatch(r"[0-9a-fA-F]{32}", key):
        raise RuntimeError("bad KID/key pair for " + name)
    if not mpd.startswith("https://"):
        raise RuntimeError("bad direct manifest URL for " + name)
    streams.append({"name": name, "url": mpd, "kid": kid, "key": key})

out = Path(sys.argv[1])
out.write_text(json.dumps({"streams": streams}, indent=2) + "\n", encoding="utf-8")
print(f"wrote {out} ({len(streams)} streams)")
PY
