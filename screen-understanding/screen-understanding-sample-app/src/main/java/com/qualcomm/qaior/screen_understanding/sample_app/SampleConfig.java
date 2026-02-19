/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.  
 * SPDX-License-Identifier: BSD-3-Clause-Clear 
 */

package com.qualcomm.qaior.screen_understanding.sample_app;

import org.json.JSONObject;

/**
 * Holds parsed sample-app configuration sections.
 */
public class SampleConfig {
    private final JSONObject startConfig;
    private final JSONObject updateConfig;
    private final JSONObject deleteConfig;

    public SampleConfig(JSONObject startConfig, JSONObject updateConfig, JSONObject deleteConfig) {
        this.startConfig = startConfig;
        this.updateConfig = updateConfig;
        this.deleteConfig = deleteConfig;
    }

    public JSONObject getStartConfig() {
        return startConfig;
    }

    public JSONObject getUpdateConfig() {
        return updateConfig;
    }

    public JSONObject getDeleteConfig() {
        return deleteConfig;
    }
}
