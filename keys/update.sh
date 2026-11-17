#!/usr/bin/env bash
# Refresh the checked-in fallback snapshot from the pbb-v2 site bundle.
# The runtime app reads the same site; the Rust fallback includes this snapshot.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SITE="https://pbb-v2.pages.dev"

python3 - "$SITE" "$HERE/keys.json" <<'PY'
import base64
import datetime
import email.utils
import json
import re
import sys
import urllib.request
from pathlib import Path

site, out_path = sys.argv[1:3]
channels = [("PBB All Access", "1"), ("PBB Dining Area", "2"), ("PBB External Den", "3")]
prefix = "data:application/dash+xml;base64,"
mask = bytes([0x7a, 0x56, 0x33, 0x30, 0x9e, 0x37, 0x79, 0xb9, 0x7f, 0x4a, 0x7c, 0x15,
              0xf3, 0x9c, 0xc0, 0x60, 0x51, 0xd7, 0x28, 0xae, 0x52, 0x99, 0x16, 0x34,
              0xc6, 0x71, 0x48, 0x92, 0xa5, 0x5d, 0xe8, 0x1f])


def get(url):
    request = urllib.request.Request(url, headers={"User-Agent": "PBB/1.0", "Cache-Control": "no-cache"})
    with urllib.request.urlopen(request, timeout=20) as response:
        return response.read().decode("utf-8")


def unseal(js):
    payload = json.loads(re.search(r"const SEALED_VAULT_PAYLOAD\s*=\s*(\{.*?\});", js).group(1))
    salt = base64.b64decode(payload["s"])
    data = base64.b64decode(payload["d"])
    plain = bytearray()
    for i, b in enumerate(data):
        unrot = ((b >> 3) | (b << 5)) & 0xff
        m = mask[(i * 7 + 3) & 31] ^ salt[i & 7] ^ ((i * 131 + 17) & 0xff)
        plain.append(unrot ^ m)
    return json.loads(plain.decode("utf-8"))


def probe_sidx(base, segment):
    url = base + f"manifest_360p_{segment}.mp4"
    request = urllib.request.Request(url, headers={"User-Agent": "PBB/1.0", "Range": "bytes=0-511"})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            data = response.read(512)
            modified = int(email.utils.parsedate_to_datetime(
                response.headers["Last-Modified"]
            ).timestamp()) if response.headers.get("Last-Modified") else 0
    except Exception:
        return None
    off = 0
    while off + 32 <= len(data):
        size = int.from_bytes(data[off:off + 4], "big")
        if size < 8:
            return None
        if data[off + 4:off + 8] == b"sidx":
            ept = int.from_bytes(data[off + 20:off + 24], "big")
            index = max(0, round((ept - 3003) / 360360))
            return segment - index, modified
        off += size
    return None


def resync(base, start, period):
    now = int(datetime.datetime.now(datetime.timezone.utc).timestamp())
    estimate = start + int((now - period) / 4.004) - 3
    hit = probe_sidx(base, estimate)
    if hit is None:
        hit = probe_sidx(base, now - 10)
    if hit is None:
        return start, period
    return hit[0], hit[1] or hit[0] + 1


def pssh(kid):
    box = bytearray(52)
    box[0:4] = (52).to_bytes(4, "big")
    box[4:8] = b"pssh"
    box[8:12] = (0x01000000).to_bytes(4, "big")
    box[12:28] = bytes.fromhex("1077efecc0b24d02ace33c1e52e2fb4b")
    box[28:32] = (1).to_bytes(4, "big")
    box[32:48] = bytes.fromhex(kid.replace("-", ""))
    return base64.b64encode(box).decode("ascii")


def mpd(base, kid, start, period_start):
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    protection = "<cenc:pssh>" + pssh(kid) + "</cenc:pssh>"
    period = datetime.datetime.fromtimestamp(period_start, datetime.timezone.utc).strftime(
        "%Y-%m-%dT%H:%M:%S.000Z")
    uuid = f"{kid[:8]}-{kid[8:12]}-{kid[12:16]}-{kid[16:20]}-{kid[20:]}"
    videos = [("1", "1920", "1080", "4000000", "avc1.4d4028", "1080p"),
              ("2", "1280", "720", "2000000", "avc1.4d401f", "720p"),
              ("3", "640", "360", "1000000", "avc1.4d401e", "360p"),
              ("4", "320", "180", "500000", "avc1.4d400d", "180p")]
    out = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013" '
        f'type="dynamic" minimumUpdatePeriod="PT4S" availabilityStartTime="{period}" '
        'timeShiftBufferDepth="PT120S" minBufferTime="PT4.000S" '
        'suggestedPresentationDelay="PT12S" profiles="urn:mpeg:dash:profile:isoff-live:2011" '
        f'publishTime="{now}"><BaseURL>{base}</BaseURL>',
        f'<Period start="PT0S" id="p_{start}"><AdaptationSet mimeType="video/mp4" segmentAlignment="true">',
        f'<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" cenc:default_KID="{uuid}">{protection}</ContentProtection>',
        '<ContentProtection schemeIdUri="urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e" value="ClearKey1.0"/>',
        f'<SegmentTemplate timescale="90000" duration="360360" startNumber="{start}"/>',
    ]
    for ident, width, height, bandwidth, codecs, label in videos:
        out.append(
            f'<Representation id="{ident}" width="{width}" height="{height}" '
            f'bandwidth="{bandwidth}" codecs="{codecs}">'
            f'<SegmentTemplate media="manifest_{label}_$Number$.mp4" '
            f'initialization="manifest_{label}_init.mp4"/></Representation>')
    out.extend([
        '</AdaptationSet><AdaptationSet mimeType="audio/mp4" segmentAlignment="true">',
        f'<ContentProtection schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc" cenc:default_KID="{uuid}">{protection}</ContentProtection>',
        '<ContentProtection schemeIdUri="urn:uuid:e2719d58-a985-b3c9-781a-b030af78d30e" value="ClearKey1.0"/>',
        f'<SegmentTemplate timescale="48000" duration="192192" startNumber="{start}" '
        'media="manifest_audio_$Number$.mp4" initialization="manifest_audio_init.mp4"/>',
        '<Representation id="5" bandwidth="96000" audioSamplingRate="48000" codecs="mp4a.40.2"/>',
        f'</AdaptationSet></Period><UTCTiming schemeIdUri="urn:mpeg:dash:utc:direct:2014" value="{now}"/></MPD>',
    ])
    return prefix + base64.b64encode("".join(out).encode("utf-8")).decode("ascii")


html = get(site + "/")
bundle = re.search(r"live-stream4\.js\?v=\d+", html)
if not bundle:
    raise RuntimeError("no live-stream4 bundle on the pbb-v2 site")
js = get(site + "/" + bundle.group(0))
vault = unseal(js)

out = []
for name, channel_id in channels:
    spec = re.search(
        re.escape('"' + channel_id + '":') + r'\s*\{\s*baseDir:\s*"([^"]+)",\s*availStart:\s*"[^"]+",\s*'
        r'kid:\s*"([^"]+)",\s*p1Dur:\s*"[^"]+",\s*p1StartNum:\s*\d+,\s*'
        r'p2StartNum:\s*(\d+),\s*periodStartSec:\s*(\d+)',
        js)
    if not spec:
        raise RuntimeError("no pbb-v2 period spec for " + name)
    base, kid, start, period = spec.group(1), spec.group(2), int(spec.group(3)), int(spec.group(4))
    start, period = resync(base, start, period)
    out.append({"name": name, "url": mpd(base, kid.replace("-", ""), start, period),
                "kid": kid.replace("-", ""), "key": vault[channel_id]["key"]})

out_path = Path(out_path)
out_path.write_text(json.dumps({"streams": out}, indent=2) + "\n", encoding="utf-8")
print(f"wrote {out_path} ({len(out)} streams)")
PY
