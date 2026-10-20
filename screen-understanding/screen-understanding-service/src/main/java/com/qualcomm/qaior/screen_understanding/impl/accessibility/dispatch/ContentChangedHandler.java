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

    // Set by checkAfterWindowSettled to carry the window eventType through to onStable,
    // so the deferred capture fires with the right tag ("window_settled") instead of "".
    private volatile boolean isWindowSettledPoll = false;
    private volatile int     windowSettledEventType = 0;

    /**
     * Structural hash of the last captured accessibility tree.
     * Accessed only from the main looper callbacks and reset post.
     */
    private String currentNodeStructHash = "";
    private int    currentRichness       = -1;

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
        scheduleStabilityPoll(appName, /* oldKey= */ "", /* stableCount= */ 0, startTime);
    }

    /**
     * Starts a proactive content-stability poll immediately after window_settled fires,
     * bypassing the debounce gate. Allows the fully-loaded page state to be captured once
     * network images settle, without waiting for a TYPE_WINDOW_CONTENT_CHANGED event that
     * may have been dropped during the transition debounce window.
     */
    void checkAfterWindowSettled(String appName, int eventType) {
        if (isWaitingUiAnimation) return;
        isWaitingUiAnimation = true;
        isWindowSettledPoll = true;
        windowSettledEventType = eventType;
        long startTime = System.currentTimeMillis();
        scheduleStabilityPoll(appName, /* oldKey= */ "", 0, startTime);
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
        mainHandler.post(() -> { currentNodeStructHash = ""; currentRichness = -1; });
    }

    // -------------------------------------------------------------------------
    // Private — poll loop runs on main looper
    // -------------------------------------------------------------------------

    private void scheduleStabilityPoll(String appName, String oldKey,
            int stableCount, long startTime) {
        Log.i(TAG, "[DISPATCH_TIMER_CHECK] type=content_change poll_interval_ms=" + POLL_INTERVAL_MS);
        mainHandler.postDelayed(() ->
                doPoll(appName, oldKey, stableCount, startTime), POLL_INTERVAL_MS);
    }

    private void doPoll(String appName, String oldKey, int stableCount, long startTime) {
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
                scheduleStabilityPoll(appName, oldKey, stableCount, startTime);
            } else {
                Log.d(TAG, "WINDOW_CONTENT_CHANGED: root null at timeout; fallback.");
                onFallback();
            }
            return;
        }

        TreeSignature sig = signature(root);
        root.recycle();

        // Stability keys on hash AND richness: text still streaming in keeps richness
        // rising, changing the key and resetting stableCount each cycle.
        String newKey = sig.hash + "#" + sig.richness;
        int nextStableCount = newKey.equals(oldKey) ? stableCount + 1 : 0;
        if (nextStableCount >= STABLE_CYCLES_REQUIRED) {
            onStable(appName, sig);
            return;
        }

        if (elapsed < CONTENT_CHANGED_DEBOUNCE_MS) {
            scheduleStabilityPoll(appName, newKey, nextStableCount, startTime);
        } else {
            Log.d(TAG, "WINDOW_CONTENT_CHANGED: timeout waiting for stable tree; fallback.");
            onFallback();
        }
    }

    private void onStable(String appName, TreeSignature pollSig) {
        AccessibilityNodeInfo root = rootProvider.getRootInActiveWindow();
        isWaitingUiAnimation = false;
        boolean wasWindowSettled = isWindowSettledPoll;
        int settledEventType     = windowSettledEventType;
        isWindowSettledPoll      = false;
        windowSettledEventType   = 0;

        if (root == null) {
            Log.d(TAG, "[DISPATCH_CAPTURE_SKIP] WINDOW_CONTENT_CHANGED: root null after stabilisation; skip capture.");
            return;
        }

        TreeSignature finalSig = signature(root);
        root.recycle();

        boolean sameStructure = finalSig.hash.equals(currentNodeStructHash);
        boolean richer        = finalSig.richness > currentRichness;
        if (sameStructure && !richer) {
            Log.v(TAG, "[DISPATCH_CAPTURE_SKIP] WINDOW_CONTENT_CHANGED: structure+richness unchanged; skip capture.");
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

        currentNodeStructHash = finalSig.hash;
        currentRichness       = finalSig.richness;
        lastUiScreenChangeTsMs = System.currentTimeMillis();
        if (wasWindowSettled) {
            Log.i(TAG, "[DISPATCH_TIMER_FIRE] type=window_settled result=capture (deferred, content loaded)");
            captureCallback.onCapture(appName, settledEventType,
                    System.currentTimeMillis(), "window_settled");
        } else {
            Log.i(TAG, "[DISPATCH_TIMER_FIRE] type=content_changed result=capture hash_changed=true");
            captureCallback.onCapture(appName, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    System.currentTimeMillis(), "");
        }
    }

    private void onFallback() {
        isWaitingUiAnimation = false;
        isWindowSettledPoll  = false;
        windowSettledEventType = 0;
        Log.d(TAG, "WINDOW_CONTENT_CHANGED: animation wait aborted; no capture.");
    }

    /** Struct hash + content-richness from one traversal. */
    static final class TreeSignature {
        final String hash;
        final int richness;
        TreeSignature(String hash, int richness) { this.hash = hash; this.richness = richness; }
    }

    private static int walk(AccessibilityNodeInfo node, StringBuilder sb) {
        sb.append(node.getClassName()).append("-").append(node.getWindowId()).append("[");
        CharSequence t  = node.getText();
        CharSequence cd = node.getContentDescription();
        int richness = ((t != null && t.length() > 0) || (cd != null && cd.length() > 0)) ? 1 : 0;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { richness += walk(child, sb); child.recycle(); }
        }
        sb.append("]");
        return richness;
    }

    private static TreeSignature signature(AccessibilityNodeInfo root) {
        StringBuilder sb = new StringBuilder();
        int r = walk(root, sb);
        return new TreeSignature(sb.toString(), r);
    }
}
