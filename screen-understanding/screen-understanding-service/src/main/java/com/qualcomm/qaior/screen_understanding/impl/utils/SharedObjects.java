/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.utils;

import android.app.Application;
import com.qualcomm.qaior.screen_understanding.impl.bridge.NativeBridge;

public class SharedObjects extends Application {
    private static final Object lock = new Object();
    private static volatile NativeBridge nativeBridge = null;

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
