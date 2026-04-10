/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.sample_app;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class TestScenarioLoader {
    private static final String TAG = "ScreenUnderstandingScenario";
    private static final String ASSET_FILE = "test_scenarios.json";

    public static List<TestScenario> load(Context context) {
        try {
            String json = readAsset(context, ASSET_FILE);
            if (json != null) {
                return parse(json);
            }
            Log.w(TAG, "test_scenarios.json not found, using defaults");
        } catch (Exception e) {
            Log.w(TAG, "Failed to load test_scenarios.json, using defaults", e);
        }
        return buildDefault();
    }

    private static String readAsset(Context context, String fileName) {
        try (InputStream is = context.getAssets().open(fileName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "Unable to read asset: " + fileName, e);
            return null;
        }
    }

    private static List<TestScenario> parse(String json) throws Exception {
        List<TestScenario> list = new ArrayList<>();
        JSONArray arr = new JSONArray(json);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String name = o.optString("name", "Scenario " + (i + 1));
            String desc = o.optString("description", "");
            List<TestScenario.Operation> ops = new ArrayList<>();
            JSONArray opArr = o.optJSONArray("operations");
            if (opArr != null) {
                for (int j = 0; j < opArr.length(); j++) {
                    JSONObject opObj = opArr.getJSONObject(j);
                    TestScenario.Operation op = new TestScenario.Operation();
                    op.op = opObj.optString("op", "");
                    op.waitMs = opObj.optInt("waitMs", 0);
                    ops.add(op);
                }
            }
            list.add(new TestScenario(name, desc, ops));
        }
        return list;
    }

    private static List<TestScenario> buildDefault() {
        List<TestScenario> list = new ArrayList<>();

        // Scenario 1: start -> stop
        List<TestScenario.Operation> s1 = new ArrayList<>();
        s1.add(op("start"));
        s1.add(op("stop"));
        list.add(new TestScenario("Start and Stop", "Start then stop capture", s1));

        // Scenario 2: start -> update -> stop -> delete
        List<TestScenario.Operation> s2 = new ArrayList<>();
        s2.add(op("start"));
        s2.add(op("update"));
        s2.add(op("stop"));
        s2.add(op("delete"));
        list.add(new TestScenario("Full Flow", "Start, update, stop, delete", s2));

        // Scenario 3: negative update without start
        List<TestScenario.Operation> s3 = new ArrayList<>();
        s3.add(op("update"));
        list.add(
            new TestScenario("Update without Start", "Negative path: update before start", s3));

        return list;
    }

    private static TestScenario.Operation op(String name) {
        TestScenario.Operation op = new TestScenario.Operation();
        op.op = name;
        op.waitMs = 0;
        return op;
    }
}
