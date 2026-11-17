#!/usr/bin/env bash
# Refresh the checked-in fallback snapshot from the live channel list.
# The runtime app fetches the same file directly; the Rust fallback includes
# this snapshot, so one rewrite keeps them from drifting.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

# One place holds the list URL: the app itself.
LIST_URL="$(grep -A1 -m1 'CHANNEL_LIST_URL =' "$HERE/../android/java/com/pbb/live/MainActivity.java" \
  | grep -o 'https[^"]*' | head -1)"
if [ -z "$LIST_URL" ]; then
  echo "no channel list URL in MainActivity.java" >&2
  exit 1
fi

python3 - "$LIST_URL" "$HERE/keys.json" <<'PY'
import json
import re
import sys
import urllib.request
from pathlib import Path

NAMES = ["PBB All Access", "PBB Dining Area", "PBB External Den"]
LICENSE = "#KODIPROP:inputstream.adaptive.license_key="
OLD_CDN = "absp-live.akamaized.net"
LIVE_CDN = "abslive.akamaized.net"

def parse_m3u(text):
    found = {}
    name = license = None
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("#EXTINF:"):
            name = line[line.rfind(",") + 1:].strip()
            if name.endswith(" Live"):
                name = name[:-5]
            license = None
        elif line.startswith(LICENSE):
            license = line[len(LICENSE):].strip()
        elif line and not line.startswith("#") and name in NAMES and license:
            kid, key = license.split(":", 1)
            found[name] = {"name": name, "url": line, "kid": kid, "key": key}
            name = license = None
    return [found[n] for n in NAMES if n in found]

url = sys.argv[1]
request = urllib.request.Request(url, headers={"Cache-Control": "no-cache", "User-Agent": "PBB/1.0"})
with urllib.request.urlopen(request, timeout=20) as response:
    data = response.read().decode("utf-8")

listed = parse_m3u(data)
if len(listed) != 3:
    raise RuntimeError("the channel list needs 3 channels")
streams = []
for entry in listed:
    name = entry.get("name") or "Channel"
    mpd = entry.get("url", "").replace(OLD_CDN, LIVE_CDN)
    kid = entry.get("kid", "").replace("-", "")
    key = entry.get("key", "").replace("-", "")
    if not re.fullmatch(r"[0-9a-fA-F]{32}", kid) or not re.fullmatch(r"[0-9a-fA-F]{32}", key):
        raise RuntimeError("bad KID/key pair for " + name)
    if not mpd.startswith("https://"):
        raise RuntimeError("bad manifest URL for " + name)
    streams.append({"name": name, "url": mpd, "kid": kid, "key": key})

out = Path(sys.argv[2])
out.write_text(json.dumps({"streams": streams}, indent=2) + "\n", encoding="utf-8")
print(f"wrote {out} ({len(streams)} streams)")
PY
