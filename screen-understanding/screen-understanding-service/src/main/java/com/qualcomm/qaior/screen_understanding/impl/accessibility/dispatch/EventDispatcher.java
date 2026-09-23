/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.accessibility.dispatch;

import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Owns the serial event thread, event routing, and trailing-capture scheduling.
 *
 * Accessibility event thread calls handleEvent; immediately posts work to eventThread
 * HandlerThread serialises all scroll/click/window logic; one thread.
 * Main looper schedules postDelayed for trailing capture
 * then re-dispatches back to eventThread for execution.
 */
public class EventDispatcher {

    private static final String TAG = "ScreenUnderstanding.EventDispatcher";

    /** Trailing delay = DISABLE_SCROLLING_MILLIS (500) + 100 ms buffer */
    private static final long TRAILING_DELAY_MS = ScrollTracker.DISABLE_SCROLLING_MILLIS + 100;

    private final HandlerThread eventThread;
    private final Handler eventHandler;
    private final Handler mainHandler;

    private final ScrollTracker scrollTracker;
    private final ClickDeduplicator clickDeduplicator;
    private final TypingDetector typingDetector;
    private final WindowTransitionHandler windowTransitionHandler;
    private final ContentChangedHandler contentChangedHandler;
    private final CaptureCallback captureCallback;

    private volatile String lastActivePackage = null;

    /** Controls whether window-transition events (STATE_CHANGED / WINDOWS_CHANGED) trigger
     *  a capture after debounce. False by default — click+scroll only. */
    private volatile boolean onWindowTransition = false;

    /**
     * Volatile prevents stale-read; the single-writer guarantee (eventThread) makes the
     * write-then-postDelayed sequence safe without additional locking.
     */
    private volatile Runnable trailingCaptureRunnable = null;

    public EventDispatcher(CaptureCallback captureCallback, RootNodeProvider rootNodeProvider) {
        this.captureCallback = captureCallback;
        this.scrollTracker = new ScrollTracker();
        this.clickDeduplicator = new ClickDeduplicator();
        this.typingDetector = new TypingDetector();

        eventThread = new HandlerThread("EventDispatcher");
        eventThread.start();
        eventHandler = new Handler(eventThread.getLooper());
        mainHandler = new Handler(Looper.getMainLooper());
        windowTransitionHandler = new WindowTransitionHandler(eventHandler, captureCallback);
        contentChangedHandler = new ContentChangedHandler(
                mainHandler, rootNodeProvider, scrollTracker,
                windowTransitionHandler, typingDetector,
                captureCallback, this::cancelTrailingCapture);
        windowTransitionHandler.setContentChangedHandler(contentChangedHandler);
    }

    /** Forward screen dimensions to ScrollTracker for the 70%-viewport gate. */
    public void setScreenSize(int width, int height) {
        scrollTracker.setScreenSize(width, height);
    }

    /** Sets the onWindowTransition capture policy.
     *  Must only be called when no session is running. */
    public void setOnWindowTransition(boolean value) {
        onWindowTransition = value;
    }

    /**
     * Entry point from onAccessibilityEvent.
     *
     * TEXT_CHANGED and TEXT_SELECTION_CHANGED are handled synchronously.
     * TYPE_VIEW_FOCUSED and all other events are
     * forwarded to the serial event thread. The caller must pass an event copy obtained via
     * AccessibilityEvent.obtain(event) — the original may be recycled by the system
     * as soon as onAccessibilityEvent returns.
     */
    public void handleEvent(final AccessibilityEvent event) {
        if (event == null) return;

        final int eventType = event.getEventType();

        // TYPE_VIEW_TEXT_CHANGED and TYPE_VIEW_TEXT_SELECTION_CHANGED are always typing events.
        // Update the typing timestamp synchronously (volatile write visible to event thread)
        // and skip posting — they never trigger a capture.
        if (eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                || eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            typingDetector.markTypingNow();
            event.recycle();
            return;
        }

        // Extract click coordinates synchronously here on the accessibility callback thread,
        // before the source view can detach (e.g. during back-navigation).
        final String preExtractedClickTag;
        if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                Rect rect = new Rect();
                source.getBoundsInScreen(rect);
                source.recycle();
                preExtractedClickTag = rect.isEmpty() ? "" : "x=" + rect.centerX() + ",y=" + rect.centerY();
            } else {
                preExtractedClickTag = "";
            }
            final long tapWallClock = System.currentTimeMillis() - SystemClock.elapsedRealtime() + event.getEventTime();
            final long deliveryLatencyMs = System.currentTimeMillis() - tapWallClock;
            Log.i(TAG, "[TAP_LATENCY] tap_time_ms=" + tapWallClock
                    + " service_received_ms=" + System.currentTimeMillis()
                    + " delivery_latency_ms=" + deliveryLatencyMs);
        } else {
            preExtractedClickTag = null;
        }

        final String appName = event.getPackageName() != null
                ? event.getPackageName().toString() : "Unknown";
        final long timestamp = System.currentTimeMillis() - SystemClock.elapsedRealtime() + event.getEventTime();

        eventHandler.post(() -> {
            try {
                dispatchOnEventThread(event, eventType, appName, timestamp, preExtractedClickTag);
            } finally {
                event.recycle();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Private — runs on eventThread
    // -------------------------------------------------------------------------

    private void dispatchOnEventThread(AccessibilityEvent event, int eventType,
            String appName, long timestamp, String preExtractedClickTag) {

        // Typing detection
        // Step 1: If this event's source has inputType != 0, update the typing timestamp.
        //         Drop non-click events — but allow TYPE_VIEW_CLICKED through so that taps
        //         on input-type views (e.g. quantity steppers) are still captured with coordinates.
        boolean isTypingSource = typingDetector.checkAndUpdateIfTyping(event);
        if (isTypingSource && eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            Log.v(TAG, "[DISPATCH_EVENT_DROP] Typing event detected (type=" + eventType + "); suppressing capture.");
            return;
        }
        // Step 2: If the user was recently typing, allow scroll/click through but drop everything else.
        if (typingDetector.isUserTyping()
                && eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
                && eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            Log.v(TAG, "[DISPATCH_EVENT_DROP] Event " + eventType + " dropped: user is typing.");
            return;
        }
        // Step 3: TYPE_VIEW_FOCUSED never triggers a capture even when not a typing event.
        if (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            Log.v(TAG, "[DISPATCH_EVENT_DROP] type=TYPE_VIEW_FOCUSED reason=never_captures");
            return;
        }

        // Package-change detection: reset scroll session and cancel trailing capture.
        if (lastActivePackage != null && !lastActivePackage.equals(appName)) {
            cancelTrailingCapture();
            scrollTracker.resetScrollSession();
        }
        lastActivePackage = appName;

        // Mark UI as changed for window-transition stability tracking.
        if (eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            windowTransitionHandler.markUiChanged();
        }

        // Event filter during active scroll:
        //   - TYPE_VIEW_SCROLLED → always passes
        //   - TYPE_VIEW_CLICKED  → passes, but resets scroll session first
        //   - all others         → dropped while user is scrolling
        if (eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
                && scrollTracker.isUserScrolling()) {
            if (eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) {
                // A window transition arriving mid-scroll means the preceding scroll was
                // rendering-induced (layout shift / reflow), not a real user scroll — a real
                // scroll always stops before navigation completes. Still arm the render-settle
                // window (and clear scroll state) before dropping, so the destination screen's
                // settling scrolls are suppressed. Mirrors EventRouter.kt.
                if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    cancelTrailingCapture();
                    scrollTracker.resetScrollSession();
                    windowTransitionHandler.markTransition();
                }
                Log.v(TAG, "[DISPATCH_EVENT_DROP] Dropping event " + eventType + " during active scroll");
                return;
            } else {
                // Click-during-scroll: clear scroll timing so trailing guard fires correctly.
                scrollTracker.resetScrollSession();
            }
        }

        switch (eventType) {
            case AccessibilityEvent.TYPE_VIEW_SCROLLED: {
                // System-induced scrolls during typing (keyboard pushing list up) must not fire captures.
                if (typingDetector.isUserTyping()) {
                    scrollTracker.markScrolling();
                    Log.v(TAG, "[DISPATCH_CAPTURE_SKIP] type=scroll reason=user_typing");
                    break;
                }

                boolean inSettleOrTransition =
                        windowTransitionHandler.isWaitingEvent() ||
                        windowTransitionHandler.timeSinceTransition() < WindowTransitionHandler.RENDER_SETTLE_MS;
                if (inSettleOrTransition) {
                    scrollTracker.markScrolling();
                    Log.d(TAG, "[DISPATCH_CAPTURE_SKIP] type=scroll reason=transition_or_render_settle");
                    break;
                }

                // Reset click gate on every scroll.
                clickDeduplicator.reset();
                boolean capture = scrollTracker.shouldCapture(event, appName, captureCallback);
                if (capture) {
                    captureCallback.onCapture(appName, eventType, timestamp, "");
                    scrollTracker.updateAllAfterCapture();
                    contentChangedHandler.updateUiScreenChangeTs();
                }
                if (capture) scheduleTrailingCapture(appName);
                break;
            }

            case AccessibilityEvent.TYPE_VIEW_CLICKED: {
                scrollTracker.markUserInteraction();
                if (clickDeduplicator.checkAndConsume()) {
                    String tag = preExtractedClickTag != null ? preExtractedClickTag : "";
                    if (tag.isEmpty()) {
                        // Source detached — capture without coordinates rather than drop.
                        Log.d(TAG, "[DISPATCH_CAPTURE] type=click no_coordinates");
                    }
                    Log.v(TAG, "tag " + tag);
                    captureCallback.onCapture(appName, eventType, timestamp, tag);
                    contentChangedHandler.updateUiScreenChangeTs();
                }
                break;
            }

            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                cancelTrailingCapture();
                windowTransitionHandler.markTransition();
                windowTransitionHandler.startOrResetDebounce(
                        appName, eventType,
                        clickDeduplicator::reset,
                        scrollTracker::resetScrollSession, onWindowTransition);
                break;
            }

            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: {
                // Light handling: re-arm click gate; then start the animation-wait debounce.
                clickDeduplicator.reset();
                // Content-changed captures are a window-transition capture — only run the
                // stability poll when the policy allows it. Under the default
                // (onWindowTransition=false, click+scroll only) these must be suppressed.
                // Mirrors EventRouter.kt. clickDeduplicator.reset() stays unconditional.
                if (onWindowTransition) {
                    contentChangedHandler.check(appName, timestamp);
                }
                break;
            }

            case AccessibilityEvent.TYPE_WINDOWS_CHANGED: {
                cancelTrailingCapture();
                windowTransitionHandler.markTransition();
                windowTransitionHandler.startOrResetDebounce(
                        appName, eventType,
                        clickDeduplicator::reset,
                        scrollTracker::resetScrollSession, onWindowTransition);
                break;
            }

            default:
                break;
        }
    }

    // -------------------------------------------------------------------------
    // Trailing capture
    // -------------------------------------------------------------------------

    private void scheduleTrailingCapture(String appName) {
        cancelTrailingCapture();
        Log.d(TAG, "[DISPATCH_TIMER_START] type=trailing_capture delay_ms=" + TRAILING_DELAY_MS +
        " app=" + appName);
        Runnable runnable = () -> eventHandler.post(() -> {
            if (!scrollTracker.isUserScrolling() && !windowTransitionHandler.isWaitingEvent()
                && !typingDetector.isUserTyping()) {
                Log.d(TAG, "[DISPATCH_TIMER_FIRE] type=trailing_capture result=capture");
                captureCallback.onCapture(appName, AccessibilityEvent.TYPE_VIEW_SCROLLED,
                        System.currentTimeMillis(), "scroll_final");
            } else {
                Log.d(TAG, "[DISPATCH_CAPTURE_SKIP] type=trailing_capture reason=" +
                (scrollTracker.isUserScrolling() ? "still_scrolling" :
                windowTransitionHandler.isWaitingEvent() ? "window_transition" : "user_typing"));
            }
        });
        trailingCaptureRunnable = runnable;
        mainHandler.postDelayed(runnable, TRAILING_DELAY_MS);
    }

    /**
     * Cancels any pending trailing capture immediately.
     * removeCallbacks is safe to call from any thread.
     */
    public void cancelTrailingCapture() {
        Runnable pending = trailingCaptureRunnable;
        if (pending != null) {
            Log.d(TAG, "[DISPATCH_TIMER_CANCEL] type=trailing_capture");
            mainHandler.removeCallbacks(pending);
            trailingCaptureRunnable = null;
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Resets all event-handling state. Safe to call from any thread.
     * The trailing-capture cancellation is immediate; the scroll/click state reset is async.
     */
    public void reset() {
        cancelTrailingCapture(); // immediate
        windowTransitionHandler.reset(); // immediate
        contentChangedHandler.reset(); // immediate
        eventHandler.post(() -> {
            scrollTracker.reset();
            clickDeduplicator.reset();
            typingDetector.reset();
            lastActivePackage = null;
        });
    }

    /**
     * Cancels pending callbacks and shuts down the event thread.
     * Must be called from onDestroy() before super.onDestroy().
     */
    public void shutdown() {
        cancelTrailingCapture();
        windowTransitionHandler.cancelDebounce();
        eventThread.quitSafely();
    }
}
