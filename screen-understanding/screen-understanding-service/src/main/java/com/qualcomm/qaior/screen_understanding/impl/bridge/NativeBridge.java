/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.impl.bridge;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import com.qualcomm.qaior.screen_understanding.impl.utils.ConfigParser;
import vendor.qti.screen_understanding.display_capture.CaptureConfig;
import vendor.qti.screen_understanding.display_capture.CaptureParams;
import vendor.qti.screen_understanding.display_capture.DeleteConfig;
import vendor.qti.screen_understanding.display_capture.IDisplayCaptureControl;
import vendor.qti.screen_understanding.display_capture.IDisplayCaptureControlCallback;
import vendor.qti.screen_understanding.display_capture.Status;
import vendor.qti.screen_understanding.display_capture.StatusCode;

/**
 * Bridge to the native display capture service.
 * This class communicates directly with the native service using AIDL.
 */
public class NativeBridge {
    private static final String LOG_TAG = "ScreenUnderstanding.NativeBridge";
    private static final String SERVICE_NAME =
        "vendor.qti.screen_understanding.display_capture.IDisplayCaptureControl/default";
    private static final int NUM_RETRIES = 5;

    private IDisplayCaptureControl mService;
    private CallbackListener mListener;
    private IBinder mServiceBinder;

    // Callback implementation that native display capture service will call
    private final IDisplayCaptureControlCallback.Stub mCallback =
        new IDisplayCaptureControlCallback.Stub() {
            @Override
            public void onReady(long sessionId) throws RemoteException {
                Log.i(LOG_TAG, "Callback: onReady");

                if (mListener != null) {
                    mListener.onControlSessionReady(sessionId, true);
                }
            }

            @Override
            public void onStopped(long sessionId) throws RemoteException {
                Log.i(LOG_TAG, "Callback: onStopped");

                if (mListener != null) {
                    mListener.onControlSessionStopped(sessionId);
                }
            }

            @Override
            public void onError(long sessionId, Status status) throws RemoteException {
                Log.e(LOG_TAG, "Callback: onError - " + sessionId + ": ");

                Log.e(LOG_TAG, "Callback: onError - " + status.message);

                // TODO: error handling part is pending. Adding placeholders for now.
                switch (status.code) {
                        // sent during create
                    case StatusCode.INVALID_SESSION:
                        Log.e(LOG_TAG, "Callback: onError error code" + status.code);
                        break;
                    case StatusCode.INVALID_CONFIG:
                        // sent during create/addCaptureFd
                        Log.e(LOG_TAG, "Callback: onError error code" + status.code);
                        break;
                    case StatusCode.CAPTURE_FAILED:
                        Log.e(LOG_TAG, "Callback: onError error code" + status.code);
                        break;
                    case StatusCode.UNSUPPORTED_OPERATION:
                        Log.e(LOG_TAG, "Callback: onError error code" + status.code);
                        break;
                    case StatusCode.INTERNAL_ERROR:
                        // sent during create/update/triggerCapture/addCaptureFd
                        Log.e(LOG_TAG, "Callback: onError error code" + status.code);
                        break;
                    default:
                        Log.e(LOG_TAG, "Callback: onError invalid error code" + status.code);
                }

                if (mListener != null) {
                    mListener.onControlSessionReady(sessionId, false);
                }
            }

            @Override
            public void onConfigUpdated(long sessionId) throws RemoteException {
                Log.d(LOG_TAG, "Callback: onConfigUpdated - " + sessionId);
            }

            @Override
            public int getInterfaceVersion() {
                return IDisplayCaptureControlCallback.VERSION;
            }

            @Override
            public String getInterfaceHash() {
                return IDisplayCaptureControlCallback.HASH;
            }
        };

    // Death recipient
    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.e(LOG_TAG, "Native service died!");
            mService = null;
            mServiceBinder = null;
        }
    };

    public interface CallbackListener {
        /**
         * Called when control session is ready.
         *
         * @param sessionId Session identifier.
         * @param enabled  Whether native capture is enabled.
         */
        void onControlSessionReady(long sessionId, boolean enabled);

        /**
         * Called when control session is stopped.
         *
         * @param sessionId Session identifier.
         */
        void onControlSessionStopped(long sessionId);

        /**
         * Called when control session config is updated.
         *
         * @param sessionId Session identifier.
         */
        void onControlSessionConfigUpdated(long sessionId);
    }

    /**
     * Connect to native display capture service and wait for callback.
     * This function obtains binder of native display capture service.
     *
     * @return true if connection succeeded, false otherwise
     */
    public boolean connect(CallbackListener listener) {
        mListener = listener;

        if (mService != null) {
            Log.d(LOG_TAG, "Already connected");
            return true;
        }

        try {
            int numAttempts = 1;
            while (numAttempts <= NUM_RETRIES) {
                // Get service binder
                mServiceBinder = ServiceManager.waitForService(SERVICE_NAME);

                if (mServiceBinder != null) {
                    break;
                }

                Log.e(LOG_TAG, "Failed to get native service binder");

                if (numAttempts == NUM_RETRIES) {
                    Log.e(LOG_TAG, "Unable to get native service binder");
                    return false;
                }

                Log.d(LOG_TAG, "Retrying connection");
                numAttempts++;
            }

            mService = IDisplayCaptureControl.Stub.asInterface(mServiceBinder);

            // Register death recipient
            mServiceBinder.linkToDeath(mDeathRecipient, 0);

            Log.i(LOG_TAG, "Successfully connected to native display capture service");
            return true;
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "RemoteException during connection", e);
            return false;
        }
    }

    /**
     * Disconnect from native service.
     * Note: Callbacks will be automatically unregistered as part of destroySession().
     */
    public void disconnect() {
        if (mServiceBinder != null) {
            mServiceBinder.unlinkToDeath(mDeathRecipient, 0);
        }
        mService = null;
        mServiceBinder = null;
        mListener = null;

        Log.i(LOG_TAG, "Disconnected from native service");
    }

    /**
     * Check if currently connected to the service.
     *
     * @return true if connected, false otherwise
     */
    public boolean isConnected() {
        return mService != null && mServiceBinder != null && mServiceBinder.isBinderAlive();
    }

    /**
     * Create a new control session.
     *
     * @param configJson JSON string containing capture configuration
     */
    public long createSession(String configJson) {
        if (mService == null) {
            Log.e(LOG_TAG, "Service not connected");
            return -1;
        }

        try {
            CaptureConfig captureConfig = ConfigParser.parseConfig(configJson);
            if (captureConfig == null) {
                Log.e(LOG_TAG, "Failed to parse capture config");
                return -1;
            }

            long sessionId = mService.createControlSession(mCallback, captureConfig);
            Log.i(LOG_TAG, "Called createControlSession()");
            return sessionId;
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Error calling createControlSession()", e);
            return -1;
        }
    }

    /**
     * Update configuration for an existing control session.
     *
     * @param sessionId Control session identifier
     * @param configJson JSON string containing updated configuration
     */
    public void updateConfig(long sessionId, String configJson) {
        if (mService == null) {
            Log.e(LOG_TAG, "Service not connected");
            return;
        }

        try {
            CaptureConfig captureConfig = ConfigParser.parseConfig(configJson);
            if (captureConfig == null) {
                Log.e(LOG_TAG, "Failed to parse capture config");
                return;
            }

            Status status = mService.updateControlConfig(sessionId, captureConfig);
            Log.i(LOG_TAG, "Updated config for session " + sessionId + ", status: " + status);

        } catch (Exception e) {
            Log.e(LOG_TAG, "Error calling updateControlConfig()", e);
        }
    }

    /**
     * Trigger a capture event for the given session.
     *
     * @param sessionId Control session identifier
     * @param appName package name of app
     * @param accessibilityEventType accessibility event number
     * @param timestampNs timestamp when event occurred
     * @param tag optional fields for debugging
     */
    public void triggerCapture(
        long sessionId, String appName, int accessibilityEventType, long timestampNs, String tag) {
        if (mService == null) {
            Log.e(LOG_TAG, "Service not connected");
            return;
        }

        try {
            CaptureParams captureParams =
                ConfigParser.createCaptureParams(appName, accessibilityEventType, timestampNs, tag);

            Status status = mService.triggerCapture(sessionId, captureParams);
            Log.i(LOG_TAG,
                "Triggered capture for session " + sessionId + ", app: " + appName
                    + ", status: " + status);

        } catch (Exception e) {
            Log.e(LOG_TAG, "Error calling triggerCapture()", e);
        }
    }

    /**
     * Provide a captured buffer via FD when native capture is disabled.
     *
     * @param sessionId Control session identifier
     * @param appName package name of app
     * @param accessibilityEventType accessibility event number
     * @param timestampNs timestamp when event occurred
     * @param tag optional fields for debugging
     * @param captureFd File descriptor containing raw image data
     */
    public void addCaptureFd(long sessionId, String appName, int accessibilityEventType,
        long timestampNs, String tag, ParcelFileDescriptor captureFd) {
        if (mService == null) {
            Log.e(LOG_TAG, "Service not connected");
            return;
        }

        if (captureFd == null) {
            Log.e(LOG_TAG, "Capture file descriptor is null");
            return;
        }

        try {
            CaptureParams captureParams =
                ConfigParser.createCaptureParams(appName, accessibilityEventType, timestampNs, tag);
            if (captureParams == null) {
                Log.e(LOG_TAG, "Failed to create capture params");
                return;
            }

            Status status = mService.addCaptureFd(sessionId, captureParams, captureFd);
            Log.i(LOG_TAG,
                "Added capture FD for session " + sessionId + ", app: " + appName
                    + ", status: " + status);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error calling addCaptureFd()", e);
        }
    }

    /**
     * Delete capture or session state based on DeleteConfig.
     *
     * @param sessionId Control session identifier
     * @param deleteConfigJson JSON string containing delete configuration
     */
    public void deleteCapture(long sessionId, String deleteConfigJson) {
        if (mService == null) {
            Log.e(LOG_TAG, "Service not connected");
            return;
        }

        try {
            DeleteConfig deleteConfig = ConfigParser.parseDeleteConfig(deleteConfigJson);
            if (deleteConfig == null) {
                Log.e(LOG_TAG, "Failed to parse delete config");
                return;
            }
            Status status = mService.deleteCapture(sessionId, deleteConfig);
            Log.i(LOG_TAG, "Deleted capture for session " + sessionId + ", status: " + status);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error calling deleteCapture()", e);
        }
    }

    /**
     * Destroy a control session and release associated resources.
     * Callbacks will be automatically unregistered as part of this operation.
     *
     * @param sessionId Control session identifier
     */
    public void destroySession(long sessionId) {
        if (mService == null) {
            Log.e(LOG_TAG, "Service not connected");
            return;
        }

        try {
            Status status = mService.destroyControlSession(sessionId);
            Log.i(LOG_TAG, "Destroyed control session " + sessionId + ", status: " + status);
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error calling destroyControlSession()", e);
        }
    }
}
