/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.accessibility.dispatch;

import android.os.Handler;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 *
 * When a TYPE_WINDOW_CONTENT_CHANGED event arrives, this class:
 *
 * Gates out events that arrive within 1000 ms of the last scroll or content capture
 * CONTENT_CHANGED_DEBOUNCE_MS.
 * Polls the accessibility tree every 200 ms (on the main looper) until the node-structure
 * hash is identical for 2 consecutive cycles, or the 1000 ms timeout expires.
 * On stable: fires a capture only if the hash differs from the last captured hash,
 * re-checking scroll/debounce guards one final time.
 * On timeout/fallback: silently discards the event.
 *
 */
class ContentChangedHandler {

    private static final String TAG = "ScreenUnderstanding.ContentChangedHandler";

    /** Matches WindowContentChangedEventHandler.CONTENT_CHANGED_DEBOUNCE_MS. */
    static final long CONTENT_CHANGED_DEBOUNCE_MS = 1000L;

    private static final long POLL_INTERVAL_MS = 200L;
    private static final int STABLE_CYCLES_REQUIRED = 2;

    private final Handler mainHandler;
    private final RootNodeProvider rootProvider;
    private final ScrollTracker scrollTracker;
    private final WindowTransitionHandler windowTransitionHandler;
    private final TypingDetector typingDetector;
    private final CaptureCallback captureCallback;
    private final Runnable cancelTrailing;

    /**
     * Updated after scroll captures and after content-changed captures to debounce.
     */
    volatile long lastUiScreenChangeTsMs = 0;

    /**
     * Re-entrancy guard: true while a stability-poll cycle is active.
     * Written on the main looper; read on the event thread and main looper.
     * Declared volatile so the event-thread read in check is not stale.
     */
    private volatile boolean isWaitingUiAnimation = false;

    /**
     * Structural hash of the last captured accessibility tree.
     * Accessed only from the main looper callbacks and reset post.
     */
    private String currentNodeStructHash = "";

    ContentChangedHandler(Handler mainHandler, RootNodeProvider rootProvider,
            ScrollTracker scrollTracker, WindowTransitionHandler windowTransitionHandler,
            TypingDetector typingDetector, CaptureCallback captureCallback,
            Runnable cancelTrailing) {
        this.mainHandler = mainHandler;
        this.rootProvider = rootProvider;
        this.scrollTracker = scrollTracker;
        this.windowTransitionHandler = windowTransitionHandler;
        this.typingDetector = typingDetector;
        this.captureCallback = captureCallback;
        this.cancelTrailing = cancelTrailing;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Entry point for TYPE_WINDOW_CONTENT_CHANGED. Called on the event thread.
     * Applies debounce, re-entrancy, and scroll gates before starting the animation wait.
     */
    void check(String appName, long timestamp) {
        Log.d(TAG, "[DISPATCH_TIMER_START] type=content_changed_debounce delay_ms=" +
            CONTENT_CHANGED_DEBOUNCE_MS);
        if (scrollTracker.isUserScrolling()) {
            Log.v(TAG, "[DISPATCH_CAPTURE_SKIP] WINDOW_CONTENT_CHANGED: user scrolling; skip.");
            return;
        }
        if (isWaitingUiAnimation) {
            Log.v(TAG, "[DISPATCH_CAPTURE_SKIP] WINDOW_CONTENT_CHANGED: already waiting for animation; skip.");
            return;
        }
        if (System.currentTimeMillis() - lastUiScreenChangeTsMs <= CONTENT_CHANGED_DEBOUNCE_MS) {
            Log.v(TAG, "[DISPATCH_CAPTURE_SKIP] WINDOW_CONTENT_CHANGED: within debounce window; skip.");
            return;
        }

        // Mark UI as changed for the transition stability tracker.
        windowTransitionHandler.markUiChanged();

        isWaitingUiAnimation = true;
        Log.v(TAG, "WINDOW_CONTENT_CHANGED: starting animation wait for " + appName);

        long startTime = System.currentTimeMillis();
        scheduleStabilityPoll(appName, /* oldHash= */ "", /* stableCount= */ 0, startTime);
    }

    /**
     * Called after a scroll capture fires to reset the debounce gate.
     * Prevents a WINDOW_CONTENT_CHANGED from firing within 1 s of a scroll capture.
     */
    void updateUiScreenChangeTs() {
        lastUiScreenChangeTsMs = System.currentTimeMillis();
    }

    /** Resets all state. Safe to call from any thread. */
    void reset() {
        isWaitingUiAnimation = false;
        lastUiScreenChangeTsMs = 0;
        // currentNodeStructHash is main-looper-only; post the clear to avoid a data race.
        mainHandler.post(() -> currentNodeStructHash = "");
    }

    // -------------------------------------------------------------------------
    // Private — poll loop runs on main looper
    // -------------------------------------------------------------------------

    private void scheduleStabilityPoll(String appName, String oldHash,
            int stableCount, long startTime) {
        Log.i(TAG, "[DISPATCH_TIMER_CHECK] type=content_change poll_interval_ms=" + POLL_INTERVAL_MS);
        mainHandler.postDelayed(() ->
                doPoll(appName, oldHash, stableCount, startTime), POLL_INTERVAL_MS);
    }

    private void doPoll(String appName, String oldHash, int stableCount, long startTime) {
        // Abort if any inhibitor became true while we were waiting.
        if (scrollTracker.isUserScrolling()) {
            Log.d(TAG, "WINDOW_CONTENT_CHANGED: scroll resumed; aborting animation wait.");
            onFallback();
            return;
        }
        if (windowTransitionHandler.isWaitingEvent()) {
            Log.d(TAG, "WINDOW_CONTENT_CHANGED: window transition active; aborting animation wait.");
            onFallback();
            return;
        }
        if (typingDetector.isUserTyping()) {
            Log.d(TAG, "WINDOW_CONTENT_CHANGED: user typing; aborting animation wait.");
            onFallback();
            return;
        }

        AccessibilityNodeInfo root = rootProvider.getRootInActiveWindow();
        long elapsed = System.currentTimeMillis() - startTime;

        if (root == null) {
            if (elapsed < CONTENT_CHANGED_DEBOUNCE_MS) {
                scheduleStabilityPoll(appName, oldHash, stableCount, startTime);
            } else {
                Log.d(TAG, "WINDOW_CONTENT_CHANGED: root null at timeout; fallback.");
                onFallback();
            }
            return;
        }

        String newHash = nodeStructHash(root, new StringBuilder());
        root.recycle();

        int nextStableCount;
        if (newHash.equals(oldHash)) {
            nextStableCount = stableCount + 1;
            if (nextStableCount >= STABLE_CYCLES_REQUIRED) {
                onStable(appName, newHash);
                return;
            }
        } else {
            nextStableCount = 0;
        }

        if (elapsed < CONTENT_CHANGED_DEBOUNCE_MS) {
            scheduleStabilityPoll(appName, newHash, nextStableCount, startTime);
        } else {
            Log.d(TAG, "WINDOW_CONTENT_CHANGED: timeout waiting for stable tree; fallback.");
            onFallback();
        }
    }

    private void onStable(String appName, String stableHash) {
        AccessibilityNodeInfo root = rootProvider.getRootInActiveWindow();
        isWaitingUiAnimation = false;

        if (root == null) {
            Log.d(TAG, "[DISPATCH_CAPTURE_SKIP] WINDOW_CONTENT_CHANGED: root null after stabilisation; skip capture.");
            return;
        }

        String finalHash = nodeStructHash(root, new StringBuilder());
        root.recycle();

        if (finalHash.equals(currentNodeStructHash)) {
            Log.v(TAG, "[DISPATCH_CAPTURE_SKIP] WINDOW_CONTENT_CHANGED: structure unchanged; skip capture.");
            return;
        }

        // Re-check guards one last time before committing.
        if (System.currentTimeMillis() - lastUiScreenChangeTsMs <= CONTENT_CHANGED_DEBOUNCE_MS) {
            Log.v(TAG, "[DISPATCH_CAPTURE_SKIP] WINDOW_CONTENT_CHANGED: fell back inside debounce window; skip.");
            return;
        }
        if (scrollTracker.isUserScrolling()) {
            Log.v(TAG, "[DISPATCH_CAPTURE_SKIP] WINDOW_CONTENT_CHANGED: scroll resumed before capture; skip.");
            return;
        }

        // Cancel any pending trailing-scroll capture to avoid near-duplicate frames.
        cancelTrailing.run();

        currentNodeStructHash = finalHash;
        lastUiScreenChangeTsMs = System.currentTimeMillis();
        Log.i(TAG, "[DISPATCH_TIMER_FIRE] type=content_changed result=capture hash_changed=true");
        Log.v(TAG, "WINDOW_CONTENT_CHANGED: structure changed; firing capture for " + appName);
        captureCallback.onCapture(appName, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                System.currentTimeMillis(), "");
    }

    private void onFallback() {
        isWaitingUiAnimation = false;
        Log.d(TAG, "WINDOW_CONTENT_CHANGED: animation wait aborted; no capture.");
    }

    /**
     * Builds a structural fingerprint of the accessibility tree rooted at node.
     * Appends className-windowId[child0_hash child1_hash ...] recursively.
     *
     *
     * Caller is responsible for recycling node; children are recycled here.
     */
    private static String nodeStructHash(AccessibilityNodeInfo node, StringBuilder sb) {
        sb.append(node.getClassName())
          .append("-")
          .append(node.getWindowId())
          .append("[");
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                nodeStructHash(child, sb);
                child.recycle();
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
