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
import android.util.Rational;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 2001;
    // The M3U carries the channel paths and keys. The two Akamai hosts are the
    // livestream servers: the APK's host first, the old one as backup.
    private static final String[] CHANNEL_LIST_URLS = {
        "https://raw.githubusercontent.com/kupsbabaero-ux/cignal-yarn/refs/heads/main/zslive.m3u"
    };
    private static final String[] STREAM_HOSTS = {
        "abslive.akamaized.net", "absp-live.akamaized.net"
    };
    private static final String[] CHANNEL_NAMES = {
        "PBB All Access", "PBB Dining Area", "PBB External Den"
    };
    // 0 = auto (working host first), 1..2 = that livestream host first.
    private static volatile int channelSource = 0;
    // The gallery is one large feed over two small ones, so the list has to
    // hold the same number of channels as the built-in config.
    private static final int CHANNEL_COUNT = 3;
    private static final String KEYS_CACHE = "streams.json";

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
    // The gallery's light switch: the page owns the choice, the shell follows
    // it so the window behind the WebView and the system bar icons match.
    private boolean darkTheme = false;
    private float appVolumeLevel = -1f;
    private float appBrightnessLevel = -1f;
    private int savedSystemVolume = -1;
    // The volume level the app itself set, so it knows whether it still owns
    // the system volume when the activity goes away.
    private int appliedVolume = -1;
    private volatile String streamConfigJson;

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
        channelSource = getSharedPreferences("pbb", MODE_PRIVATE)
            .getInt("channelSource", 0);
        loadCachedKeys();
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
        rootView.setBackgroundColor(0xFFFAFADF);
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
        webView.setBackgroundColor(0xFFFAFADF);
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
                refreshKeys();
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
                    reportKeys(rotationReport());
                    return;
                }
                streamConfigJson = json;
                final String quoted = JSONObject.quote(json);
                writeKeysCache(json);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        webView.evaluateJavascript(
                            "window.onStreamConfig && window.onStreamConfig(" + quoted + ")", null);
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
        File tmp = new File(getFilesDir(), KEYS_CACHE + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(json.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            if (!tmp.renameTo(new File(getFilesDir(), KEYS_CACHE))) {
                throw new IOException("cannot replace the keys cache");
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Stores a new source for one channel: the manifest link, the kid and the
     * key. The config goes to filesDir/streams.json, so the source survives a
     * restart, and it is pushed to the page like a fetched config, so the
     * players pick it up without a restart.
     */
    private String setSource(int index, String url, String kid, String key) {
        String cleanUrl = url == null ? "" : url.trim();
        String cleanKid = kid == null ? "" : kid.replace("-", "").toLowerCase();
        String cleanKey = key == null ? "" : key.replace("-", "").toLowerCase();
        if (!cleanUrl.startsWith("https://")) {
            return "the manifest link has to be https";
        }
        if (!cleanKid.matches("[0-9a-f]{32}") || !cleanKey.matches("[0-9a-f]{32}")) {
            return "the kid and the key are 32 hex characters";
        }
        String json;
        try {
            JSONObject config = new JSONObject(streamConfigJson);
            JSONArray streams = config.getJSONArray("streams");
            if (index < 0 || index >= streams.length()) {
                return "no such channel";
            }
            JSONObject stream = streams.getJSONObject(index);
            stream.put("url", cleanUrl);
            stream.put("kid", cleanKid);
            stream.put("key", cleanKey);
            json = config.toString();
        } catch (Exception e) {
            return "could not store the source";
        }
        streamConfigJson = json;
        writeKeysCache(json);
        final String quoted = JSONObject.quote(json);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(
                    "window.onStreamConfig && window.onStreamConfig(" + quoted + ")", null);
            }
        });
        return "";
    }

    private void reportKeys(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(
                    "window.onKeysMessage && window.onKeysMessage(" + JSONObject.quote(message) + ")", null);
            }
        });
    }

    /**
     * Reads the kid out of every manifest and compares it with the kid the
     * app plays with. A manifest that carries another kid is a rotated
     * channel: the key in the config is old and that tile will not play.
     */
    private String rotationReport() {
        JSONArray streams;
        try {
            streams = new JSONObject(streamConfigJson).getJSONArray("streams");
        } catch (Exception e) {
            return "cannot read the sources";
        }
        StringBuilder rotated = new StringBuilder();
        int answered = 0;
        for (int i = 0; i < streams.length(); i++) {
            JSONObject stream;
            try {
                stream = streams.getJSONObject(i);
            } catch (Exception e) {
                continue;
            }
            String url = stream.optString("url", "");
            String kid = stream.optString("kid", "").replace("-", "").toLowerCase();
            if (!url.startsWith("https://") || kid.isEmpty()) {
                continue;
            }
            String found = manifestKid(url);
            if (found.isEmpty()) {
                continue;
            }
            answered++;
            if (!found.equals(kid)) {
                if (rotated.length() > 0) {
                    rotated.append(", ");
                }
                rotated.append(stream.optString("name", "channel " + (i + 1)).toLowerCase());
            }
        }
        if (rotated.length() > 0) {
            return "keys rotated on " + rotated + " - open sources to paste the new key";
        }
        if (answered == 0) {
            return "could not reach the streams - tap refresh to retry";
        }
        return "no published channel list - the sources sheet holds the current feed";
    }

    private static String manifestKid(String url) {
        String raw;
        try {
            raw = Http.getText(url, 4000, 5000);
        } catch (Exception e) {
            return "";
        }
        if (raw == null) {
            return "";
        }
        return RecordService.manifestKid(raw);
    }

    /** Fetch the list once, then try the chosen livestream host and its backup. */
    private static String publishedConfig() {
        if (channelSource > 0) {
            String pinned = fetchKeys(CHANNEL_LIST_URLS[0], STREAM_HOSTS[channelSource - 1]);
            return validKeys(pinned) != null ? pinned : null;
        }
        for (String host : STREAM_HOSTS) {
            String candidate = fetchKeys(CHANNEL_LIST_URLS[0], host);
            if (candidate == null || validKeys(candidate) == null) {
                continue;
            }
            try {
                String first = new JSONObject(candidate).getJSONArray("streams")
                    .getJSONObject(0).getString("url");
                if (Http.getBytes(first, 3000, 3000) != null) {
                    return candidate;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** Reads the three PBB rows from the M3U list onto one livestream host. */
    private static String fetchKeys(String url, String streamHost) {
        String raw;
        try {
            raw = Http.getText(url, 5000, 8000);
        } catch (Exception e) {
            return null;
        }
        if (raw == null) {
            return null;
        }
        try {
            List<ChannelList.Entry> listed = ChannelList.fromM3u(raw, CHANNEL_NAMES, streamHost);
            if (listed == null) {
                return null;
            }
            JSONArray streams = new JSONArray();
            for (ChannelList.Entry entry : listed) {
                JSONObject stream = new JSONObject();
                stream.put("name", entry.name);
                stream.put("url", entry.url);
                stream.put("kid", entry.kid);
                stream.put("key", entry.key);
                streams.put(stream);
            }
            return new JSONObject().put("streams", streams).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static JSONArray validKeys(String json) {
        try {
            JSONArray streams = new JSONObject(json).getJSONArray("streams");
            if (streams.length() != CHANNEL_COUNT) {
                return null;
            }
            for (int i = 0; i < streams.length(); i++) {
                JSONObject s = streams.getJSONObject(i);
                if (!s.getString("url").startsWith("https://")
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
        // shows up as a cream strip in front of the notch.
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

    // The bars sit over the desk, so the icon glyphs have to fight whatever
    // the page is painted: dark glyphs on the cream day desk, pale glyphs on
    // the charcoal night one.
    private void applyBarIconAppearance() {
        View decor = getWindow().getDecorView();
        boolean dark = darkTheme;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller != null) {
                int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(dark ? 0 : light, light);
            }
            return;
        }
        int flags = decor.getSystemUiVisibility();
        flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (!dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    // Paints the shell behind the page in the same colour as the desk, so
    // there is no pale flash before the first frame of a dark gallery.
    private void applyTheme(boolean dark) {
        darkTheme = dark;
        int background = dark ? 0xFF171310 : 0xFFFAFADF;
        if (rootView != null) {
            rootView.setBackgroundColor(background);
        }
        if (webView != null) {
            webView.setBackgroundColor(background);
        }
        applyBarIconAppearance();
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
            toast("focus one stream first");
            return;
        }
        if (isInPictureInPictureMode()) {
            return;
        }
        applyImmersive(false);
        boolean entered = enterPictureInPictureMode(pipParams(false));
        if (entered) {
            isInPip = true;
            notifyPipState(true);
        } else {
            toast("PiP unavailable");
        }
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
                RecordService.KIND_ERROR, "permission denied, so nothing started recording");
            return;
        }
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                notifyRecordingState(false, -1, RecordService.activeCount(), 0,
                    RecordService.KIND_ERROR, "permission denied, so nothing started recording");
                return;
            }
        }
        startRecordService();
    }

    /** Starts every camera in the batch that is not already recording. */
    private void requestRecording(int[] indices, String[] labels) {
        ArrayList<Integer> wantedIndices = new ArrayList<Integer>();
        ArrayList<String> wantedLabels = new ArrayList<String>();
        for (int i = 0; i < indices.length; i++) {
            if (indices[i] < 0 || RecordService.isActive(indices[i])) {
                continue;
            }
            wantedIndices.add(indices[i]);
            wantedLabels.add(labels != null && i < labels.length && labels[i] != null
                ? labels[i] : "stream");
        }
        if (wantedIndices.isEmpty()) {
            notifyRecordingState(false, -1, RecordService.activeCount(), 0,
                RecordService.KIND_NOTICE, "that camera is already recording");
            return;
        }
        pendingRecordIndices.clear();
        pendingRecordLabels.clear();
        pendingRecordIndices.addAll(wantedIndices);
        pendingRecordLabels.addAll(wantedLabels);

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
        JSONArray streams;
        try {
            streams = new JSONObject(streamConfigJson).getJSONArray("streams");
        } catch (Exception e) {
            notifyRecordingState(false, -1, RecordService.activeCount(), 0,
                RecordService.KIND_ERROR, "cannot start recording: " + e.getMessage());
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
                    RecordService.KIND_ERROR, "cannot start recording: " + e.getMessage());
            }
        }
        if (started == 0) {
            notifyRecordingState(false, -1, RecordService.activeCount(), 0,
                RecordService.KIND_ERROR, "nothing to record");
        }
    }

    /** Stops one camera, or every camera when the index is negative. */
    private void stopRecording(int index) {
        if (!RecordService.isActive()) {
            notifyRecordingState(false, -1, 0, 0,
                RecordService.KIND_NOTICE, "nothing is recording");
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
            return streamConfigJson;
        }

        @JavascriptInterface
        public void refreshKeys() {
            MainActivity.this.refreshKeys();
        }

        /** Pick which livestream host to try first. */
        @JavascriptInterface
        public void setChannelSource(int index) {
            channelSource = Math.max(0, Math.min(STREAM_HOSTS.length, index));
            getSharedPreferences("pbb", MODE_PRIVATE).edit()
                .putInt("channelSource", channelSource).apply();
        }

        /** Returns an empty string when the source was stored. */
        @JavascriptInterface
        public String setSource(int index, String url, String kid, String key) {
            return MainActivity.this.setSource(index, url, kid, key);
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
        public void setTheme(final String name) {
            final boolean dark = "dark".equals(name);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyTheme(dark);
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
        public void markStackPair(int top, int bottom) {
            RecordService.markStackPair(top, bottom);
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
