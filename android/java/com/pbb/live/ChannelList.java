package com.pbb.live;

import java.util.ArrayList;
import java.util.List;

/** Reads the PBB rows out of the operator's M3U channel list. */
final class ChannelList {
    private static final String LICENSE = "#KODIPROP:inputstream.adaptive.license_key=";
    private static final String[] M3U_HOSTS = {
        "absp-live.akamaized.net", "abslive.akamaized.net"
    };

    private ChannelList() {
    }

    static final class Entry {
        final String name;
        final String url;
        final String kid;
        final String key;

        Entry(String name, String url, String kid, String key) {
            this.name = name;
            this.url = url;
            this.kid = kid;
            this.key = key;
        }
    }

    static List<Entry> fromM3u(String raw, String[] names, String streamHost) {
        Entry[] found = new Entry[names.length];
        String name = null;
        String license = null;
        for (String line : raw.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith("#EXTINF:")) {
                name = line.substring(line.lastIndexOf(',') + 1).trim();
                license = null;
            } else if (line.startsWith(LICENSE)) {
                license = line.substring(LICENSE.length()).trim();
            } else if (!line.isEmpty() && !line.startsWith("#") && name != null && license != null) {
                int index = indexOf(names, name);
                int colon = license.indexOf(':');
                if (index >= 0 && colon > 0) {
                    found[index] = new Entry(names[index], onHost(line, streamHost),
                        license.substring(0, colon).replace("-", ""),
                        license.substring(colon + 1).replace("-", ""));
                }
                name = null;
                license = null;
            }
        }
        List<Entry> streams = new ArrayList<Entry>();
        for (Entry entry : found) {
            if (entry == null) {
                return null;
            }
            streams.add(entry);
        }
        return streams;
    }

    private static String onHost(String url, String host) {
        for (String old : M3U_HOSTS) {
            url = url.replace(old, host);
        }
        return url;
    }

    private static int indexOf(String[] names, String name) {
        String clean = name.endsWith(" Live") ? name.substring(0, name.length() - 5) : name;
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(clean)) {
                return i;
            }
        }
        return -1;
    }
}
