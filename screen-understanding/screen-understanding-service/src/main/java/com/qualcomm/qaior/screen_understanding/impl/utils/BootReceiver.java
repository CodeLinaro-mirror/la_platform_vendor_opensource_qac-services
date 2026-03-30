/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.utils;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.qualcomm.qaior.screen_understanding.impl.MainActivity;
import com.qualcomm.qaior.screen_understanding.impl.service.ScreenUnderstandingService;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ScreenUnderstanding.BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        Log.i(TAG, "Received broadcast: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            startScreenUnderstandingService(context);
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
