/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.sample_app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import androidx.core.content.ContextCompat;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Loads sample app configuration from assets/config.json, with safe defaults.
 */
public class ConfigLoader {
    private static final String TAG = "ScreenUnderstandingConfig";
    private static final String ASSET_FILE = "config.json";

    /**
     * Result of loading configuration from file.
     */
    public static class LoadResult {
        private final boolean success;
        private final SampleConfig config;
        private final String errorMessage;

        private LoadResult(boolean success, SampleConfig config, String errorMessage) {
            this.success = success;
            this.config = config;
            this.errorMessage = errorMessage;
        }

        public static LoadResult success(SampleConfig config) {
            return new LoadResult(true, config, null);
        }

        public static LoadResult failure(SampleConfig fallbackConfig, String errorMessage) {
            return new LoadResult(false, fallbackConfig, errorMessage);
        }

        public boolean isSuccess() {
            return success;
        }

        public SampleConfig getConfig() {
            return config;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    /**
     * Load configuration from assets. Falls back to built-in defaults on error.
     */
    public static SampleConfig load(Context context) {
        try {
            String json = readAsset(context, ASSET_FILE);
            if (json != null) {
                JSONObject root = new JSONObject(json);
                JSONObject startCfg = root.optJSONObject("startConfig");
                JSONObject updateCfg = root.optJSONObject("updateConfig");
                JSONObject deleteCfg = root.optJSONObject("deleteConfig");
                if (startCfg != null && updateCfg != null && deleteCfg != null) {
                    return new SampleConfig(startCfg, updateCfg, deleteCfg);
                }
                Log.w(TAG, "config.json missing sections, falling back to defaults");
            } else {
                Log.w(TAG, "config.json not found, falling back to defaults");
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load config.json, using defaults", e);
        }
        return buildDefaultConfig();
    }

    /**
     * Load configuration from a custom file path with security validation.
     * @param context Android context.
     * @param filePath Path to the JSON configuration file.
     * @return LoadResult containing success status, config, and error message.
     */
    public static LoadResult loadFromFile(Context context, String filePath) {
        if (filePath == null || filePath.trim().isEmpty()) {
            String error = "Empty file path provided";
            Log.w(TAG, error);
            return LoadResult.failure(load(context), error);
        }

        // Check for READ_EXTERNAL_STORAGE permission on Android 6.0+ but below Android 13
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                String error = "READ_EXTERNAL_STORAGE permission not granted";
                Log.e(TAG, error);
                return LoadResult.failure(load(context), error);
            }
        }

        try {
            File file = new File(filePath.trim());
            File allowedDir = context.getExternalFilesDir(null);
            if (allowedDir == null) {
                String error = "External files directory not available";
                Log.e(TAG, error);
                return LoadResult.failure(load(context), error);
            }

            // Get canonical paths for security validation
            String canonicalAllowedPath;
            String canonicalFilePath;
            try {
                canonicalAllowedPath = allowedDir.getCanonicalPath();
                canonicalFilePath = file.getCanonicalPath();
            } catch (IOException e) {
                String error = "Failed to resolve canonical file path: " + e.getMessage();
                Log.e(TAG, error, e);
                return LoadResult.failure(load(context), error);
            }

            // Check for symbolic links - validate entire path hierarchy
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    java.nio.file.Path nioPath = file.toPath();
                    if (java.nio.file.Files.isSymbolicLink(nioPath)) {
                        String error = "Security: Symbolic links are not allowed";
                        Log.e(TAG, error);
                        return LoadResult.failure(load(context), error);
                    }
                } catch (Exception e) {
                    String error = "Failed to check for symbolic links: " + e.getMessage();
                    Log.e(TAG, error, e);
                    return LoadResult.failure(load(context), error);
                }
            } else {
                // Fallback: Check entire path hierarchy for symbolic links
                File current = file;
                while (current != null) {
                    try {
                        if (!current.getCanonicalPath().equals(current.getAbsolutePath())) {
                            String error = "Security: Symbolic links are not allowed in path";
                            Log.e(TAG, error);
                            return LoadResult.failure(load(context), error);
                        }
                        current = current.getParentFile();
                        // Stop when we reach the allowed directory
                        if (current != null
                            && current.getCanonicalPath().equals(canonicalAllowedPath)) {
                            break;
                        }
                    } catch (IOException e) {
                        String error = "Failed to validate path component: " + e.getMessage();
                        Log.e(TAG, error, e);
                        return LoadResult.failure(load(context), error);
                    }
                }
            }

            // Validate file is within allowed directory using canonical paths
            if (!canonicalFilePath.startsWith(canonicalAllowedPath + File.separator)
                && !canonicalFilePath.equals(canonicalAllowedPath)) {
                String error = "Security: Config file path outside allowed directory: " + filePath;
                Log.e(TAG, error);
                return LoadResult.failure(load(context), error);
            }

            // Open file once and perform all operations on the descriptor to prevent TOCTOU issues
            String json;
            try (FileInputStream fis = new FileInputStream(file)) {
                if (fis.getChannel().size() == 0) {
                    String error = "Config file is empty: " + filePath;
                    Log.w(TAG, error);
                    return LoadResult.failure(load(context), error);
                }
                json = readFromStream(fis);
            } catch (IOException e) {
                String error = "Config file not readable: " + e.getMessage();
                Log.w(TAG, error, e);
                return LoadResult.failure(load(context), error);
            }

            JSONObject root = new JSONObject(json);
            JSONObject startCfg = root.optJSONObject("startConfig");
            JSONObject updateCfg = root.optJSONObject("updateConfig");
            JSONObject deleteCfg = root.optJSONObject("deleteConfig");
            if (startCfg != null && updateCfg != null && deleteCfg != null) {
                Log.i(TAG, "Successfully loaded config from: " + filePath);
                return LoadResult.success(new SampleConfig(startCfg, updateCfg, deleteCfg));
            } else {
                String error =
                    "Config file missing required sections (startConfig, updateConfig, deleteConfig)";
                Log.w(TAG, error + ": " + filePath);
                return LoadResult.failure(load(context), error);
            }
        } catch (org.json.JSONException e) {
            String error = "Failed to parse JSON from file: " + e.getMessage();
            Log.e(TAG, error, e);
            return LoadResult.failure(load(context), error);
        } catch (Exception e) {
            String error = "Failed to load config from file: " + e.getMessage();
            Log.e(TAG, error, e);
            return LoadResult.failure(load(context), error);
        }
    }

    private static String readAsset(Context context, String fileName) {
        try (InputStream is = context.getAssets().open(fileName)) {
            return readFromStream(is);
        } catch (IOException e) {
            Log.w(TAG, "Unable to read asset: " + fileName, e);
            return null;
        }
    }

    private static String readFromStream(InputStream is) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder(16384); // 16KB initial capacity
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    /**
     * Build the previous hardcoded defaults.
     */
    private static SampleConfig buildDefaultConfig() {
        try {
            // startConfig defaults
            JSONObject startCfg = new JSONObject();
            startCfg.put("width", 1920);
            startCfg.put("height", 1080);
            startCfg.put("format", 1);
            startCfg.put("framerate", 30);
            startCfg.put("activeUserId", 0);

            JSONArray appList = new JSONArray();
            JSONObject app1 = new JSONObject();
            app1.put("packageName", "com.example.app1");
            app1.put("isSensitive", false);
            appList.put(app1);

            JSONObject app2 = new JSONObject();
            app2.put("packageName", "com.example.app2");
            app2.put("isSensitive", true);
            appList.put(app2);

            startCfg.put("appList", appList);

            JSONObject smartConfig = new JSONObject();
            smartConfig.put("enableSmartSelection", true);
            smartConfig.put("keypointThreshold", 12);
            smartConfig.put("maxDetections", 500);
            smartConfig.put("inputWidth", 288);
            smartConfig.put("inputHeight", 640);
            smartConfig.put("pixelFormat", 1);
            startCfg.put("smartConfig", smartConfig);

            // updateConfig defaults
            JSONObject updateCfg = new JSONObject();
            updateCfg.put("enabled", false);
            JSONArray updateApps = new JSONArray();
            JSONObject updateApp = new JSONObject();
            updateApp.put("packageName", "com.example.app1");
            updateApp.put("isSensitive", true);
            updateApps.put(updateApp);
            updateCfg.put("appList", updateApps);

            // deleteConfig defaults
            JSONObject deleteCfg = new JSONObject();
            deleteCfg.put("deleteAll", false);

            return new SampleConfig(startCfg, updateCfg, deleteCfg);
        } catch (Exception e) {
            Log.e(TAG, "Failed to build default config", e);
            // In extreme failure, return minimal empty configs to avoid crash
            return new SampleConfig(new JSONObject(), new JSONObject(), new JSONObject());
        }
    }
}
