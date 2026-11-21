package com.pbb.live;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The two HTTP GETs the app makes and the one stream reader, in a single place
 * so a timeout change lands in both the key fetch and the recorder.
 */
final class Http {
    private static final String AGENT = "PBB/1.0";
    private static final String CDN_LITERAL =
        "https://absp-live.akamaized.net/dash/live/2099522/pbbcollab30";
    private static final String CDN_ESCAPED =
        "https://absp-live.akamaized.net/dash/live/2099522/%70bbcollab30";

    static {
        // HttpURLConnection drops Origin and Referer unless this is set. The
        // session API rejects the request without them.
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    private Http() {
    }

    /**
     * Android's HttpURLConnection drops restricted headers even when the
     * JDK property is set. Put the value into the connection's own header map
     * as well, so the site sees the same request its page sends.
     */
    private static void setHeader(HttpURLConnection conn, String name, String value) {
        conn.setRequestProperty(name, value);
        try {
            Class<?> type = conn.getClass();
            while (type != null) {
                try {
                    Field field = type.getDeclaredField("requests");
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<String, List<String>> requests =
                        (Map<String, List<String>>) field.get(conn);
                    if (requests != null) {
                        String key = name.toLowerCase(Locale.US);
                        List<String> values = requests.get(key);
                        if (values == null) {
                            values = new ArrayList<String>();
                            requests.put(key, values);
                        }
                        values.clear();
                        values.add(value);
                    }
                    return;
                } catch (NoSuchFieldException e) {
                    type = type.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
            // setRequestProperty above still works on implementations without
            // the field.
        }
    }

    /** Akamai rejects the literal camera folder; escape its first letter. */
    static String normalizeCdnUrl(String url) {
        return url.replace(CDN_LITERAL, CDN_ESCAPED);
    }

    static HttpURLConnection open(String url, int connectMs, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(normalizeCdnUrl(url)).openConnection();
        c.setConnectTimeout(connectMs);
        c.setReadTimeout(readMs);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", AGENT);
        c.setRequestProperty("Cache-Control", "no-cache");
        return c;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** Returns null when the server answered anything but 200. */
    static byte[] getBytes(String url, int connectMs, int readMs, String cookie, String client)
            throws IOException {
        String prefix = "data:application/dash+xml;base64,";
        if (url.startsWith(prefix)) {
            try {
                return Base64.getDecoder().decode(url.substring(prefix.length()));
            } catch (IllegalArgumentException e) {
                throw new IOException("bad data manifest", e);
            }
        }
        HttpURLConnection conn = open(url, connectMs, readMs);
        if (cookie != null) {
            conn.setRequestProperty("Cookie", cookie);
        }
        if (client != null) {
            conn.setRequestProperty("X-PBB-Client", client);
        }
        try {
            if (conn.getResponseCode() != 200) {
                return null;
            }
            InputStream in = conn.getInputStream();
            try {
                return readAll(in);
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    static byte[] getBytes(String url, int connectMs, int readMs) throws IOException {
        return getBytes(url, connectMs, readMs, null, null);
    }

    static String getText(String url, int connectMs, int readMs) throws IOException {
        byte[] body = getBytes(url, connectMs, readMs);
        return body == null ? null : new String(body, "UTF-8");
    }

    static String getText(String url, int connectMs, int readMs, String cookie, String client)
            throws IOException {
        byte[] body = getBytes(url, connectMs, readMs, cookie, client);
        return body == null ? null : new String(body, "UTF-8");
    }

    /** A POST reply plus the session cookie the server set, if any. */
    static final class Reply {
        final String body;
        final String cookie;

        Reply(String body, String cookie) {
            this.body = body;
            this.cookie = cookie;
        }
    }

    /**
     * The plain payload the site seals: a three-bit server mode in the tag,
     * the first eight key characters and the clock in seconds. It lives here,
     * and not in the activity, because keys/session.py writes the offline
     * snapshot with the same format and the host test compares the two.
     */
    static String sessionPlain(byte[] seed, String key, int mode, long millis) {
        int tag = (seed[12] & 0xf8) | ((mode ^ (seed[0] & 0x07)) & 0x07);
        return String.format(Locale.US, "%02x:%s:%08x",
            tag, key.substring(0, 8), (millis / 1000L) & 0xffffffffL);
    }

    /** Posts a JSON body. Returns null when the server answered anything but 200. */
    static Reply postJson(String url, String body, String client, int connectMs, int readMs)
            throws IOException {
        byte[] payload = body.getBytes("UTF-8");
        HttpURLConnection conn = open(url, connectMs, readMs);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        // The session API rejects a plain API client. It wants the same
        // same-origin fetch headers the site's own page sends.
        setHeader(conn, "Accept", "application/json, text/plain, */*");
        setHeader(conn, "Origin", "https://www.pbbcollab.live");
        setHeader(conn, "Referer", "https://www.pbbcollab.live/");
        setHeader(conn, "Sec-Fetch-Site", "same-origin");
        setHeader(conn, "Sec-Fetch-Mode", "cors");
        setHeader(conn, "Sec-Fetch-Dest", "empty");
        setHeader(conn, "User-Agent", "Mozilla/5.0 (Android) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36");
        if (client != null) {
            conn.setRequestProperty("X-PBB-Client", client);
        }
        try {
            java.io.OutputStream out = conn.getOutputStream();
            try {
                out.write(payload);
            } finally {
                out.close();
            }
            if (conn.getResponseCode() != 200) {
                return null;
            }
            InputStream in = conn.getInputStream();
            String cookie = conn.getHeaderField("Set-Cookie");
            try {
                int end = cookie == null ? -1 : cookie.indexOf(';');
                return new Reply(new String(readAll(in), "UTF-8"),
                    end > 0 ? cookie.substring(0, end).trim() : null);
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }
}
