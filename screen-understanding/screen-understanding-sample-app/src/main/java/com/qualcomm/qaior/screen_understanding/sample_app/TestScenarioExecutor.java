/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.sample_app;

import android.os.RemoteException;
import android.util.Log;
import vendor.qti.qaior.screen_understanding.IScreenUnderstandingService;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;

/**
 * Executes test scenarios by calling the AIDL service methods.
 */
public class TestScenarioExecutor {
    private static final String TAG = "ScreenUnderstandingScenarioExec";

    private final IScreenUnderstandingService service;
    private final SampleConfig sampleConfig;

    public TestScenarioExecutor(IScreenUnderstandingService service, SampleConfig sampleConfig) {
        this.service = service;
        this.sampleConfig = sampleConfig;
    }

    public String run(TestScenario scenario) {
        StringBuilder sb = new StringBuilder();
        String sessionId = null;
        sb.append("Running: ").append(scenario.getName()).append("\n");
        if (scenario.getDescription() != null && !scenario.getDescription().isEmpty()) {
            sb.append("Desc: ").append(scenario.getDescription()).append("\n");
        }
        List<TestScenario.Operation> ops = scenario.getOperations();
        for (int i = 0; i < ops.size(); i++) {
            TestScenario.Operation op = ops.get(i);
            try {
                switch (op.op.toLowerCase()) {
                    case "start":
                        sessionId = doStart();
                        sb.append(step(i, "start", "session=" + sessionId, true));
                        break;
                    case "update":
                        boolean upd = doUpdate(sessionId);
                        sb.append(step(i, "update", "session=" + sessionId, upd));
                        break;
                    case "stop":
                        boolean st = doStop();
                        sb.append(step(i, "stop", "", st));
                        sessionId = null;
                        break;
                    case "delete":
                        boolean del = doDelete(sessionId);
                        sb.append(step(i, "delete", "session=" + sessionId, del));
                        sessionId = null;
                        break;
                    case "wait":
                        sleep(op.waitMs);
                        sb.append(step(i, "wait", op.waitMs + "ms", true));
                        break;
                    default:
                        sb.append(step(i, op.op, "unsupported op", false));
                        break;
                }
            } catch (Exception e) {
                Log.w(TAG, "Op failed: " + op.op, e);
                sb.append(step(i, op.op, "error: " + e.getMessage(), false));
            }
        }
        return sb.toString();
    }

    private String step(int idx, String op, String extra, boolean success) {
        return String.format("#%d %s %s [%s]%n", idx + 1, op, extra, success ? "OK" : "FAIL");
    }

    private String doStart() throws Exception {
        String sessionId = "scenario_" + UUID.randomUUID();
        JSONObject cfg = cloneJson(sampleConfig.getStartConfig());
        cfg.put("sessionId", sessionId);
        service.startCapture(cfg.toString());
        return sessionId;
    }

    private boolean doUpdate(String sessionId) throws Exception {
        if (sessionId == null) {
            // intentionally exercise error path
            sessionId = "missing_session";
        }
        JSONObject cfg = cloneJson(sampleConfig.getUpdateConfig());
        cfg.put("sessionId", sessionId);
        try {
            service.updateCaptureConfig(cfg.toString());
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "updateCaptureConfig failed", e);
            return false;
        }
    }

    private boolean doStop() {
        try {
            service.stopCapture();
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "stopCapture failed", e);
            return false;
        }
    }

    private boolean doDelete(String sessionId) throws Exception {
        if (sessionId == null) {
            sessionId = "missing_session";
        }
        JSONObject cfg = cloneJson(sampleConfig.getDeleteConfig());
        cfg.put("sessionId", sessionId);
        try {
            service.deleteCapture(cfg.toString());
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "deleteCapture failed", e);
            return false;
        }
    }

    private void sleep(int ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private JSONObject cloneJson(JSONObject src) throws Exception {
        return new JSONObject(src.toString());
    }
}
