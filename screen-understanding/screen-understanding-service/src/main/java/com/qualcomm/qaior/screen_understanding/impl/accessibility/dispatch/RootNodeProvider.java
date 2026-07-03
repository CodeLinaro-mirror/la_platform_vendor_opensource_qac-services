/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.accessibility.dispatch;

import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Abstracts android.accessibilityservice.AccessibilityService.getRootInActiveWindow()
 * so that ContentChangedHandler can be tested without a real service instance.
 *
 */
public interface RootNodeProvider {
    /** Returns the root node for the active window, or null if unavailable. */
    AccessibilityNodeInfo getRootInActiveWindow();
}
