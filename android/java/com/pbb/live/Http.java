package com.pbb.live;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;
import java.util.Locale;

/**
 * The two HTTP GETs the app makes and the one stream reader, in a single place
 * so a timeout change lands in both the key fetch and the recorder.
 */
final class Http {
    private static final String AGENT = "PBB/1.0";

    static {
        // HttpURLConnection treats Origin as restricted and drops it unless
        // this is set. The site's chat API and proxy reject requests without it.
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    private Http() {
    }

    static HttpURLConnection open(String url, int connectMs, int readMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
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

    /**
     * The chat API only answers requests carrying the site's own Origin. The
     * app makes the hop natively because a file:// page cannot claim it.
     */
    static String getTextWithOrigin(String url, String origin, int connectMs, int readMs)
            throws IOException {
        HttpURLConnection conn = open(url, connectMs, readMs);
        conn.setRequestProperty("Origin", origin);
        try {
            if (conn.getResponseCode() != 200) {
                return null;
            }
            InputStream in = conn.getInputStream();
            try {
                return new String(readAll(in), "UTF-8");
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
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
     * The plain payload the site seals: a tag byte, the first eight key
     * characters and sixteen bits of the clock. It lives here, and not in the
     * activity, because keys/session.py writes the offline snapshot with the
     * same format and the host test compares the two.
     */
    static String sessionPlain(byte[] seed, String key, boolean backup, long millis) {
        int modeBit = backup ? 1 : 0;
        return String.format(Locale.US, "%02x:%s:%04x",
            (seed[12] & 0xfe) | (modeBit ^ (seed[0] & 0x01)), key.substring(0, 8),
            millis & 0xffffL);
    }

    /**
     * Posts a JSON body for chat and returns the reply even on an error, so
     * the page can tell a rejected message from a broken connection.
     */
    static String postJsonWithOrigin(String url, String body, String origin,
            int connectMs, int readMs) throws IOException {
        byte[] payload = body.getBytes("UTF-8");
        HttpURLConnection conn = open(url, connectMs, readMs);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Origin", origin);
        try {
            java.io.OutputStream out = conn.getOutputStream();
            try {
                out.write(payload);
            } finally {
                out.close();
            }
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in == null) {
                return "{\"error\":\"chat request failed\"}";
            }
            try {
                return new String(readAll(in), "UTF-8");
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Posts a JSON body. Returns null when the server answered anything but 200. */
    static Reply postJson(String url, String body, String client, int connectMs, int readMs)
            throws IOException {
        byte[] payload = body.getBytes("UTF-8");
        HttpURLConnection conn = open(url, connectMs, readMs);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
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
