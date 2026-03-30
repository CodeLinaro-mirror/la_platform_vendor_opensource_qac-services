/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.service;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import com.qualcomm.qaior.screen_understanding.IScreenUnderstandingService;
import com.qualcomm.qaior.screen_understanding.R;
import com.qualcomm.qaior.screen_understanding.impl.MainActivity;
import com.qualcomm.qaior.screen_understanding.impl.accessibility.DisplayCaptureAccessibilityService;
import com.qualcomm.qaior.screen_understanding.impl.bridge.NativeBridge;
import com.qualcomm.qaior.screen_understanding.impl.utils.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class ScreenUnderstandingService extends Service implements NativeBridge.CallbackListener {
    private static final int FOREGROUND_NOTIFICATION_ID = 1001;
    private static final int REQUEST_MEDIA_PROJECTION = 1002;

    private static final String CHANNEL_ID = "screen_understanding_boot";
    private static final int NOTIFICATION_ID = 1004;

    private final String tag = "ScreenUnderstanding.ScreenUnderStandingService";
    private boolean isForeground = false;
    private long currentSessionId = -1;

    private int currentForegroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
    // private final Object permissionLock = new Object();
    private BroadcastReceiver permissionResultReceiver;

    // TODO: Handling concurrency with below variables
    private int waitingCommand = -1;
    private String waitingConfig = null;

    /* AIDL Binder start */

    private final IScreenUnderstandingService.Stub mBinderAidl =
        new IScreenUnderstandingService.Stub() {
            @Override
            public void startCapture(String config) throws RemoteException {
                Log.i(tag, "startCapture called with config: " + config);
                waitingCommand = Commands.CMD_START_SESSION;
                waitingConfig = config;

                NativeBridge bridge = ensureServiceConnected();
                if (bridge != null) {
                    // Create session with native service
                    long output = bridge.createSession(config);
                    Log.i(tag, "createSession() : " + output);
                } else {
                    Log.e(tag, "NativeBridge not connected");
                }
            }

            @Override
            public void updateCaptureConfig(String config) throws RemoteException {
                Log.i(tag, "updateCaptureConfig called with config: " + config);
                waitingCommand = Commands.CMD_UPDATE_SESSION;
                waitingConfig = config;

                NativeBridge bridge = ensureServiceConnected();
                if (bridge != null) {
                    // Update config for active session with native service
                    bridge.updateConfig(currentSessionId, config);
                } else {
                    Log.e(tag, "NativeBridge not connected");
                }
            }

            @Override
            public void stopCapture() throws RemoteException {
                Log.i(tag, "stopCapture called");
                waitingCommand = Commands.CMD_STOP_SESSION;

                NativeBridge bridge = ensureServiceConnected();
                if (bridge != null) {
                    // Update config for active session with native service
                    bridge.destroySession(currentSessionId);
                } else {
                    Log.e(tag, "NativeBridge not connected");
                }
            }

            @Override
            public void deleteCapture(String deleteConfig) throws RemoteException {
                Log.i(tag, "deleteCapture called with config: " + deleteConfig);

                NativeBridge bridge = ensureServiceConnected();
                if (bridge != null) {
                    // Update config for active session with native service
                    bridge.deleteCapture(currentSessionId, deleteConfig);
                } else {
                    Log.e(tag, "NativeBridge not connected");
                }
            }

            @Override
            public int getInterfaceVersion() {
                return IScreenUnderstandingService.VERSION;
            }

            @Override
            public String getInterfaceHash() {
                return IScreenUnderstandingService.HASH;
            }
        };

    /* AIDL Binder end */

    @Override
    public IBinder onBind(Intent intent) {
        if (!isForeground) {
            startAsForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }

        Log.i(tag, "Returning AIDL binder for cross-APK communication");
        return mBinderAidl;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // create handler thread

        // initialize native bridge here
        NativeBridge bridge = ensureServiceConnected();

        Log.i(tag, "Media Projection permission status:" + SharedObjects.hasPermission());

        /* Permission broadcast receiver for subsequent start sessions */
        permissionResultReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Log.i(tag, "Service BroadcastReceiver.onReceive called");
                Log.i(tag, "Action: " + (intent != null ? intent.getAction() : "null"));
                Log.i(tag, "waitingCommand: " + waitingCommand);

                if (PermissionReceiver.ACTION_PERMISSION_GRANTED.equals(intent.getAction())) {
                    updateForegroundServiceType(
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

                    NotificationManager nm = getSystemService(NotificationManager.class);
                    if (nm != null) {
                        nm.cancel(NOTIFICATION_ID);
                        Log.i(tag, "Permission notification dismissed");
                    }

                    if (waitingCommand != -1) {
                        Log.i(tag,
                            "Permission granted, processing waiting command: " + waitingCommand);
                        handleCommand(waitingCommand, waitingConfig);
                        waitingCommand = -1;
                        waitingConfig = null;
                    } else {
                        Log.i(tag, "No waiting command to process");
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(PermissionReceiver.ACTION_PERMISSION_GRANTED);
        registerReceiver(permissionResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        /* Permission broadcast receiver end */
    }

    @Override
    public void onDestroy() {
        // Unregister broadcast receiver
        if (permissionResultReceiver != null) {
            try {
                unregisterReceiver(permissionResultReceiver);
                permissionResultReceiver = null;
            } catch (IllegalArgumentException e) {
                Log.w(tag, "Receiver was not registered", e);
            }
        }

        NativeBridge bridge = SharedObjects.getNativeBridge();
        if (bridge != null) {
            if (currentSessionId > 0) {
                bridge.destroySession(currentSessionId);
            }
            bridge.disconnect();
            SharedObjects.setNativeBridge(null);
        }
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(tag, "onStartCommand called");

        if (!isForeground) {
            startAsForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        }

        Log.i(tag, "isForeground: " + isForeground);

        // Return START_STICKY so service restarts if killed
        return START_STICKY;
    }

    private final NativeBridge ensureServiceConnected() {
        NativeBridge bridge = SharedObjects.getNativeBridge();

        // NativeBridge not created
        if (bridge == null) {
            bridge = new NativeBridge();
            boolean connected = bridge.connect(this);

            if (!connected) {
                Log.e(tag, "Failed to connect NativeBridge");
                return null;
            }

            SharedObjects.setNativeBridge(bridge);
            Log.i(tag, "NativeBridge connected and stored in SharedObjects");
            return bridge;
        }

        // NativeBridge created but not connected
        if (!bridge.isConnected()) {
            Log.w(tag, "NativeBridge disconnected, attempting to reconnect...");
            boolean reconnected = bridge.connect(this);

            if (!reconnected) {
                Log.e(tag, "Failed to reconnect NativeBridge");
                return null;
            }

            Log.i(tag, "NativeBridge reconnected successfully");
            return bridge;
        }

        Log.i(tag, "Display capture service is connected.");
        return bridge;
    }

    private final boolean isAccessibilityServiceEnabled(Context context, Class service) {
        String expectedComponentName = context.getPackageName() + '/' + service.getName();
        String enabledServices = Settings.Secure.getString(
            context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) {
            return false;
        }

        for (String serviceStr : enabledServices.split(":")) {
            if (serviceStr.equalsIgnoreCase(expectedComponentName)) {
                return true;
            }
        }
        return false;
    }

    private final void savePendingCommand(int command, String config, long sessionId) {
        if (sessionId == -1) {
            return;
        }

        Log.i(tag, "saving " + command + " " + config);
        SharedPreferences prefs = getSharedPreferences("pending_commands", 0);
        String json = "[]";

        // if sessionId has changed clear existing commands
        long storedSessionId = prefs.getLong("sessionId", -1);
        if (storedSessionId == sessionId) {
            json = prefs.getString("command_queue", "[]");
        }

        try {
            // Parse the array
            JSONArray queue = new JSONArray(json);

            // Create the new command object
            JSONObject item = new JSONObject();
            item.put("command", command);
            item.put("config", config);

            // Append to the array
            queue.put(item);

            // Persist the updated array
            prefs.edit().putString("command_queue", queue.toString());
            prefs.edit().putLong("sessionId", sessionId);
            prefs.edit().apply();

        } catch (JSONException e) {
            // If parsing fails, start fresh with an array containing only the new command
            Log.w(tag, "Failed to parse existing queue, resetting.", e);
            try {
                JSONArray fresh = new JSONArray();
                JSONObject item = new JSONObject();
                item.put("command", command);
                item.put("config", config);
                fresh.put(item);

                prefs.edit().putLong("sessionId", sessionId);
                prefs.edit().putString("command_queue", fresh.toString());
                prefs.edit().apply();
            } catch (JSONException jsonException) {
                Log.e(tag, "Failed to create JSON for pending command.", jsonException);
            }
        }
    }

    private final void startAsForeground(int serviceType) {
        String channelId = "bound_foreground_channel";
        String channelName = "Bound Foreground Service";

        NotificationChannel channel = new NotificationChannel(
            channelId, (CharSequence) channelName, NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Manages screen capture and understanding operations");
        channel.setShowBadge(true);
        channel.enableLights(true);
        channel.enableVibration(false);

        NotificationManager manager = this.getSystemService(NotificationManager.class);
        if (manager == null) {
            Log.e(tag, "NotificationManager is null, cannot start foreground service");
            return;
        }
        manager.createNotificationChannel(channel);
        Log.i(tag, "Created notification channel: " + channelId);

        Notification notif =
            (new NotificationCompat.Builder(this, channelId))
                .setContentTitle((CharSequence) "Screen Understanding Service")
                .setContentText((CharSequence) "Screen Understanding Service running")
                .setSmallIcon(R.drawable.qaior_small)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(false)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .build();

        startForeground(FOREGROUND_NOTIFICATION_ID, notif, serviceType);
        isForeground = true;
        Log.i(tag, "Started foreground service with type: " + serviceType);
    }

    private void updateForegroundServiceType(int serviceType) {
        if (!isForeground) {
            Log.w(tag, "Service not in foreground, cannot update type");
            return;
        }

        if (currentForegroundServiceType == serviceType) {
            Log.d(tag, "Service type already set to: " + serviceType);
            return;
        }

        Log.i(tag,
            "Updating foreground service type from " + currentForegroundServiceType + " to "
                + serviceType);

        // Stop current foreground
        stopForeground(false); // Keep notification

        // Restart with new type
        String channelId = "bound_foreground_channel";
        Notification notif =
            (new NotificationCompat.Builder(this, channelId))
                .setContentTitle((CharSequence) "Screen Understanding Service")
                .setContentText((CharSequence) "Screen Understanding Service running")
                .setSmallIcon(R.drawable.qaior_small)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(false)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .build();

        startForeground(FOREGROUND_NOTIFICATION_ID, notif, serviceType);
        currentForegroundServiceType = serviceType;
        Log.i(tag, "Foreground service type updated successfully");
    }

    private final void stopForegroundService() {
        stopForeground(FOREGROUND_NOTIFICATION_ID);
        isForeground = false;
        currentForegroundServiceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
    }

    private void handleCommand(int command, String config) {
        Log.i(tag, "handling command" + command);
        Log.i(tag, "config" + config);
        if (isAccessibilityServiceEnabled(this, DisplayCaptureAccessibilityService.class)) {
            Log.i(tag, "accessibility enabled : " + config + " received");
            this.forwardToAccessibility(command, config);
        } else {
            Log.i(tag, "saving pending command " + command);
            Log.i(tag, "saving config" + config);
            savePendingCommand(command, config, currentSessionId);

            showAccessibilityPromptNotification();
        }
    }

    private final void forwardToAccessibility(int command, String config) {
        Intent accessibilityIntent = new Intent(Commands.ACTION_FORWARD_TO_ACCESSIBILITY);
        accessibilityIntent.setPackage(this.getPackageName());
        accessibilityIntent.putExtra("command", command);
        accessibilityIntent.putExtra("config", config);
        accessibilityIntent.putExtra("sessionId", currentSessionId);
        Log.i(tag, "sending broadcast ");
        try {
            sendBroadcast(accessibilityIntent, "com.qualcomm.qaior.permission.SCREEN_CAPTURE");
            Log.i(tag, "broadcast sent successfully for command " + command + " " + config);
        } catch (Exception e) {
            Log.e(tag, "Failed to send broadcast for command " + config, e);
            // Consider saving as pending command if broadcast fails
            savePendingCommand(command, config, currentSessionId);
        }
    }

    private void showAccessibilityPromptNotification() {
        String channelId = "accessibility_required";
        NotificationChannel channel = new NotificationChannel(
            channelId, "Accessibility Required", NotificationManager.IMPORTANCE_HIGH);

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);

        // Create intent for settings
        Intent settingsIntent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification =
            new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Enable Accessibility Service")
                .setContentText("Tap to enable DisplayCapture Accessibility Service")
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                    "Screen capture requires accessibility service.\n\n"
                    + "Tap to open settings and enable 'DisplayCapture Accessibility Service'"))
                .setSmallIcon(R.drawable.qaior_small)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();

        nm.notify(3001, notification);
        Log.i(tag, "Accessibility prompt notification shown");
    }

    private void showPermissionNotification(Context context) {
        createNotificationChannel(context);

        Intent permissionIntent = new Intent(context, MainActivity.class);
        permissionIntent.setAction(
            "com.qualcomm.qaior.screen_understanding.REQUEST_MEDIA_PROJECTION");
        permissionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, permissionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(context, CHANNEL_ID)
                                        .setContentTitle("Screen Understanding")
                                        .setContentText("Tap to enable screen capture")
                                        .setSmallIcon(android.R.drawable.ic_menu_camera)
                                        .setContentIntent(pendingIntent)
                                        .setAutoCancel(true)
                                        .setOngoing(true)
                                        .setPriority(Notification.PRIORITY_HIGH)
                                        .build();

        NotificationManager notificationManager =
            context.getSystemService(NotificationManager.class);
        notificationManager.notify(NOTIFICATION_ID, notification);

        Log.i(tag, "Permission notification shown");
    }

    private void createNotificationChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "Screen Understanding Boot", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Notifications for screen capture permission");

        NotificationManager notificationManager =
            context.getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    // CallbackListener implementation
    @Override
    public void onControlSessionReady(long sessionId, boolean enabled) {
        Log.i(tag,
            "onReady Callback from NativeBridge: SessionId: " + sessionId + " ready: " + enabled);
        currentSessionId = sessionId;

        // send to DisplayCaptureAccessibilityService
        if (waitingConfig != null) {
            // if display HAL available
            if (enabled) {
                handleCommand(waitingCommand, waitingConfig);
                // Clear the waiting variables after processing
                waitingCommand = -1;
                waitingConfig = null;
            } else {
                // show notification to request Media Projection permisison
                if (SharedObjects.hasPermission()) {
                    // Permission already exists, upgrade and forward
                    Log.i(tag, "MediaProjection permission already granted");
                    updateForegroundServiceType(
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
                    handleCommand(waitingCommand, waitingConfig);
                    waitingCommand = -1;
                    waitingConfig = null;
                } else {
                    // Need to request permission
                    Log.i(tag, "MediaProjection permission not granted, showing notification");
                    showPermissionNotification(this);
                    // waitingCommand/waitingConfig will be processed after permission grant
                }
            }
        }
    }

    @Override
    public void onControlSessionStopped(long sessionId) {
        Log.i(tag, "onStopped Callback from NativeBridge: SessionId: " + sessionId);
        currentSessionId = -1;

        // send to DisplayCaptureAccessibilityService
        if (waitingCommand != -1) {
            handleCommand(waitingCommand, "");
            // Clear the waiting variables after processing
            waitingCommand = -1;
            waitingConfig = null;
        }
    }

    @Override
    public void onControlSessionConfigUpdated(long sessionId) {
        Log.i(tag, "onConfigUpdated Callback from NativeBridge: SessionId: " + sessionId);

        // send to DisplayCaptureAccessibilityService
        if (waitingConfig != null) {
            handleCommand(waitingCommand, waitingConfig);
            // Clear the waiting variables after processing
            waitingCommand = -1;
            waitingConfig = null;
        }
    }
}
