#!/usr/bin/env bash
# Refresh the checked-in fallback snapshot from the live channel list.
# The runtime app fetches the same file directly; this rewrites both places
# the last known keys live, so the Rust fallback can not drift from the
# snapshot.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

# One place holds the list URL: the app itself.
LIST_URL="$(grep -A3 'CHANNEL_LIST_URLS' "$HERE/../android/java/com/pbb/live/MainActivity.java" \
  | grep -o 'https[^"]*' | head -1)"
if [ -z "$LIST_URL" ]; then
  echo "no channel list URL in MainActivity.java" >&2
  exit 1
fi

python3 - "$LIST_URL" "$HERE/keys.json" "$HERE/../src/lib.rs" <<'PY'
import json
import re
import sys
import urllib.request
from pathlib import Path

url = sys.argv[1]
request = urllib.request.Request(url, headers={"Cache-Control": "no-cache", "User-Agent": "PBB/1.0"})
with urllib.request.urlopen(request, timeout=20) as response:
    data = json.load(response)

listed = data.get("streams")
if not isinstance(listed, list) or len(listed) != 3:
    raise RuntimeError("the channel list needs 3 channels")
streams = []
for entry in listed:
    name = entry.get("name") or "Channel"
    mpd = entry.get("url", "")
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

rust = Path(sys.argv[3])
block = 'const STREAM_CONFIG_JSON: &str = r#"' + json.dumps(
    {"app": "PBB", "streams": streams}, indent=2) + '"#;'
source = rust.read_text(encoding="utf-8")
patched = re.sub(
    r'const STREAM_CONFIG_JSON: &str = r#".*?"#;',
    lambda match: block,
    source,
    count=1,
    flags=re.S,
)
if patched == source and block not in source:
    raise RuntimeError("STREAM_CONFIG_JSON block not found in " + str(rust))
rust.write_text(patched, encoding="utf-8")
print(f"wrote {rust}")
PY
