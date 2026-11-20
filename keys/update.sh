#!/usr/bin/env bash
# Refresh the checked-in fallback snapshot from the pbbcollab.live session API.
# The runtime app reads the same site; the Rust fallback includes this snapshot.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

python3 - "$HERE/keys.json" <<'PY'
import json
import os
import secrets
import sys
import time
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(sys.argv[1])))
import session

CAMERAS = [("all", "PBB All Access"), ("din", "PBB Dining Area"), ("ext", "PBB External Den")]


def handshake():
    """The randomised payload the site's own page posts before it plays."""
    seed = secrets.token_bytes(13)
    key = seed[:12].hex()
    body = json.dumps({"k": key,
                       "v": session.seal(session.plain(seed, key, 0, int(time.time() * 1000)), key)})
    request = urllib.request.Request(session.SITE + "/api/streams/session", data=body.encode("utf-8"),
                                     method="POST",
                                     headers={"Content-Type": "application/json",
                                              "X-PBB-Client": session.CLIENT,
                                              "User-Agent": "PBB/1.0"})
    with urllib.request.urlopen(request, timeout=20) as response:
        cookie = "; ".join(raw.split(";")[0].strip()
                           for raw in response.headers.get_all("Set-Cookie") or [])
        return json.loads(response.read().decode("utf-8")), cookie


def manifest(endpoint, cookie):
    request = urllib.request.Request(session.SITE + endpoint,
                                     headers={"Cookie": cookie, "X-PBB-Client": session.CLIENT,
                                              "User-Agent": "PBB/1.0",
                                              "Cache-Control": "no-cache"})
    with urllib.request.urlopen(request, timeout=20) as response:
        return response.read().decode("utf-8")


reply, cookie = handshake()
streams = []
for cam, name in CAMERAS:
    kid, key = session.unseal(reply["streams"][cam]["token"], reply["nonce"])
    xml = session.inlined(manifest(reply["streams"][cam]["endpoint"], cookie))
    streams.append({"name": name,
                    "url": session.PREFIX + __import__("base64").b64encode(xml.encode("utf-8")).decode("ascii"),
                    "kid": kid, "key": key})

out = sys.argv[1]
with open(out, "w", encoding="utf-8") as handle:
    handle.write(json.dumps({"streams": streams}, indent=2) + "\n")
print("wrote %s (%d streams)" % (out, len(streams)))
PY
