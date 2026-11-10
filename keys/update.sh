#!/usr/bin/env bash
# Refresh the runtime key list from the studio's data.js.
# Run this when the broadcaster rotates clear keys; no APK rebuild needed.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
GIST_ID="c21aa2d5114aa18b2114e75479a0e778"

curl -fsSL --max-time 25 -o /tmp/pbb-data.js 'https://pbb-live-studio.vercel.app/data.js'
node "$HERE/decode-data.js" /tmp/pbb-data.js > "$HERE/keys.json"

python3 - "$HERE/keys.json" > /tmp/pbb-gist-payload.json <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    print(json.dumps({"files": {"keys.json": {"content": f.read()}}}))
PY

gh api -X PATCH "/gists/$GIST_ID" --input /tmp/pbb-gist-payload.json > /dev/null
echo "updated gist $GIST_ID"
curl -fsSL --max-time 25 "https://gist.githubusercontent.com/codeghost-glitch/$GIST_ID/raw/keys.json" | head -8
