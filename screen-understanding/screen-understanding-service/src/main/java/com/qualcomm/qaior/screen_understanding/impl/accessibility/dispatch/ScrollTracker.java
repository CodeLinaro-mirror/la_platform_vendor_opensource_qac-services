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

        /** Sync lastCapturedRect to the current position after any capture fires. */
        void updateAfterCapture() {
            if (!lastVisibleRect.isEmpty()) {
                if (lastPositionY != -1) {
                    lastCapturedRect.top = lastPositionY;
                    lastCapturedRect.bottom = lastPositionY + lastVisibleRect.height();
                }
                if (lastPositionX != -1) {
                    lastCapturedRect.left = lastPositionX;
                    lastCapturedRect.right = lastPositionX + lastVisibleRect.width();
                }
            }
        }
    }

    static class ListViewState {
        int lastCapturedFromIndex = -1;
        int lastCapturedToIndex = -1;
        final Rect lastCapturedFromRect = new Rect();
        final Rect lastCapturedToRect = new Rect();
        // last known item count — for lazy-load / append detection (#6)
        int lastItemCount = -1;
        // visible rect bounds stored at last capture — for tall-item midpoint accounting (#8)
        int lastCapturedFromRectHeight = -1;
        int lastCapturedToRectHeight = -1;
        // last-event visible rects — for same-indices pixel detection (#7)
        final Rect lastVisibleFromRect = new Rect();
        final Rect lastVisibleToRect = new Rect();
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
     * Syncs lastCapturedRect on all tracked scroll widgets after any capture fires.
     * Mirrors Kotlin's post-capture updatePosAfterCapturedScreen() loop over both maps.
     * Must be called from the event thread immediately after captureCallback.onCapture().
     */
    void updateAllAfterCapture() {
        for (ScrollViewState s : scrollViewStates.values()) {
            s.updateAfterCapture();
        }
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

        int itemCount = event.getItemCount();

        ListViewState state = listViewStates.get(hashCode);
        if (state == null) {
            state = new ListViewState();
            listViewStates.put(hashCode, state);
        }

        // #6: First scroll OR new items appended (lazy-load / infinite-scroll detection).
        boolean isFirstScroll = state.lastCapturedFromIndex == -1 && state.lastCapturedToIndex == -1;
        boolean isNewItemsAppended = state.lastCapturedToIndex != -1
                && itemCount > state.lastItemCount && state.lastItemCount != -1
                && Math.abs(toIndex - state.lastCapturedToIndex) <= 1;
        if (isFirstScroll || isNewItemsAppended) {
            updateListViewAfterCapture(state, fromIndex, toIndex, itemCount, source);
            return true;
        }

        // Update current-event visible rects for #7 same-indices detection.
        Rect curFromRect = new Rect();
        Rect curToRect = new Rect();
        if (source.getChildCount() >= 2) {
            AccessibilityNodeInfo first = source.getChild(0);
            AccessibilityNodeInfo last = source.getChild(source.getChildCount() - 1);
            if (first != null) { first.getBoundsInScreen(curFromRect); first.recycle(); }
            if (last != null)  { last.getBoundsInScreen(curToRect);  last.recycle();  }
        }

        int visibleMidY = visibleRect.centerY();
        boolean shouldCapture = false;

        boolean scrollingDown = toIndex > state.lastCapturedToIndex
                || fromIndex > state.lastCapturedFromIndex;
        boolean scrollingUp = toIndex < state.lastCapturedToIndex
                || fromIndex < state.lastCapturedFromIndex;

        // #7: Same indices but pixel position changed — detect via stored last-capture child rects.
        boolean sameIndices = fromIndex == state.lastCapturedFromIndex
                && toIndex == state.lastCapturedToIndex;
        if (sameIndices && !curFromRect.isEmpty() && !state.lastCapturedFromRect.isEmpty()) {
            if (curFromRect.bottom > state.lastCapturedFromRect.bottom) {
                // Items moved up visually → user scrolled down within the same index window.
                scrollingDown = true;
            } else if (curFromRect.bottom < state.lastCapturedFromRect.bottom) {
                scrollingUp = true;
            }
        }

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
                    if (!childRect.isEmpty()) {
                        // #8: Tall-item midpoint accounting.
                        // If the item spans more than half the viewport use its actual bottom;
                        // otherwise use the stored capture-time height to reconstruct the bottom edge.
                        int effectiveBottom;
                        if (state.lastCapturedToRectHeight != -1
                                && state.lastCapturedToRectHeight < visibleRect.height() / 2) {
                            effectiveBottom = childRect.top + state.lastCapturedToRectHeight;
                        } else {
                            effectiveBottom = childRect.bottom;
                        }
                        if (effectiveBottom < visibleMidY) {
                            shouldCapture = true;
                        }
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
                    if (!childRect.isEmpty()) {
                        // #8: Tall-item midpoint accounting (scroll-up mirror).
                        int effectiveTop;
                        if (state.lastCapturedFromRectHeight != -1
                                && state.lastCapturedFromRectHeight < visibleRect.height() / 2) {
                            effectiveTop = childRect.bottom - state.lastCapturedFromRectHeight;
                        } else {
                            effectiveTop = childRect.top;
                        }
                        if (effectiveTop > visibleMidY) {
                            shouldCapture = true;
                        }
                    }
                }
            }
        }

        // Always update current-event visible rects for next-event #7 comparison.
        state.lastVisibleFromRect.set(curFromRect);
        state.lastVisibleToRect.set(curToRect);

        if (shouldCapture) {
            updateListViewAfterCapture(state, fromIndex, toIndex, itemCount, source);
        }

        if (shouldCapture) {
            Log.i(TAG, "[DISPATCH_TIMER_FIRE] type=scroll_capture");
        } else {
            Log.v(TAG, "[DISPATCH_CAPTURE_SKIP] type=scroll reason=within_500ms_window");
        }

        return shouldCapture;
    }

    private void updateListViewAfterCapture(ListViewState state, int fromIndex, int toIndex,
            int itemCount, AccessibilityNodeInfo source) {
        state.lastCapturedFromIndex = fromIndex;
        state.lastCapturedToIndex = toIndex;
        if (itemCount > 0) state.lastItemCount = itemCount;
        int childCount = source.getChildCount();
        if (childCount >= 2) {
            AccessibilityNodeInfo first = source.getChild(0);
            AccessibilityNodeInfo last = source.getChild(childCount - 1);
            if (first != null) {
                first.getBoundsInScreen(state.lastCapturedFromRect);
                // #8: record height at capture time for tall-item midpoint accounting.
                state.lastCapturedFromRectHeight = state.lastCapturedFromRect.height();
                first.recycle();
            }
            if (last != null) {
                last.getBoundsInScreen(state.lastCapturedToRect);
                state.lastCapturedToRectHeight = state.lastCapturedToRect.height();
                last.recycle();
            }
        }
    }
}
