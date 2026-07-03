/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.accessibility.dispatch;

import android.util.Log;

/**
 * One boolean gate: armed after every UI change, consumed by the first click.
 */
class ClickDeduplicator {

    private static final String TAG = "ScreenUnderstanding.ClickDeduplicator";
    private boolean armed = true;

    /**
     * Returns true (and disarms) only on the first call after a reset().
     * Subsequent calls return false until the gate is re-armed.
     */
    boolean checkAndConsume() {
        if (armed) {
            armed = false;
            Log.d(TAG, "[DISPATCH_TIMER_FIRE] type=click_capture result=consumed");
            return true;
        }
        Log.v(TAG, "[DISPATCH_CAPTURE_SKIP] type=click reason=not_armed");
        return false;
    }

    /**
     * Re-arms the gate.
     * Called on TYPE_VIEW_SCROLLED, TYPE_WINDOW_CONTENT_CHANGED, and TYPE_WINDOW_STATE_CHANGED.
     */
    void reset() {
        if (!armed) {
            Log.v(TAG, "[DISPATCH_TIMER_START] type=click_gate action=armed");
        }
        armed = true;
    }
}
