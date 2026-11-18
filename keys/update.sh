#!/usr/bin/env bash
# Refresh the checked-in fallback snapshot from the pbbcollab.live session API.
# The runtime app reads the same site; the Rust fallback includes this snapshot.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

python3 - "$HERE/keys.json" <<'PY'
import base64
import json
import re
import secrets
import sys
import time
import urllib.request
from pathlib import Path

SITE = "https://www.pbbcollab.live"
CLIENT = "v3"
CAMERAS = [("all", "PBB All Access"), ("din", "PBB Dining Area"), ("ext", "PBB External Den")]
PREFIX = "data:application/dash+xml;base64,"


def handshake():
    """The randomised payload the site's own page posts before it plays."""
    seed = secrets.token_bytes(13)
    key = seed[:12].hex()
    tag = (seed[12] & 0xfe) | (seed[0] & 0x01)
    plain = f"{tag:02x}:{key[:8]}:{int(time.time() * 1000) & 0xffff:04x}"
    sealed = bytes(ord(c) ^ ord(key[i % len(key)]) ^ 0x3c for i, c in enumerate(plain))
    body = json.dumps({"k": key, "v": base64.b64encode(sealed).decode()}).encode("utf-8")
    request = urllib.request.Request(SITE + "/api/streams/session", data=body, method="POST",
                                     headers={"Content-Type": "application/json",
                                              "X-PBB-Client": CLIENT,
                                              "User-Agent": "PBB/1.0"})
    with urllib.request.urlopen(request, timeout=20) as response:
        cookie = "; ".join(raw.split(";")[0].strip()
                           for raw in response.headers.get_all("Set-Cookie") or [])
        return json.loads(response.read().decode("utf-8")), cookie


def unseal(token, nonce):
    raw = base64.b64decode(token)
    plain = "".join(chr(b ^ 0x5A ^ ord(nonce[i % len(nonce)])) for i, b in enumerate(raw))
    kid, key = plain.split(":")
    return kid.replace("-", "").lower(), key.replace("-", "").lower()


def manifest(endpoint, cookie):
    request = urllib.request.Request(SITE + endpoint,
                                     headers={"Cookie": cookie, "X-PBB-Client": CLIENT,
                                              "User-Agent": "PBB/1.0",
                                              "Cache-Control": "no-cache"})
    with urllib.request.urlopen(request, timeout=20) as response:
        return response.read().decode("utf-8")


def inlined(xml):
    """Hoist the absolute segment base into BaseURL, so init names stay relative."""
    base = re.search(r'media="(https://[^"]*/)', xml)
    if not base:
        return xml
    end = xml.find(">", xml.find("<MPD"))
    head = base.group(1)
    return (xml[:end + 1] + "<BaseURL>" + head + "</BaseURL>"
            + xml[end + 1:].replace('media="' + head, 'media="'))


reply, cookie = handshake()
streams = []
for cam, name in CAMERAS:
    kid, key = unseal(reply["streams"][cam]["token"], reply["nonce"])
    xml = inlined(manifest(reply["streams"][cam]["endpoint"], cookie))
    streams.append({"name": name,
                    "url": PREFIX + base64.b64encode(xml.encode("utf-8")).decode("ascii"),
                    "kid": kid, "key": key})

out = Path(sys.argv[1])
out.write_text(json.dumps({"streams": streams}, indent=2) + "\n", encoding="utf-8")
print(f"wrote {out} ({len(streams)} streams)")
PY
