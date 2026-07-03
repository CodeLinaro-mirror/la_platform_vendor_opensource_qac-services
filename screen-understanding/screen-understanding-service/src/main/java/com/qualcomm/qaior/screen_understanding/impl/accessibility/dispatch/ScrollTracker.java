/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.accessibility.dispatch;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tracks per-widget scroll state and decides whether the current event has scrolled far enough
 * to warrant a new capture (≥ 50% of the viewport). Fires an extra CaptureCallback call
 * for high-velocity intermediate frames (previous position) when needed.
 *
 * All public methods must be called from the same serial thread
 * (the EventDispatcher event thread). isUserScrolling() may be called from any thread.
 */
class ScrollTracker {

    private static final String TAG = "ScreenUnderstanding.ScrollTracker";

    static final long DISABLE_SCROLLING_MILLIS = 500;

    private volatile long latestScrollingTime = 0;

    private int screenWidth = 0;
    private int screenHeight = 0;

    // Keyed by source.hashCode() — same widget, same key.
    private final Map<Integer, ListViewState> listViewStates = new HashMap<>();

    // Keyed by visible Rect, but looked up by 80% overlap.
    private final Map<Rect, ScrollViewState> scrollViewStates = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Inner state classes
    // -------------------------------------------------------------------------

    static class ScrollViewState {
        int lastPositionY = -1;
        int lastPositionX = -1;
        final Rect lastCapturedRect = new Rect(-1, -1, -1, -1);
        final Rect lastVisibleRect = new Rect();
        // Stored for high-velocity intermediate capture tag.
        long lastEventTimestamp = 0;
    }

    static class ListViewState {
        int lastCapturedFromIndex = -1;
        int lastCapturedToIndex = -1;
        final Rect lastCapturedFromRect = new Rect();
        final Rect lastCapturedToRect = new Rect();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    void setScreenSize(int width, int height) {
        screenWidth = width;
        screenHeight = height;
    }

    /**
     * Evaluates whether the event warrants a capture.
     *
     * May call cb.onCapture() once for a high-velocity intermediate frame (tag =
     * {@code "scroll_highvel_prev"}) before returning. Returns true if the caller should
     * also fire a capture for the current position.
     */
    boolean shouldCapture(AccessibilityEvent event, String appName, CaptureCallback cb) {
        latestScrollingTime = System.currentTimeMillis();

        AccessibilityNodeInfo source = event.getSource();
        if (source == null) return false;

        try {
            // Screen-size gate: skip scrollables covering < 70% of the screen.
            Rect visibleRect = new Rect();
            source.getBoundsInScreen(visibleRect);
            if (visibleRect.isEmpty()) return false;

            if (screenWidth > 0 && screenHeight > 0) {
                long visibleArea = (long) visibleRect.width() * visibleRect.height();
                long screenArea = (long) screenWidth * screenHeight;
                if (visibleArea < 0.7 * screenArea) return false;
            }

            int hashCode = source.hashCode();
            String resourceName = source.getViewIdResourceName();

            // ListView detection: no pixel coordinates reported, or resource name contains "list".
            boolean isList = (Math.abs(event.getScrollY()) < 1 && Math.abs(event.getScrollX()) < 1)
                    || (resourceName != null && resourceName.contains("list"));

            if (isList) {
                return handleListView(event, source, hashCode, visibleRect, appName, cb);
            } else {
                return handleScrollView(event, source, visibleRect, appName, cb);
            }
        } finally {
            source.recycle();
        }
    }

    /**
     * Updates the scroll timestamp without triggering capture logic.
     * Used during window transitions where scroll events still need to keep
     * isUserScrolling() current but capture should be suppressed.
     */
    void markScrolling() {
        latestScrollingTime = System.currentTimeMillis();
    }

    /** Returns true if a scroll event arrived within the last DISABLE_SCROLLING_MILLIS ms. */
    boolean isUserScrolling() {
        if (latestScrollingTime == 0) return false;
        return System.currentTimeMillis() <= (latestScrollingTime + DISABLE_SCROLLING_MILLIS);
    }

    /**
     * Clears per-widget state and resets scroll timing.
     * Called on TYPE_WINDOW_STATE_CHANGED, package change, and session stop.
     */
    void resetScrollSession() {
        listViewStates.clear();
        scrollViewStates.clear();
        latestScrollingTime = 0;
    }

    /** Alias used on session stop/start to fully reset all state. */
    void reset() {
        resetScrollSession();
    }

    // -------------------------------------------------------------------------
    // ScrollView handling
    // -------------------------------------------------------------------------

    private boolean handleScrollView(AccessibilityEvent event, AccessibilityNodeInfo source,
            Rect visibleRect, String appName, CaptureCallback cb) {

        int scrollY = event.getScrollY();
        int scrollX = event.getScrollX();
        int visibleHeight = visibleRect.height();
        int visibleWidth = visibleRect.width();

        // No pixel coordinates means not a pixel-based scroll view.
        if (Math.abs(scrollY) < 1 && Math.abs(scrollX) < 1) return false;

        ScrollViewState state = findOrCreateScrollViewState(visibleRect);
        state.lastVisibleRect.set(visibleRect);
        long now = System.currentTimeMillis();

        boolean shouldCapture = false;

        // Vertical overlap.
        if (visibleHeight > 1 && scrollY > 0) {
            int top = scrollY;
            int bottom = scrollY + visibleHeight;

            if (state.lastCapturedRect.top == -1 && state.lastCapturedRect.bottom == -1) {
                // First vertical scroll for this widget.
                shouldCapture = true;
            } else {
                int overlapTop = Math.max(top, state.lastCapturedRect.top);
                int overlapBottom = Math.min(bottom, state.lastCapturedRect.bottom);
                int overlapH = overlapBottom - overlapTop;

                if (overlapH <= visibleHeight / 2) {
                    // High-velocity gap: scrolled past more than one screen.
                    if (overlapH < 0
                            && state.lastPositionY != -1
                            && state.lastPositionY != state.lastCapturedRect.top) {
                        cb.onCapture(appName, AccessibilityEvent.TYPE_VIEW_SCROLLED,
                                state.lastEventTimestamp, "scroll_highvel_prev");
                    }
                    shouldCapture = true;
                }
            }
            state.lastPositionY = scrollY;
        }

        // Horizontal overlap.
        if (visibleWidth > 1 && scrollX > 0) {
            int left = scrollX;
            int right = scrollX + visibleWidth;

            if (state.lastCapturedRect.left == -1 && state.lastCapturedRect.right == -1) {
                shouldCapture = true;
            } else {
                int overlapLeft = Math.max(left, state.lastCapturedRect.left);
                int overlapRight = Math.min(right, state.lastCapturedRect.right);
                int overlapW = overlapRight - overlapLeft;

                if (overlapW <= visibleWidth / 2) {
                    if (overlapW < 0
                            && state.lastPositionX != -1
                            && state.lastPositionX != state.lastCapturedRect.left) {
                        cb.onCapture(appName, AccessibilityEvent.TYPE_VIEW_SCROLLED,
                                state.lastEventTimestamp, "scroll_highvel_prev");
                    }
                    shouldCapture = true;
                }
            }
            state.lastPositionX = scrollX;
        }

        state.lastEventTimestamp = now;

        if (shouldCapture) {
            // Shift lastCapturedRect to current position for the next overlap check.
            if (scrollY > 0) {
                state.lastCapturedRect.top = scrollY;
                state.lastCapturedRect.bottom = scrollY + visibleHeight;
            }
            if (scrollX > 0) {
                state.lastCapturedRect.left = scrollX;
                state.lastCapturedRect.right = scrollX + visibleWidth;
            }
        }

        if (shouldCapture) {
            Log.i(TAG, "[DISPATCH_TIMER_FIRE] type=scroll_capture");
        } else {
            Log.v(TAG, "[DISPATCH_CAPTURE_SKIP] type=scroll reason=within_500ms_window");
        }

        return shouldCapture;
    }

    /**
     * Finds a ScrollViewState whose stored Rect overlaps the given visibleRect by ≥ 80%.
     * If none is found, creates and inserts a new entry.
     *
     * Exact HashMap.get(rect) is NOT used because the same physical widget may report
     * slightly different bounds across events, which would create a new entry each time and reset
     * lastCapturedRect causing a capture on every event.
     */
    private ScrollViewState findOrCreateScrollViewState(Rect visibleRect) {
        for (Map.Entry<Rect, ScrollViewState> entry : scrollViewStates.entrySet()) {
            Rect stored = entry.getKey();
            int oh = Math.min(stored.bottom, visibleRect.bottom) - Math.max(stored.top, visibleRect.top);
            int ow = Math.min(stored.right, visibleRect.right) - Math.max(stored.left, visibleRect.left);
            if (oh > 0 && ow > 0 && stored.height() > 0 && stored.width() > 0) {
                double overlap = (double) (oh * ow) / ((double) stored.height() * stored.width());
                if (overlap > 0.8) return entry.getValue();
            }
        }
        ScrollViewState newState = new ScrollViewState();
        scrollViewStates.put(new Rect(visibleRect), newState);
        return newState;
    }

    // -------------------------------------------------------------------------
    // ListView handling
    // -------------------------------------------------------------------------

    private boolean handleListView(AccessibilityEvent event, AccessibilityNodeInfo source,
            int hashCode, Rect visibleRect, String appName, CaptureCallback cb) {

        int fromIndex = event.getFromIndex();
        int toIndex = event.getToIndex();
        if (fromIndex == -1 && toIndex == -1) return false;
        if (visibleRect.isEmpty()) return false;

        ListViewState state = listViewStates.get(hashCode);
        if (state == null) {
            state = new ListViewState();
            listViewStates.put(hashCode, state);
        }

        // First scroll for this widget always capture.
        if (state.lastCapturedFromIndex == -1 && state.lastCapturedToIndex == -1) {
            updateListViewAfterCapture(state, fromIndex, toIndex, source);
            return true;
        }

        int visibleMidY = visibleRect.centerY();
        boolean shouldCapture = false;

        boolean scrollingDown = toIndex > state.lastCapturedToIndex
                || fromIndex > state.lastCapturedFromIndex;
        boolean scrollingUp = toIndex < state.lastCapturedToIndex
                || fromIndex < state.lastCapturedFromIndex;

        if (scrollingDown) {
            // childNodeIndex: position of the previously-captured toIndex within source's current children.
            int childNodeIndex = state.lastCapturedToIndex - fromIndex;
            if (childNodeIndex < 0) {
                // Scrolled past an entire screen — fire intermediate and capture current.
                cb.onCapture(appName, AccessibilityEvent.TYPE_VIEW_SCROLLED,
                        System.currentTimeMillis(), "scroll_highvel_prev");
                shouldCapture = true;
            } else if (childNodeIndex < source.getChildCount()) {
                AccessibilityNodeInfo child = source.getChild(childNodeIndex);
                if (child != null) {
                    Rect childRect = new Rect();
                    child.getBoundsInScreen(childRect);
                    child.recycle();
                    // Capture once the bottom edge of the previous toIndex crosses the midpoint.
                    if (!childRect.isEmpty() && childRect.bottom < visibleMidY) {
                        shouldCapture = true;
                    }
                }
            }
        } else if (scrollingUp) {
            int childNodeIndex = state.lastCapturedFromIndex - fromIndex;
            if (childNodeIndex >= source.getChildCount()) {
                cb.onCapture(appName, AccessibilityEvent.TYPE_VIEW_SCROLLED,
                        System.currentTimeMillis(), "scroll_highvel_prev");
                shouldCapture = true;
            } else if (childNodeIndex >= 0) {
                AccessibilityNodeInfo child = source.getChild(childNodeIndex);
                if (child != null) {
                    Rect childRect = new Rect();
                    child.getBoundsInScreen(childRect);
                    child.recycle();
                    // Capture once the top edge of the previous fromIndex crosses the midpoint.
                    if (!childRect.isEmpty() && childRect.top > visibleMidY) {
                        shouldCapture = true;
                    }
                }
            }
        }

        if (shouldCapture) {
            updateListViewAfterCapture(state, fromIndex, toIndex, source);
        }

        if (shouldCapture) {
            Log.i(TAG, "[DISPATCH_TIMER_FIRE] type=scroll_capture");
        } else {
            Log.v(TAG, "[DISPATCH_CAPTURE_SKIP] type=scroll reason=within_500ms_window");
        }

        return shouldCapture;
    }

    private void updateListViewAfterCapture(ListViewState state, int fromIndex, int toIndex,
            AccessibilityNodeInfo source) {
        state.lastCapturedFromIndex = fromIndex;
        state.lastCapturedToIndex = toIndex;
        int childCount = source.getChildCount();
        if (childCount >= 2) {
            AccessibilityNodeInfo first = source.getChild(0);
            AccessibilityNodeInfo last = source.getChild(childCount - 1);
            if (first != null) {
                first.getBoundsInScreen(state.lastCapturedFromRect);
                first.recycle();
            }
            if (last != null) {
                last.getBoundsInScreen(state.lastCapturedToRect);
                last.recycle();
            }
        }
    }
}
