"""The site's session payload, in one place.

keys/update.sh imports this to write the offline snapshot, and the host test
RecorderLogicTest compares it with the app's own copy, so the two cannot drift.
"""
import base64
import re

SITE = "https://www.pbbcollab.live"
CLIENT = "v3"
PREFIX = "data:application/dash+xml;base64,"


def plain(seed, key, mode, millis):
    """three-bit server mode, first 8 key characters, clock in seconds."""
    tag = (seed[12] & 0xF8) | ((mode ^ (seed[0] & 0x07)) & 0x07)
    return "%02x:%s:%08x" % (tag, key[:8], (millis // 1000) & 0xFFFFFFFF)


def seal(text, key):
    sealed = bytes(ord(c) ^ ord(key[i % len(key)]) ^ 0x3C
                   for i, c in enumerate(text))
    return base64.b64encode(sealed).decode("ascii")


def unseal(token, nonce):
    raw = base64.b64decode(token)
    text = "".join(chr(b ^ 0x5A ^ ord(nonce[i % len(nonce)]))
                   for i, b in enumerate(raw))
    kid, key = text.split(":")
    return kid.replace("-", "").lower(), key.replace("-", "").lower()


def inlined(xml):
    """Hoist the absolute segment base into BaseURL, so init names stay relative."""
    base = re.search(r'media="(https://[^"]*/)', xml)
    if not base:
        return xml
    end = xml.find(">", xml.find("<MPD"))
    head = base.group(1)
    return (xml[:end + 1] + "<BaseURL>" + head + "</BaseURL>"
            + xml[end + 1:].replace('media="' + head, 'media="'))
