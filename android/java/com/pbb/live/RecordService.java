package com.pbb.live;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;

import java.io.File;
import java.io.IOException;

public class RecordService extends Service {
    public static final String ACTION_START = "com.pbb.live.RECORD_START";
    public static final String ACTION_STOP = "com.pbb.live.RECORD_STOP";
    public static final String ACTION_STATUS = "com.pbb.live.RECORD_STATUS";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_DISPLAY_NAME = "display_name";
    public static final String EXTRA_STREAM_INDEX = "stream_index";
    public static final String EXTRA_RECORDING = "recording";
    public static final String EXTRA_MESSAGE = "message";

    private static final String CHANNEL_ID = "pbb_recording";
    private static final int NOTIFICATION_ID = 4101;
    private static final String TAG = "PbbRecordService";

    private MediaProjectionManager projectionManager;
    private MediaProjection projection;
    private MediaRecorder mediaRecorder;
    private VirtualDisplay virtualDisplay;
    private ParcelFileDescriptor outputPfd;
    private Uri outputUri;
    private String outputPath;
    private boolean recording = false;

    @Override
    public void onCreate() {
        super.onCreate();
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRecording();
            status(false, outputPath == null ? "recording stopped" : "saved to " + outputPath);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            if (recording) {
                status(true, "recording already active");
                return START_NOT_STICKY;
            }
            if (projectionManager == null) {
                status(false, "screen capture unavailable");
                stopForeground(true);
                stopSelf();
                return START_NOT_STICKY;
            }
            startForegroundWithNotification();
            beginRecording(intent);
            return START_NOT_STICKY;
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    private void beginRecording(Intent intent) {
        try {
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultData == null) {
                throw new IOException("missing screen capture consent");
            }
            String displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME);
            if (displayName == null || displayName.isEmpty()) {
                displayName = "PBB_recording.mp4";
            }
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int width = metrics.widthPixels & ~1;
            int height = metrics.heightPixels & ~1;
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            mediaRecorder.setVideoSize(width, height);
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.setVideoEncodingBitRate(6000000);
            mediaRecorder.setOrientationHint(0);
            prepareOutput(displayName);
            mediaRecorder.prepare();
            projection = projectionManager.getMediaProjection(Activity.RESULT_OK, resultData);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopRecording();
                    status(false, "screen capture stopped");
                    stopForeground(true);
                    stopSelf();
                }
            }, null);
            virtualDisplay = projection.createVirtualDisplay(
                "PBBRecorder",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.getSurface(),
                null,
                null);
            mediaRecorder.start();
            recording = true;
            status(true, "recording to " + outputPath);
        } catch (Exception e) {
            Log.e(TAG, "recording failed", e);
            stopRecording();
            status(false, "recording failed: " + e.getMessage());
            stopForeground(true);
            stopSelf();
        }
    }

    private void prepareOutput(String displayName) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put("_display_name", displayName);
            values.put("mime_type", "video/mp4");
            values.put("relative_path", Environment.DIRECTORY_MOVIES + "/PBB");
            values.put("is_pending", 1);
            outputUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (outputUri == null) {
                throw new IOException("MediaStore insert failed");
            }
            outputPfd = getContentResolver().openFileDescriptor(outputUri, "w");
            if (outputPfd == null) {
                throw new IOException("MediaStore open failed");
            }
            mediaRecorder.setOutputFile(outputPfd.getFileDescriptor());
            outputPath = "Movies/PBB/" + displayName;
        } else {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "PBB");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("cannot create Movies/PBB");
            }
            File out = new File(dir, displayName);
            mediaRecorder.setOutputFile(out.getAbsolutePath());
            outputPath = out.getAbsolutePath();
        }
    }

    private void publishOutput() {
        if (Build.VERSION.SDK_INT >= 29 && outputUri != null) {
            ContentValues publish = new ContentValues();
            publish.put("is_pending", 0);
            getContentResolver().update(outputUri, publish, null, null);
        }
    }

    private void stopRecording() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.stop();
            } catch (RuntimeException e) {
                Log.w(TAG, "recorder stop failed", e);
            }
            try {
                mediaRecorder.reset();
            } catch (RuntimeException ignored) {
            }
            mediaRecorder.release();
            mediaRecorder = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        publishOutput();
        if (outputPfd != null) {
            try {
                outputPfd.close();
            } catch (IOException e) {
                Log.w(TAG, "close failed", e);
            }
            outputPfd = null;
        }
        outputUri = null;
        recording = false;
    }

    private void startForegroundWithNotification() {
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("PBB recording")
            .setContentText("Saving to Movies/PBB")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
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
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "PBB recording", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Screen recording status");
        manager.createNotificationChannel(channel);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopRecording();
        super.onDestroy();
    }
}
