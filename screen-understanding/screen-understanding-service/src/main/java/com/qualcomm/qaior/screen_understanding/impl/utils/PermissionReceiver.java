/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.utils;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.qualcomm.qaior.screen_understanding.impl.service.ScreenUnderstandingService;

public class PermissionReceiver extends BroadcastReceiver {
    private static final String TAG = "ScreenUnderstanding.PermissionReceiver";
    private static final int BOOT_NOTIFICATION_ID = 1004;

    public static final String ACTION_PERMISSION_GRANTED =
        "com.qualcomm.qaior.screen_understanding.PERMISSION_GRANTED";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "PermissionReceiver.onReceive called");
        if (intent == null || intent.getAction() == null) {
            return;
        }

        if (ACTION_PERMISSION_GRANTED.equals(intent.getAction())) {
            Log.i(TAG, "Permission granted, starting service");

            // Dismiss the boot notification
            dismissBootNotification(context);

            // Start the foreground service
            startScreenUnderstandingService(context);
        }
    }

    private void dismissBootNotification(Context context) {
        NotificationManager notificationManager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancel(BOOT_NOTIFICATION_ID);
            Log.i(TAG, "Boot notification dismissed");
        }
    }

    private void startScreenUnderstandingService(Context context) {
        Intent serviceIntent = new Intent(context, ScreenUnderstandingService.class);

        try {
            context.startForegroundService(serviceIntent);
            Log.i(TAG, "ScreenUnderstandingService started successfully");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service", e);
        }
    }
}
