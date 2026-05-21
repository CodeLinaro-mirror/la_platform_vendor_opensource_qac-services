/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.sample_app;

import org.json.JSONObject;
import org.json.JSONException;
import org.json.JSONArray;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import vendor.qti.qaior.screen_understanding.AppInfo;
import vendor.qti.qaior.screen_understanding.AppSmartSelectionConfig;
import vendor.qti.qaior.screen_understanding.CaptureConfig;
import vendor.qti.qaior.screen_understanding.DeleteConfig;
import vendor.qti.qaior.screen_understanding.ExtractorConfig;
import vendor.qti.qaior.screen_understanding.MatcherConfig;
import vendor.qti.qaior.screen_understanding.PixelFormat;
import vendor.qti.qaior.screen_understanding.SelectorConfig;
import vendor.qti.qaior.screen_understanding.SmartSelectionConfig;

/**
 * Holds parsed sample-app configuration sections.
 */
public class SampleConfig {
    private final JSONObject startConfig;
    private final JSONObject updateConfig;
    private final JSONObject deleteConfig;
    private static final String LOG_TAG = "ScreenUnderstandingSample.SampleConfig";

    public SampleConfig(JSONObject startConfig, JSONObject updateConfig, JSONObject deleteConfig) {
        this.startConfig = startConfig;
        this.updateConfig = updateConfig;
        this.deleteConfig = deleteConfig;
    }

    public CaptureConfig getStartConfig() {
        return parseConfig(startConfig);
    }

    public CaptureConfig getUpdateConfig() {
        return parseConfig(updateConfig);
    }

    public DeleteConfig getDeleteConfig() {
        return parseDeleteConfig(deleteConfig);
    }

    public JSONObject getStartConfigJson() {
        return startConfig;
    }

    public JSONObject getUpdateConfigJson() {
        return updateConfig;
    }

    public JSONObject getDeleteConfigJson() {
        return deleteConfig;
    }

    /**
     * Parse capture configuration JSON.
     *
     * @param configJson JSON object containing capture configuration.
     * @return Parsed configuration object, or null if parsing fails.
     */
    private static CaptureConfig parseConfig(JSONObject json) {
        if (json == null) {
            Log.w(LOG_TAG, "Empty config JSON");
            return null;
        }

        try {
            CaptureConfig config = new CaptureConfig();

            config.width = json.optInt("width", 1440);
            config.height = json.optInt("height", 3200);
            config.format = (byte) json.optInt("format", 1);
            config.framerate = json.optInt("framerate", 30);
            config.userId = json.optInt("activeUserId", 0);
            
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

            JSONObject smartConfigObj = json.optJSONObject("ss_config");
            if (smartConfigObj != null) {
                String jsonString = smartConfigObj.toString();
                config.algoConfigBlob = jsonString.getBytes(StandardCharsets.UTF_8);
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

        config.enableSmartSelection = json.optBoolean("enableSmartSelection", false);
        config.keypointThreshold = json.optInt("keypointThreshold", 12);
        config.maxDetections = json.optInt("maxDetections", 500);
        config.inputWidth = json.optInt("inputWidth", 288);
        config.inputHeight = json.optInt("inputHeight", 640);
        config.inputFormat = (byte) json.optInt("inputFormat", 1);

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
     * @param json JSON Object containing deletion configuration.
     * @return Parsed delete configuration, or null if parsing fails.
     */
    private static DeleteConfig parseDeleteConfig(JSONObject json) {
        if (json == null) {
            Log.w(LOG_TAG, "Empty delete config JSON");
            return null;
        }

        try {
            DeleteConfig config = new DeleteConfig();

            if (json.has("deleteAllCaptures")) {
                config.deleteAllCaptures = json.getBoolean("deleteAllCaptures");
            } else if (json.has("deleteAll")) {
                config.deleteAllCaptures = json.getBoolean("deleteAll");
            }

            config.deleteSensitiveAppCaptures = json.optBoolean("deleteSensitiveAppCaptures", false);

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

}
