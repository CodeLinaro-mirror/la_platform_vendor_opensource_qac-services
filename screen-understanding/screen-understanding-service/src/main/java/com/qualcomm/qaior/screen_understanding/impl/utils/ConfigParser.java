/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.utils;

import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.HashSet;
import java.util.Iterator;
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
    * Validates ss_config objects against an embedded Schema.
    */
    private static class ConfigValidator {
        /**
         * Field specification - just name and whether it's required.
         */
        private static class FieldSpec {
            final String name;
            final boolean required;
            final FieldType type;
            final StructureSpec nestedSpec; // For objects
            final StructureSpec arrayItemSpec; // For arrays

            enum FieldType {
                SIMPLE,  // Any simple value (string, number, boolean)
                OBJECT,  // Nested object
                ARRAY    // Array of objects
            }

            // Simple field (string, number, boolean, etc.)
            FieldSpec(String name, boolean required) {
                this.name = name;
                this.required = required;
                this.type = FieldType.SIMPLE;
                this.nestedSpec = null;
                this.arrayItemSpec = null;
            }

            // Nested object field
            FieldSpec(String name, boolean required, StructureSpec nestedSpec) {
                this.name = name;
                this.required = required;
                this.type = FieldType.OBJECT;
                this.nestedSpec = nestedSpec;
                this.arrayItemSpec = null;
            }

            // Array field
            static FieldSpec array(String name, boolean required, StructureSpec arrayItemSpec) {
                FieldSpec spec = new FieldSpec(name, required);
                return new FieldSpec(name, required, FieldType.ARRAY, null, arrayItemSpec);
            }

            private FieldSpec(String name, boolean required, FieldType type,
                            StructureSpec nestedSpec, StructureSpec arrayItemSpec) {
                this.name = name;
                this.required = required;
                this.type = type;
                this.nestedSpec = nestedSpec;
                this.arrayItemSpec = arrayItemSpec;
            }
        }

        /**
         * Structure specification defining allowed fields.
         */
        private static class StructureSpec {
            final List<FieldSpec> fields;

            StructureSpec(FieldSpec... fields) {
                this.fields = Arrays.asList(fields);
            }
        }

        // Define the schema for ss_config
        private static final StructureSpec SS_CONFIG_SCHEMA = createSsConfigSchema();

        private static StructureSpec createSsConfigSchema() {
            // Matcher spec
            StructureSpec matcherSpec = new StructureSpec(
                new FieldSpec("min_cossim", true),
                new FieldSpec("mnn_matcher_n_max", true),
                new FieldSpec("mnn_matcher_m_max", true),
                new FieldSpec("mnn_matcher_d", true),
                new FieldSpec("mnn_matcher_pretranspose_desc2", true),
                new FieldSpec("use_spatial_pruning", true),
                new FieldSpec("spatial_pruning_grid_size", true),
                new FieldSpec("spatial_pruning_top_k_per_cell", true),
                new FieldSpec("spatial_dist_tol", true),
                new FieldSpec("min_cluster_size", true),
                new FieldSpec("heatmap_cossim", true),
                new FieldSpec("zero_translation_cossim", true)
            );

            // Extractor spec
            StructureSpec extractorSpec = new StructureSpec(
                new FieldSpec("top_k", true),
                new FieldSpec("detection_threshold", true),
                new FieldSpec("is_path", true),
                new FieldSpec("use_fixed_size", true),
                new FieldSpec("preproc_fixed_height", true),
                new FieldSpec("preproc_fixed_width", true)
            );

            // Queue threshold config
            StructureSpec queueThresholdSpec = new StructureSpec(
                new FieldSpec("enabled", true),
                new FieldSpec("notification_threshold", true),
                new FieldSpec("hysteresis", true)
            );

            // Selector spec
            StructureSpec selectorSpec = new StructureSpec(
                new FieldSpec("accept_threshold", true),
                new FieldSpec("remove_threshold", true),
                new FieldSpec("max_size", true),
                new FieldSpec("pruning_strategy", true),
                new FieldSpec("queue_threshold_config", true, queueThresholdSpec)
            );

            // App config spec (array item)
            StructureSpec appConfigSpec = new StructureSpec(
                new FieldSpec("appName", true),
                new FieldSpec("selector", true, selectorSpec),
                new FieldSpec("extractor", true, extractorSpec),
                new FieldSpec("matcher", true, matcherSpec)
            );

            // Device config spec
            StructureSpec deviceConfigSpec = new StructureSpec(
                new FieldSpec("device", true),
                new FieldSpec("cpu_config_path", true),
                new FieldSpec("npu_config_path", true)
            );

            // Root ss_config spec
            return new StructureSpec(
                new FieldSpec("activeuserId", true),
                new FieldSpec("smartSelectionDeviceConfig", true, deviceConfigSpec),
                FieldSpec.array("smartSelectionAppConfig", true, appConfigSpec)
            );
        }

        /**
         * Result of validation containing success status and error details.
         */
        static class ValidationResult {
            private final boolean isValid;
            private final List<String> errors;
            private final String summary;

            private ValidationResult(boolean isValid, List<String> errors) {
                this.isValid = isValid;
                this.errors = errors;
                this.summary = buildSummary(errors);
            }

            static ValidationResult success() {
                return new ValidationResult(true, new ArrayList<>());
            }

            static ValidationResult failure(List<String> errors) {
                return new ValidationResult(false, errors);
            }

            boolean isValid() {
                return isValid;
            }

            List<String> getErrors() {
                return new ArrayList<>(errors);
            }

            String getSummary() {
                return summary;
            }

            private String buildSummary(List<String> errors) {
                if (errors.isEmpty()) {
                    return "Validation successful";
                }
                StringBuilder sb = new StringBuilder();
                sb.append(errors.size()).append(" validation error(s):\n");
                for (int i = 0; i < errors.size(); i++) {
                    sb.append("  ").append(i + 1).append(". ").append(errors.get(i));
                    if (i < errors.size() - 1) {
                        sb.append("\n");
                    }
                }
                return sb.toString();
            }
        }

        /**
         * Validate ss_config object against the embedded schema.
         *
         * @param ssConfig The ss_config JSON object to validate
         * @return ValidationResult containing validation status and errors
         */
        static ValidationResult validate(JSONObject ssConfig) {
            if (ssConfig == null) {
                return ValidationResult.failure(Arrays.asList("ss_config is null"));
            }

            List<String> errors = new ArrayList<>();
            validateStructure(ssConfig, SS_CONFIG_SCHEMA, "", errors);

            if (errors.isEmpty()) {
                Log.d(LOG_TAG, "ss_config validation successful");
                return ValidationResult.success();
            } else {
                Log.w(LOG_TAG, "ss_config validation failed: " + errors.size() + " error(s)");
                return ValidationResult.failure(errors);
            }
        }

        /**
         * Recursively validate a JSON object against a structure spec.
         */
        private static void validateStructure(JSONObject obj, StructureSpec spec,
                                            String path, List<String> errors) {
            // Build set of allowed field names
            Set<String> allowedFields = new HashSet<>();
            for (FieldSpec field : spec.fields) {
                allowedFields.add(field.name);
            }

            // Check for unknown fields
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!allowedFields.contains(key)) {
                    errors.add(buildPath(path, key) + ": Unknown field");
                }
            }

            // Validate each field in spec
            for (FieldSpec field : spec.fields) {
                String fieldPath = buildPath(path, field.name);

                // Check if field exists
                if (!obj.has(field.name) || obj.isNull(field.name)) {
                    if (field.required) {
                        errors.add(fieldPath + ": Missing required field");
                    }
                    continue;
                }

                // Validate based on type
                switch (field.type) {
                    case SIMPLE:
                        // No type checking - just presence check (already done above)
                        break;

                    case OBJECT:
                        JSONObject nestedObj = obj.optJSONObject(field.name);
                        if (nestedObj == null) {
                            errors.add(fieldPath + ": Must be an object");
                        } else if (field.nestedSpec != null) {
                            validateStructure(nestedObj, field.nestedSpec, fieldPath, errors);
                        }
                        break;

                    case ARRAY:
                        JSONArray array = obj.optJSONArray(field.name);
                        if (array == null) {
                            errors.add(fieldPath + ": Must be an array");
                        } else {
                            if (array.length() == 0) {
                                errors.add(fieldPath + ": Array must not be empty");
                            }
                            if (field.arrayItemSpec != null) {
                                for (int i = 0; i < array.length(); i++) {
                                    JSONObject item = array.optJSONObject(i);
                                    if (item == null) {
                                        errors.add(fieldPath + "[" + i + "]: Array item must be an object");
                                    } else {
                                        validateStructure(item, field.arrayItemSpec,
                                                        fieldPath + "[" + i + "]", errors);
                                    }
                                }
                            }
                        }
                        break;
                }
            }
        }

        private static String buildPath(String parent, String child) {
            return parent.isEmpty() ? child : parent + "." + child;
        }
    }

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

            if (json.has("algoConfigBlob")) {
                try {
                    Object algoConfigObj = json.get("algoConfigBlob");

                    if (algoConfigObj instanceof JSONObject) {
                        JSONObject algoConfig = (JSONObject) algoConfigObj;
                        String algoJsonString = algoConfig.toString();
                        config.algoConfigBlob = algoJsonString.getBytes(StandardCharsets.UTF_8);
                    } else if (algoConfigObj instanceof String) {
                        String algoJsonString = (String) algoConfigObj;
                        config.algoConfigBlob = algoJsonString.getBytes(StandardCharsets.UTF_8);
                    } else {
                        Log.w(LOG_TAG, "algoConfigBlob has unexpected type: " + algoConfigObj.getClass().getName());
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Failed to parse algoConfigBlob, setting to null", e);
                    config.algoConfigBlob = null;
                }
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

    // 

    /**
     * Parse CaptureConfig to JSON.
     *
     * @param config capture configuration.
     * @return JSON string, or null if parsing fails.
     */
    public static String parseCaptureConfig(vendor.qti.qaior.screen_understanding.CaptureConfig config) {
        if (config == null) {
            Log.w(LOG_TAG, "Invalid CaptureConfig");
            return null;
        }

        try {
            JSONObject json = new JSONObject();

            json.put("width", config.width);
            json.put("height", config.height);
            json.put("format", (int) config.format);
            json.put("framerate", config.framerate);
            json.put("userId", config.userId);

            if (config.appList != null && config.appList.length > 0) {
                JSONArray appListArray = new JSONArray();
                for (vendor.qti.qaior.screen_understanding.AppInfo appInfo : config.appList) {
                    JSONObject appObj = new JSONObject();
                    appObj.put("packageName", appInfo.packageName);
                    appObj.put("isSensitive", appInfo.isSensitive);
                    appListArray.put(appObj);
                }
                json.put("appList", appListArray);
            }

            // Parse optional smart selection config
            if (config.smartConfig != null) {
                json.put("smartConfig", parseSmartSelectionConfigJson(config.smartConfig));
            }

            if (config.algoConfigBlob != null && config.algoConfigBlob.length > 0) {
                try {
                    // Convert byte array back to JSON string
                    String algoJsonString = new String(config.algoConfigBlob, StandardCharsets.UTF_8);
                    // Parse as JSON object to validate and embed
                    JSONObject algoConfigJson = new JSONObject(algoJsonString);
                    // Validate before saving
                    ConfigValidator.ValidationResult validationResult =
                        ConfigValidator.validate(algoConfigJson);

                    if (!validationResult.isValid()) {
                        Log.e(LOG_TAG, "ss_config validation failed during serialization: " +
                            validationResult.getSummary());
                    } else {
                        json.put("algoConfigBlob", algoConfigJson);
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Failed to parse algoConfigBlob, skipping", e);
                }
            }

            return json.toString();
        } catch (JSONException e) {
            Log.e(LOG_TAG, "Failed to parse config JSON", e);
            return null;
        }
    }

    /**
     * Parse JSON from SmartSelectionConfig.
     */
    private static JSONObject parseSmartSelectionConfigJson(vendor.qti.qaior.screen_understanding.SmartSelectionConfig config)
        throws JSONException {
        JSONObject json = new JSONObject();

        json.put("enableSmartSelection", config.enableSmartSelection);
        json.put("keypointThreshold", config.keypointThreshold);
        json.put("maxDetections", config.maxDetections);
        json.put("inputWidth", config.inputWidth);
        json.put("inputHeight", config.inputHeight);
        
        // Convert PixelFormat enum to integer
        json.put("inputFormat", (int) config.inputFormat);

        // Add per-app config if present
        if (config.perAppConfig != null && config.perAppConfig.length > 0) {
            JSONArray perAppArray = new JSONArray();
            for (vendor.qti.qaior.screen_understanding.AppSmartSelectionConfig appConfig : config.perAppConfig) {
                perAppArray.put(parseAppSmartSelectionConfigJson(appConfig));
            }
            json.put("perAppConfig", perAppArray);
        }

        return json;
    }

    /**
     * Parse JSON from AppSmartSelectionConfig.
     */
    private static JSONObject parseAppSmartSelectionConfigJson(vendor.qti.qaior.screen_understanding.AppSmartSelectionConfig config)
        throws JSONException {
        JSONObject json = new JSONObject();

        json.put("appName", config.appName);
        json.put("enabled", config.enabled);
        json.put("selector", parseSelectorConfigJson(config.selector));
        json.put("extractor", parseExtractorConfigJson(config.extractor));
        json.put("matcher", parseMatcherConfigJson(config.matcher));

        return json;
    }

    /**
     * Parse JSON from SelectorConfig.
     */
    private static JSONObject parseSelectorConfigJson(vendor.qti.qaior.screen_understanding.SelectorConfig config) throws JSONException {
        JSONObject json = new JSONObject();       

        json.put("acceptThreshold", config.acceptThreshold);
        json.put("removeThreshold", config.removeThreshold);
        json.put("maxSize", config.maxSize);

        return json;
    }

    /**
     * Parse JSON from ExtractorConfig.
     */
    private static JSONObject parseExtractorConfigJson(vendor.qti.qaior.screen_understanding.ExtractorConfig config) throws JSONException {
        JSONObject json = new JSONObject();

        json.put("topK", config.topK);
        json.put("height", config.height);
        json.put("detectionThreshold", config.detectionThreshold);
        json.put("isPath", config.isPath);
        
        return json;
    }

    /**
     * Parse JSON from MatcherConfig.
     */
    private static JSONObject parseMatcherConfigJson(vendor.qti.qaior.screen_understanding.MatcherConfig config) throws JSONException {
        JSONObject json = new JSONObject();

        json.put("minCossim", config.minCossim);
        
        return json;
    }

    /**
     * Parse JSON from delete configuration.
     *
     * @param deleteConfig deletion configuration.
     * @return JSON string, or null if parsing fails.
     */
    public static String parseDeleteConfig(vendor.qti.qaior.screen_understanding.DeleteConfig deleteConfig) {
        if (deleteConfig == null) {
            Log.w(LOG_TAG, "Empty delete config");
            return null;
        }

        try {
            JSONObject json = new JSONObject();

            json.put("deleteAllCaptures", deleteConfig.deleteAllCaptures);
            json.put("deleteSensitiveAppCaptures", deleteConfig.deleteSensitiveAppCaptures);
            

            if (deleteConfig.appPackageNames !=  null && deleteConfig.appPackageNames.length > 0) {
                JSONArray packageArray = new JSONArray();

                for (String packageName : deleteConfig.appPackageNames) {
                    packageArray.put(packageName);
                }

                json.put("appPackageNames", packageArray);
            }

            return json.toString();
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
