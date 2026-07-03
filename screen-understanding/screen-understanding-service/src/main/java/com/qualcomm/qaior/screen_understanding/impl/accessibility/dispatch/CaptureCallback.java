/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.accessibility.dispatch;

/**
 * Decouples EventDispatcher from NativeBridge. The main accessibility service implements this
 * interface and owns session-ID bookkeeping.
 */
public interface CaptureCallback {
    /**
     * @param appName   package name of the foreground app
     * @param eventType {@link android.view.accessibility.AccessibilityEvent} type constant
     * @param timestamp wall-clock milliseconds at event time
     * @param tag
     *
     */
    void onCapture(String appName, int eventType, long timestamp, String tag);
}
