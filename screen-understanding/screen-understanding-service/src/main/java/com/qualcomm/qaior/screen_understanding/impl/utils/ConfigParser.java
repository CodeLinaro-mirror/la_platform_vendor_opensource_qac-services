/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.utils;

import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
// Import AIDL-generated types
import vendor.qti.screen_understanding.display_capture.AppInfo;
import vendor.qti.screen_understanding.display_capture.AppSmartSelectionConfig;
import vendor.qti.screen_understanding.display_capture.CaptureConfig;
import vendor.qti.screen_understanding.display_capture.CaptureParams;
import vendor.qti.screen_understanding.display_capture.DeleteConfig;
import vendor.qti.screen_understanding.display_capture.ExtractorConfig;
import vendor.qti.screen_understanding.display_capture.MatcherConfig;
import vendor.qti.screen_understanding.display_capture.PixelFormat;
import vendor.qti.screen_understanding.display_capture.SelectorConfig;
import vendor.qti.screen_understanding.display_capture.SmartSelectionConfig;

/**
 * Parses JSON configuration for capture operations.
 */
public class ConfigParser {
    private static final String LOG_TAG = "ScreenUnderstanding.ConfigParser";

    /**
     * Parse capture configuration JSON.
     *
     * @param configJson JSON string containing capture configuration.
     * @return Parsed configuration object, or null if parsing fails.
     */
    public static CaptureConfig parseConfig(String configJson) {
        if (configJson == null || configJson.isEmpty()) {
            Log.w(LOG_TAG, "Empty config JSON");
            return null;
        }

        try {
            JSONObject json = new JSONObject(configJson);
            CaptureConfig config = new CaptureConfig();

            // Parse common fields
            // if (json.has("sessionId")) {
            //     config.sessionId = json.getString("sessionId");
            // }
            if (json.has("width")) {
                config.width = json.getInt("width");
            }
            if (json.has("height")) {
                config.height = json.getInt("height");
            }
            if (json.has("format")) {
                config.format = (byte) json.getInt("format");
            }
            if (json.has("framerate")) {
                config.framerate = json.getInt("framerate");
            }
            if (json.has("userId")) {
                config.userId = json.getInt("userId");
            } else if (json.has("activeUserId")) {
                config.userId = json.getInt("activeUserId");
            }
            if (json.has("appList")) {
                JSONArray appListArray = json.getJSONArray("appList");
                AppInfo[] appInfoArray = new AppInfo[appListArray.length()];

                for (int i = 0; i < appListArray.length(); i++) {
                    JSONObject appObj = appListArray.getJSONObject(i);
                    AppInfo appInfo = new AppInfo();

                    if (appObj.has("packageName")) {
                        appInfo.packageName = appObj.getString("packageName");
                    }
                    if (appObj.has("isSensitive")) {
                        appInfo.isSensitive = appObj.getBoolean("isSensitive");
                    }

                    appInfoArray[i] = appInfo;
                }

                config.appList = appInfoArray;
            }

            // Parse optional smart selection config
            if (json.has("smartConfig")) {
                config.smartConfig = parseSmartSelectionConfig(json.getJSONObject("smartConfig"));
            }

            return config;
        } catch (JSONException e) {
            Log.e(LOG_TAG, "Failed to parse config JSON", e);
            return null;
        }
    }

    /**
     * Parse SmartSelectionConfig from JSON.
     */
    private static SmartSelectionConfig parseSmartSelectionConfig(JSONObject json)
        throws JSONException {
        SmartSelectionConfig config = new SmartSelectionConfig();

        if (json.has("enableSmartSelection")) {
            config.enableSmartSelection = json.getBoolean("enableSmartSelection");
        }
        if (json.has("keypointThreshold")) {
            config.keypointThreshold = json.getInt("keypointThreshold");
        }
        if (json.has("maxDetections")) {
            config.maxDetections = json.getInt("maxDetections");
        }
        if (json.has("inputWidth")) {
            config.inputWidth = json.getInt("inputWidth");
        }
        if (json.has("inputHeight")) {
            config.inputHeight = json.getInt("inputHeight");
        }
        if (json.has("inputFormat")) {
            config.inputFormat = (byte) json.getInt("inputFormat");
        } else if (json.has("pixelFormat")) {
            config.inputFormat = (byte) json.getInt("pixelFormat");
        }

        // Parse per-app config if present
        if (json.has("perAppConfig")) {
            JSONArray perAppArray = json.getJSONArray("perAppConfig");
            AppSmartSelectionConfig[] perAppConfigs =
                new AppSmartSelectionConfig[perAppArray.length()];

            for (int i = 0; i < perAppArray.length(); i++) {
                perAppConfigs[i] = parseAppSmartSelectionConfig(perAppArray.getJSONObject(i));
            }

            config.perAppConfig = perAppConfigs;
        }

        return config;
    }

    /**
     * Parse AppSmartSelectionConfig from JSON.
     */
    private static AppSmartSelectionConfig parseAppSmartSelectionConfig(JSONObject json)
        throws JSONException {
        AppSmartSelectionConfig config = new AppSmartSelectionConfig();

        if (json.has("appName")) {
            config.appName = json.getString("appName");
        }
        if (json.has("enabled")) {
            config.enabled = json.getBoolean("enabled");
        }
        if (json.has("selector")) {
            config.selector = parseSelectorConfig(json.getJSONObject("selector"));
        }
        if (json.has("extractor")) {
            config.extractor = parseExtractorConfig(json.getJSONObject("extractor"));
        }
        if (json.has("matcher")) {
            config.matcher = parseMatcherConfig(json.getJSONObject("matcher"));
        }

        return config;
    }

    /**
     * Parse SelectorConfig from JSON.
     */
    private static SelectorConfig parseSelectorConfig(JSONObject json) throws JSONException {
        SelectorConfig config = new SelectorConfig();

        if (json.has("acceptThreshold")) {
            config.acceptThreshold = (float) json.getDouble("acceptThreshold");
        }
        if (json.has("removeThreshold")) {
            config.removeThreshold = (float) json.getDouble("removeThreshold");
        }
        if (json.has("maxSize")) {
            config.maxSize = json.getInt("maxSize");
        }

        return config;
    }

    /**
     * Parse ExtractorConfig from JSON.
     */
    private static ExtractorConfig parseExtractorConfig(JSONObject json) throws JSONException {
        ExtractorConfig config = new ExtractorConfig();

        if (json.has("topK")) {
            config.topK = json.getInt("topK");
        }
        if (json.has("height")) {
            config.height = json.getInt("height");
        }
        if (json.has("detectionThreshold")) {
            config.detectionThreshold = (float) json.getDouble("detectionThreshold");
        }
        if (json.has("isPath")) {
            config.isPath = json.getBoolean("isPath");
        }

        return config;
    }

    /**
     * Parse MatcherConfig from JSON.
     */
    private static MatcherConfig parseMatcherConfig(JSONObject json) throws JSONException {
        MatcherConfig config = new MatcherConfig();

        if (json.has("minCossim")) {
            config.minCossim = (float) json.getDouble("minCossim");
        }

        return config;
    }

    /**
     * Parse delete configuration JSON.
     *
     * @param deleteConfigJson JSON string containing deletion configuration.
     * @return Parsed delete configuration, or null if parsing fails.
     */
    public static DeleteConfig parseDeleteConfig(String deleteConfigJson) {
        if (deleteConfigJson == null || deleteConfigJson.isEmpty()) {
            Log.w(LOG_TAG, "Empty delete config JSON");
            return null;
        }

        try {
            JSONObject json = new JSONObject(deleteConfigJson);
            DeleteConfig config = new DeleteConfig();

            if (json.has("deleteAllCaptures")) {
                config.deleteAllCaptures = json.getBoolean("deleteAllCaptures");
            } else if (json.has("deleteAll")) {
                config.deleteAllCaptures = json.getBoolean("deleteAll");
            }

            if (json.has("deleteSensitiveAppCaptures")) {
                config.deleteSensitiveAppCaptures = json.getBoolean("deleteSensitiveAppCaptures");
            }

            if (json.has("appPackageNames")) {
                JSONArray packageArray = json.getJSONArray("appPackageNames");
                String[] packages = new String[packageArray.length()];

                for (int i = 0; i < packageArray.length(); i++) {
                    packages[i] = packageArray.getString(i);
                }

                config.appPackageNames = packages;
            }

            return config;
        } catch (JSONException e) {
            Log.e(LOG_TAG, "Failed to parse delete config JSON", e);
            return null;
        }
    }

    /**
     * Create CaptureParams from individual parameters.
     *
     * @param appName Application package name
     * @param accessibilityEventType Accessibility event type
     * @param timestampNs Timestamp in nanoseconds
     * @param tag Optional tag for debugging
     * @return AIDL CaptureParams object
     */
    public static CaptureParams createCaptureParams(
        String appName, int accessibilityEventType, long timestampNs, String tag) {
        CaptureParams params = new CaptureParams();
        params.appName = appName;
        params.accessibilityEventType = accessibilityEventType;
        params.timestampNs = timestampNs;
        params.tag = tag;
        return params;
    }
}
