package com.pbb.live;

import android.Manifest;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
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
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 2001;

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
    private boolean recording = false;
    private boolean isInPip = false;
    private int focusedStream = -1;
    private int pendingRecordIndex = -1;
    private String pendingRecordLabel = "stream";
    private boolean orientationLocked = false;
    private float appVolumeLevel = -1f;
    private float appBrightnessLevel = -1f;
    private int savedSystemVolume = -1;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean active = intent.getBooleanExtra(RecordService.EXTRA_RECORDING, false);
            String message = intent.getStringExtra(RecordService.EXTRA_MESSAGE);
            notifyRecordingState(active, message);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String filesDir = getFilesDir().getAbsolutePath();
        appVolumeLevel = appVolume(filesDir);
        appBrightnessLevel = appBrightness(filesDir);
        setupWebView();
        registerStatusReceiver();
    }

    private void setupWebView() {
        webView = new WebView(this);
        setContentView(webView);
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
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
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
        webView.setWebViewClient(new WebViewClient());
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

    private void notifyRecordingState(boolean active, String message) {
        recording = active;
        if (!active) {
            lockLandscape(false);
        }
        final String quoted = JSONObject.quote(message == null ? "" : message);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                webView.evaluateJavascript(
                    "window.onRecordingState && window.onRecordingState(" + (active ? "true" : "false") + ", " + quoted + ")",
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

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void applyImmersive(boolean enabled) {
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
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
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
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(appVolumeLevel * max), 0);
    }

    private void restoreSystemVolume() {
        if (savedSystemVolume < 0) {
            return;
        }
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, savedSystemVolume, 0);
        }
        savedSystemVolume = -1;
    }

    private void clearBrightnessOverride() {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        if (params.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            return;
        }
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(params);
    }

    private void lockLandscape(boolean lock) {
        if (lock && !orientationLocked) {
            orientationLocked = true;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            return;
        }
        if (!lock && orientationLocked) {
            orientationLocked = false;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    private void enterPipMode() {
        if (recording) {
            toast("stop recording before pip");
            return;
        }
        if (focusedStream < 0) {
            toast("focus one stream first");
            return;
        }
        if (isInPictureInPictureMode()) {
            return;
        }
        applyImmersive(false);
        PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
        builder.setAspectRatio(new Rational(16, 9));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setTitle("PBB");
            builder.setSubtitle("live stream");
        }
        boolean entered = enterPictureInPictureMode(builder.build());
        if (entered) {
            isInPip = true;
            notifyPipState(true);
        } else {
            toast("PiP unavailable");
        }
    }

    @Override
    protected void onUserLeaveHint() {
        if (focusedStream >= 0 && !recording && !isInPip) {
            enterPipMode();
        }
        super.onUserLeaveHint();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        isInPip = isInPictureInPictureMode;
        // In the pip window the device brightness applies, not the app's level.
        if (isInPictureInPictureMode) {
            clearBrightnessOverride();
        } else if (appBrightnessLevel >= 0f) {
            applyBrightness(appBrightnessLevel, false);
        }
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
            notifyRecordingState(false, "permission denied");
            return;
        }
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                notifyRecordingState(false, "permission denied");
                return;
            }
        }
        startRecordService();
    }

    private void requestRecording(int index, String label) {
        if (recording) {
            stopRecording();
            return;
        }
        if (isInPip) {
            notifyRecordingState(false, "exit picture-in-picture before recording");
            return;
        }
        pendingRecordIndex = index;
        pendingRecordLabel = label;
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
        try {
            JSONArray streams = new JSONObject(streamConfig()).getJSONArray("streams");
            if (pendingRecordIndex < 0 || pendingRecordIndex >= streams.length()) {
                notifyRecordingState(false, "unknown stream");
                return;
            }
            JSONObject stream = streams.getJSONObject(pendingRecordIndex);
            String displayName = makeDisplayName(pendingRecordLabel);
            Intent service = new Intent(this, RecordService.class);
            service.setAction(RecordService.ACTION_START);
            service.putExtra(RecordService.EXTRA_MPD_URL, stream.getString("url"));
            service.putExtra(RecordService.EXTRA_KEY, stream.getString("key"));
            service.putExtra(RecordService.EXTRA_DISPLAY_NAME, displayName);
            service.putExtra(RecordService.EXTRA_QUALITY, defaultQuality(getFilesDir().getAbsolutePath()));
            startForegroundService(service);
            recording = true;
            notifyRecordingState(true, "recording to Movies/PBB/" + displayName);
        } catch (Exception e) {
            notifyRecordingState(false, "cannot start recording: " + e.getMessage());
        }
    }

    private void stopRecording() {
        if (!recording) {
            notifyRecordingState(false, "not recording");
            return;
        }
        Intent service = new Intent(this, RecordService.class);
        service.setAction(RecordService.ACTION_STOP);
        startService(service);
        recording = false;
        notifyRecordingState(false, "stopping recording...");
    }

    private String makeDisplayName(String label) {
        String safe = label.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isEmpty()) {
            safe = "stream";
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return "PBB_" + safe + "_" + stamp + ".mp4";
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
        if (appBrightnessLevel >= 0f && !isInPictureInPictureMode()) {
            applyBrightness(appBrightnessLevel, false);
        }
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
            return streamConfig();
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
        }

        @JavascriptInterface
        public void clearFocusedStream() {
            focusedStream = -1;
        }

        @JavascriptInterface
        public void setOrientation(final String mode) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if ("landscape".equals(mode)) {
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                    } else if ("portrait".equals(mode)) {
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
                    } else {
                        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
                    }
                }
            });
        }

        @JavascriptInterface
        public void setVolume(final float value) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyVolume(value, true);
                }
            });
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
        public void setBrightness(final float value) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    applyBrightness(value, true);
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
        public void startRecording(int index, String label) {
            final int streamIndex = index;
            final String streamLabel = label;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    requestRecording(streamIndex, streamLabel);
                }
            });
        }

        @JavascriptInterface
        public void stopRecording() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.stopRecording();
                }
            });
        }

        @JavascriptInterface
        public boolean isRecording() {
            return recording;
        }

        @JavascriptInterface
        public boolean isInPip() {
            return isInPip;
        }

        @JavascriptInterface
        public void toast(String message) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    MainActivity.this.toast(message);
                }
            });
        }
    }
}
