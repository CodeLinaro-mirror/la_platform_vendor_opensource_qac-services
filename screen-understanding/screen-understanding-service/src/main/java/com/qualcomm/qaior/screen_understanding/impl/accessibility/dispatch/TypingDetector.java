/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.accessibility.dispatch;

import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 *
 * Two signals indicate that the user is typing:
 *
 * TYPE_VIEW_TEXT_CHANGED / TYPE_VIEW_TEXT_SELECTION_CHANGED — these are
 * always fired by editable fields
 *
 * Any event whose source node has inputType != 0 (an active input field)
 *
 * Once a typing signal is seen, non-scroll/non-click events are suppressed for
 * DISABLE_TYPING_MILLIS ms to avoid capturing mid-input states.
 */
class TypingDetector {

    /** Matches ScreenshotFilterManager.DISABLE_TYPING_MILLIS. */
    static final long DISABLE_TYPING_MILLIS = 2000;

    private volatile long latestTypingTime = 0;

    /**
     * Called in EventDispatcher.handleEvent for TYPE_VIEW_TEXT_CHANGED and
     * TYPE_VIEW_TEXT_SELECTION_CHANGED before the event is posted to the event thread.
     * These are always typing signals and do not need source-node inspection.
     */
    void markTypingNow() {
        latestTypingTime = System.currentTimeMillis();
    }

    /**
     * Checks whether event is a typing event by inspecting the source node's
     * inputType. If it is, updates the typing timestamp and returns true.
     *
     * Must be called on the event thread (where source-node access is safe).
     * Caller must NOT recycle the event before calling this method.
     */
    boolean checkAndUpdateIfTyping(AccessibilityEvent event) {
        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return false;
        try {
            if (source.getInputType() != 0) {
                latestTypingTime = System.currentTimeMillis();
                return true;
            }
        } finally {
            source.recycle();
        }
        return false;
    }

    /**
     * Returns true if a typing signal was received within the last
     * DISABLE_TYPING_MILLIS ms.
     */
    boolean isUserTyping() {
        if (latestTypingTime == 0) return false;
        return System.currentTimeMillis() <= (latestTypingTime + DISABLE_TYPING_MILLIS);
    }

    void reset() {
        latestTypingTime = 0;
    }
}
