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
import android.os.Process;
import android.provider.MediaStore;
import android.system.Os;
import android.util.Base64;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records one or more streams by pulling their DASH segments directly. No
 * MediaProjection, no activity, no foreground window: the service keeps working
 * with the app in the background or the screen off.
 *
 * Each stream is an independent session with its own worker thread, temporary
 * files and notification, so several cameras can record at the same time. The
 * sessions share the foreground service, the downloaded ffmpeg plugin and one
 * remux lock: libavformat is not safe to drive from two threads at once, but the
 * pulls run in parallel and only the final mux is serialised.
 *
 * The ffmpeg remuxer is downloaded on demand instead of shipping in the APK.
 * Android 10+ forbids execve() on files in an app's home directory, so the
 * plugin is a shared library loaded with System.load() from a read-only file
 * (Android 14+ rejects writable dynamic code).
 */
public class RecordService extends Service {
    // sd asks for 480 lines; a 16:9 frame of that is 854 px wide.
private static final int SD_MIN_WIDTH = 854;

public static final String ACTION_START = "com.pbb.live.RECORD_START";
    public static final String ACTION_STOP = "com.pbb.live.RECORD_STOP";
    public static final String ACTION_STATUS = "com.pbb.live.RECORD_STATUS";
    public static final String EXTRA_MPD_URL = "mpd_url";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_DISPLAY_NAME = "display_name";
    public static final String EXTRA_STREAM_NAME = "stream_name";
    public static final String EXTRA_QUALITY = "quality";
    public static final String EXTRA_STREAM_INDEX = "stream_index";
    public static final String EXTRA_RECORDING = "recording";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_KIND = "kind";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_ELAPSED = "elapsed";

    /** A stream started recording. */
    public static final String KIND_START = "start";
    /** A notice about the request itself, not about a recording's progress. */
    public static final String KIND_NOTICE = "notice";
    /** The stream stopped cleanly and its file is in Movies/PBB. */
    public static final String KIND_DONE = "done";
    /** The stream stopped because of a failure. */
    public static final String KIND_ERROR = "error";

    private static final String CHANNEL_ID = "pbb_recording";
    private static final String GROUP = "pbb_recording_group";
    private static final int SUMMARY_ID = 4101;
    private static final int SESSION_ID_BASE = 4200;
    private static final int SESSION_REQUEST_BASE = 4300;
    private static final int STOP_ALL_REQUEST_CODE = 4102;
    private static final int OPEN_REQUEST_CODE = 4103;
    private static final String TAG = "PbbRecordService";
    private static final String PLUGIN_FILE = "libpbbffmpeg.so";
    private static final String PLUGIN_ASSET = "libpbbffmpeg.so.b64";

    private static final String PLUGIN_URL =
        "https://gist.githubusercontent.com/codeghost-glitch/e2db5f9ab594739147c94ad82f5bd3d1/raw/7a5bd9a1ea01410443b94f310be6a1684c967823/libpbbffmpeg.so.b64";
    private static final String PLUGIN_SHA256 =
        "6c8b08e0a2e0be40c80bd01bad14e3941df8ffa04c6b3ab3c10bdb41ace891f6";

    private static final Pattern NUMBER = Pattern.compile("\\$Number(%0(\\d+)d)?\\$");
    private static final Pattern DEFAULT_KID =
        Pattern.compile("default_KID=\\\"([0-9a-fA-F-]{32,36})\\\"");

    /** How long a single segment pull may take before it counts as a miss. */
    private static final long FETCH_TIMEOUT_SECONDS = 45;

    /** Consecutive empty parts allowed before a stalled stream is given up on. */
    static final int MAX_EMPTY_RETRIES = 3;
    private static final int MAX_PROBE_RETRIES = 20;

    private static final Object PAIR_LOCK = new Object();
    private static int pairTop = -1;
    private static int pairBottom = -1;
    private static boolean pairStacking = false;
    private static boolean pairFailed = false;
    private static final Map<Integer, File> pairSources = new HashMap<Integer, File>();
    private static final Map<Integer, Uri> pairUris = new HashMap<Integer, Uri>();
    private static final Map<Integer, File> pairPublished = new HashMap<Integer, File>();

    /** Called by the page before starting the two feeds of a stitch recording. */
    static void markStackPair(int top, int bottom) {
        synchronized (PAIR_LOCK) {
            // A generic record start clears the pair. Ignore it while a stack
            // is pending or running, or the two feeds finish as separate files.
            if (top < 0 && (pairTop >= 0 || pairStacking)) {
                return;
            }
            pairTop = top;
            pairBottom = bottom;
            pairStacking = false;
            pairFailed = false;
            pairSources.clear();
            pairUris.clear();
            pairPublished.clear();
        }
    }

    /**
     * The live edge of every manifest this app has pulled, so the next
     * recording starts from there instead of walking the whole archive again.
     */
    private static final Map<String, Long> LAST_EDGE = new HashMap<String, Long>();

    /**
     * Start time per recording stream, in one place the activity can read while
     * the UI is rebuilt. Keyed by stream index.
     */
    private static final Map<Integer, Long> STARTED = new HashMap<Integer, Long>();

    private static boolean pluginLoaded = false;
    private static final Object REMUX_LOCK = new Object();

    public static boolean isActive() {
        synchronized (STARTED) {
            return !STARTED.isEmpty();
        }
    }

    public static boolean isActive(int index) {
        synchronized (STARTED) {
            return STARTED.containsKey(index);
        }
    }

    public static int activeCount() {
        synchronized (STARTED) {
            return STARTED.size();
        }
    }

    /** The service can only stop when no worker is left and no join is running. */
    static boolean shouldStopService(int activeCount, boolean stacking) {
        return activeCount == 0 && !stacking;
    }

    /** Stream indices recording right now, ascending. */
    public static int[] activeStreams() {
        synchronized (STARTED) {
            int[] out = new int[STARTED.size()];
            int i = 0;
            for (Integer key : STARTED.keySet()) {
                out[i++] = key;
            }
            Arrays.sort(out);
            return out;
        }
    }

    /** Seconds the stream has been recording, or 0 when it is not recording. */
    public static long elapsedSeconds(int index) {
        long started;
        synchronized (STARTED) {
            Long value = STARTED.get(index);
            if (value == null) {
                return 0L;
            }
            started = value;
        }
        return Math.max(0L, (System.currentTimeMillis() - started) / 1000L);
    }

    private final Map<Integer, Session> sessions = new HashMap<Integer, Session>();

    private static native void nativeRemux(String video, String audio, String output, String keyHex);
    private static native void nativeStack(String top, String bottom, String output);

    static class Track {
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
        long suggestedOffset = -1;
    }

    /** Where a file landed, and whether it is only an app-private fallback. */
    private static final class Published {
        final String path;
        final boolean fallback;

        Published(String path, boolean fallback) {
            this.path = path;
            this.fallback = fallback;
        }
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

    /** One stream recording: its own arguments, worker and stop flag. */
    private static final class Session {
        final int index;
        final String mpdUrl;
        final String key;
        final String displayName;
        final String streamName;
        final String quality;
        final long startedAt;
        volatile boolean running = true;
        Thread worker;
        Uri publishedUri;
        File publishedFile;
        File publishedFallbackFile;

        Session(int index, String mpdUrl, String key, String displayName,
                String streamName, String quality) {
            this.index = index;
            this.mpdUrl = mpdUrl;
            this.key = key;
            this.displayName = displayName;
            this.streamName = streamName;
            this.quality = quality;
            this.startedAt = System.currentTimeMillis();
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
            stopIfIdle();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            int index = intent.getIntExtra(EXTRA_STREAM_INDEX, -1);
            if (index < 0) {
                stopAll();
            } else {
                stopSession(index);
            }
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) {
            stopIfIdle();
            return START_NOT_STICKY;
        }

        final String mpdUrl = intent.getStringExtra(EXTRA_MPD_URL);
        final String key = intent.getStringExtra(EXTRA_KEY);
        final String name = intent.getStringExtra(EXTRA_DISPLAY_NAME);
        final String streamName = intent.getStringExtra(EXTRA_STREAM_NAME);
        final String quality = intent.getStringExtra(EXTRA_QUALITY);
        int index = intent.getIntExtra(EXTRA_STREAM_INDEX, -1);
        if (mpdUrl == null || key == null || index < 0) {
            status(false, -1, "missing stream config", KIND_ERROR);
            stopIfIdle();
            return START_NOT_STICKY;
        }

        final Session session;
        synchronized (sessions) {
            if (sessions.containsKey(index)) {
                session = null;
            } else {
                session = new Session(index, mpdUrl, key,
                    name == null ? "PBB_recording.mp4" : name,
                    streamName == null ? "camera " + (index + 1) : streamName,
                    quality == null ? "hd" : quality);
                sessions.put(index, session);
            }
        }
        if (session == null) {
            // The service is already foreground, but answer this start with an
            // updated notification rather than leaving the call unanswered.
            updateSummary();
            status(true, index, "this camera is already recording", KIND_NOTICE);
            return START_NOT_STICKY;
        }
        synchronized (STARTED) {
            STARTED.put(index, session.startedAt);
        }

        updateSummary();
        notifySession(session);
        status(true, index, "", KIND_START);

        Thread worker = backgroundThreads("pbb-pull-record-" + index).newThread(
            new Runnable() {
                @Override
                public void run() {
                    record(session);
                }
            });
        session.worker = worker;
        worker.start();
        return START_NOT_STICKY;
    }

    /**
     * Android 15 caps dataSync foreground services at six hours. Stop the pulls so
     * the normal path still remuxes and publishes what was recorded.
     */
    public void onTimeout(int startId, int foregroundServiceType) {
        stopAll();
    }

    private void record(Session session) {
        int index = session.index;
        // The temp files live in filesDir, not the cache dir: Android is free
        // to delete the cache when it needs space, and these are the only copy
        // of the recording until the remux publishes them.
        File videoTmp = new File(getFilesDir(), "pull_video_" + index + ".mp4");
        File audioTmp = new File(getFilesDir(), "pull_audio_" + index + ".mp4");
        File outTmp = new File(getFilesDir(), "pull_out_" + index + ".mp4");
        String result = "nothing recorded - stopped before the first segment";
        String savedMessage = null;
        boolean failed = false;
        boolean keepSegments = false;
        int totalSegments = 0;
        int emptyRetries = 0;
        int probeRetries = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2,
            backgroundThreads("pbb-pull-fetch-" + index));
        try {
            if (!isArm64()) {
                throw new IOException("recorder plugin is arm64 only on this build");
            }
            loadPlugin(this);

            // One part per uninterrupted run. A gap in the archive ends a part and
            // a second file continues, so no file ever splices across a hole.
            String name = session.displayName;
            int part = 1;
            while (session.running) {
                delete(videoTmp);
                delete(audioTmp);
                delete(outTmp);

                Plan plan = loadPlan(session.mpdUrl, session.quality);
                byte[] videoInit = fetch(plan.baseUrl + plan.video.init);
                byte[] audioInit = fetch(plan.baseUrl + plan.audio.init);
                if (videoInit == null || audioInit == null) {
                    throw new IOException("init segment missing");
                }
                write(videoTmp, videoInit);
                write(audioTmp, audioInit);

                long offset;
                try {
                    offset = findLiveOffset(session.mpdUrl, plan, session);
                } catch (IOException e) {
                    if (!session.running) {
                        long fallback = rememberedEdge(session.mpdUrl);
                        if (savedMessage != null || fallback < 0) {
                            result = savedMessage != null ? savedMessage : result;
                            break;
                        }
                        offset = fallback;
                    } else {
                        // A CDN hiccup, a rolled DVR window or a slow range
                        // probe is not the end of a live broadcast. Re-read the
                        // manifest and probe again; the user decides when to stop.
                        probeRetries++;
                        forgetEdge(session.mpdUrl);
                        if (probeRetries > MAX_PROBE_RETRIES) {
                            throw e;
                        }
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException ie) {
                            break;
                        }
                        continue;
                    }
                }
                if (offset < 0) {
                    long fallback = rememberedEdge(session.mpdUrl);
                    if (savedMessage == null && fallback >= 0) {
                        offset = fallback;
                    } else {
                        result = savedMessage != null ? savedMessage : result;
                        break;
                    }
                }
                int misses = 0;
                int segmentsThisPart = 0;
                boolean gap = false;
                int stopFetches = 0;
                while (session.running || segmentsThisPart == 0) {
                    if (!session.running) {
                        // stopSession() interrupts the worker to end the pull.
                        // pulled() re-sets that flag, which would poison the
                        // final fetch and leave us with "nothing recorded".
                        Thread.interrupted();
                        if (++stopFetches > 3) {
                            break;
                        }
                    }
                    String vUrl = plan.baseUrl
                        + segmentUrl(plan.video.media, plan.video.startNumber + offset);
                    String aUrl = plan.baseUrl
                        + segmentUrl(plan.audio.media, plan.audio.startNumber + offset);
                    // The video and the audio travel together, so one segment
                    // costs one round trip instead of two.
                    byte[][] both = fetchBoth(pool, vUrl, aUrl, index);
                    byte[] v = both[0];
                    byte[] a = both[1];
                    if (v == null || a == null || v.length < 128 || a.length < 128) {
                        misses++;
                        if (misses > 30) {
                            // Long stall: the live edge moved past what the archive
                            // still serves. Save this part and start the next one.
                            gap = true;
                            break;
                        }
                        if (session.running) {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                        continue;
                    }
                    append(videoTmp, v);
                    append(audioTmp, a);
                    segmentsThisPart++;
                    misses = 0;
                    offset++;
                }

                Thread.interrupted();
                if (segmentsThisPart == 0) {
                    // Thirty misses before the first segment means the live edge
                    // moved past the numbers we were waiting on while the archive
                    // was being probed. Those numbers never come back: forget the
                    // stale edge, re-plan from the start and try again.
                    if (retryEmptyPart(gap, session.running, emptyRetries)) {
                        emptyRetries++;
                        forgetEdge(session.mpdUrl);
                        continue;
                    }
                    // Do not replace the file a previous part already published.
                    result = savedMessage != null ? savedMessage
                        : gap ? "nothing recorded before the stream gap" : "nothing recorded";
                    break;
                }
                emptyRetries = 0;
                probeRetries = 0;
                totalSegments += segmentsThisPart;
                // libavformat keeps process-wide state; two remuxes at once would
                // interleave their muxers. Pull in parallel, mux one at a time.
                try {
                    synchronized (REMUX_LOCK) {
                        nativeRemux(videoTmp.getAbsolutePath(), audioTmp.getAbsolutePath(),
                            outTmp.getAbsolutePath(), session.key);
                    }
                    if (outTmp.length() < 4096) {
                        throw new IOException("remux produced no media");
                    }
                    result = "saved to " + publish(outTmp, name, session).path;
                    savedMessage = result;
                } catch (Throwable t) {
                    // Keep this part's segments: a remux failure is recoverable,
                    // the pulled media is not.
                    Log.e(TAG, "remux failed for camera " + index, t);
                    result = "recording failed: " + t.getMessage();
                    failed = true;
                    keepSegments = true;
                    break;
                }
                if (!gap) {
                    break;
                }
                // A hole ends this part; the next one starts at the fresh edge.
                part++;
                name = partName(session.displayName, part);
            }
        } catch (Throwable t) {
            Log.e(TAG, "recording failed for camera " + index, t);
            result = "recording failed: " + t.getMessage();
            failed = true;
            keepSegments = totalSegments > 0;
        } finally {
            pool.shutdownNow();
            session.running = false;
            // A failed remux must not throw away the only copy of a long
            // recording: keep the pulled segments and say where they are. A part
            // with no media in it is just init data, so drop it instead.
            boolean stacked;
            synchronized (PAIR_LOCK) {
                stacked = session.index == pairTop || session.index == pairBottom;
            }
            if (keepSegments && videoTmp.exists()) {
                result = result + " - segments kept in " + getFilesDir().getAbsolutePath();
            } else {
                delete(videoTmp);
                delete(audioTmp);
                // A stitch pair needs the finished MP4 for the stack step.
                if (!stacked) {
                    delete(outTmp);
                }
            }
            synchronized (sessions) {
                sessions.remove(index);
            }
            synchronized (STARTED) {
                STARTED.remove(index);
            }
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.cancel(SESSION_ID_BASE + index);
            }
            if (activeCount() > 0) {
                updateSummary();
            }
            status(false, index, result, failed ? KIND_ERROR : KIND_DONE);
            if (stacked) {
                maybeStack(session, !failed && outTmp.exists() && outTmp.length() > 4096);
            }
            boolean joinRunning;
            synchronized (PAIR_LOCK) {
                joinRunning = pairStacking;
            }
            if (shouldStopService(activeCount(), joinRunning)) {
                stopForeground(true);
                stopSelf();
            }
        }
    }

    /** Both feeds of a stitch pair have finished: join them into one tall MP4. */
    private void maybeStack(Session session, boolean savedOk) {
        File top;
        File bottom;
        Uri topUri;
        Uri bottomUri;
        File topPublished;
        File bottomPublished;
        synchronized (PAIR_LOCK) {
            if (pairTop < 0 || pairStacking || pairFailed) {
                return;
            }
            if (!savedOk) {
                pairFailed = true;
                pairTop = -1;
                pairBottom = -1;
                delete(new File(getFilesDir(), "pull_out_" + session.index + ".mp4"));
                return;
            }
            pairSources.put(session.index,
                new File(getFilesDir(), "pull_out_" + session.index + ".mp4"));
            if (session.publishedUri != null) {
                pairUris.put(session.index, session.publishedUri);
            }
            File published = session.publishedFile != null
                ? session.publishedFile : session.publishedFallbackFile;
            if (published != null) {
                pairPublished.put(session.index, published);
            }
            if (!pairSources.containsKey(pairTop) || !pairSources.containsKey(pairBottom)) {
                return;
            }
            pairStacking = true;
            top = pairSources.get(pairTop);
            bottom = pairSources.get(pairBottom);
            topUri = pairUris.get(pairTop);
            bottomUri = pairUris.get(pairBottom);
            topPublished = pairPublished.get(pairTop);
            bottomPublished = pairPublished.get(pairBottom);
        }
        final File fTop = top;
        final File fBottom = bottom;
        final Uri uTop = topUri;
        final Uri uBottom = bottomUri;
        backgroundThreads("pbb-stack").newThread(new Runnable() {
            @Override
            public void run() {
                joinPair(fTop, fBottom, uTop, uBottom, topPublished, bottomPublished);
            }
        }).start();
    }

    private void joinPair(File top, File bottom, Uri topUri, Uri bottomUri,
                          File topPublished, File bottomPublished) {
        File joined = new File(getFilesDir(), "pbb_stack_" + System.currentTimeMillis() + ".mp4");
        String result;
        boolean ok = false;
        try {
            synchronized (REMUX_LOCK) {
                nativeStack(top.getAbsolutePath(), bottom.getAbsolutePath(),
                    joined.getAbsolutePath());
            }
            if (joined.length() < 8192) {
                throw new IOException("stack produced no media");
            }
            String name = "PBB_stitch_" + System.currentTimeMillis() + ".mp4";
            Published joinedOut = publish(joined, name, null);
            result = "saved stacked video to " + joinedOut.path;
            ok = true;
            // Only remove the originals when the joined file is in a real
            // gallery location; a fallback copy must leave them alone.
            if (!joinedOut.fallback) {
                if (topUri != null) getContentResolver().delete(topUri, null, null);
                if (bottomUri != null) getContentResolver().delete(bottomUri, null, null);
                delete(topPublished);
                delete(bottomPublished);
            }
        } catch (Throwable t) {
            Log.e(TAG, "stitch failed", t);
            result = "stitch failed: " + t.getMessage();
        } finally {
            delete(top);
            delete(bottom);
            delete(joined);
            synchronized (PAIR_LOCK) {
                pairTop = -1;
                pairBottom = -1;
                pairStacking = false;
            }
        }
        status(false, -1, result, ok ? KIND_DONE : KIND_ERROR);
        if (shouldStopService(activeCount(), false)) {
            stopForeground(true);
            stopSelf();
        }
    }

    static String partName(String displayName, int part) {
        int dot = displayName.lastIndexOf('.');
        String base = dot > 0 ? displayName.substring(0, dot) : displayName;
        String ext = dot > 0 ? displayName.substring(dot) : ".mp4";
        return base + "_" + part + ext;
    }

    /**
     * The pull, the FFmpeg remux and the MediaStore copy are heavy. Running
     * them at the UI thread's priority makes the page stutter while recording
     * and especially while a long recording is being saved on stop.
     */
    private static ThreadFactory backgroundThreads(final String name) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(final Runnable task) {
                return new Thread(new Runnable() {
                    @Override
                    public void run() {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                        task.run();
                    }
                }, name);
            }
        };
    }

    private static boolean isArm64() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The plugin ships as base64 in the assets, with the pinned gist as a fallback
     * for builds made without it. Either copy is verified against the pinned
     * digest before it is written read-only and loaded.
     */
    private static synchronized void loadPlugin(Context context) throws Exception {
        if (pluginLoaded) {
            return;
        }
        File file = new File(context.getFilesDir(), PLUGIN_FILE);
        byte[] existing = null;
        if (file.exists()) {
            try (FileInputStream in = new FileInputStream(file)) {
                existing = Http.readAll(in);
            }
        }
        if (existing == null || !PLUGIN_SHA256.equals(hex(sha256(existing)))) {
            byte[] so = bundledPlugin(context);
            if (so == null) {
                so = downloadedPlugin();
            }
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

    private static byte[] bundledPlugin(Context context) {
        try {
            InputStream in = context.getAssets().open(PLUGIN_ASSET);
            try {
                byte[] encoded = Http.readAll(in);
                return Base64.decode(
                    new String(encoded, StandardCharsets.US_ASCII).trim(), Base64.DEFAULT);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "no bundled recorder plugin", e);
            return null;
        }
    }

    private static byte[] downloadedPlugin() throws IOException {
        byte[] encoded = fetch(PLUGIN_URL);
        if (encoded == null) {
            throw new IOException("cannot download the recorder plugin, check the network and record again");
        }
        return Base64.decode(
            new String(encoded, StandardCharsets.US_ASCII).trim(), Base64.DEFAULT);
    }

    private Plan loadPlan(String mpdUrl, String quality) throws Exception {
        byte[] body = Http.getBytes(mpdUrl, 15000, 20000);
        if (body == null) {
            throw new IOException("manifest request failed");
        }

        List<Track> videos = new ArrayList<Track>();
        List<Track> audios = new ArrayList<Track>();
        parseManifest(body, videos, audios);
        long maxStart = -1;
        for (Track t : videos) maxStart = Math.max(maxStart, t.startNumber);
        for (Track t : audios) maxStart = Math.max(maxStart, t.startNumber);

        String xml = new String(body, StandardCharsets.UTF_8);
        Plan plan = new Plan();
        plan.suggestedOffset = liveOffsetFromMpd(xml);
        int baseStart = xml.indexOf("<BaseURL>");
        int baseEnd = baseStart < 0 ? -1 : xml.indexOf("</BaseURL>", baseStart);
        plan.baseUrl = baseEnd > baseStart
            ? xml.substring(baseStart + "<BaseURL>".length(), baseEnd)
            : mpdUrl.substring(0, mpdUrl.lastIndexOf('/') + 1);
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

    /**
     * The recorder cannot adapt the way the page does, so it picks one
     * representation and sticks to it. The rules are the page's rules: hd is
     * the tallest variant the feed publishes, sd is the smallest one that is
     * at least 480 lines wide (854 px at 16:9), never the half-height ones.
     */
    static Track pickVideo(List<Track> tracks, String quality, long liveStart) {
        Track best = null;
        Track fallback = null;
        Track smallest = null;
        for (Track t : tracks) {
            if (t.seconds <= 0 || t.startNumber < 0 || t.media == null || t.init == null) {
                continue;
            }
            if (fallback == null) {
                fallback = t;
            }
            if (t.startNumber != liveStart) {
                continue;
            }
            if (smallest == null || t.width < smallest.width
                || (t.width == smallest.width && t.bandwidth < smallest.bandwidth)) {
                smallest = t;
            }
            if (best == null || t.width > best.width
                || (t.width == best.width && t.bandwidth > best.bandwidth)) {
                best = t;
            }
        }
        if ("sd".equals(quality)) {
            Track sd = null;
            for (Track t : tracks) {
                if (t.seconds <= 0 || t.startNumber != liveStart || t.media == null) {
                    continue;
                }
                if (t.width < SD_MIN_WIDTH) {
                    continue;
                }
                if (sd == null || t.width < sd.width
                    || (t.width == sd.width && t.bandwidth < sd.bandwidth)) {
                    sd = t;
                }
            }
            return sd != null ? sd : (smallest != null ? smallest : fallback);
        }
        return best == null ? fallback : best;
    }

    static Track pickAudio(List<Track> tracks, long liveStart) {
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
     * Where the live edge is, straight from the MPD: the number of whole
     * segments between availabilityStartTime and publishTime. Scanning forward
     * from zero cannot work forever on a stream that has been up for days -
     * the edge moves ~21,600 segments (4s each) per day. Two segments back
     * keeps us inside the short DVR window. Pure so host tests can check it.
     */
    static long liveOffsetFromMpd(String xml) {
        try {
            String astText = mpdAttribute(xml, "availabilityStartTime");
            String pubText = mpdAttribute(xml, "publishTime");
            String scaleText = mpdAttribute(xml, "timescale");
            String durText = mpdAttribute(xml, "duration");
            if (astText == null || pubText == null || scaleText == null || durText == null) {
                return -1L;
            }
            long ast = Instant.parse(astText).toEpochMilli();
            long pub = Instant.parse(pubText).toEpochMilli();
            double segmentSeconds = Double.parseDouble(durText) / Double.parseDouble(scaleText);
            if (ast <= 0 || pub <= ast || segmentSeconds <= 0) {
                return -1L;
            }
            long offset = (long) (((pub - ast) / 1000.0) / segmentSeconds) - 2;
            return offset < 0 ? 0L : offset;
        } catch (Exception e) {
            return -1L;
        }
    }

    private static String mpdAttribute(String xml, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]+)\"").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    /**
     * The broadcaster's segment numbering does not follow availabilityStartTime,
     * and some representations have holes in their archive, so discover the live
     * edge by probing one-byte range requests: coarse scan for the highest
     * published segment, then binary search the 404 boundary.
     *
     * The last edge found for a manifest is remembered, because the scan from
     * the start of the archive costs about fifty requests every time.
     */
    private static long findLiveOffset(String mpdUrl, Plan plan, Session session)
            throws IOException {
        long start = plan.suggestedOffset >= 0
            ? plan.suggestedOffset : Math.max(0L, rememberedEdge(mpdUrl));
        try {
            return scanEdge(mpdUrl, plan, start, session);
        } catch (IOException e) {
            long known = rememberedEdge(mpdUrl);
            if (known < 0 || Math.abs(known - start) <= 8) {
                throw e;
            }
            // The computed edge is stale; try the last edge we actually played.
            return scanEdge(mpdUrl, plan, known, session);
        }
    }

    /** Scans forward from start for the last segment the server still serves.
     *  Returns -1 when the session was stopped mid-probe. */
    private static long scanEdge(String mpdUrl, Plan plan, long start, Session session)
            throws IOException {
        long best = coarseScan(plan, start, 1024L, 65, session);
        if (best < 0 && session.running) {
            // A short DVR window, or a hole at every 1 KiB sample, can hide the
            // entire archive from the coarse pass. Walk a bounded fine range
            // before deciding the stream has gone away; a full 64-step scan of
            // the whole archive would take hundreds of requests.
            best = coarseScan(plan, start, 64L, 128, session);
        }
        if (best < 0 && session.running) {
            // The computed edge can sit a little ahead of what the CDN has
            // published. Step backwards inside the DVR window before giving up.
            for (long back = 4; back <= 64 && best < 0; back *= 2) {
                if (!session.running) {
                    break;
                }
                long candidate = Math.max(0L, start - back);
                if (exists(plan, candidate)) {
                    best = candidate;
                }
            }
        }
        if (best < 0) {
            throw new IOException("no live segments available");
        }
        long good = best;
        long bad = best + 2049L;
        while (bad - good > 1) {
            if (!session.running) {
                // Keep the best hit found so far so the final fetch can still
                // land on a real segment instead of reporting nothing recorded.
                synchronized (LAST_EDGE) {
                    LAST_EDGE.put(mpdUrl, Long.valueOf(good));
                }
                return Math.max(0L, good - 1);
            }
            long mid = good + (bad - good) / 2;
            if (exists(plan, mid)) {
                good = mid;
            } else {
                bad = mid;
            }
        }
        synchronized (LAST_EDGE) {
            LAST_EDGE.put(mpdUrl, Long.valueOf(good));
        }
        return Math.max(0L, good - 1);
    }

    /** Highest sampled offset that still exists, or -1 when none did (or the
     *  session was stopped). Two misses past the last hit end the pass. */
    private static long coarseScan(Plan plan, long start, long step, int maxProbes,
            Session session) {
        long best = -1;
        int probes = 0;
        for (long off = start; off <= start + 65536L && probes < maxProbes; off += step) {
            probes++;
            if (!session.running) {
                break;
            }
            if (exists(plan, off)) {
                best = off;
            } else if (best >= 0 && off - best > 2L * step) {
                break;
            }
        }
        return best;
    }

    /**
     * A long stall before the first segment is a stale live edge, not a hole in
     * the recording. Re-anchor and retry, but never spin forever on a stream
     * that is genuinely down. Kept pure so the host tests can check the rule.
     */
    static boolean retryEmptyPart(boolean gap, boolean running, int emptyRetries) {
        return gap && running && emptyRetries < MAX_EMPTY_RETRIES;
    }

    /** Force the next live-edge scan to start from the beginning of the archive. */
    private static void forgetEdge(String mpdUrl) {
        synchronized (LAST_EDGE) {
            LAST_EDGE.remove(mpdUrl);
        }
    }

    private static long rememberedEdge(String mpdUrl) {
        synchronized (LAST_EDGE) {
            Long value = LAST_EDGE.get(mpdUrl);
            return value == null ? -1L : value.longValue();
        }
    }

    private static boolean exists(Plan plan, long offset) {
        String url = plan.baseUrl
            + segmentUrl(plan.video.media, plan.video.startNumber + offset);
        HttpURLConnection conn = null;
        try {
            conn = Http.open(url, 4000, 6000);
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
        if ((v = p.getAttributeValue(null, "init")) != null) t.init = v;
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

    /**
     * The kid a manifest carries, as 32 hex characters, or an empty string
     * when the manifest has none. A manifest with another kid than the
     * config is a rotated channel.
     */
    static String manifestKid(String xml) {
        Matcher m = DEFAULT_KID.matcher(xml);
        if (!m.find()) {
            return "";
        }
        return m.group(1).replace("-", "").toLowerCase();
    }

    static String segmentUrl(String template, long number) {
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
        HttpURLConnection conn = Http.open(url, 15000, 20000);
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
            return Http.readAll(in);
        } finally {
            in.close();
            conn.disconnect();
        }
    }

    /**
     * Pulls the video and the audio segment at the same time. A pull that
     * failed comes back as null and the caller counts it as a miss.
     */
    private static byte[][] fetchBoth(ExecutorService pool, final String vUrl,
            final String aUrl, final int index) {
        Future<byte[]> video = pool.submit(new Callable<byte[]>() {
            @Override
            public byte[] call() throws IOException {
                return fetch(vUrl);
            }
        });
        Future<byte[]> audio = pool.submit(new Callable<byte[]>() {
            @Override
            public byte[] call() throws IOException {
                return fetch(aUrl);
            }
        });
        return new byte[][] { pulled(video, index), pulled(audio, index) };
    }

    /** The bytes of a finished pull, or null when the pull failed. */
    private static byte[] pulled(Future<byte[]> future, int index) {
        try {
            return future.get(FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            Log.w(TAG, "segment fetch failed for camera " + index, e.getCause());
            return null;
        } catch (TimeoutException e) {
            Log.w(TAG, "segment fetch timed out for camera " + index, e);
            return null;
        }
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
        if (f == null) {
            return;
        }
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

    private Published publish(File src, String displayName, Session session) throws IOException {
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
                return publishFallback(src, displayName, session);
            }
            ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
            if (pfd == null) {
                getContentResolver().delete(uri, null, null);
                return publishFallback(src, displayName, session);
            }
            try {
                copy(src, pfd);
            } catch (IOException e) {
                getContentResolver().delete(uri, null, null);
                throw e;
            }
            ContentValues publishValues = new ContentValues();
            publishValues.put(MediaStore.Video.Media.IS_PENDING, 0);
            if (getContentResolver().update(uri, publishValues, null, null) < 1) {
                // An entry left IS_PENDING is invisible in the gallery; keep a
                // real copy rather than reporting a save that cannot be found.
                getContentResolver().delete(uri, null, null);
                return publishFallback(src, displayName, session);
            }
            if (session != null) {
                session.publishedUri = uri;
            }
            return new Published("Movies/PBB/" + displayName, false);
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
        if (session != null) {
            session.publishedFile = dest;
        }
        return new Published(dest.getAbsolutePath(), false);
    }

    /** Last-resort copy when MediaStore will not take the file. App-private,
     *  but the recording is not lost; the status text carries the real path. */
    private Published publishFallback(File src, String displayName, Session session) throws IOException {
        File dir = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "PBB");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("cannot create the fallback PBB folder");
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
        if (session != null) {
            session.publishedFallbackFile = dest;
        }
        return new Published(dest.getAbsolutePath(), true);
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

    private void stopSession(int index) {
        Session session;
        synchronized (sessions) {
            session = sessions.get(index);
        }
        if (session == null) {
            stopIfIdle();
            return;
        }
        session.running = false;
        if (session.worker != null) {
            session.worker.interrupt();
        }
        // The remux and the MediaStore copy of a long recording take a while.
        // Clear the tally and the stop button now; "saved to ..." lands later.
        status(false, index, "saving " + session.streamName, KIND_NOTICE);
    }

    private void stopAll() {
        List<Session> copy;
        synchronized (sessions) {
            copy = new ArrayList<Session>(sessions.values());
        }
        if (copy.isEmpty()) {
            stopIfIdle();
            return;
        }
        for (Session session : copy) {
            session.running = false;
            if (session.worker != null) {
                session.worker.interrupt();
            }
            status(false, session.index, "saving " + session.streamName, KIND_NOTICE);
        }
    }

    private void stopIfIdle() {
        boolean idle;
        synchronized (sessions) {
            idle = sessions.isEmpty();
        }
        if (idle) {
            stopForeground(true);
            stopSelf();
        }
    }

    private String summaryTitle(int count) {
        return count == 1 ? "1 camera recording" : count + " cameras recording";
    }

    /**
     * The foreground notification is the group summary: it stays put while the
     * per-camera notifications come and go underneath it. Count and names are
     * read in one lock, and the names are sorted, so the notification does not
     * flicker or reorder while sessions come and go.
     */
    private void updateSummary() {
        final int count;
        final String text;
        synchronized (sessions) {
            count = sessions.size();
            Integer[] keys = sessions.keySet().toArray(new Integer[0]);
            Arrays.sort(keys);
            StringBuilder sb = new StringBuilder();
            for (Integer key : keys) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(sessions.get(key).streamName);
            }
            text = sb.toString();
        }
        if (count == 0) {
            return;
        }
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(summaryTitle(count))
            .setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setContentIntent(openIntent())
            .addAction(android.R.drawable.ic_media_pause, "stop all", stopAllIntent());
        Notification notification = builder.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(SUMMARY_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(SUMMARY_ID, notification);
        }
    }

    private void notifySession(Session session) {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(session.streamName)
            .setContentText("recording to Movies/PBB")
            .setOngoing(true)
            .setShowWhen(true)
            .setWhen(session.startedAt)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP)
            .setContentIntent(openIntent())
            .addAction(android.R.drawable.ic_media_pause, "stop", stopIntent(session.index))
            .build();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(SESSION_ID_BASE + session.index, notification);
        }
    }

    private PendingIntent stopIntent(int index) {
        Intent stop = new Intent(this, RecordService.class)
            .setAction(ACTION_STOP)
            .putExtra(EXTRA_STREAM_INDEX, index);
        return pending(stop, SESSION_REQUEST_BASE + index);
    }

    private PendingIntent stopAllIntent() {
        Intent stop = new Intent(this, RecordService.class).setAction(ACTION_STOP);
        return pending(stop, STOP_ALL_REQUEST_CODE);
    }

    private PendingIntent openIntent() {
        Intent open = new Intent(this, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, OPEN_REQUEST_CODE, open, flags);
    }

    private PendingIntent pending(Intent intent, int requestCode) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private void status(boolean active, int index, String message, String kind) {
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName());
        intent.putExtra(EXTRA_RECORDING, active);
        intent.putExtra(EXTRA_STREAM_INDEX, index);
        intent.putExtra(EXTRA_MESSAGE, message == null ? "" : message);
        intent.putExtra(EXTRA_KIND, kind == null ? KIND_NOTICE : kind);
        intent.putExtra(EXTRA_COUNT, activeCount());
        intent.putExtra(EXTRA_ELAPSED, elapsedSeconds(index));
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
        List<Session> copy;
        synchronized (sessions) {
            copy = new ArrayList<Session>(sessions.values());
            sessions.clear();
        }
        for (Session session : copy) {
            session.running = false;
            if (session.worker != null) {
                session.worker.interrupt();
            }
        }
        synchronized (STARTED) {
            STARTED.clear();
        }
        super.onDestroy();
    }
}
