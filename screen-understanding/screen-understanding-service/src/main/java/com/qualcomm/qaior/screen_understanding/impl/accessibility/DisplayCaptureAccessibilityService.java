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
import android.os.SystemClock;
import android.util.Log;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import com.qualcomm.qaior.screen_understanding.R;
import com.qualcomm.qaior.screen_understanding.impl.bridge.NativeBridge;
import com.qualcomm.qaior.screen_understanding.impl.utils.Commands;
import com.qualcomm.qaior.screen_understanding.impl.utils.SharedObjects;
import com.qualcomm.qaior.screen_understanding.impl.accessibility.dispatch.CaptureCallback;
import com.qualcomm.qaior.screen_understanding.impl.accessibility.dispatch.EventDispatcher;
import com.qualcomm.qaior.screen_understanding.impl.accessibility.dispatch.RootNodeProvider;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import androidx.core.app.NotificationCompat;

public class DisplayCaptureAccessibilityService extends AccessibilityService 
    implements CaptureCallback, RootNodeProvider {
    private String logTag = "ScreenUnderstanding.AccessibilityService";

    private boolean ongoingSession = false;
    private long sessionId = -1;
    private boolean isReceiverRegistered = false;

    private EventDispatcher eventDispatcher;

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

        // Initialise EventDispatcher (owns its own HandlerThread).
        eventDispatcher = new EventDispatcher(this, this);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        eventDispatcher.setScreenSize(dm.widthPixels, dm.heightPixels);

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
        if (eventDispatcher != null) {
            eventDispatcher.shutdown();
        }
        super.onDestroy();
    }

    // -------------------------------------------------------------------------
    // CaptureCallback implementation
    // -------------------------------------------------------------------------

    @Override
    public void onCapture(String appName, int eventType, long timestamp, String tag) {
        NativeBridge bridge = SharedObjects.getNativeBridge();
        if (bridge != null && bridge.isConnected()) {
            bridge.triggerCapture(sessionId, appName, eventType, timestamp, tag);
        } else {
            Log.e(logTag, "NativeBridge not connected");
        }
    }

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
     * Shows recording notification when a session is active.
     */
    private void showRecordingNotification() {
        if (!ongoingSession) {
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

                showRecordingNotification();
            }
        } catch (JSONException e) {
            Log.e(logTag, "Failed to create JSON for start session.", e);
        }

        if (eventDispatcher != null) {
            eventDispatcher.reset();
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

        if (eventDispatcher != null) {
            eventDispatcher.reset();
        }

        // Hide notification
        hideRecordingNotification();
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

            // Update notification visibility
            if (!ongoingSession) {
                hideRecordingNotification();
            }

        } catch (JSONException e) {
            Log.e(logTag, "Failed to create JSON for update config.", e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !ongoingSession || eventDispatcher == null) return;

        // Obtain a copy before posting asynchronously — the system may recycle the
        // original event as soon as onAccessibilityEvent() returns.
        eventDispatcher.handleEvent(AccessibilityEvent.obtain(event));

        // if (ongoingSession && event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
        //     CharSequence packageName = event.getPackageName();
        //     if (packageName == null) {
        //         Log.w(logTag, "Event has null package name, skipping");
        //         return;
        //     }

        //     String packageNameStr = packageName.toString();
        //     Log.i(logTag, "capturing screenshot for " + packageNameStr);
        //     long eventTimeMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime() + event.getEventTime();

        //     NativeBridge bridge = SharedObjects.getNativeBridge();
        //     if (bridge != null && bridge.isConnected()) {
        //         bridge.triggerCapture(sessionId, packageNameStr, event.getEventType(),
        //             eventTimeMillis, "");
        //     } else {
        //         Log.e(logTag, "NativeBridge not connected");
        //     }
        // }
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

}
