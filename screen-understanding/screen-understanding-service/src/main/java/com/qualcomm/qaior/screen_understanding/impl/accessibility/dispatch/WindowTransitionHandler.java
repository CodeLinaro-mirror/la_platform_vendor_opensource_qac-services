/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.accessibility.dispatch;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 *
 * On TYPE_WINDOWS_CHANGED / TYPE_WINDOW_STATE_CHANGED, the system fires rapid bursts of events
 * while windows animate in/out. startOrResetDebounce arms a 300 ms debounce; on expiry it polls
 * until the UI has been quiet for another 300 ms, then fires a single capture via
 * CaptureCallback with tag "window_settled". All debounce runnables run on the event thread (the
 * same Handler passed at construction) so no extra synchronisation is needed for fields
 * accessed only from that thread.
 *
 */
class WindowTransitionHandler {

    private static final String TAG = "ScreenUnderstanding.WindowTransitionHandler";

    /** Matches WindowsStateChangedEventHandler.STATE_CHANGED_WAIT_TIME_MS. */
    static final long STATE_CHANGED_WAIT_TIME_MS = 300L;

    /** Extra settle window after the debounce gate drops. Keyed only on
     *  STATE_CHANGED / WINDOWS_CHANGED — not CONTENT_CHANGED — so it does not
     *  suppress legitimate user scrolls that interleave with content updates. */
    static final long RENDER_SETTLE_MS = 800L;

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

    /** Wall-clock time of the last transition event (STATE_CHANGED / WINDOWS_CHANGED).
     *  Written by markTransition() on the event thread; read by the scroll branch via
     *  timeSinceTransition() to suppress rendering-induced scroll captures. */
    private volatile long lastTransitionTs = 0L;

    private ContentChangedHandler contentChangedHandler;

    /** Written only from event thread; accessed only from event thread. */
    private String pendingAppName;
    private int pendingEventType;

    WindowTransitionHandler(Handler eventHandler, CaptureCallback captureCallback) {
        this.eventHandler = eventHandler;
        this.captureCallback = captureCallback;
    }

    void setContentChangedHandler(ContentChangedHandler handler) {
        this.contentChangedHandler = handler;
    }

    /** Records the current wall-clock time as the last transition instant.
     *  Must be called on the event thread before startOrResetDebounce. */
    void markTransition() {
        lastTransitionTs = System.currentTimeMillis();
    }

    /** Milliseconds elapsed since the last navigation transition. */
    long timeSinceTransition() {
        return System.currentTimeMillis() - lastTransitionTs;
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
     * @param shouldCapture  when false, debounce still runs (keeps gate active) but no capture fires
     */
    void startOrResetDebounce(String appName, int eventType,
            Runnable resetClick, Runnable resetScroll, boolean shouldCapture) {
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

                debouncing = false;
                debounceRunnable = null;
                if (shouldCapture) {
                    Log.i(TAG, "[DISPATCH_TIMER_FIRE] type=window_transition result=capture app=" + pendingAppName);
                    // Do NOT fire window_settled immediately — the page may still be blank.
                    // Delegate to ContentChangedHandler's stability poll.
                    if (contentChangedHandler != null) {
                        contentChangedHandler.updateUiScreenChangeTs();
                        contentChangedHandler.checkAfterWindowSettled(pendingAppName, pendingEventType);
                    } else {
                        captureCallback.onCapture(pendingAppName, pendingEventType,
                                System.currentTimeMillis(), "window_settled");
                    }
                } else {
                    Log.d(TAG, "[DISPATCH_TIMER_FIRE] type=window_transition result=no_capture (shouldCapture=false)");
                }
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
        lastTransitionTs = 0;
    }
}
