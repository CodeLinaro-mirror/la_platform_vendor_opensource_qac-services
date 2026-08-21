/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.sample_app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.os.Binder;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import vendor.qti.qaior.screen_understanding.IScreenUnderstandingService;
import com.qualcomm.qaior.screen_understanding.sample_app.ConfigLoader;
import com.qualcomm.qaior.screen_understanding.sample_app.SampleConfig;
import com.qualcomm.qaior.screen_understanding.sample_app.TestScenario;
import com.qualcomm.qaior.screen_understanding.sample_app.TestScenarioExecutor;
import com.qualcomm.qaior.screen_understanding.sample_app.TestScenarioLoader;
import vendor.qti.qaior.screen_understanding.CaptureConfig;
import vendor.qti.qaior.screen_understanding.DeleteConfig;
import vendor.qti.qaior.screen_understanding.IScreenUnderstandingCallback;
import vendor.qti.qaior.screen_understanding.Status;
import vendor.qti.qaior.screen_understanding.ErrorCode;
import java.util.List;
import org.json.JSONObject;

public class ScreenUnderstandingSampleActivity extends AppCompatActivity {
    private static final String TAG = "ScreenUnderstandingSample";
    private static final String SERVICE_PACKAGE = "com.qualcomm.qaior.screen_understanding";
    private static final String SERVICE_ACTION =
        "vendor.qti.qaior.screen_understanding.IScreenUnderstandingService";
    private static final String SCREEN_CAPTURE_PERMISSION =
        "com.qualcomm.qaior.permission.SCREEN_CAPTURE";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int STORAGE_PERMISSION_REQUEST_CODE = 101;

    private IScreenUnderstandingService service;
    private ServiceConnection mDataServiceConnection = null;
    private CaptureDataService.LocalBinder mDataServiceBinder = null;
    private boolean isBound = false;
    private boolean isDataServiceBound = false;
    private String currentSessionId = null;
    private TextView statusText;
    private Button btnBind;
    private Button btnBindData;
    private Button btnStartCapture;
    private Button btnStopCapture;
    private Button btnUpdateConfig;
    private Button btnDeleteCapture;
    private Button btnRunScenario;
    private Button btnParseConfig;
    private EditText configPathInput;
    private Spinner scenarioSpinner;
    private TextView scenarioResult;
    private SwitchCompat switchDoCompression;
    private SwitchCompat switchDumpScreenshot;

    private SampleConfig sampleConfig;
    private List<TestScenario> scenarios;
    private boolean pendingConfigParse = false;

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "Service connected: " + name);
            service = IScreenUnderstandingService.Stub.asInterface(binder);
            isBound = true;
            updateUI();
            statusText.setText("Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(TAG, "Service disconnected: " + name);
            service = null;
            isBound = false;
            currentSessionId = null;
            updateUI();
            statusText.setText("Service disconnected");
        }
    };

    private CaptureDataClient.ConnectionListener mDataClientConnectionListener =
        new CaptureDataClient.ConnectionListener() {
            @Override
            public void onServiceConnected(long sessionId) {
                if (sessionId == -1) {
                    statusText.setText("Data service connection failed");
                } else {
                    statusText.setText("Data service connected with session: " + sessionId);
                }
            }
        };

    // callback implementation
    private final IScreenUnderstandingCallback.Stub callback = new IScreenUnderstandingCallback.Stub() {
        @Override
        public void onStart(long sessionId) throws RemoteException {
            runOnUiThread(() -> {
                currentSessionId = String.valueOf(sessionId);
                statusText.setText("Capture started - Session ID: " + sessionId);
                updateUI();
            });
            Log.i(TAG, "Callback: onStart - sessionId=" + sessionId);
        }

        @Override
        public void onError(long sessionId, Status status) throws RemoteException {
            runOnUiThread(() -> {
                String errorMsg = "Error in session " + sessionId + 
                                ": " + status.code + 
                                (status.message != null ? " - " + status.message : "");
                statusText.setText(errorMsg);
                Log.e(TAG, "Callback: onError - " + errorMsg);
            });
        }

        @Override
        public void onStopped(long sessionId) throws RemoteException {
            runOnUiThread(() -> {
                currentSessionId = null;
                statusText.setText("Capture stopped - Session ID: " + sessionId);
                updateUI();
            });
            Log.i(TAG, "Callback: onStopped - sessionId=" + sessionId);
        }

        @Override
        public void onConfigUpdated(long sessionId) throws RemoteException {
            runOnUiThread(() -> {
                statusText.setText("Config updated - Session ID: " + sessionId);
            });
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test);

        // Check and request permission
        if (ContextCompat.checkSelfPermission(this, SCREEN_CAPTURE_PERMISSION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, new String[] {SCREEN_CAPTURE_PERMISSION}, PERMISSION_REQUEST_CODE);
        }

        statusText = findViewById(R.id.statusText);
        btnBind = findViewById(R.id.btnBind);
        btnBindData = findViewById(R.id.btnBindData);
        btnStartCapture = findViewById(R.id.btnStartCapture);
        btnStopCapture = findViewById(R.id.btnStopCapture);
        btnUpdateConfig = findViewById(R.id.btnUpdateConfig);
        btnDeleteCapture = findViewById(R.id.btnDeleteCapture);
        btnRunScenario = findViewById(R.id.btnRunScenario);
        btnParseConfig = findViewById(R.id.btnParseConfig);
        configPathInput = findViewById(R.id.configPathInput);
        scenarioSpinner = findViewById(R.id.scenarioSpinner);
        scenarioResult = findViewById(R.id.scenarioResult);
        switchDoCompression = findViewById(R.id.switchDoCompression);
        switchDumpScreenshot = findViewById(R.id.switchDumpScreenshot);

        btnBind.setOnClickListener(v -> bindService());
        btnBindData.setOnClickListener(v -> bindDataService());
        btnStartCapture.setOnClickListener(v -> testStartCapture());
        btnStopCapture.setOnClickListener(v -> testStopCapture());
        btnUpdateConfig.setOnClickListener(v -> testUpdateConfig());
        btnDeleteCapture.setOnClickListener(v -> testDeleteCapture());
        btnRunScenario.setOnClickListener(v -> runSelectedScenario());
        btnParseConfig.setOnClickListener(v -> parseCustomConfig());

        switchDoCompression.setOnCheckedChangeListener(
            (buttonView, isChecked) -> onDoCompressionChanged(isChecked));
        switchDumpScreenshot.setOnCheckedChangeListener(
            (buttonView, isChecked) -> onDumpScreenshotChanged(isChecked));

        // Load configurable parameters (fallback to defaults on error)
        sampleConfig = ConfigLoader.load(getApplicationContext());

        // Load test scenarios
        scenarios = TestScenarioLoader.load(getApplicationContext());
        ArrayAdapter<String> adapter =
            new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, scenarioNames());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        scenarioSpinner.setAdapter(adapter);

        updateUI();
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                statusText.setText("Screen capture permission granted");
            } else {
                statusText.setText(
                    "Screen capture permission denied - app may not function correctly");
            }
        } else if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                statusText.setText("Storage permission granted");
                // Re-attempt the config parsing now that we have permission
                if (pendingConfigParse) {
                    parseCustomConfig();
                }
            } else {
                statusText.setText("Storage permission denied - cannot load external config files");
                pendingConfigParse = false;
            }
        }
    }

    private void bindService() {
        if (isBound) {
            statusText.setText("Service already bound");
            return;
        }

        Intent intent = new Intent(SERVICE_ACTION);
        intent.setPackage(SERVICE_PACKAGE);

        boolean result = bindService(intent, connection, Context.BIND_AUTO_CREATE);
        Log.i(TAG, "bindService result: " + result);

        if (result) {
            statusText.setText("Binding in progress...");
            btnBind.setEnabled(false);
        } else {
            statusText.setText(
                "Binding failed: Service not found. Ensure ScreenUnderstandingService is installed.");
            Log.e(TAG,
                "Failed to bind to service. Check if service package exists: " + SERVICE_PACKAGE);
        }
    }

    private void bindDataService() {
        if (mDataServiceConnection != null) {
            statusText.setText("Data service already bound");
            return;
        }
        Intent dataIntent = new Intent(this, CaptureDataService.class);
        mDataServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                mDataServiceBinder = (CaptureDataService.LocalBinder) binder;
                mDataServiceBinder.setConnectionListener(mDataClientConnectionListener);
                mDataServiceBinder.setDoScreenshotDump(switchDumpScreenshot.isChecked());
                try {
                    mDataServiceBinder.setCompressionEnabled(switchDoCompression.isChecked());
                } catch (Exception e) {
                    Log.e(TAG, "Failed to apply compression pref on bind", e);
                }
                isDataServiceBound = true;
                updateUI();
                statusText.setText("Data service bound");
            }
            @Override
            public void onServiceDisconnected(ComponentName name) {
                mDataServiceBinder = null;
                isDataServiceBound = false;
                updateUI();
            }
        };
        ContextCompat.startForegroundService(this, dataIntent);
        bindService(dataIntent, mDataServiceConnection, Context.BIND_AUTO_CREATE);
    }

    private void onDoCompressionChanged(boolean isChecked) {
        Log.i(TAG, "doCompression changed to: " + isChecked);
        statusText.setText("doCompression: " + (isChecked ? "enabled" : "disabled"));
        if (mDataServiceBinder != null) {
            try {
                mDataServiceBinder.setCompressionEnabled(isChecked);
            } catch (Exception e) {
                statusText.setText("Change Config Error: " + e.getMessage());
            }
        }
    }

    private void onDumpScreenshotChanged(boolean isChecked) {
        Log.i(TAG, "DumpScreenshot changed to: " + isChecked);
        statusText.setText("DumpScreenshot: " + (isChecked ? "enabled" : "disabled"));
        if (mDataServiceBinder != null) {
            mDataServiceBinder.setDoScreenshotDump(isChecked);
        }
    }

    private void testStartCapture() {
        if (!isBound || service == null) {
            statusText.setText("Error: Service not bound");
            return;
        }

        try {
            CaptureConfig config = sampleConfig.getStartConfig();
            if(config == null){
                Log.e(TAG, "Error parsing CaptureConfig." + sampleConfig.getStartConfigJson().toString());
                statusText.setText("startCapture failed. Invalid config");
                return;
            }

            Log.i(TAG, "Calling startCapture with config: " + sampleConfig.getStartConfigJson().toString());
            long startCaptureKPI = SystemClock.elapsedRealtime();
            Log.v(TAG, "Start Capture Invoked at Timestamp: " + startCaptureKPI);
            service.startCapture(config, callback);
        } catch (Exception e) {
            Log.e(TAG, "Error creating config", e);
            statusText.setText("Error creating config: " + e.getMessage());
        }
    }

    private void testStopCapture() {
        if (!isBound || service == null) {
            statusText.setText("Error: Service not bound");
            return;
        }

        if (currentSessionId == null) {
            statusText.setText("Error: No active session. Start capture first.");
            return;
        }

        try {
            Log.i(TAG, "Calling stopCapture");
            service.stopCapture(Long.parseLong(currentSessionId));
            statusText.setText("stopCapture called");
        } catch (Exception e) {
            Log.e(TAG, "Error calling stopCapture", e);
            statusText.setText("Error: " + e.getMessage());
        }
    }

    private void testUpdateConfig() {
        if (!isBound || service == null) {
            statusText.setText("Error: Service not bound");
            return;
        }

        if (currentSessionId == null) {
            statusText.setText("Error: No active session. Start capture first.");
            return;
        }

        try {
            CaptureConfig config = sampleConfig.getUpdateConfig();
            if(config == null){
                Log.e(TAG, "Error parsing CaptureConfig." + sampleConfig.getUpdateConfigJson().toString());
                statusText.setText("updateConfig failed. Invalid config");
                return;
            }

            Log.i(TAG, "Calling updateCaptureConfig with config: " + sampleConfig.getUpdateConfigJson().toString());
            service.updateCaptureConfig(Long.parseLong(currentSessionId), config);
            statusText.setText("updateCaptureConfig called");
        } catch (Exception e) {
            Log.e(TAG, "Error creating config", e);
            statusText.setText("Error creating config: " + e.getMessage());
        }
    }

    private void testDeleteCapture() {
        if (!isBound || service == null) {
            statusText.setText("Error: Service not bound");
            return;
        }

        if (currentSessionId == null) {
            statusText.setText("Error: No active session. Start capture first.");
            return;
        }

        try {
            DeleteConfig deleteConfig = sampleConfig.getDeleteConfig();
            if(deleteConfig == null){
                Log.e(TAG, "Error parsing CaptureConfig." + sampleConfig.getDeleteConfigJson().toString());
                statusText.setText("updateConfig failed. Invalid config");
                return;
            }

            Log.i(TAG, "Calling deleteCapture with config: " + sampleConfig.getDeleteConfigJson().toString());
            service.deleteCapture(Long.parseLong(currentSessionId), deleteConfig);
            statusText.setText("deleteCapture called");
        } catch (RemoteException e) {
            Log.e(TAG, "Error calling deleteCapture", e);
            statusText.setText("Error: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Error creating config", e);
            statusText.setText("Error creating config: " + e.getMessage());
        }
    }

    // ==== Scenario support ====

    private String[] scenarioNames() {
        String[] arr = new String[scenarios.size()];
        for (int i = 0; i < scenarios.size(); i++) {
            arr[i] = scenarios.get(i).getName();
        }
        return arr;
    }

    private void parseCustomConfig() {
        // Check for READ_EXTERNAL_STORAGE permission on Android 6.0+ but below Android 13
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                pendingConfigParse = true;
                ActivityCompat.requestPermissions(this,
                    new String[] {android.Manifest.permission.READ_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_REQUEST_CODE);
                statusText.setText("Requesting storage permission...");
                return;
            }
        }
        pendingConfigParse = false;

        String filePath = configPathInput.getText().toString().trim();
        if (filePath.isEmpty()) {
            statusText.setText("Config path is empty, using default config from assets");
            sampleConfig = ConfigLoader.load(getApplicationContext());
            return;
        }

        // Warn if session is active
        if (currentSessionId != null) {
            statusText.setText(
                "Warning: Active session detected. Stop capture before changing config.");
            Log.w(TAG, "Attempted to change config while session active: " + currentSessionId);
            return;
        }

        ConfigLoader.LoadResult result =
            ConfigLoader.loadFromFile(getApplicationContext(), filePath);
        if (result.isSuccess()) {
            sampleConfig = result.getConfig();
            statusText.setText("Config successfully loaded from: " + filePath);
            Log.i(TAG, "Config updated and will be used for subsequent operations");
        } else {
            sampleConfig = result.getConfig();
            String errorMsg = result.getErrorMessage();
            statusText.setText("Failed to load config: " + errorMsg + ". Using previous config.");
            Log.w(TAG, "Config load failed: " + errorMsg);
        }
    }

    private void runSelectedScenario() {
        if (!isBound || service == null) {
            statusText.setText("Error: Service not bound");
            return;
        }
        int idx = scenarioSpinner.getSelectedItemPosition();
        if (idx < 0 || idx >= scenarios.size()) {
            statusText.setText("Error: No scenario selected");
            return;
        }
        TestScenario scenario = scenarios.get(idx);
        TestScenarioExecutor executor = new TestScenarioExecutor(service, sampleConfig);
        String result = executor.run(scenario);
        scenarioResult.setText(result);
    }

    private void updateUI() {
        btnBind.setEnabled(!isBound);
        btnBindData.setEnabled(!isDataServiceBound);
        btnStartCapture.setEnabled(isBound && (currentSessionId == null));
        btnStopCapture.setEnabled(isBound && (currentSessionId != null));
        btnUpdateConfig.setEnabled(isBound && (currentSessionId != null));
        btnDeleteCapture.setEnabled(isBound && (currentSessionId != null));
        btnRunScenario.setEnabled(isBound);
        switchDumpScreenshot.setEnabled(isDataServiceBound);
        switchDoCompression.setEnabled(isDataServiceBound);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(connection);
            isBound = false;
        }
        if (mDataServiceConnection != null) {
            unbindService(mDataServiceConnection);
            mDataServiceConnection = null;
            isDataServiceBound = false;
        }
    }

    public static class CaptureDataService extends Service {
        private static final String NOTIF_CHANNEL_ID = "capture_data_svc";
        private static final int NOTIF_ID = 2001;

        private CaptureDataClient mDataClient;

        public class LocalBinder extends Binder {
            void setConnectionListener(CaptureDataClient.ConnectionListener l) {
                if (mDataClient != null) mDataClient.setConnectionListener(l);
            }
            void setDoScreenshotDump(boolean dump) {
                if (mDataClient != null) mDataClient.setDoScreenshotDump(dump);
            }
            void setCompressionEnabled(boolean enabled) throws RemoteException {
                if (mDataClient != null) mDataClient.setCompressionEnabled(enabled);
            }
        }

        private final LocalBinder mBinder = new LocalBinder();

        @Override
        public IBinder onBind(Intent intent) {
            return mBinder;
        }

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIF_CHANNEL_ID, "Capture Data", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
            startForeground(NOTIF_ID,
                    new Notification.Builder(this, NOTIF_CHANNEL_ID)
                            .setContentTitle("Screen capture data active")
                            .setSmallIcon(android.R.drawable.ic_menu_camera)
                            .build());
            if (mDataClient == null) {
                mDataClient = new CaptureDataClient(this);
            }
            return START_STICKY;
        }

        @Override
        public void onDestroy() {
            if (mDataClient != null) {
                mDataClient.teardown();
                mDataClient = null;
            }
            super.onDestroy();
        }
    }
}
