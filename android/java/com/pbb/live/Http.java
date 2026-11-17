package com.pbb.live;

import android.webkit.CookieManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * The HTTP calls the app makes, in one place. The pbbcollab.live session cookie
 * is remembered here because its manifests and segments need the same session.
 */
final class Http {
    private static final String AGENT = "PBB/1.0";
    private static final String SITE = "https://www.pbbcollab.live";
    private static volatile String siteCookie = null;

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
        if (c.getURL().getHost().endsWith("pbbcollab.live")) {
            c.setRequestProperty("X-PBB-Client", "v3");
            c.setRequestProperty("Origin", SITE);
            c.setRequestProperty("Referer", SITE + "/");
            String cookie = siteCookie;
            if (cookie != null) {
                c.setRequestProperty("Cookie", cookie);
            }
        }
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
    static byte[] getBytes(String url, int connectMs, int readMs) throws IOException {
        HttpURLConnection conn = open(url, connectMs, readMs);
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

    static String getText(String url, int connectMs, int readMs) throws IOException {
        byte[] body = getBytes(url, connectMs, readMs);
        return body == null ? null : new String(body, "UTF-8");
    }

    /** Posts JSON and keeps the session cookie returned by pbbcollab.live. */
    static String postJson(String url, String body, int connectMs, int readMs) throws IOException {
        HttpURLConnection conn = open(url, connectMs, readMs);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        OutputStream out = conn.getOutputStream();
        try {
            out.write(body.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        try {
            String set = conn.getHeaderField("Set-Cookie");
            if (set != null && set.toLowerCase(Locale.ROOT).contains("pbb_sid")) {
                siteCookie = set.split(";", 2)[0];
                CookieManager.getInstance().setCookie(SITE, set);
            }
            if (conn.getResponseCode() != 200) {
                return null;
            }
            return new String(readAll(conn.getInputStream()), "UTF-8");
        } finally {
            conn.disconnect();
        }
    }
}
