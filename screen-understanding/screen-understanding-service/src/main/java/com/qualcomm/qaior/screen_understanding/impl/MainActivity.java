/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl;

import android.Manifest;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.qualcomm.qaior.screen_understanding.impl.service.ScreenUnderstandingService;
import com.qualcomm.qaior.screen_understanding.impl.utils.*;

/**
 * Activity to request MediaProjection permission; on success, triggers pending captures.
 */
public class MainActivity extends AppCompatActivity {
    private static final String LOG_TAG = "ScreenUnderstanding.MainActivity";
    private static final int REQUEST_CODE_POST_NOTIFICATIONS = 1003;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_CODE_POST_NOTIFICATIONS);
        }

        requestMediaProjectionIfNeeded();
    }

    private void requestMediaProjectionIfNeeded() {
        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        if (mpm != null) {
            mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent());
        } else {
            Log.e(LOG_TAG, "MediaProjectionManager null");
            finish();
        }
    }

    private final ActivityResultLauncher<Intent> mediaProjectionLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                Intent data = result.getData();
                // Validate MediaProjection data
                if (data.getAction() == null) {
                    Log.w(LOG_TAG, "Invalid MediaProjection data received");
                    SharedObjects.clearPermission();
                    finish();
                    return;
                }

                SharedObjects.setMediaProjectionPermission(
                    result.getResultCode(), result.getData());
                Log.i(LOG_TAG, "MediaProjection permission granted");

                // Send broadcast to PermissionReceiver
                Intent broadcast = new Intent(PermissionReceiver.ACTION_PERMISSION_GRANTED);
                broadcast.setPackage(getPackageName());
                sendBroadcast(broadcast);
                Log.i(LOG_TAG, "Broadcast sent: " + PermissionReceiver.ACTION_PERMISSION_GRANTED);

                // Ensure service is started to handle the permission
                Intent serviceIntent = new Intent(this, ScreenUnderstandingService.class);
                try {
                    startForegroundService(serviceIntent);
                    Log.i(LOG_TAG, "Service start requested after permission grant");
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Failed to start service after permission grant", e);
                }

            } else {
                SharedObjects.clearPermission();
                Log.w(LOG_TAG, "MediaProjection permission denied");
            }
            finish();
        });
}
