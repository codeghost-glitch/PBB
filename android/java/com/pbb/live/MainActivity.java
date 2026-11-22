package com.pbb.live;

import android.Manifest;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Rational;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 2001;
    private static final String SITE = "https://www.pbbcollab.live";
    private static final String SITE2 = "https://pbbcollab.com";
    private static final String CLIENT = "v3";
    private static final String DATA_MPD = "data:application/dash+xml;base64,";
    // The site carries four cameras; the grid is drawn for three, so the
    // official broadcast feed stays on the web player.
    private static final String[] CAM_IDS = {"all", "din", "ext"};
    // Server 3 plays the same feeds through the pbb3collablive.xyz mirror, so
    // its segment route is a third way in when the CDN is blocked.
    private static final String MIRROR = "https://pbb3collablive.xyz/api/proxy/stream/";
    private static final String[] CAM_MIRROR = {
        "4f8b2c15-7d9a-4e3b-8c0d-1e3f6a5b9c22",
        "6a1e3f89-2c4d-4b0e-9a1c-5d7e8f3a2b44",
        "8d2f7c34-1e5f-4a0e-8b1c-6a3e9f2d5b11"
    };
    // The direct CDN manifests published by pbb-3.pages.dev. Same cameras,
    // same keys, one more way in when the site routes are blocked. Akamai
    // rejects the literal folder name, so the site spells its first letter %70.
    private static final String[] PBB3_MPD = {
        "https://absp-live.akamaized.net/dash/live/2099522/"
            + "%70bbcollab30allaccesslivestream/manifest.mpd",
        "https://absp-live.akamaized.net/dash/live/2099522/"
            + "%70bbcollab30allaccesslivestream-diningarea/manifest.mpd",
        "https://absp-live.akamaized.net/dash/live/2099522/"
            + "%70bbcollab30allaccesslivestream-externalden/manifest.mpd"
    };
    // The mirror publishes one tokenized MPD per camera. Its segment names
    // carry an access token in the query, so it is its own route.
    private static final String[] MIRROR_MPD = {
        MIRROR + CAM_MIRROR[0] + "/manifest.mpd",
        MIRROR + CAM_MIRROR[1] + "/manifest.mpd",
        MIRROR + CAM_MIRROR[2] + "/manifest.mpd"
    };
    private static final String[] CHANNEL_NAMES = {
        "PBB All Access", "PBB Dining Area", "PBB External Den"
    };
    // The gallery is one large feed over two small ones, so the list has to
    // hold the same number of channels as the built-in config.
    private static final int CHANNEL_COUNT = 3;
    private static final Pattern MEDIA_BASE = Pattern.compile(
        "media=\"((?:https://[^\"?]*/|/)[^\"?]*/)[^\"?]*");
    private static final String KEYS_CACHE = "streams.json";
    private static final String SERVER_FILE = "stream_server.txt";
    private static final String LIVE_SERVER_FILE = "live_server.txt";
    private static final String SOURCE_FILE = "stream_source.txt";
    // The remote catalog is the source of truth. The checked-in asset is the
    // first-run copy; a downloaded copy is kept for offline starts.
    private static final String CATALOG_URL =
        "https://raw.githubusercontent.com/codeghost-glitch/PBB/main/android/assets/servers.json";
    private static final String CATALOG_ASSET = "servers.json";
    private static final String CATALOG_CACHE = "server_catalog.json";
    private static volatile String serverCatalogJson = "";

    static {
        System.loadLibrary("pbb");
    }

    private static native String streamConfig();
    private static native String defaultQuality(String filesDir);
    private static native void setDefaultQuality(String filesDir, String quality);
    private static native float appVolume(String filesDir);
    private static native void setAppVolume(String filesDir, float value);
    private static native float appBrightness(String filesDir);
    private static native void setAppBrightness(String filesDir, float value);

    private WebView webView;
    private FrameLayout rootView;
    private boolean immersive = false;
    private boolean isInPip = false;
    private int focusedStream = -1;
    // Permissions are requested once for a batch, then every waiting camera is
    // started together, so recording all three is one tap and one dialog.
    private final ArrayList<Integer> pendingRecordIndices = new ArrayList<Integer>();
    private final ArrayList<String> pendingRecordLabels = new ArrayList<String>();
    // The gallery opens after hours: the page owns the choice, the shell follows
    // it so the window behind the WebView and the system bar icons match.
    private float appVolumeLevel = -1f;
    private float appBrightnessLevel = -1f;
    private int savedSystemVolume = -1;
    // The volume level the app itself set, so it knows whether it still owns
    // the system volume when the activity goes away.
    private int appliedVolume = -1;
    private volatile String streamConfigJson;
    // The site runs the cameras off a main server and a backup one.
    private volatile String streamServer = "s1";
    private volatile String liveServer = "s1";
    private volatile boolean useLiveSite = false;
    // The site's segment route only answers its own page: a file:// WebView
    // asking for it is "cross-site stream hotlinking". The cookie and the
    // origin for that hop are kept here.
    private volatile String sessionCookie = "";
    private volatile String sessionClient = "v3";
    // The page boots once. Handing it a cached config while the launch
    // handshake is still running is what made every tile load twice.
    private final Object configLock = new Object();
    private volatile boolean configFresh = false;
    // The operator asked for a stitch. All three starts can also arrive as one
    // batch from three plain record taps, and only this flag tells them apart.
    private volatile boolean stitchPending = false;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean active = intent.getBooleanExtra(RecordService.EXTRA_RECORDING, false);
            int index = intent.getIntExtra(RecordService.EXTRA_STREAM_INDEX, -1);
            int count = intent.getIntExtra(RecordService.EXTRA_COUNT, 0);
            long elapsed = intent.getLongExtra(RecordService.EXTRA_ELAPSED, 0);
            String kind = intent.getStringExtra(RecordService.EXTRA_KIND);
            String message = intent.getStringExtra(RecordService.EXTRA_MESSAGE);
            notifyRecordingState(active, index, count, elapsed, kind, message);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String filesDir = getFilesDir().getAbsolutePath();
        appVolumeLevel = appVolume(filesDir);
        appBrightnessLevel = appBrightness(filesDir);
        streamConfigJson = streamConfig();
        loadServerCatalog();
        streamServer = loadServer();
        liveServer = loadLiveServer();
        useLiveSite = loadSource();
        loadCachedKeys();
        // Start the handshake before the page exists, so the config the page
        // asks for on boot is the fresh one.
        refreshServerCatalog();
        refreshKeys();
        setupWebView();
        applyOrientation();
        registerStatusReceiver();
        updatePipParams();
    }

    private void setupWebView() {
        webView = new WebView(this);
        // Android 15 with targetSdk 35 forces edge to edge, so nothing reserves
        // the status and navigation bar space any more. Pad the container instead
        // of the WebView: a child is laid out inside its parent's padding, and
        // padding on a WebView itself does not shrink what it renders.
        rootView = new FrameLayout(this);
        rootView.setBackgroundColor(0xFF0E0E0F);
        applyBarIconAppearance();
        rootView.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                applyInsetsAsPadding(insets);
                return insets;
            }
        });
        rootView.addView(
            webView,
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(rootView);
        if (Build.VERSION.SDK_INT >= 35) {
            // Correct the first frame, before any inset dispatch arrives.
            rootView.setPadding(
                0, dimension("status_bar_height"), 0, dimension("navigation_bar_height"));
            rootView.requestApplyInsets();
        }
        webView.setBackgroundColor(0xFF0E0E0F);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        // The page needs to fetch the manifest and the segments over https,
        // which is what the universal access flag is for. It does not need to
        // read the photo library or other files through content:// or file://.
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setGeolocationEnabled(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setDefaultTextEncodingName("utf-8");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // The launch handshake already ran and the page booted on it.
                // A second one here handed the page a manifest whose publish
                // time had moved, which read as "keys changed" and reloaded
                // every tile right after start-up.
                if (!configFresh) {
                    refreshKeys();
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(
                WebView view, WebResourceRequest request) {
                // The site publishes its segment names as relative paths now,
                // so even server 1 lands on the site's proxy. That proxy
                // rejects a file:// page as cross-site hotlinking and the CDN
                // is not always reachable. Route every media hop through here:
                // the app can send the right Origin for each host and hand the
                // WebView a plain CORS-clean response.
                String url = request.getUrl().toString();
                if (url.startsWith(SITE + "/stream/")
                    || url.startsWith(SITE2 + "/stream/")
                    || url.startsWith(SITE2 + "/chat/")
                    || url.startsWith("https://absp-live.akamaized.net/")
                    || url.startsWith(MIRROR)) {
                    return proxyStream(url, request.getRequestHeaders());
                }
                return null;
            }
        });
        webView.addJavascriptInterface(new PbbBridge(), "Pbb");
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void registerStatusReceiver() {
        IntentFilter filter = new IntentFilter(RecordService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
    }

    private void loadCachedKeys() {
        File cache = new File(getFilesDir(), KEYS_CACHE);
        if (!cache.exists()) {
            return;
        }
        try {
            FileInputStream in = new FileInputStream(cache);
            String json;
            try {
                json = new String(Http.readAll(in), "UTF-8");
            } finally {
                in.close();
            }
            if (validKeys(json) != null) {
                streamConfigJson = json;
            }
        } catch (Exception ignored) {
        }
    }

    /** Fetches the current stream config off the UI thread and pushes it to the page. */
    private void refreshKeys() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String json = publishedConfig();
                if (json == null) {
                    // Nothing to wait for: the page keeps the cached config.
                    markConfigFresh();
                    reportKeys(rotationReport());
                    return;
                }
                streamConfigJson = json;
                markConfigFresh();
                final String quoted = JSONObject.quote(json);
                writeKeysCache(json);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (webView != null) {
                            webView.evaluateJavascript(
                                "window.onStreamConfig && window.onStreamConfig(" + quoted + ")",
                                null);
                        }
                    }
                });
            }
        }, "pbb-keys").start();
    }

    /**
     * Writes the keys cache through a temp file and a rename, so a crash in
     * the middle of the write cannot leave a half written file behind.
     */
    private void writeKeysCache(String json) {
        writeAtomic(KEYS_CACHE, json);
    }

    /** Writes a small JSON file through a temp file and rename. */
    private void writeAtomic(String name, String json) {
        File tmp = new File(getFilesDir(), name + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(json.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(new File(getFilesDir(), name))) {
                throw new IOException("cannot replace " + name);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Reads the server catalog. A downloaded copy wins; the checked-in asset
     * is there for the first run and for when GitHub is unreachable.
     */
    private void loadServerCatalog() {
        String cached = readText(new File(getFilesDir(), CATALOG_CACHE));
        String catalog = validServerCatalog(cached);
        if (catalog == null) {
            catalog = validServerCatalog(readAsset(CATALOG_ASSET));
        }
        if (catalog != null) {
            serverCatalogJson = catalog;
        }
    }

    private String readText(File file) {
        try {
            FileInputStream in = new FileInputStream(file);
            try {
                return new String(Http.readAll(in), "UTF-8");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String readAsset(String name) {
        try {
            InputStream in = getAssets().open(name);
            try {
                return new String(Http.readAll(in), "UTF-8");
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Fetches the catalog on the refresh path and pushes it to the page. */
    private void refreshServerCatalog() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String raw = null;
                try {
                    raw = Http.getText(CATALOG_URL + "?_pbb=" + System.currentTimeMillis(),
                        5000, 8000);
                } catch (Exception ignored) {
                }
                String next = validServerCatalog(raw);
                if (next == null) {
                    return;
                }
                if (!next.equals(serverCatalogJson)) {
                    serverCatalogJson = next;
                    writeAtomic(CATALOG_CACHE, next);
                    if (reconcileServerPicks()) {
                        refreshKeys();
                    }
                }
                final String quoted = JSONObject.quote(next);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (webView != null) {
                            webView.evaluateJavascript(
                                "window.onServerCatalog && window.onServerCatalog(" + quoted + ")",
                                null);
                        }
                    }
                });
            }
        }, "pbb-catalog").start();
    }

    /** Validates and normalises a downloaded catalog before it can steer a route. */
    static String validServerCatalog(String raw) {
        if (raw == null || raw.length() > 65536) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", -1) != 1) {
                return null;
            }
            JSONArray site = root.optJSONArray("site");
            JSONArray live = root.optJSONArray("live");
            if (site == null || live == null || site.length() < 1 || live.length() < 1
                || site.length() > 64 || live.length() > 64) {
                return null;
            }
            return new JSONObject()
                .put("version", 1)
                .put("site", validCatalogGroup(site, false))
                .put("live", validCatalogGroup(live, true))
                .toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONArray validCatalogGroup(JSONArray group, boolean live) throws Exception {
        JSONArray out = new JSONArray();
        Set<String> ids = new HashSet<String>();
        for (int i = 0; i < group.length(); i++) {
            JSONObject in = group.getJSONObject(i);
            String id = in.optString("id", "");
            if (!id.matches("[a-z0-9][a-z0-9_-]{0,31}") || !ids.add(id)) {
                throw new IllegalArgumentException("bad server id");
            }
            String name = catalogText(in, "name", 1, 64);
            String host = catalogText(in, "host", 1, 120);
            String note = catalogText(in, "note", 0, 120);
            String source = catalogText(in, "source", 2, 16).toUpperCase(Locale.US);
            if (!source.matches("[A-Z][A-Z0-9 ]{1,15}")) {
                throw new IllegalArgumentException("bad source label");
            }
            int mode = in.optInt("mode", -1);
            int srv = in.optInt("srv", -1);
            if (mode < 0 || mode > 7 || srv < 0 || srv > 7) {
                throw new IllegalArgumentException("bad route number");
            }
            String kind = live ? "live" : in.optString("kind", "site");
            String segments = in.optString("segments", "site");
            if (live) {
                if (!"mirror".equals(segments) && !"site".equals(segments)) {
                    throw new IllegalArgumentException("bad segment route");
                }
            } else {
                if (!"site".equals(kind) && !"pbb3".equals(kind) && !"mirror".equals(kind)) {
                    throw new IllegalArgumentException("bad server kind");
                }
                if (!"site".equals(segments) && !"mirror".equals(segments)) {
                    throw new IllegalArgumentException("bad segment route");
                }
            }
            JSONObject entry = new JSONObject()
                .put("id", id)
                .put("name", name)
                .put("host", host)
                .put("note", note)
                .put("source", source)
                .put("kind", kind)
                .put("mode", mode)
                .put("srv", srv)
                .put("segments", segments);
            out.put(entry);
        }
        return out;
    }

    private static String catalogText(JSONObject entry, String key, int min, int max)
        throws Exception {
        String value = entry.optString(key, "").trim();
        if (value.length() < min || value.length() > max) {
            throw new IllegalArgumentException("bad " + key);
        }
        return value;
    }

    private static JSONObject catalogEntry(String id, boolean live) {
        try {
            JSONArray group = new JSONObject(serverCatalogJson)
                .getJSONArray(live ? "live" : "site");
            for (int i = 0; i < group.length(); i++) {
                JSONObject entry = group.getJSONObject(i);
                if (entry.optString("id", "").equals(id)) {
                    return entry;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String firstCatalogId(boolean live) {
        try {
            JSONArray group = new JSONObject(serverCatalogJson)
                .getJSONArray(live ? "live" : "site");
            return group.getJSONObject(0).optString("id", "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean reconcileServerPicks() {
        boolean changed = false;
        if (!validServer(streamServer)) {
            streamServer = defaultServerId(false);
            storeServer(streamServer);
            changed = true;
        }
        if (!validLiveServer(liveServer)) {
            liveServer = defaultServerId(true);
            storeLiveServer(liveServer);
            changed = true;
        }
        return changed;
    }

    private static String defaultServerId(boolean live) {
        String id = firstCatalogId(live);
        return id.isEmpty() ? "s1" : id;
    }

    /** The pbbcollab.com route picked last. */
    private String loadServer() {
        try {
            FileInputStream in = new FileInputStream(new File(getFilesDir(), SERVER_FILE));
            String raw;
            try {
                raw = new String(Http.readAll(in), "UTF-8").trim();
            } finally {
                in.close();
            }
            return validServer(raw) ? raw : defaultServerId(false);
        } catch (Exception e) {
            return "s1";
        }
    }

    private String loadLiveServer() {
        try {
            FileInputStream in = new FileInputStream(new File(getFilesDir(), LIVE_SERVER_FILE));
            String raw;
            try {
                raw = new String(Http.readAll(in), "UTF-8").trim();
            } finally {
                in.close();
            }
            return validLiveServer(raw) ? raw : defaultServerId(true);
        } catch (Exception e) {
            return "s1";
        }
    }

    private boolean loadSource() {
        try {
            FileInputStream in = new FileInputStream(new File(getFilesDir(), SOURCE_FILE));
            String raw;
            try {
                raw = new String(Http.readAll(in), "UTF-8").trim();
            } finally {
                in.close();
            }
            return "live".equals(raw);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean validServer(String server) {
        return server != null && catalogEntry(server, false) != null;
    }

    private static boolean validLiveServer(String server) {
        return server != null && catalogEntry(server, true) != null;
    }

    private static int catalogNumber(String server, boolean live, String key, int fallback) {
        JSONObject entry = catalogEntry(server, live);
        return entry == null ? fallback : entry.optInt(key, fallback);
    }

    /** The site MPD route published by the catalog entry. */
    private static int site2Number(String server) {
        return catalogNumber(server, false, "srv", 0);
    }

    private static String serverKind(String server) {
        JSONObject entry = catalogEntry(server, false);
        return entry == null ? "site" : entry.optString("kind", "site");
    }

    private static String serverSegments(String server) {
        JSONObject entry = catalogEntry(server, false);
        return entry == null ? "site" : entry.optString("segments", "site");
    }

    private int currentSrv() {
        if (useLiveSite) {
            return catalogNumber(liveServer, true, "srv", 1);
        }
        return site2Number(streamServer);
    }

    private void storeServer(String server) {
        storeText(SERVER_FILE, server);
    }

    private void storeLiveServer(String server) {
        storeText(LIVE_SERVER_FILE, server);
    }

    private void storeSource(boolean live) {
        storeText(SOURCE_FILE, live ? "live" : "site");
    }

    private void storeText(String name, String value) {
        try {
            FileOutputStream out = new FileOutputStream(new File(getFilesDir(), name));
            try {
                out.write(value.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Switches the feed between the main server and the backup one and pulls
     * a fresh config from it. Returns an empty string when the pick was taken.
     */
    private String setStreamServer(String server) {
        if (!validServer(server)) {
            return "Choose a server from pbbcollab.com";
        }
        boolean changed = !server.equals(streamServer) || useLiveSite;
        streamServer = server;
        useLiveSite = false;
        storeServer(server);
        storeSource(false);
        if (changed) {
            refreshKeys();
        }
        return "";
    }

    private String setLiveServer(String server) {
        if (!validLiveServer(server)) {
            return "Choose a server from pbbcollab.live";
        }
        boolean changed = !server.equals(liveServer) || !useLiveSite;
        liveServer = server;
        useLiveSite = true;
        storeLiveServer(server);
        storeSource(true);
        if (changed) {
            refreshKeys();
        }
        return "";
    }

    private void reportKeys(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.evaluateJavascript(
                        "window.onKeysMessage && window.onKeysMessage("
                            + JSONObject.quote(message) + ")", null);
                }
            }
        });
    }

    /**
     * Server 2 exists so a blocked CDN can still be watched, but the site's
     * proxy answers its own session only. A WebView page cannot carry it, so
     * the bytes are fetched here - session cookie and client tag - and handed
     * back with a permissive CORS header. Streaming straight from the
     * connection keeps a segment out of memory and passes Range through.
     */
    private WebResourceResponse proxyStream(String url, Map<String, String> asked) {
        return proxyStream(url, asked, 0);
    }

    private WebResourceResponse proxyStream(String url, Map<String, String> asked, int hops) {
        HttpURLConnection conn = null;
        try {
            conn = Http.open(url, 5000, 20000);
            boolean siteHop = url.startsWith(SITE + "/");
            // Automatic redirects re-send the request headers, cookie included,
            // to whatever host the site names. Follow the hop here instead, and
            // only while it stays on the site.
            if (siteHop) {
                conn.setInstanceFollowRedirects(false);
            }
            // The session cookie belongs only to the site. Sending it to the
            // CDN or the mirror could leak it through a redirect.
            if (siteHop) {
                // The site's own player sends no Origin on segment reads.
                // HttpURLConnection now allows restricted headers for the
                // handshake, so adding one here turns a valid read into a
                // cross-site request and gets a 403.
                conn.setRequestProperty("Cookie", sessionCookie);
                conn.setRequestProperty("X-PBB-Client", sessionClient);
            }
            // The bytes go straight to the media stack, so no transcoding layer.
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (asked != null && asked.get("Range") != null) {
                conn.setRequestProperty("Range", asked.get("Range"));
            }
            int status = conn.getResponseCode();
            // The site proxy occasionally answers a valid segment with 503.
            // The same bytes are on the CDN the PBB3.0 route already uses,
            // so retry there before handing Shaka a fatal HTTP error.
            if (status == 503 && hops < 1) {
                String cdn = cdnSegment(url);
                if (cdn != null) {
                    conn.disconnect();
                    conn = null;
                    return proxyStream(cdn, asked, hops + 1);
                }
            }
            if (siteHop && status >= 300 && status < 400 && hops < 3) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                conn = null;
                if (location != null && location.startsWith(SITE + "/")) {
                    return proxyStream(location, asked, hops + 1);
                }
                return null;
            }
            Map<String, String> out = new HashMap<String, String>();
            out.put("Access-Control-Allow-Origin", "*");
            out.put("Access-Control-Allow-Headers", "*");
            out.put("Access-Control-Expose-Headers", "Content-Length,Content-Range");
            out.put("Cache-Control", "no-store");
            if (status < 200 || status >= 300) {
                out.put("Content-Type", "text/plain");
                String reason = status >= 500 ? "Server Error" : "Not Found";
                WebResourceResponse error = new WebResourceResponse(
                    "text/plain", null, status, reason, out,
                    new java.io.ByteArrayInputStream(new byte[0]));
                conn.disconnect();
                return error;
            }
            String type = conn.getContentType();
            return new WebResourceResponse(
                type == null ? "video/mp4" : type,
                null,
                status,
                status == 206 ? "Partial Content" : "OK",
                out,
                conn.getInputStream());
        } catch (Exception e) {
            if (conn != null) {
                conn.disconnect();
            }
            return null;
        }
    }

    /** The CDN copy of a site-proxy segment, or null when there is no mapping. */
    private static String cdnSegment(String url) {
        String root = SITE2 + "/chat/clearmpd/";
        if (!url.startsWith(root)) {
            return null;
        }
        String rest = url.substring(root.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return null;
        }
        for (int i = 0; i < CAM_IDS.length; i++) {
            if (rest.startsWith(CAM_IDS[i] + "/")) {
                String cdn = PBB3_MPD[i].substring(0, PBB3_MPD[i].lastIndexOf('/') + 1);
                int query = rest.indexOf('?');
                String name = query < 0 ? rest.substring(slash + 1)
                    : rest.substring(slash + 1, query);
                return cdn + name;
            }
        }
        return null;
    }

    /** Waits for the launch handshake, then falls back to whatever is cached. */
    private void waitForFreshConfig(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (configLock) {
            while (!configFresh) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return;
                }
                try {
                    configLock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void markConfigFresh() {
        synchronized (configLock) {
            configFresh = true;
            configLock.notifyAll();
        }
    }

    /**
     * Compares the published clear keys with the keys in the config. The site
     * MPD body can carry an old default KID - it is not the key source. Its
     * X-PBB-ClearKey header is, and it is what the site player uses.
     */
    private String rotationReport() {
        JSONArray streams;
        try {
            streams = new JSONObject(streamConfigJson).getJSONArray("streams");
        } catch (Exception e) {
            return "Could not read the stream list";
        }
        StringBuilder rotated = new StringBuilder();
        int srv = currentSrv();
        int answered = 0;
        for (int i = 0; i < streams.length() && i < CAM_IDS.length; i++) {
            JSONObject stream;
            try {
                stream = streams.getJSONObject(i);
            } catch (Exception e) {
                continue;
            }
            String kid = stream.optString("kid", "").replace("-", "").toLowerCase();
            String[] published = publishedClearKey(i, srv);
            if (kid.isEmpty() || published == null) {
                continue;
            }
            answered++;
            if (!published[0].equals(kid)) {
                if (rotated.length() > 0) {
                    rotated.append(", ");
                }
                rotated.append(stream.optString("name", "channel " + (i + 1)).toLowerCase());
            }
        }
        if (rotated.length() > 0) {
            return "Keys changed for " + rotated + ". Choose a server";
        }
        if (answered == 0) {
            return "Could not reach the streams. Tap Refresh to retry";
        }
        return "No channel list. The selected server is still in use";
    }

    /** The kid/key pair in the site's X-PBB-ClearKey header, or null. */
    private static String[] publishedClearKey(int index, int srv) {
        Http.Mpd mpd = siteMpdText(index, srv);
        return mpd == null ? null : clearKeyPair(mpd.clearKey);
    }

    /**
     * Reads the cameras and keys off the pbbcollab.live session handshake.
     * The site hands out one short lived token per camera and serves the
     * manifests only to the cookie that handshake sets.
     */
    private String publishedConfig() {
        JSONArray streams = null;
        String kind = serverKind(streamServer);
        if ("mirror".equals(kind) && !useLiveSite) {
            streams = mirrorStreams();
            if (streams == null) {
                streams = siteStreamsWithBundledKeys();
            }
        } else if ("pbb3".equals(kind) && !useLiveSite) {
            streams = pbb3Streams();
            if (streams == null) {
                streams = siteStreamsWithBundledKeys();
            }
        } else {
            Http.Reply handshake = handshake();
            streams = handshake == null ? null : siteStreams(handshake);
            if (streams == null) {
                streams = siteStreamsWithBundledKeys();
            }
        }
        if (streams == null && !useLiveSite) {
            streams = mirrorStreams();
        }
        if (streams == null) {
            return null;
        }
        try {
            String json = new JSONObject().put("streams", streams).toString();
            return validKeys(json) != null ? json : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * If the session API is blocked, use the site's MPD with the bundled key
     * pair instead of replaying a cached manifest with an expired token.
     */
    private JSONArray siteStreamsWithBundledKeys() {
        String[] mpds = new String[CHANNEL_COUNT];
        int srv = currentSrv();
        if (siteMpdText(0, srv) == null) {
            srv = srv == 0 ? 1 : 0;
            if (siteMpdText(0, srv) == null) {
                return null;
            }
        }
        for (int i = 0; i < CAM_IDS.length; i++) {
            mpds[i] = SITE2 + "/api/live/mpd/" + CAM_IDS[i] + ".mpd?srv=s" + srv;
        }
        return bundledKeyStreams(mpds, SITE2);
    }

    /** One site MPD route. Null means that route answered with an error. */
    private static Http.Mpd siteMpdText(int index, int srv) {
        try {
            return Http.getMpd(SITE2 + "/api/live/mpd/" + CAM_IDS[index]
                + ".mpd?srv=s" + srv, 5000, 8000);
        } catch (Exception e) {
            return null;
        }
    }

    /** The pbb-3.pages.dev route, straight from the CDN manifests it names. */
    private JSONArray pbb3Streams() {
        return bundledKeyStreams(PBB3_MPD, null);
    }

    /** The mirror route, using its own tokenized manifest endpoints. */
    private JSONArray mirrorStreams() {
        return bundledKeyStreams(MIRROR_MPD, null);
    }

    private JSONArray bundledKeyStreams(String[] mpds, String fallbackBase) {
        try {
            JSONArray bundled = new JSONObject(streamConfig()).getJSONArray("streams");
            if (bundled.length() != CHANNEL_COUNT) {
                return null;
            }
            JSONArray streams = new JSONArray();
            for (int i = 0; i < CAM_IDS.length; i++) {
                Http.Mpd doc = Http.getMpd(mpds[i], 5000, 8000);
                if (doc == null) {
                    return null;
                }
                String xml = doc.body;
                String base = manifestBase(xml);
                if (base == null) {
                    base = mediaBase(xml);
                }
                if (base == null) {
                    base = fallbackBase == null
                        ? mpds[i].substring(0, mpds[i].lastIndexOf('/') + 1) : fallbackBase;
                }
                // The MPD body can name a stale KID. The site header is current.
                String[] liveKeys = clearKeyPair(doc.clearKey);
                String kid = liveKeys == null ? bundled.getJSONObject(i).getString("kid") : liveKeys[0];
                String key = liveKeys == null ? bundled.getJSONObject(i).getString("key") : liveKeys[1];
                JSONObject stream = new JSONObject();
                stream.put("name", CHANNEL_NAMES[i]);
                stream.put("base", base);
                stream.put("url", DATA_MPD + Base64.encodeToString(
                    siteMpd(xml, base, kid).getBytes("UTF-8"), Base64.NO_WRAP));
                stream.put("kid", kid);
                stream.put("key", key);
                streams.put(stream);
            }
            return streams;
        } catch (Exception e) {
            return null;
        }
    }

    /** Splits a kid:key header into 32-hex pairs, or null when unusable. */
    private static String[] clearKeyPair(String raw) {
        if (raw == null) {
            return null;
        }
        int colon = raw.indexOf(':');
        if (colon < 1) {
            return null;
        }
        String kid = raw.substring(0, colon).replace("-", "").toLowerCase(Locale.US);
        String key = raw.substring(colon + 1).replace("-", "").toLowerCase(Locale.US);
        return kid.matches("[0-9a-f]{32}") && key.matches("[0-9a-f]{32}")
            ? new String[] {kid, key} : null;
    }

    /** The randomised handshake the site's own page posts before it plays. */
    private Http.Reply handshake() {
        try {
            byte[] seed = new byte[13];
            new SecureRandom().nextBytes(seed);
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                key.append(String.format(Locale.US, "%02x", seed[i] & 0xff));
            }
            String route = useLiveSite ? liveServer : streamServer;
            String plain = Http.sessionPlain(seed, key.toString(),
                serverMode(route, useLiveSite), System.currentTimeMillis());
            byte[] sealed = new byte[plain.length()];
            for (int i = 0; i < plain.length(); i++) {
                sealed[i] = (byte) (plain.charAt(i) ^ key.charAt(i % key.length()) ^ 0x3c);
            }
            JSONObject body = new JSONObject();
            body.put("k", key.toString());
            body.put("v", Base64.encodeToString(sealed, Base64.NO_WRAP));
            return Http.postJson(SITE + "/api/streams/session", body.toString(), CLIENT, 5000, 8000);
        } catch (Exception e) {
            return null;
        }
    }

    private JSONArray siteStreams(Http.Reply handshake) {
        JSONArray streams = new JSONArray();
        try {
            JSONObject reply = new JSONObject(handshake.body);
            String nonce = reply.getString("nonce");
            sessionCookie = handshake.cookie == null ? "" : handshake.cookie;
            JSONObject cams = reply.getJSONObject("streams");
            for (int i = 0; i < CAM_IDS.length; i++) {
                JSONObject cam = cams.getJSONObject(CAM_IDS[i]);
                String[] pair = sessionToken(cam.getString("token"), nonce);
                String endpoint = cam.getString("endpoint");
                String xml = null;
                String base = null;
                // The session host gives fresh keys, but its /stream/ segments
                // answer 403/503 for several routes. The site MPD names the
                // same cameras through a proxy that stays reachable. Keep the
                // session keys and only take the session manifest when both
                // site routes are down.
                int srv = currentSrv();
                Http.Mpd doc = null;
                if (!useLiveSite) {
                    doc = siteMpdText(i, srv);
                    if (doc == null) {
                        doc = siteMpdText(i, srv == 0 ? 1 : 0);
                    }
                    if (doc != null) {
                        xml = doc.body;
                        base = "mirror".equals(serverSegments(streamServer))
                            ? MIRROR + CAM_MIRROR[i] : mediaBase(xml);
                    }
                }
                if (xml == null) {
                    // The site's player busts the CDN cache every 15s. A
                    // bare endpoint is rejected with 403.
                    String bucket = Long.toString(System.currentTimeMillis() / 15000L);
                    String join = endpoint.contains("?") ? "&" : "?";
                    xml = Http.getText(SITE + endpoint + join + "_b=" + bucket,
                        5000, 8000, handshake.cookie, CLIENT);
                    if (xml != null) {
                        base = siteBase(xml, endpoint, i);
                    }
                }
                if (xml == null) {
                    doc = siteMpdText(i, srv);
                    if (doc == null && srv != (srv == 0 ? 1 : 0)) {
                        doc = siteMpdText(i, srv == 0 ? 1 : 0);
                    }
                    if (doc != null) {
                        xml = doc.body;
                        base = mediaBase(xml);
                    }
                }
                if (xml == null) {
                    return null;
                }
                if (base == null) {
                    base = SITE2;
                }
                String[] liveKeys = clearKeyPair(doc == null ? null : doc.clearKey);
                String kid = liveKeys == null ? pair[0] : liveKeys[0];
                String key = liveKeys == null ? pair[1] : liveKeys[1];
                JSONObject stream = new JSONObject();
                stream.put("name", CHANNEL_NAMES[i]);
                // The manifest body moves its publishTime on every handshake, so
                // the page treats this base plus the key pair as the identity.
                stream.put("base", base);
                stream.put("url", DATA_MPD + Base64.encodeToString(
                    siteMpd(xml, base, kid).getBytes("UTF-8"), Base64.NO_WRAP));
                stream.put("kid", kid);
                stream.put("key", key);
                streams.put(stream);
            }
        } catch (Exception e) {
            return null;
        }
        return streams;
    }

    /** The three-bit mode the site's session handshake uses for a route. */
    private static int serverMode(String server, boolean live) {
        return catalogNumber(server, live, "mode", 0);
    }

    /** Unseals one camera token into its kid and key. */
    private static String[] sessionToken(String token, String nonce) throws Exception {
        byte[] raw = Base64.decode(token, Base64.DEFAULT);
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < raw.length; i++) {
            plain.append((char) ((raw[i] & 0xff) ^ 0x5a ^ nonce.charAt(i % nonce.length())));
        }
        int colon = plain.indexOf(":");
        if (colon < 1) {
            throw new IllegalArgumentException("bad session token");
        }
        return new String[] {plain.substring(0, colon).replace("-", "").toLowerCase(Locale.US),
            plain.substring(colon + 1).replace("-", "").toLowerCase(Locale.US)};
    }

    /**
     * Server 1 names its segments absolutely, straight off the CDN; server 2
     * leaves them relative because they come back through the site's own
     * proxy; server 3 keeps the live manifest and plays the same segment
     * numbers through the pbb3collablive.xyz mirror, and servers 4-7 use the
     * four routes listed on pbbcollab.com/live. They end up with one BaseURL
     * and relative names - the shape the page and the recorder join.
     */
    private String siteBase(String xml, String endpoint, int index) {
        if (useLiveSite) {
            String media = mediaBase(xml);
            return media != null ? media
                : SITE + endpoint.substring(0, endpoint.lastIndexOf('/') + 1);
        }
        if (site2Number(streamServer) > 0) {
            String media = mediaBase(xml);
            return media != null ? media : SITE2;
        }
        if ("s3".equals(streamServer)) {
            return MIRROR + CAM_MIRROR[index] + "/";
        }
        String media = mediaBase(xml);
        if (media != null) {
            return media;
        }
        return SITE + endpoint.substring(0, endpoint.lastIndexOf('/') + 1);
    }

    /** The manifest's own BaseURL, before any tokenized segment names. */
    private static String manifestBase(String xml) {
        int start = xml.indexOf("<BaseURL>");
        int end = start < 0 ? -1 : xml.indexOf("</BaseURL>", start);
        return end > start ? xml.substring(start + "<BaseURL>".length(), end) : null;
    }

    /** The absolute segment folder a manifest names, or null when it names none. */
    private static String mediaBase(String xml) {
        Matcher media = MEDIA_BASE.matcher(xml);
        if (!media.find()) {
            return null;
        }
        String base = media.group(1);
        // A root-relative folder has no scheme. Resolving it from a file://
        // page gives Shaka Error 1000, so anchor it to the site now.
        return base.startsWith("/") ? SITE2 + base : base;
    }

    private String siteMpd(String xml, String base, String kid) {
        // Strip only a leading folder. Mirror segment names carry signed query
        // tokens with slashes; cutting at the last slash would break them.
        xml = xml.replaceAll("(media|initialization)=\"(?:https?://[^\"?]*/|/)[^\"?]*",
            "$1=\"");
        // Drop every baked BaseURL, including root-relative ones. Leaving one
        // behind gives Shaka a second, scheme-less location to try.
        xml = xml.replaceAll("<BaseURL>[^<]*</BaseURL>", "");
        // The site MPD can carry another camera's KID in its PSSH. Replace it
        // with the KID paired with the key we are about to give Shaka.
        String cleanKid = kid == null ? "" : kid.replace("-", "").toLowerCase(Locale.US);
        if (cleanKid.matches("[0-9a-f]{32}")) {
            String uuid = cleanKid.substring(0, 8) + "-" + cleanKid.substring(8, 12)
                + "-" + cleanKid.substring(12, 16) + "-" + cleanKid.substring(16, 20)
                + "-" + cleanKid.substring(20);
            xml = xml.replaceAll("cenc:default_KID=\"[^\"]*\"",
                "cenc:default_KID=\"" + uuid + "\"");
            String pssh = Http.psshForKid(cleanKid);
            if (!pssh.isEmpty()) {
                xml = xml.replaceAll("(<cenc:pssh[^>]*>)[^<]*(</cenc:pssh>)",
                    "$1" + java.util.regex.Matcher.quoteReplacement(pssh) + "$2");
            }
        }
        // A baked copy cannot publish updates. Leaving this in makes the player
        // re-poll the same static window every 30s for nothing.
        xml = xml.replaceAll("\\s+minimumUpdatePeriod=\"[^\"]*\"", "");
        // The site edge sometimes returns a cached 503 for a bare segment URL.
        // A constant query is enough to use the healthy cache key.
        if (base.startsWith(SITE2 + "/") || base.startsWith(SITE + "/")) {
            xml = xml.replaceAll("(media|initialization)=\"([^\"?]+)\"", "$1=\"$2?_pbb=1\"");
        }
        int head = xml.indexOf("<MPD");
        int end = head < 0 ? -1 : xml.indexOf('>', head);
        if (end < 0) {
            return xml;
        }
        return xml.substring(0, end + 1) + "<BaseURL>" + base + "</BaseURL>"
            + xml.substring(end + 1);
    }

    private static JSONArray validKeys(String json) {
        try {
            JSONArray streams = new JSONObject(json).getJSONArray("streams");
            if (streams.length() != CHANNEL_COUNT) {
                return null;
            }
            for (int i = 0; i < streams.length(); i++) {
                JSONObject s = streams.getJSONObject(i);
                String url = s.getString("url");
                if ((!url.startsWith("https://") && !url.startsWith(DATA_MPD))
                        || !s.getString("kid").matches("(?i)[0-9a-f]{32}")
                        || !s.getString("key").matches("(?i)[0-9a-f]{32}")) {
                    return null;
                }
            }
            return streams;
        } catch (Exception e) {
            return null;
        }
    }

    private void notifyRecordingState(boolean active, int index, int count, long elapsed,
            String kind, String message) {
        final String payload;
        try {
            JSONObject state = new JSONObject();
            state.put("active", active);
            state.put("index", index);
            state.put("count", count);
            state.put("elapsed", elapsed);
            state.put("kind", kind == null ? "" : kind);
            state.put("message", message == null ? "" : message);
            payload = state.toString();
        } catch (Exception e) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(
                    "window.onRecordingState && window.onRecordingState(" + payload + ")",
                    null);
            }
        });
    }

    private void notifyPipState(boolean active) {
        isInPip = active;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript("window.setPipMode && window.setPipMode(" + (active ? "true" : "false") + ")", null);
            }
        });
    }

    // Only Android 15 and later force edge to edge here; below that the window
    // already fits the system bars and padding would be applied twice.
    private void applyInsetsAsPadding(WindowInsets insets) {
        if (rootView == null) {
            return;
        }
        // Fullscreen ignores the bars and the camera cutout: any padding here
        // shows up as a light strip in front of the notch.
        if (immersive) {
            rootView.setPadding(0, 0, 0, 0);
            return;
        }
        if (Build.VERSION.SDK_INT < 35 || insets == null) {
            return;
        }
        android.graphics.Insets bars =
            insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        rootView.setPadding(bars.left, bars.top, bars.right, bars.bottom);
    }

    private int dimension(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && rootView != null) {
            applyInsetsAsPadding(rootView.getRootWindowInsets());
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void applyImmersive(boolean enabled) {
        immersive = enabled;
        setBarsHidden(enabled);
        applyOrientation();
        applyBrightnessForScreen();
        if (rootView != null) {
            applyInsetsAsPadding(rootView.getRootWindowInsets());
            rootView.requestApplyInsets();
        }
    }

    /**
     * The main screen is a portrait layout, so it stays portrait. Only the
     * fullscreen picture turns the phone sideways.
     */
    private void applyOrientation() {
        setRequestedOrientation(immersive
            ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private void setBarsHidden(boolean enabled) {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller == null) {
                return;
            }
            if (enabled) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                controller.show(WindowInsets.Type.systemBars());
            }
            applyBarIconAppearance();
            return;
        }
        if (enabled) {
            decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_VISIBLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
        applyBarIconAppearance();
    }

    // The bars sit over the near-black wall, so their glyphs stay pale.
    private void applyBarIconAppearance() {
        View decor = getWindow().getDecorView();
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(0, light);
            }
            return;
        }
        int flags = decor.getSystemUiVisibility();
        flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        decor.setSystemUiVisibility(flags);
    }

    /**
     * The remembered brightness belongs to the fullscreen picture: on the main
     * screen the phone keeps its own brightness, and in the pip window the
     * device brightness applies too.
     */
    private void applyBrightnessForScreen() {
        if (immersive && !isInPictureInPictureMode() && appBrightnessLevel >= 0f) {
            applyBrightness(appBrightnessLevel, false);
        } else {
            clearBrightnessOverride();
        }
    }

    // Window-scoped only: never written to Settings.System, and cleared when
    // the app leaves the foreground so the device keeps its own brightness.
    private void applyBrightness(float value, boolean persist) {
        appBrightnessLevel = Math.max(0.01f, Math.min(1f, value));
        if (persist) {
            setAppBrightness(getFilesDir().getAbsolutePath(), appBrightnessLevel);
        }
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = appBrightnessLevel;
        getWindow().setAttributes(params);
    }

    private void applyVolume(float value, boolean persist) {
        appVolumeLevel = Math.max(0f, Math.min(1f, value));
        if (persist) {
            setAppVolume(getFilesDir().getAbsolutePath(), appVolumeLevel);
        }
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) {
            return;
        }
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int level = Math.round(appVolumeLevel * max);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
        appliedVolume = level;
    }

    /**
     * Gives the system volume back, but only when the app still owns it: if
     * the user changed the volume from the shade, that change wins.
     */
    private void restoreSystemVolume() {
        if (savedSystemVolume < 0) {
            return;
        }
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio != null && (appliedVolume < 0
                || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == appliedVolume)) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedSystemVolume, 0);
        }
        savedSystemVolume = -1;
        appliedVolume = -1;
    }

    private void clearBrightnessOverride() {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        if (params.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            return;
        }
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(params);
    }

    private PictureInPictureParams pipParams(boolean autoEnter) {
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
        builder.setAspectRatio(new Rational(16, 9));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setTitle("PBB");
            builder.setSubtitle("live stream");
            builder.setAutoEnterEnabled(autoEnter);
        }
        return builder.build();
    }

    // Let the system decide when to auto-enter PiP. It fires on the go-home
    // gesture, not on the recents/overview gesture. onUserLeaveHint fires for
    // both, which is why it used to jump into PiP on a swipe up to recents.
    private void updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setPictureInPictureParams(pipParams(focusedStream >= 0 && !isInPip));
        }
    }

    private void enterPipMode() {
        if (focusedStream < 0) {
            toast("Choose a feed first");
            return;
        }
        if (isInPictureInPictureMode()) {
            return;
        }
        applyImmersive(false);
        boolean entered = enterPictureInPictureMode(pipParams(false));
        if (!entered) {
            toast("Picture-in-picture is unavailable");
        }
        // onPictureInPictureModeChanged is the only announcement. Calling this
        // here too ran setPipMode twice and restarted the video on each hop.
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        isInPip = isInPictureInPictureMode;
        updatePipParams();
        applyBrightnessForScreen();
        notifyPipState(isInPictureInPictureMode);
    }

    @Override
    public void onBackPressed() {
        webView.evaluateJavascript(
            "window.handleBack ? (window.handleBack() ? 'true' : 'false') : 'false'",
            new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if (value == null || !value.contains("true")) {
                        finish();
                    }
                }
            });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_PERMISSIONS) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }
        if (grantResults.length == 0) {
            notifyRecordingState(false, -1, RecordService.activeCount(), 0,
                RecordService.KIND_ERROR, "Permission denied; no recording started");
            return;
        }
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                notifyRecordingState(false, -1, RecordService.activeCount(), 0,
                    RecordService.KIND_ERROR, "Permission denied; no recording started");
                return;
            }
        }
        startRecordService();
    }

    /** Starts every camera in the batch that is not already recording. */
    private void requestRecording(int[] indices, String[] labels) {
        // A stitch tap arrives as three bridge calls. Keep all three while a
        // permission dialog is open; clearing here dropped the later feeds.
        for (int i = 0; i < indices.length; i++) {
            int index = indices[i];
            if (index < 0 || RecordService.isActive(index)
                    || pendingRecordIndices.contains(Integer.valueOf(index))) {
                continue;
            }
            pendingRecordIndices.add(index);
            pendingRecordLabels.add(labels != null && i < labels.length && labels[i] != null
                ? labels[i] : "stream");
        }
        if (pendingRecordIndices.isEmpty()) {
            notifyRecordingState(false, -1, RecordService.activeCount(), 0,
                RecordService.KIND_NOTICE, "That camera is already recording");
            return;
        }

        ArrayList<String> needed = new ArrayList<String>();
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQUEST_PERMISSIONS);
            return;
        }
        startRecordService();
    }

    private void startRecordService() {
        ArrayList<Integer> indices = new ArrayList<Integer>(pendingRecordIndices);
        ArrayList<String> labels = new ArrayList<String>(pendingRecordLabels);
        pendingRecordIndices.clear();
        pendingRecordLabels.clear();
        if (indices.isEmpty()) {
            return;
        }
        // The stitch tap arrives as three separate bridge calls. Mark the group
        // again here, at the point the starts are actually sent, so a stale or
        // missed UI mark cannot leave three ordinary recordings. Three plain
        // record taps can land in one batch but do not set stitchPending.
        boolean stitch = stitchPending;
        stitchPending = false;
        if (stitch && indices.size() == 3
                && indices.contains(Integer.valueOf(0))
                && indices.contains(Integer.valueOf(1))
                && indices.contains(Integer.valueOf(2))) {
            RecordService.markStackGroup(0, 1, 2);
        }
        JSONArray streams;
        try {
            streams = new JSONObject(streamConfigJson).getJSONArray("streams");
        } catch (Exception e) {
            notifyRecordingState(false, -1, RecordService.activeCount(), 0,
                RecordService.KIND_ERROR, "Cannot start recording: " + e.getMessage());
            return;
        }
        int started = 0;
        for (int i = 0; i < indices.size(); i++) {
            int index = indices.get(i);
            String label = labels.get(i);
            try {
                if (index < 0 || index >= streams.length()) {
                    continue;
                }
                JSONObject stream = streams.getJSONObject(index);
                Intent service = new Intent(this, RecordService.class);
                service.setAction(RecordService.ACTION_START);
                service.putExtra(RecordService.EXTRA_MPD_URL, stream.getString("url"));
                service.putExtra(RecordService.EXTRA_KEY, stream.getString("key"));
                service.putExtra(RecordService.EXTRA_DISPLAY_NAME, makeDisplayName(label));
                service.putExtra(RecordService.EXTRA_STREAM_NAME, stream.optString("name", label));
                service.putExtra(RecordService.EXTRA_QUALITY,
                    defaultQuality(getFilesDir().getAbsolutePath()));
                service.putExtra(RecordService.EXTRA_STREAM_INDEX, index);
                startForegroundService(service);
                started++;
            } catch (Exception e) {
                notifyRecordingState(false, index, RecordService.activeCount(), 0,
                    RecordService.KIND_ERROR, "Cannot start recording: " + e.getMessage());
            }
        }
        if (started == 0) {
            notifyRecordingState(false, -1, RecordService.activeCount(), 0,
                RecordService.KIND_ERROR, "Nothing to record");
        }
    }

    /** Stops one camera, or every camera when the index is negative. */
    private void stopRecording(int index) {
        if (!RecordService.isActive()) {
            notifyRecordingState(false, -1, 0, 0,
                RecordService.KIND_NOTICE, "Nothing is recording");
            return;
        }
        Intent service = new Intent(this, RecordService.class);
        service.setAction(RecordService.ACTION_STOP);
        service.putExtra(RecordService.EXTRA_STREAM_INDEX, index);
        startService(service);
    }

    private String makeDisplayName(String label) {
        String safe = label.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isEmpty()) {
            safe = "stream";
        }
        // The stream names already start with PBB; do not stack a second prefix.
        if (!safe.regionMatches(true, 0, "PBB_", 0, 4)) {
            safe = "PBB_" + safe;
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        return safe + "_" + stamp + ".mp4";
    }

    @Override
    protected void onStart() {
        super.onStart();
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio != null && savedSystemVolume < 0) {
            savedSystemVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        }
        if (appVolumeLevel >= 0f) {
            applyVolume(appVolumeLevel, false);
        }
        applyBrightnessForScreen();
    }

    @Override
    protected void onStop() {
        if (!isInPictureInPictureMode()) {
            restoreSystemVolume();
            clearBrightnessOverride();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        restoreSystemVolume();
        clearBrightnessOverride();
        try {
            unregisterReceiver(statusReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    public class PbbBridge {
        @JavascriptInterface
        public String getConfig() {
            waitForFreshConfig(8000);
            return streamConfigJson;
        }

        @JavascriptInterface
        public String getServerCatalog() {
            return serverCatalogJson;
        }

        @JavascriptInterface
        public void refreshServerCatalog() {
            MainActivity.this.refreshServerCatalog();
        }

        @JavascriptInterface
        public void refreshKeys() {
            MainActivity.this.refreshKeys();
        }

        /** s1 or s2. */
        @JavascriptInterface
        public String getStreamServer() {
            return streamServer;
        }

        /** Returns an empty string when the pick was stored. */
        @JavascriptInterface
        public String setStreamServer(String server) {
            return MainActivity.this.setStreamServer(server);
        }

        @JavascriptInterface
        public String getStreamSource() {
            return useLiveSite ? "live" : "site";
        }

        @JavascriptInterface
        public String getLiveServer() {
            return liveServer;
        }

        @JavascriptInterface
        public String setLiveServer(String server) {
            return MainActivity.this.setLiveServer(server);
        }

        @JavascriptInterface
        public String getDefaultQuality() {
            return defaultQuality(getFilesDir().getAbsolutePath());
        }

        @JavascriptInterface
        public void setDefaultQuality(String quality) {
            MainActivity.setDefaultQuality(getFilesDir().getAbsolutePath(), quality);
        }

        @JavascriptInterface
        public void setFocusedStream(int index) {
            focusedStream = index;
            updatePipParams();
        }

        @JavascriptInterface
        public void clearFocusedStream() {
            focusedStream = -1;
            updatePipParams();
        }

        @JavascriptInterface
        public void setVolume(final float value, final boolean persist) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyVolume(value, persist);
                }
            });
        }

        @JavascriptInterface
        public float getBrightness() {
            return appBrightnessLevel;
        }

        @JavascriptInterface
        public float getVolume() {
            AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audio == null) {
                return 0.7f;
            }
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (max <= 0) {
                return 0.7f;
            }
            return (float) audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max;
        }

        @JavascriptInterface
        public void setBrightness(final float value, final boolean persist) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyBrightness(value, persist);
                }
            });
        }

        @JavascriptInterface
        public void setImmersive(final boolean enabled) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyImmersive(enabled);
                }
            });
        }

        @JavascriptInterface
        public void enterPip() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    enterPipMode();
                }
            });
        }

        @JavascriptInterface
        public void markStackGroup(int first, int second, int third) {
            stitchPending = first >= 0 && second >= 0 && third >= 0;
            RecordService.markStackGroup(first, second, third);
        }

        @JavascriptInterface
        public void startRecording(int index, String label) {
            final int streamIndex = index;
            final String streamLabel = label;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    requestRecording(new int[] {streamIndex}, new String[] {streamLabel});
                }
            });
        }

        @JavascriptInterface
        public void stopRecording(final int index) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.stopRecording(index);
                }
            });
        }

        /** Every recording in flight, with its elapsed seconds, so a rebuilt page
         * can restore the tally lights instead of losing them. */
        @JavascriptInterface
        public String recordingState() {
            try {
                JSONArray streams = new JSONArray();
                for (int index : RecordService.activeStreams()) {
                    JSONObject item = new JSONObject();
                    item.put("index", index);
                    item.put("elapsed", RecordService.elapsedSeconds(index));
                    streams.put(item);
                }
                return new JSONObject().put("streams", streams).toString();
            } catch (Exception e) {
                return "{\"streams\":[]}";
            }
        }

        @JavascriptInterface
        public boolean isInPip() {
            return isInPip;
        }
    }
}
