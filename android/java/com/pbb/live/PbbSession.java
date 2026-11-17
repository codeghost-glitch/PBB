package com.pbb.live;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/** Reads the two pbbcollab.live stream servers through their session API. */
final class PbbSession {
    private static final String ORIGIN = "https://www.pbbcollab.live";
    private static final String[] CAMS = {"all", "din", "ext"};

    private PbbSession() {
    }

    static String config(String serverKey, String[] names) throws Exception {
        String raw = Http.postJson(ORIGIN + "/api/streams/session", payload(serverKey), 8000, 12000);
        JSONObject data = new JSONObject(raw);
        String nonce = data.getString("nonce");
        JSONObject streams = data.getJSONObject("streams");
        JSONArray listed = new JSONArray();
        for (int i = 0; i < CAMS.length; i++) {
            JSONObject desc = streams.getJSONObject(CAMS[i]);
            String[] pair = decode(desc.getString("token"), nonce);
            String endpoint = desc.getString("endpoint");
            JSONObject entry = new JSONObject();
            entry.put("name", names[i]);
            entry.put("url", ORIGIN + endpoint
                + (endpoint.contains("?") ? "&" : "?") + "_b="
                + (System.currentTimeMillis() / 15000L));
            entry.put("kid", pair[0]);
            entry.put("key", pair[1]);
            listed.put(entry);
        }
        return new JSONObject().put("streams", listed).toString();
    }

    private static String payload(String serverKey) throws Exception {
        byte[] rand = new byte[13];
        new SecureRandom().nextBytes(rand);
        StringBuilder k = new StringBuilder(24);
        for (int i = 0; i < 12; i++) {
            k.append(String.format(Locale.ROOT, "%02x", rand[i] & 0xff));
        }
        int modeBit = "s2".equals(serverKey) ? 1 : 0;
        int tag = (rand[12] & 0xfe) | (modeBit ^ (rand[0] & 1));
        String raw = String.format(Locale.ROOT, "%02x:%s:%04x", tag, k.substring(0, 8),
            System.currentTimeMillis() & 0xffffL);
        StringBuilder x = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            x.append((char) (raw.charAt(i) ^ k.charAt(i % k.length()) ^ 0x3c));
        }
        String v = Base64.getEncoder().encodeToString(x.toString().getBytes("ISO-8859-1"));
        return new JSONObject().put("k", k.toString()).put("v", v).toString();
    }

    private static String[] decode(String token, String nonce) throws Exception {
        byte[] body = Base64.getDecoder().decode(token);
        StringBuilder raw = new StringBuilder(body.length);
        for (int i = 0; i < body.length; i++) {
            raw.append((char) ((body[i] & 0xff) ^ 0x5a ^ nonce.charAt(i % nonce.length())));
        }
        return raw.toString().split(":", 2);
    }
}
