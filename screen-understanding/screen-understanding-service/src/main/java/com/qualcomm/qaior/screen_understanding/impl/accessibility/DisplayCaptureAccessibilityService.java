/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.accessibility;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import com.qualcomm.qaior.screen_understanding.R;
import com.qualcomm.qaior.screen_understanding.impl.MainActivity;
import com.qualcomm.qaior.screen_understanding.impl.bridge.NativeBridge;
import com.qualcomm.qaior.screen_understanding.impl.utils.Commands;
import com.qualcomm.qaior.screen_understanding.impl.utils.SharedObjects;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import androidx.core.app.NotificationCompat;

public class DisplayCaptureAccessibilityService extends AccessibilityService {
    private String logTag = "ScreenUnderstanding.AccessibilityService";

    private boolean ongoingSession = false;
    private long sessionId = -1;
    private boolean isReceiverRegistered = false;

    private MediaProjection activeProjection = null;
    private HandlerThread workerThread;
    private Handler worker;

    private ImageReader persistentImageReader = null;
    private VirtualDisplay persistentVirtualDisplay = null;
    private int captureWidth;
    private int captureHeight;
    private int captureDensity;

    private static final String NOTIFICATION_CHANNEL_ID = "accessibility_screen_capture";
    private static final int NOTIFICATION_ID = 2001;
    private NotificationManager notificationManager;

    // BroadcastReceiver for commands
    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null)
                return;

            int command = intent.getIntExtra(Commands.COMMAND, -1);
            String config = intent.getStringExtra(Commands.CONFIG);
            sessionId = intent.getLongExtra("sessionId", -1L);
            Log.i(logTag, "command received " + command);

            switch (command) {
                case Commands.CMD_START_SESSION:
                    startSession(config);
                    break;
                case Commands.CMD_UPDATE_SESSION:
                    updateConfig(config);
                    break;
                case Commands.CMD_STOP_SESSION:
                    stopSession(config);
                    break;
                default:
                    Log.w(logTag, "unknown command " + command);
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(logTag, "Service connected");

        // Initialize worker thread for MediaProjection capture
        workerThread = new HandlerThread("MP-Capture");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();

        // Register BroadcastReceiver (RECEIVER_EXPORTED requires API 33+)
        IntentFilter filter = new IntentFilter(Commands.ACTION_FORWARD_TO_ACCESSIBILITY);
        registerReceiver(commandReceiver, filter, "com.qualcomm.qaior.permission.SCREEN_CAPTURE",
            null, RECEIVER_EXPORTED);
        isReceiverRegistered = true;

        Log.i(logTag, "checking pending commands");
        getPendingCommands();
    }

    @Override
    public void onInterrupt() {
        Log.w(logTag, "Service interrupted");
    }

    @Override
    public void onDestroy() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(commandReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(logTag, "Receiver was not registered", e);
            }
        }
        if (workerThread != null) {
            workerThread.quitSafely();
        }
        super.onDestroy();
    }

    // Android requirement
    private MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.i(logTag, "MediaProjection stopped");
        }

        @Override
        public void onCapturedContentResize(int width, int height) {
            Log.i(logTag, "MediaProjection content resized: " + width + "x" + height);
        }

        @Override
        public void onCapturedContentVisibilityChanged(boolean isVisible) {
            Log.i(logTag, "MediaProjection visibility changed: " + isVisible);
        }
    };

    /**
     * Creates notification channel for screen capture indicator.
     */
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Screen Capture Indicator",
            NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows when accessibility service is capturing screen");
        channel.setShowBadge(false);

        if (notificationManager != null) {
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * Builds notification for recording.
     */
    private Notification buildRecordingNotification() {
        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_recording_dot)
            .setContentTitle("Recording Screen")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .build();
    }

    /**
     * Shows recording notification when capturing without MediaProjection.
     * Only shown when ongoingSession is true and MediaProjection permission is not available.
     */
    private void showRecordingNotification() {
        if (!ongoingSession || SharedObjects.hasPermission()) {
            // Don't show notification if no session or MediaProjection is handling it
            hideRecordingNotification();
            return;
        }

        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID, buildRecordingNotification());
            Log.i(logTag, "Recording notification shown");
        }
    }

    /**
     * Hides the recording notification.
     */
    private void hideRecordingNotification() {
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
            Log.i(logTag, "Recording notification hidden");
        }
    }

    private void startSession(String config) {
        Log.i(logTag, "new session starts here");
        ongoingSession = true;

        try {
            JSONObject configJson = new JSONObject(config);
            Log.i(logTag, "start config" + config);

            JSONArray appListJsonArray = configJson.optJSONArray("appList");
            if (appListJsonArray != null) {
                // get packageNames
                String[] packageNames = new String[appListJsonArray.length()];
                for (int i = 0; i < appListJsonArray.length(); i++) {
                    packageNames[i] = appListJsonArray.getJSONObject(i).getString("packageName");
                    // TODO: add a global map structure to save package names and isSensitive to
                    // enable saving only non-sensitive data
                }

                // modify accessibility service here
                AccessibilityServiceInfo info = getServiceInfo();
                if (info != null) {
                    Log.i(logTag, "starting new session for :" + Arrays.toString(packageNames));
                    info.packageNames = packageNames;
                    // Apply the updated config
                    setServiceInfo(info);
                }

                Log.i(
                    logTag, "Media Projection permission status:" + SharedObjects.hasPermission());
                if (SharedObjects.hasPermission()) {
                    // Initialize MediaProjection ONCE for the session
                    initializeMediaProjection();

                    // Create persistent VirtualDisplay for fast captures
                    setupPersistentCapture();
                }

                // Show notification if not using MediaProjection
                if (!SharedObjects.hasPermission()) {
                    showRecordingNotification();
                }
            }
        } catch (JSONException e) {
            Log.e(logTag, "Failed to create JSON for start session.", e);
        }
    }

    private void stopSession(String config) {
        Log.i(logTag, "session ends here");
        ongoingSession = false;
        sessionId = -1;

        Log.i(logTag, "stop config" + config);

        // modify accessibility service here
        // set package names to empty array
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.packageNames = new String[] {}; // Empty array means listen to no packages
            setServiceInfo(info); // Apply the updated config
        }

        // Hide notification
        hideRecordingNotification();

        if (SharedObjects.hasPermission()) {
            // Clean up persistent capture
            teardownPersistentCapture();

            // Stop and cleanup MediaProjection
            if (activeProjection != null) {
                activeProjection.unregisterCallback(projectionCallback);
                activeProjection.stop();
                activeProjection = null;
            }

            // Remove MediaProjection Permission
            SharedObjects.clearPermission();
        }
    }

    private void updateConfig(String config) {
        if (!ongoingSession) {
            return;
        }

        Log.i(logTag, "config modified here");
        try {
            JSONObject configJson = new JSONObject(config);
            Log.i(logTag, "update config" + config);

            JSONArray appListJsonArray = configJson.optJSONArray("appList");
            if (appListJsonArray != null) {
                // get packageNames
                String[] packageNames = new String[appListJsonArray.length()];
                for (int i = 0; i < appListJsonArray.length(); i++) {
                    packageNames[i] = appListJsonArray.getJSONObject(i).getString("packageName");
                    // TODO: add a global map structure to save package names and isSensitive to
                    // enable saving only non-sensitive data
                }

                // modify accessibility service here
                AccessibilityServiceInfo info = getServiceInfo();
                if (info != null) {
                    Log.i(logTag, "updating session for :" + Arrays.toString(packageNames));
                    info.packageNames = packageNames;
                    // Apply the updated config
                    setServiceInfo(info);
                }
            }

            // Update notification visibility based on permission status
            if (!SharedObjects.hasPermission()) {
                showRecordingNotification();
            } else {
                hideRecordingNotification();
            }

        } catch (JSONException e) {
            Log.e(logTag, "Failed to create JSON for update config.", e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null)
            return;

        Log.i(logTag, "Event: " + event.getEventType() + ", Package: " + event.getPackageName());

        if (ongoingSession && event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            CharSequence packageName = event.getPackageName();
            if (packageName == null) {
                Log.w(logTag, "Event has null package name, skipping");
                return;
            }

            String packageNameStr = packageName.toString();
            Log.i(logTag, "capturing screenshot for " + packageNameStr);

            // Capture screenshot using MediaProjection if permission is available
            if (SharedObjects.hasPermission() && persistentImageReader != null) {
                if (worker != null) {
                    worker.post(()
                                    -> captureOnce(packageNameStr, event.getEventType(),
                                        "")); // TODO: tag to be set
                } else {
                    Log.w(logTag, "Worker thread not initialized, skipping capture");
                }
            } else {
                Log.w(logTag, "MediaProjection permission not available, using triggerCapture");

                NativeBridge bridge = SharedObjects.getNativeBridge();
                if (bridge != null && bridge.isConnected()) {
                    // send event to Display Capture service
                    bridge.triggerCapture(sessionId, packageNameStr, event.getEventType(),
                        System.currentTimeMillis(), "");
                } else {
                    Log.e(logTag, "NativeBridge not connected");
                }
            }
        }
    }

    /**
     * Reads the pending command queue from SharedPreferences, parses with org.json,
     * and executes each command. The stored format is a JSON array of objects:
     *   [{"command": 1, "config" : ""}, {"command": 2, "config": ""}, ...]
     */
    private void getPendingCommands() {
        SharedPreferences prefs = getSharedPreferences("pending_commands", MODE_PRIVATE);
        sessionId = prefs.getLong("sessionId", -1);
        String json = prefs.getString("command_queue", null);
        if (json == null || json.isEmpty()) {
            Log.i(logTag, "0 commands pending");
            return;
        }

        try {
            JSONArray queue = new JSONArray(json);
            Log.i(logTag, queue.length() + " commands pending");

            for (int i = 0; i < queue.length(); i++) {
                JSONObject item = queue.optJSONObject(i);
                if (item == null) {
                    Log.w(logTag, "Skipping malformed queue item at index " + i);
                    continue;
                }

                int cmd = item.optInt(Commands.COMMAND, -1);
                String config = item.optString(Commands.CONFIG, "No config");
                Log.i(logTag, "executing command " + cmd);

                switch (cmd) {
                    case Commands.CMD_START_SESSION:
                        startSession(config);
                        break;
                    case Commands.CMD_UPDATE_SESSION:
                        updateConfig(config);
                        break;
                    case Commands.CMD_STOP_SESSION:
                        stopSession(config);
                        break;
                    default:
                        Log.w(logTag, "unknown command " + cmd);
                }
            }

            // Optional: clear the queue after executing to avoid re-running on next startup.
            prefs.edit().remove("command_queue").apply();

        } catch (JSONException e) {
            Log.w(logTag, "Failed to parse pending commands JSON, ignoring.", e);
        }
    }

    private void initializeMediaProjection() {
        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null)
            return;

        SharedObjects.MediaProjectionPermission permission = SharedObjects.getPermission();
        if (permission == null)
            return;

        // Create ONE projection instance for the entire session
        activeProjection = mpm.getMediaProjection(permission.resultCode, permission.data);

        if (activeProjection != null) {
            // Register callback
            activeProjection.registerCallback(projectionCallback, worker);
        }
    }

    private void setupPersistentCapture() {
        if (activeProjection == null) {
            Log.e(logTag, "Cannot setup capture - no active projection");
            return;
        }

        // Get display metrics
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) {
            Log.e(logTag, "WindowManager null");
            return;
        }
        Display display = wm.getDefaultDisplay();
        display.getRealMetrics(metrics);

        captureWidth = metrics.widthPixels;
        captureHeight = metrics.heightPixels;
        captureDensity = metrics.densityDpi;

        Log.i(logTag,
            "Setting up persistent capture: " + captureWidth + "x" + captureHeight + " @ "
                + captureDensity + " dpi");

        // Create persistent ImageReader with larger buffer for multiple captures
        persistentImageReader = ImageReader.newInstance(
            captureWidth, captureHeight, android.graphics.PixelFormat.RGBA_8888,
            5 // Buffer for 5 images
        );

        // Create persistent VirtualDisplay
        persistentVirtualDisplay =
            activeProjection.createVirtualDisplay("persistent-capture", captureWidth, captureHeight,
                captureDensity, android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                persistentImageReader.getSurface(), null, worker);

        if (persistentVirtualDisplay != null) {
            Log.i(logTag, "Persistent VirtualDisplay created successfully");
        } else {
            Log.e(logTag, "Failed to create persistent VirtualDisplay");
        }
    }

    private void teardownPersistentCapture() {
        if (persistentVirtualDisplay != null) {
            persistentVirtualDisplay.release();
            persistentVirtualDisplay = null;
            Log.i(logTag, "Persistent VirtualDisplay released");
        }

        if (persistentImageReader != null) {
            persistentImageReader.close();
            persistentImageReader = null;
            Log.i(logTag, "Persistent ImageReader closed");
        }
    }

    /**
     * Capture screenshot using MediaProjection.
     * Called when ongoingSession is true and a scroll event is detected.
     */
    private void captureOnce(String appName, int accessibilityEventType, String tag) {
        if (persistentImageReader == null) {
            Log.w(logTag, "ImageReader not initialized");
            return;
        }

        Image image = null;
        ParcelFileDescriptor fd = null;
        try {
            // Try to acquire latest image
            image = persistentImageReader.acquireLatestImage();

            if (image == null) {
                Log.d(logTag, "No image available yet, skipping this capture");
                return;
            }

            Log.i(logTag, "Image acquired, processing...");

            // Process and save image
            fd = writeImageToPng(image);
            if (fd != null) {
                Log.i(logTag, "Capture completed");
                // Deliver capture result to native service or callback

                NativeBridge bridge = SharedObjects.getNativeBridge();
                if (bridge != null && bridge.isConnected()) {
                    // send fd to Display Capture service
                    bridge.addCaptureFd(sessionId, appName, accessibilityEventType,
                        System.currentTimeMillis(), tag, fd);
                } else {
                    Log.e(logTag, "NativeBridge not connected");
                }
            }
        } catch (Exception e) {
            Log.e(logTag, "Error capturing screenshot", e);
        } finally {
            if (image != null) {
                image.close();
            }
            if (fd != null) {
                try {
                    fd.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private ParcelFileDescriptor writeImageToPng(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) {
            Log.e(logTag, "Image planes are null or empty");
            return null;
        }

        ByteBuffer buffer = planes[0].getBuffer();
        int width = image.getWidth();
        int height = image.getHeight();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;

        Log.i(logTag, "Creating bitmap: " + width + "x" + height);

        android.graphics.Bitmap bitmap = null;
        android.graphics.Bitmap cropped = null;

        try {
            bitmap = android.graphics.Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, android.graphics.Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(buffer);
            cropped = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, width, height);

            File out = new File(getCacheDir(), "capture_" + System.currentTimeMillis() + ".png");
            Log.i(logTag, "Saving to: " + out.getAbsolutePath());

            try (FileOutputStream fos = new FileOutputStream(out)) {
                cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
            }

            Log.i(logTag, "PNG saved successfully");
            return ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY);

        } catch (IOException e) {
            Log.e(logTag, "Failed to write png", e);
            return null;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            if (cropped != null && !cropped.isRecycled()) {
                cropped.recycle();
            }
        }
    }
}
