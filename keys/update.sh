#!/usr/bin/env bash
# Refresh the checked-in fallback snapshot from the liveplayph API.
# The runtime app calls the same API; the Rust fallback includes this snapshot.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ACTIVITY="$HERE/../android/java/com/pbb/live/MainActivity.java"

API="$(grep -m1 'STREAM_API =' "$ACTIVITY" | grep -o 'https[^"]*' | head -1)"
REFERER="$(grep -m1 'STREAM_API_REFERER =' "$ACTIVITY" | grep -o 'https[^"]*' | head -1)"
if [ -z "$API" ] || [ -z "$REFERER" ]; then
  echo "no liveplayph API in MainActivity.java" >&2
  exit 1
fi

python3 - "$API" "$REFERER" "$HERE/keys.json" <<'PY'
import json
import re
import sys
import urllib.request
from pathlib import Path

api, referer, out_path = sys.argv[1:4]
streams = [
    ("PBB All Access", "4f8b2c15-7d9a-4e3b-8c0d-1e3f6a5b9c22"),
    ("PBB Dining Area", "6a1e3f89-2c4d-4b0e-9a1c-5d7e8f3a2b44"),
    ("PBB External Den", "8d2f7c34-1e5f-4a0e-8b1c-6a3e9f2d5b11"),
]

out = []
for name, channel_id in streams:
    request = urllib.request.Request(
        api + channel_id,
        headers={"Referer": referer, "User-Agent": "PBB/1.0", "Cache-Control": "no-cache"},
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        data = json.load(response)
    mpd = data["manifestUri"]
    kid = data["ClearKey"]["k1"].replace("-", "")
    key = data["ClearKey"]["k2"].replace("-", "")
    if not mpd.startswith("https://"):
        raise RuntimeError("bad manifest URL for " + name)
    if not re.fullmatch(r"[0-9a-fA-F]{32}", kid) or not re.fullmatch(r"[0-9a-fA-F]{32}", key):
        raise RuntimeError("bad KID/key pair for " + name)
    out.append({"name": name, "url": mpd, "kid": kid, "key": key})

out_path = Path(out_path)
out_path.write_text(json.dumps({"streams": out}, indent=2) + "\n", encoding="utf-8")
print(f"wrote {out_path} ({len(out)} streams)")
PY
