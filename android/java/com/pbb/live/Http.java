package com.pbb.live;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;

/**
 * The two HTTP GETs the app makes and the one stream reader, in a single place
 * so a timeout change lands in both the key fetch and the recorder.
 */
final class Http {
    private static final String AGENT = "PBB/1.0";

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
    static byte[] getBytes(String url, int connectMs, int readMs, String referer) throws IOException {
        String prefix = "data:application/dash+xml;base64,";
        if (url.startsWith(prefix)) {
            try {
                return Base64.getDecoder().decode(url.substring(prefix.length()));
            } catch (IllegalArgumentException e) {
                throw new IOException("bad data manifest", e);
            }
        }
        HttpURLConnection conn = open(url, connectMs, readMs);
        if (referer != null) {
            conn.setRequestProperty("Referer", referer);
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
        return getBytes(url, connectMs, readMs, null);
    }

    static String getText(String url, int connectMs, int readMs, String referer) throws IOException {
        byte[] body = getBytes(url, connectMs, readMs, referer);
        return body == null ? null : new String(body, "UTF-8");
    }

    static String getText(String url, int connectMs, int readMs) throws IOException {
        return getText(url, connectMs, readMs, null);
    }
}
