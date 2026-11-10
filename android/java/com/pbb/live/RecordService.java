package com.pbb.live;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.system.Os;
import android.util.Base64;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records a stream by pulling its DASH segments directly. No MediaProjection,
 * no activity, no foreground window: the service keeps working with the app in
 * the background or the screen off.
 *
 * The ffmpeg remuxer is downloaded on demand instead of shipping in the APK.
 * Android 10+ forbids execve() on files in an app's home directory, so the
 * plugin is a shared library loaded with System.load() from a read-only file
 * (Android 14+ rejects writable dynamic code).
 */
public class RecordService extends Service {
    public static final String ACTION_START = "com.pbb.live.RECORD_START";
    public static final String ACTION_STOP = "com.pbb.live.RECORD_STOP";
    public static final String ACTION_STATUS = "com.pbb.live.RECORD_STATUS";
    public static final String EXTRA_MPD_URL = "mpd_url";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_DISPLAY_NAME = "display_name";
    public static final String EXTRA_QUALITY = "quality";
    public static final String EXTRA_STREAM_INDEX = "stream_index";
    public static final String EXTRA_RECORDING = "recording";
    public static final String EXTRA_MESSAGE = "message";

    private static final String CHANNEL_ID = "pbb_recording";
    private static final int NOTIFICATION_ID = 4101;
    private static final int STOP_REQUEST_CODE = 4102;
    private static final String TAG = "PbbRecordService";
    private static final String PLUGIN_FILE = "libpbbffmpeg.so";

    private static final String PLUGIN_URL =
        "https://gist.githubusercontent.com/codeghost-glitch/e2db5f9ab594739147c94ad82f5bd3d1/raw/7a5bd9a1ea01410443b94f310be6a1684c967823/libpbbffmpeg.so.b64";
    private static final String PLUGIN_SHA256 =
        "c02573c6d1ae455e50027119c36d079bf4edf20aab108e1ab849db2daa2d4482";

    private static final Pattern NUMBER = Pattern.compile("\\$Number(%0(\\d+)d)?\\$");

    private static boolean pluginLoaded = false;
    private static volatile boolean active = false;
    private static volatile int activeIndex = -1;

    public static boolean isActive() {
        return active;
    }

    public static int activeStream() {
        return activeIndex;
    }

    private volatile boolean running = false;
    private volatile boolean stopping = false;
    private Thread worker;
    private String quality = "auto";

    private static native void nativeRemux(String video, String audio, String output, String keyHex);

    private static class Track {
        String media;
        String init;
        long startNumber;
        double seconds;
        int width;
        long bandwidth;
    }

    private static class Plan {
        String baseUrl;
        Track video;
        Track audio;
    }

    private static class Template {
        String media;
        String init;
        long startNumber = -1;
        long duration = -1;
        long timescale = -1;

        Template copy() {
            Template t = new Template();
            t.media = media;
            t.init = init;
            t.startNumber = startNumber;
            t.duration = duration;
            t.timescale = timescale;
            return t;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            stopping = true;
            running = false;
            if (worker != null) {
                worker.interrupt();
            }
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) {
            status(true, "recording already active");
            return START_NOT_STICKY;
        }

        final String mpdUrl = intent.getStringExtra(EXTRA_MPD_URL);
        final String key = intent.getStringExtra(EXTRA_KEY);
        final String name = intent.getStringExtra(EXTRA_DISPLAY_NAME);
        String q = intent.getStringExtra(EXTRA_QUALITY);
        quality = q == null ? "auto" : q;
        if (mpdUrl == null || key == null) {
            status(false, "missing stream config");
            stopSelf();
            return START_NOT_STICKY;
        }

        startForegroundWithNotification();
        running = true;
        stopping = false;
        active = true;
        activeIndex = intent.getIntExtra(EXTRA_STREAM_INDEX, 0);
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                record(mpdUrl, key, name == null ? "PBB_recording.mp4" : name);
            }
        }, "pbb-pull-record");
        worker.start();
        return START_NOT_STICKY;
    }

    private void record(String mpdUrl, String key, String displayName) {
        File videoTmp = new File(getCacheDir(), "pull_video.mp4");
        File audioTmp = new File(getCacheDir(), "pull_audio.mp4");
        File outTmp = new File(getCacheDir(), "pull_out.mp4");
        delete(videoTmp);
        delete(audioTmp);
        delete(outTmp);
        int segments = 0;
        String result = null;
        try {
            if (!isArm64()) {
                throw new IOException("recorder plugin is arm64 only on this build");
            }
            status(true, "downloading recorder plugin");
            loadPlugin(this);

            Plan plan = loadPlan(mpdUrl);
            write(videoTmp, fetch(plan.baseUrl + plan.video.init));
            write(audioTmp, fetch(plan.baseUrl + plan.audio.init));

            status(true, "finding the live edge");
            long offset = findLiveOffset(plan);
            int misses = 0;
            status(true, "recording to Movies/PBB/" + displayName);
            while (running && !stopping) {
                String vUrl = plan.baseUrl + segmentUrl(plan.video.media, plan.video.startNumber + offset);
                String aUrl = plan.baseUrl + segmentUrl(plan.audio.media, plan.audio.startNumber + offset);
                byte[] v = null;
                byte[] a = null;
                try {
                    v = fetch(vUrl);
                    a = fetch(aUrl);
                } catch (IOException e) {
                    Log.w(TAG, "segment fetch failed", e);
                }
                if (v == null || a == null) {
                    misses++;
                    if (misses > 30) {
                        // Long stall: the live edge moved or a new period started.
                        plan = loadPlan(mpdUrl);
                        offset = findLiveOffset(plan);
                        misses = 0;
                    }
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
                append(videoTmp, v);
                append(audioTmp, a);
                segments++;
                misses = 0;
                offset++;
            }

            if (segments == 0) {
                result = "nothing recorded";
            } else {
                status(true, "saving " + segments + " segments");
                nativeRemux(videoTmp.getAbsolutePath(), audioTmp.getAbsolutePath(),
                    outTmp.getAbsolutePath(), key);
                result = "saved to " + publish(outTmp, displayName);
            }
        } catch (Throwable t) {
            Log.e(TAG, "recording failed", t);
            result = "recording failed: " + t.getMessage();
        } finally {
            running = false;
            active = false;
            activeIndex = -1;
            status(false, result == null ? "recording stopped" : result);
            delete(videoTmp);
            delete(audioTmp);
            delete(outTmp);
            stopForeground(true);
            stopSelf();
        }
    }

    private static boolean isArm64() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return true;
            }
        }
        return false;
    }

    /** The plugin is fetched once, verified against a pinned digest, and loaded read-only. */
    private static synchronized void loadPlugin(Context context) throws Exception {
        if (pluginLoaded) {
            return;
        }
        File file = new File(context.getFilesDir(), PLUGIN_FILE);
        byte[] existing = file.exists() ? readAll(new FileInputStream(file)) : null;
        if (existing == null || !PLUGIN_SHA256.equals(hex(sha256(existing)))) {
            byte[] encoded = fetch(PLUGIN_URL);
            if (encoded == null) {
                throw new IOException("plugin download returned 404");
            }
            byte[] so = Base64.decode(
                new String(encoded, StandardCharsets.US_ASCII).trim(), Base64.DEFAULT);
            if (!PLUGIN_SHA256.equals(hex(sha256(so)))) {
                throw new IOException("recorder plugin checksum mismatch");
            }
            File tmp = new File(context.getFilesDir(), PLUGIN_FILE + ".tmp");
            delete(tmp);
            write(tmp, so);
            Os.chmod(tmp.getAbsolutePath(), 0444);
            delete(file);
            if (!tmp.renameTo(file)) {
                throw new IOException("cannot install recorder plugin");
            }
        }
        Os.chmod(file.getAbsolutePath(), 0444);
        System.load(file.getAbsolutePath());
        pluginLoaded = true;
    }

    private Plan loadPlan(String mpdUrl) throws Exception {
        HttpURLConnection conn = open(mpdUrl);
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("manifest HTTP " + code);
        }
        byte[] body;
        InputStream in = conn.getInputStream();
        try {
            body = readAll(in);
        } finally {
            in.close();
        }
        conn.disconnect();

        List<Track> videos = new ArrayList<Track>();
        List<Track> audios = new ArrayList<Track>();
        parseManifest(body, videos, audios);
        long maxStart = -1;
        for (Track t : videos) maxStart = Math.max(maxStart, t.startNumber);
        for (Track t : audios) maxStart = Math.max(maxStart, t.startNumber);

        Plan plan = new Plan();
        plan.baseUrl = mpdUrl.substring(0, mpdUrl.lastIndexOf('/') + 1);
        plan.video = pickVideo(videos, quality, maxStart);
        plan.audio = pickAudio(audios, maxStart);
        if (plan.video == null || plan.video.seconds <= 0
                || plan.audio == null || plan.audio.seconds <= 0
                || plan.video.media == null || plan.audio.media == null
                || plan.video.init == null || plan.audio.init == null) {
            throw new IOException("unsupported manifest");
        }
        return plan;
    }

    private static Track pickVideo(List<Track> tracks, String quality, long liveStart) {
        int ceiling = "sd".equals(quality) ? 640 : 1280;
        Track best = null;
        Track fallback = null;
        for (Track t : tracks) {
            if (t.seconds <= 0 || t.startNumber < 0 || t.media == null || t.init == null) {
                continue;
            }
            if (fallback == null) {
                fallback = t;
            }
            if (t.startNumber != liveStart || (t.width > 0 && t.width > ceiling)) {
                continue;
            }
            if (best == null || t.width > best.width
                || (t.width == best.width && t.bandwidth > best.bandwidth)) {
                best = t;
            }
        }
        return best == null ? fallback : best;
    }

    private static Track pickAudio(List<Track> tracks, long liveStart) {
        Track fallback = null;
        for (Track t : tracks) {
            if (t.media == null || t.init == null) {
                continue;
            }
            if (fallback == null) {
                fallback = t;
            }
            if (t.startNumber == liveStart) {
                return t;
            }
        }
        return fallback;
    }

    /**
     * The broadcaster's segment numbering does not follow availabilityStartTime,
     * and some representations have holes in their archive, so discover the live
     * edge by probing one-byte range requests: coarse scan for the highest
     * published segment, then binary search the 404 boundary.
     */
    private static long findLiveOffset(Plan plan) throws IOException {
        long best = -1;
        for (long off = 0; off <= 65536L; off += 256L) {
            if (exists(plan, off)) {
                best = off;
            } else if (best >= 0 && off - best > 2048L) {
                break;
            }
        }
        if (best < 0) {
            throw new IOException("no live segments available");
        }
        long good = best;
        long bad = best + 2049L;
        while (bad - good > 1) {
            long mid = good + (bad - good) / 2;
            if (exists(plan, mid)) {
                good = mid;
            } else {
                bad = mid;
            }
        }
        return Math.max(0L, good - 1);
    }

    private static boolean exists(Plan plan, long offset) {
        String url = plan.baseUrl
            + segmentUrl(plan.video.media, plan.video.startNumber + offset);
        HttpURLConnection conn = null;
        try {
            conn = open(url);
            conn.setRequestProperty("Range", "bytes=0-0");
            int code = conn.getResponseCode();
            return code == 200 || code == 206;
        } catch (IOException e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Parses the small, flat SegmentTemplate MPDs these streams publish. */
    private static void parseManifest(byte[] xml, List<Track> videos, List<Track> audios)
            throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setInput(new java.io.ByteArrayInputStream(xml), "UTF-8");
        Template setTpl = new Template();
        Template repTpl = null;
        Track rep = null;
        boolean isVideo = false;
        for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
            if (event == XmlPullParser.START_TAG) {
                String name = p.getName();
                if ("AdaptationSet".equals(name)) {
                    String mime = p.getAttributeValue(null, "mimeType");
                    isVideo = mime != null && mime.startsWith("video");
                    setTpl = new Template();
                } else if ("SegmentTemplate".equals(name)) {
                    merge(p, repTpl == null ? setTpl : repTpl);
                } else if ("Representation".equals(name)) {
                    repTpl = setTpl.copy();
                    rep = new Track();
                    rep.width = intAttr(p, "width");
                    rep.bandwidth = longAttr(p, "bandwidth");
                }
            } else if (event == XmlPullParser.END_TAG) {
                String name = p.getName();
                if ("Representation".equals(name) && rep != null) {
                    fill(rep, repTpl);
                    if (isVideo) {
                        videos.add(rep);
                    } else {
                        audios.add(rep);
                    }
                    rep = null;
                    repTpl = null;
                } else if ("SegmentTemplate".equals(name)) {
                    // keep the adaptation-set level values for child representations
                }
            }
        }
    }

    private static void merge(XmlPullParser p, Template t) {
        String v;
        if ((v = p.getAttributeValue(null, "media")) != null) t.media = v;
        if ((v = p.getAttributeValue(null, "initialization")) != null) t.init = v;
        long n;
        if ((n = longAttr(p, "startNumber")) >= 0) t.startNumber = n;
        if ((n = longAttr(p, "duration")) >= 0) t.duration = n;
        if ((n = longAttr(p, "timescale")) >= 0) t.timescale = n;
    }

    private static void fill(Track track, Template t) {
        track.media = t.media;
        track.init = t.init;
        track.startNumber = t.startNumber;
        track.seconds = t.timescale > 0 ? (double) t.duration / t.timescale : 0;
    }

    private static String segmentUrl(String template, long number) {
        Matcher m = NUMBER.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String digits = m.group(2) == null
                ? Long.toString(number)
                : String.format(Locale.US, "%0" + Integer.parseInt(m.group(2)) + "d", number);
            m.appendReplacement(sb, Matcher.quoteReplacement(digits));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Returns null on 404 (the live edge has not published that segment yet). */
    private static byte[] fetch(String url) throws IOException {
        HttpURLConnection conn = open(url);
        int code = conn.getResponseCode();
        if (code == 404) {
            conn.disconnect();
            return null;
        }
        if (code != 200) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }
        InputStream in = conn.getInputStream();
        try {
            return readAll(in);
        } finally {
            in.close();
            conn.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "PBB/1.0");
        return c;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void write(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f, false);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static void append(File f, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(f, true);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private static void delete(File f) {
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "cannot delete " + f);
        }
    }

    private static int intAttr(XmlPullParser p, String name) {
        return (int) longAttr(p, name);
    }

    private static long longAttr(XmlPullParser p, String name) {
        String v = p.getAttributeValue(null, name);
        if (v == null) {
            return -1;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    private String publish(File src, String displayName) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/PBB");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("MediaStore insert failed");
            }
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
            if (pfd == null) {
                getContentResolver().delete(uri, null, null);
                throw new IOException("MediaStore open failed");
            }
            try {
                copy(src, pfd);
            } catch (IOException e) {
                getContentResolver().delete(uri, null, null);
                throw e;
            }
            ContentValues publishValues = new ContentValues();
            publishValues.put(MediaStore.Video.Media.IS_PENDING, 0);
            getContentResolver().update(uri, publishValues, null, null);
            return "Movies/PBB/" + displayName;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_MOVIES), "PBB");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create Movies/PBB");
        }
        File dest = new File(dir, displayName);
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dest, false);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
            out.close();
        }
        return dest.getAbsolutePath();
    }

    private static void copy(File src, ParcelFileDescriptor pfd) throws IOException {
        FileInputStream in = new FileInputStream(src);
        OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
        try {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            in.close();
            out.close();
        }
    }

    private PendingIntent stopIntent() {
        Intent stop = new Intent(this, RecordService.class).setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, STOP_REQUEST_CODE, stop, flags);
    }

    private void startForegroundWithNotification() {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("PBB recording")
            .setContentText("pulling stream to Movies/PBB")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "stop", stopIntent());
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void status(boolean active, String message) {
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName());
        intent.putExtra(EXTRA_RECORDING, active);
        intent.putExtra(EXTRA_MESSAGE, message == null ? "" : message);
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "PBB recording", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("stream recording status");
        manager.createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }
}
