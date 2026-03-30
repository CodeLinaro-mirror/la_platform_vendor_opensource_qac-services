/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.utils;

import android.app.Application;
import android.content.Intent;
import com.qualcomm.qaior.screen_understanding.impl.bridge.NativeBridge;

public class SharedObjects extends Application {
    private static final Object lock = new Object();
    private static volatile int mediaProjectionResultCode = 0;
    private static volatile NativeBridge nativeBridge = null;
    private static volatile Intent mediaProjectionData = null;

    public static void setMediaProjectionPermission(int resultCode, Intent data) {
        synchronized (lock) {
            mediaProjectionResultCode = resultCode;
            // Clone the intent to avoid external modifications
            mediaProjectionData = data != null ? new Intent(data) : null;
        }
    }

    public static boolean hasPermission() {
        synchronized (lock) {
            return mediaProjectionResultCode != 0 && mediaProjectionData != null;
        }
    }

    public static MediaProjectionPermission getPermission() {
        synchronized (lock) {
            if (mediaProjectionResultCode != 0 && mediaProjectionData != null) {
                return new MediaProjectionPermission(
                    mediaProjectionResultCode, new Intent(mediaProjectionData));
            }
            return null;
        }
    }

    public static void clearPermission() {
        synchronized (lock) {
            mediaProjectionResultCode = 0;
            mediaProjectionData = null;
        }
    }

    public static class MediaProjectionPermission {
        public final int resultCode;
        public final Intent data;

        MediaProjectionPermission(int resultCode, Intent data) {
            this.resultCode = resultCode;
            this.data = data;
        }
    }

    public static void setNativeBridge(NativeBridge bridge) {
        synchronized (lock) {
            nativeBridge = bridge;
        }
    }

    public static NativeBridge getNativeBridge() {
        synchronized (lock) {
            return nativeBridge;
        }
    }

    public static boolean isNativeBridgeConnected() {
        synchronized (lock) {
            return nativeBridge != null && nativeBridge.isConnected();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }
}
