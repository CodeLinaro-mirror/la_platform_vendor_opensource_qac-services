/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.accessibility.dispatch;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

/**
 *
 * On TYPE_WINDOWS_CHANGED, the system fires rapid bursts of events while windows
 * animate in/out. startOrResetDebounce arms a 300 ms debounce; on expiry it polls
 * until the UI has been quiet for another 300 ms, then fires a single capture via
 * CaptureCallback. All debounce runnables run on the event thread (the same
 * Handler passed at construction) so no extra synchronisation is needed for fields
 * accessed only from that thread.
 *
 */
class WindowTransitionHandler {

    private static final String TAG = "ScreenUnderstanding.WindowTransitionHandler";

    /** Matches WindowsStateChangedEventHandler.STATE_CHANGED_WAIT_TIME_MS. */
    static final long STATE_CHANGED_WAIT_TIME_MS = 300L;

    private final Handler eventHandler;
    private final CaptureCallback captureCallback;

    /** Written/read from event thread; read from any thread via {@link #isWaitingEvent()}. */
    private volatile boolean debouncing = false;

    /**
     * Written from event thread; read from any thread in cancelDebounce().
     * Same single-writer pattern as EventDispatcher.trailingCaptureRunnable.
     */
    private volatile Runnable debounceRunnable = null;

    private volatile long lastUiChangeTs = 0;

    /** Written only from event thread; accessed only from event thread. */
    private String pendingAppName;
    private int pendingEventType;

    WindowTransitionHandler(Handler eventHandler, CaptureCallback captureCallback) {
        this.eventHandler = eventHandler;
        this.captureCallback = captureCallback;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Cancels any pending debounce, resets click and scroll state, then schedules a delayed
     * capture that fires only after the UI has been stable for STATE_CHANGED_WAIT_TIME_MS.
     *
     * Must be called from the event thread.
     *
     * @param resetClick  runnable that re-arms the click deduplicator
     * @param resetScroll runnable that clears scroll-session timing
     */
    void startOrResetDebounce(String appName, int eventType,
            Runnable resetClick, Runnable resetScroll) {
        cancelDebounce();
        resetClick.run();
        resetScroll.run();
        markUiChanged();

        pendingAppName = appName;
        pendingEventType = eventType;
        debouncing = true;

        Log.i(TAG, "[DISPATCH_TIMER_START] type=window_transition delay_ms=" +
            STATE_CHANGED_WAIT_TIME_MS + " app=" + appName);

        Runnable stabilityCheck = new Runnable() {
            @Override
            public void run() {
                long sinceChange = SystemClock.uptimeMillis() - lastUiChangeTs;
                if (sinceChange < STATE_CHANGED_WAIT_TIME_MS) {

                    Log.v(TAG, "[DISPATCH_TIMER_CHECK] type=window_transition ui_changed_ms_ago=" +
                        sinceChange + " waiting_ms=" + (STATE_CHANGED_WAIT_TIME_MS - sinceChange));

                    // UI still in flux — re-check at half the grace period.
                    eventHandler.postDelayed(this, STATE_CHANGED_WAIT_TIME_MS / 2);
                    return;
                }

                Log.i(TAG, "[DISPATCH_TIMER_FIRE] type=window_transition result=capture tag=window_settled");

                // UI has settled — fire the capture and clear state.
                debouncing = false;
                debounceRunnable = null;
                Log.v(TAG, "Window settled; firing capture for " + pendingAppName);
                captureCallback.onCapture(pendingAppName, pendingEventType,
                        System.currentTimeMillis(), "window_settled");
            }
        };
        debounceRunnable = stabilityCheck;
        eventHandler.postDelayed(stabilityCheck, STATE_CHANGED_WAIT_TIME_MS);
    }

    /**
     * Returns true while a window-transition debounce is in progress.
     * Used by EventDispatcher to gate scroll captures and trailing captures.
     */
    boolean isWaitingEvent() {
        return debouncing;
    }

    /**
     * Records the current uptime as the last UI-change instant.
     */
    void markUiChanged() {
        lastUiChangeTs = SystemClock.uptimeMillis();
    }

    /**
     * Cancels any pending debounce immediately.
     */
    void cancelDebounce() {
        Runnable pending = debounceRunnable;
        if (pending != null) {
            Log.d(TAG, "[DISPATCH_TIMER_CANCEL] type=window_transition");
            eventHandler.removeCallbacks(pending);
            debounceRunnable = null;
        }
        debouncing = false;
    }

    /** Cancels the debounce and resets stability state. Safe to call from any thread. */
    void reset() {
        cancelDebounce();
        lastUiChangeTs = 0;
    }
}
