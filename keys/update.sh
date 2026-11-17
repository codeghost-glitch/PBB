#!/usr/bin/env bash
# Refresh the checked-in fallback snapshot from the pbb3 site bundle.
# The runtime app reads the same bundle; the Rust fallback includes this
# snapshot, so one rewrite keeps them from drifting.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SITE="https://pbb3collablive.xyz"

python3 - "$SITE" "$HERE/keys.json" <<'PY'
import base64
import datetime
import json
import re
import sys
import urllib.request
from pathlib import Path

site, out_path = sys.argv[1:3]
channels = [
    ("PBB All Access", "4f8b2c15-7d9a-4e3b-8c0d-1e3f6a5b9c22"),
    ("PBB Dining Area", "6a1e3f89-2c4d-4b0e-9a1c-5d7e8f3a2b44"),
    ("PBB External Den", "8d2f7c34-1e5f-4a0e-8b1c-6a3e9f2d5b11"),
]
prefix = "data:application/dash+xml;base64,"


def get(url):
    request = urllib.request.Request(url, headers={"User-Agent": "PBB/1.0", "Cache-Control": "no-cache"})
    with urllib.request.urlopen(request, timeout=20) as response:
        return response.read().decode("utf-8")


def mpd(base, avail, kid, start):
    now = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.000Z")
    videos = [("1", "1920", "1080", "4000000", "avc1.4d4028", "1080p"),
              ("2", "1280", "720", "2000000", "avc1.4d401f", "720p"),
              ("3", "640", "360", "1000000", "avc1.4d401e", "360p"),
              ("4", "320", "180", "500000", "avc1.4d400d", "180p")]
    out = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" xmlns:cenc="urn:mpeg:cenc:2013" '
        f'type="dynamic" publishTime="{now}" minimumUpdatePeriod="PT30S" '
        f'availabilityStartTime="{avail}" minBufferTime="PT4S" '
        'suggestedPresentationDelay="PT12S" timeShiftBufferDepth="PT7200S" '
        'profiles="urn:mpeg:dash:profile:isoff-live:2011">',
        f'<BaseURL>{base}</BaseURL>',
        '<Period start="PT0S" id="1"><AdaptationSet mimeType="video/mp4" segmentAlignment="true">',
        f'<ContentProtection cenc:default_KID="{kid}" '
        'schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>',
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
        f'<ContentProtection cenc:default_KID="{kid}" '
        'schemeIdUri="urn:mpeg:dash:mp4protection:2011" value="cenc"/>',
        f'<SegmentTemplate timescale="48000" duration="192192" startNumber="{start}" '
        'media="manifest_audio_$Number$.mp4" initialization="manifest_audio_init.mp4"/>',
        '<Representation id="5" bandwidth="96000" audioSamplingRate="48000" codecs="mp4a.40.2"/>',
        '</AdaptationSet></Period></MPD>',
    ])
    return prefix + base64.b64encode("".join(out).encode("utf-8")).decode("ascii")


html = get(site + "/")
bundle = ""
for chunk in dict.fromkeys(re.findall(r'/_next/static/chunks/[^"\']+\.js', html)):
    js = get(site + chunk)
    if "let et={" in js:
        bundle = js
        break
if not bundle:
    raise RuntimeError("no pbb3 feed data in the site bundle")

out = []
for name, channel_id in channels:
    feed = re.search(
        re.escape('"' + channel_id + '":{baseUrl:"')
        + r'([^"]+)",availStart:"([^"]+)",kid:"([^"]+)",startNum:0x([0-9a-fA-F]+)',
        bundle)
    key = re.search(re.escape('"' + channel_id + '":') + r'\{[^}]*key:"([0-9a-fA-F]{32})"', bundle)
    if not feed or not key:
        raise RuntimeError("no pbb3 feed data for " + name)
    base, avail, kid, start = feed.group(1), feed.group(2), feed.group(3), int(feed.group(4), 16)
    out.append({"name": name, "url": mpd(base, avail, kid, start),
                "kid": kid.replace("-", ""), "key": key.group(1)})

out_path = Path(out_path)
out_path.write_text(json.dumps({"streams": out}, indent=2) + "\n", encoding="utf-8")
print(f"wrote {out_path} ({len(out)} streams)")
PY
