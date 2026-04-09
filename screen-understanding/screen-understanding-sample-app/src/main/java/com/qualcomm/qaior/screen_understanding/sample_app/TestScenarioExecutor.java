/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.sample_app;

import android.os.RemoteException;
import android.util.Log;
import vendor.qti.qaior.screen_understanding.IScreenUnderstandingService;
import vendor.qti.qaior.screen_understanding.IScreenUnderstandingCallback;
import vendor.qti.qaior.screen_understanding.CaptureConfig;
import vendor.qti.qaior.screen_understanding.DeleteConfig;
import vendor.qti.qaior.screen_understanding.Status;
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
    private String sId = null;
    
    private final IScreenUnderstandingCallback.Stub callback = new IScreenUnderstandingCallback.Stub() {
        @Override
        public void onStart(long sessionId) throws RemoteException {
            sId = String.valueOf(sessionId);          
            Log.i(TAG, "Callback: onStart - sessionId=" + sessionId);
        }

        @Override
        public void onError(long sessionId, Status status) throws RemoteException {
            String errorMsg = "Error in session " + sessionId + 
                            ": " + status.code + 
                            (status.message != null ? " - " + status.message : "");
            Log.e(TAG, "Callback: onError - " + errorMsg);
        }

        @Override
        public void onStopped(long sessionId) throws RemoteException {
            sId = null;
            Log.i(TAG, "Callback: onStopped - sessionId=" + sessionId);
        }

        @Override
        public void onConfigUpdated(long sessionId) throws RemoteException {
            Log.i(TAG, "Callback: onConfigUpdated - sessionId=" + sessionId);
        }

        @Override
        public int getInterfaceVersion() {
            return IScreenUnderstandingCallback.VERSION;
        }

        @Override
        public String getInterfaceHash() {
            return IScreenUnderstandingCallback.HASH;
        }
    };

    public TestScenarioExecutor(IScreenUnderstandingService service, SampleConfig sampleConfig) {
        this.service = service;
        this.sampleConfig = sampleConfig;
    }

    public String run(TestScenario scenario) {
        StringBuilder sb = new StringBuilder();
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
                        boolean start = doStart();
                        sb.append(step(i, "start", "session=" + sId, true));
                        break;
                    case "update":
                        boolean upd = doUpdate(sId);
                        sb.append(step(i, "update", "session=" + sId, upd));
                        break;
                    case "stop":
                        boolean st = doStop();
                        sb.append(step(i, "stop", "", st));
                        break;
                    case "delete":
                        boolean del = doDelete(sId);
                        sb.append(step(i, "delete", "session=" + sId, del));
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

    private boolean doStart() throws Exception {
        CaptureConfig cfg = sampleConfig.getStartConfig();
        try{
            service.startCapture(cfg, callback);
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "startCapture failed", e);
            return false;
        }
        
    }

    private boolean doUpdate(String sessionId) throws Exception {
        if (sessionId == null) {
            // intentionally exercise error path
            sessionId = "missing_session";
        }
        CaptureConfig cfg = sampleConfig.getUpdateConfig();
        try {
            service.updateCaptureConfig(Long.parseLong(sessionId), cfg);
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "updateCaptureConfig failed", e);
            return false;
        }
    }

    private boolean doStop() {
        try {
            service.stopCapture(Long.parseLong(sId));
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
        DeleteConfig cfg = sampleConfig.getDeleteConfig();
        try {
            service.deleteCapture(Long.parseLong(sessionId), cfg);
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
}
