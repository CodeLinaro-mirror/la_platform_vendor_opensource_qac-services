/*
 * Copyright (c) Qualcomm Technologies, Inc. and/or its subsidiaries.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.qualcomm.qaior.screen_understanding.sample_app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import vendor.qti.screen_understanding.display_capture.BufferEntry;
import vendor.qti.screen_understanding.display_capture.BufferInfo;
import vendor.qti.screen_understanding.display_capture.CompressionCodec;
import vendor.qti.screen_understanding.display_capture.DeleteConfig;
import vendor.qti.screen_understanding.display_capture.IDisplayCaptureData;
import vendor.qti.screen_understanding.display_capture.IDisplayCaptureDataCallback;
import vendor.qti.screen_understanding.display_capture.PixelFormat;
import vendor.qti.screen_understanding.display_capture.ReceiverConfig;
import vendor.qti.screen_understanding.display_capture.Status;

/**
 * Client for connecting to the IDisplayCaptureData service.
 * Provides methods to bind/unbind the service and interact with the display capture data interface.
 * This class will only ever establish ONE subscription to the data service.
 */
public class CaptureDataClient {
    private static final String TAG = CaptureDataClient.class.getSimpleName();

    // Service connection details - update these based on your service configuration
    private static final String SERVICE_PACKAGE = "vendor.qti.screen_understanding";
    private static final String SERVICE_ACTION =
        "vendor.qti.screen_understanding.display_capture.IDisplayCaptureData/default";
    private static final int INVALID_SESSION_ID = -1;

    private boolean mIsRequestingCompression = false;
    private boolean mDoScreenshotDump = false;
    private Context mContext;
    private IDisplayCaptureData mCaptureDataService;
    private boolean mIsBound = false;
    private ConnectionListener mConnectionListener;
    private long mSessionId = INVALID_SESSION_ID;
    private ReceiverConfig mReceiverConfig;
    private String mSavePath;
    private Executor mSaveDataExecutor = Executors.newSingleThreadExecutor();
    private IBinder mServiceBinder;

    // BufferInfo client-side analogue
    public class ScreenshotData {
        public long timestamp;
        public Entry[] entries;

        /** Struct-like per-entry data. */
        public static class Entry {
            public ParcelFileDescriptor fd;
            public String type;
            public int width;
            public int height;
            public byte format;
            public byte codec;
        }

        /**
         * Close all duplicated FDs held by this ScreenshotData.
         */
        public void closeQuietly() {
            if (entries == null)
                return;
            for (Entry e : entries) {
                if (e != null && e.fd != null) {
                    try {
                        e.fd.close();
                    } catch (IOException ex) { /* swallow */
                    }
                    e.fd = null;
                }
            }
        }

        public static String formatToString(byte format) {
            switch (format) {
                case PixelFormat.RGBA_8888:
                    return "RGBA_8888";
                default:
                    return String.valueOf(format);
            }
        }

        public static String codecToString(byte codec) {
            switch (codec) {
                case CompressionCodec.UNKNOWN:
                    return "UNKNOWN";
                case CompressionCodec.JPEG:
                    return "JPEG";
                case CompressionCodec.H264:
                    return "H264";
                case CompressionCodec.HEVC:
                    return "HEVC";
                case CompressionCodec.AV1:
                    return "AV1";
                default:
                    return String.valueOf(codec);
            }
        }

        public static int getBytesPerPixelForFormat(byte format) {
            switch (format) {
                case PixelFormat.RGBA_8888:
                    return 3;
                default:
                    Log.e(TAG, "Unsupported pixel format, defaulting to 3");
                    return 3;
            }
        }

        public static int alignStrideFromWidth(byte format, double width) {
            switch (format) {
                case PixelFormat.RGBA_8888:
                    return (int) Math.ceil(width / 64.0) * 64;
                default:
                    Log.e(TAG, "Unsupported pixel format, defaulting to width");
                    return (int) width;
            }
        }

        public List<File> saveToTempFiles(String savePathRoot) throws IOException {
            ArrayList<File> savedFiles = new ArrayList<>();
            if (savePathRoot == null)
                throw new IllegalArgumentException("savePathRoot is null");
            if (entries == null || entries.length == 0)
                return savedFiles;

            Path saveRoot = Paths.get(savePathRoot);
            if (saveRoot == null) {
                Log.e(TAG, "Invalid root path provided: " + savePathRoot);
                return savedFiles;
            }

            for (Entry e : entries) {
                if (e == null || e.fd == null) {
                    Log.e(TAG, "Cannot save entry, null fd!");
                    continue;
                }

                String sizeStr = Integer.toString(e.height) + "x" + Integer.toString(e.width);
                String formatStr =
                    e.type + "_" + codecToString(e.codec) + "_" + formatToString(e.format);
                String filename = Long.toString(timestamp) + "_" + sizeStr + "_" + formatStr;
                File rgbOutFile = saveRoot.resolve(filename + ".rgb").toFile();
                File pngOutFile = saveRoot.resolve(filename + ".png").toFile();

                try (FileInputStream fis = new FileInputStream(e.fd.getFileDescriptor());
                     FileChannel inChannel = fis.getChannel();
                     FileOutputStream fos = new FileOutputStream(rgbOutFile, false);) {
                    long size = inChannel.size();
                    Log.d(TAG, "Screenshot buffer size: " + size);
                    if (size <= 0)
                        throw new IOException("Cannot mmap: size=" + size);

                    MappedByteBuffer mapped = inChannel.map(FileChannel.MapMode.READ_ONLY, 0, size);

                    byte[] buffer = new byte[64 * 1024];
                    while (mapped.hasRemaining()) {
                        int n = Math.min(mapped.remaining(), buffer.length);
                        mapped.get(buffer, 0, n);
                        fos.write(buffer, 0, n);
                    }

                    fos.getFD().sync();
                } finally {
                    e.fd.close();
                    e.fd = null;
                }
            }
            return savedFiles;
        }
    }

    /**
     * Listener for receiving service connection state callbacks
     * The sessionId MUST be retrieved via onServiceConnected.
     */
    public interface ConnectionListener {
        /**
         * Called when the service is successfully connected and subscribed.
         *
         * @param sessionId The session ID returned from subscribe
         */
        default void onServiceConnected(long sessionId) {}

        /**
         * Called when the service is disconnected.
         */
        default void onServiceDisconnected() {}

        /**
         * Called when buffers are received.
         *
         * @param data List of screenshot data
         */
        default void onBufferReceived(List<ScreenshotData> data) {}

        /**
         * Called when a delete request is received.
         *
         * @param deleteConfig Configuration for deletion
         */
        default void onDeleteRequest(DeleteConfig deleteConfig) {}

        /**
         * Called when an error occurs.
         *
         * @param sessionId The session ID
         * @param status Error status
         */
        default void onError(long sessionId, Status status) {}
    }

    /**
     * Toggles the receiver configuration between compressed (HEVC) and uncompressed modes.
     *
     * @param useCompression if true, uses HEVC compression config; if false, uses uncompressed
     *     config
     * @return void
     * @throws RemoteException if the service call fails
     * @throws IllegalStateException if the service is not bound or not subscribed
     */
    public void setCompressionEnabled(boolean useCompression) throws RemoteException {
        if (mIsRequestingCompression == useCompression) {
            Log.d(TAG, "setCompressionEnabled: skipping due to same config");
            return;
        }
        ReceiverConfig config = useCompression ? createAllConfig() : createUncompressedConfig();
        mIsRequestingCompression = useCompression;
        updateReceiverConfig(config);
    }

    /**
     * Internal callback implementation for IDisplayCaptureData.
     */
    private final IDisplayCaptureDataCallback.Stub captureCallback =
        new IDisplayCaptureDataCallback.Stub() {
            @Override
            public void onBufferReceived(BufferInfo[] buffers) {
                long timestamp = SystemClock.elapsedRealtime();
                Log.i(TAG, "onBufferReceived: " + buffers.length + " buffers at time " + timestamp);

                // Create screenshots, duplicating FDs so they aren't closed prematurely
                List<ScreenshotData> screenshots = createScreenshotDataFrom(buffers);
                if (screenshots.isEmpty()) {
                    Log.e(TAG, "onBufferReceived invoked but no valid data found");
                }

                if (mDoScreenshotDump == false) {
                    Log.d(TAG, "onBufferReceived: Not dumping screenshots due to user pref");
                    return;
                }

                mSaveDataExecutor.execute(() -> {
                    for (ScreenshotData data : screenshots) {
                        try {
                            data.saveToTempFiles(mSavePath);
                        } catch (Exception e) {
                            Log.e(TAG, "Exception while saving new screenshots: " + e);
                        }
                    }
                });

                if (mConnectionListener != null) {
                    mConnectionListener.onBufferReceived(screenshots);
                }
            }

            @Override
            public void onDeleteRequest(DeleteConfig deleteConfig) {
                Log.i(TAG, "onDeleteRequest");
                if (mConnectionListener != null) {
                    mConnectionListener.onDeleteRequest(deleteConfig);
                }
            }

            @Override
            public void onSubscribed(long sessionId) {
                Log.i(TAG, "onSubscribed: sessionId=" + sessionId);
                mSessionId = sessionId;

                // Notify listener after sessionId is set
                if (mConnectionListener != null) {
                    mConnectionListener.onServiceConnected(mSessionId);
                }
            }

            @Override
            public void onUnsubscribed(long sessionId) {
                Log.i(TAG, "onUnsubscribed: sessionId=" + sessionId);
                mSessionId = INVALID_SESSION_ID;
            }

            @Override
            public void onError(long sessionId, Status status) {
                Log.e(TAG, "onError: sessionId=" + sessionId + ", status=" + status);
                if (mConnectionListener != null) {
                    mConnectionListener.onError(sessionId, status);
                }
            }

            @Override
            public int getInterfaceVersion() {
                return IDisplayCaptureDataCallback.VERSION;
            }

            @Override
            public String getInterfaceHash() {
                return IDisplayCaptureDataCallback.HASH;
            }
        };

    // Death recipient
    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.e(TAG, "Native service died!");
            mCaptureDataService = null;
            mServiceBinder = null;
        }
    };

    /**
     * Packed constructor for CaptureDataClient
     *
     * @param context Application or Activity context
     * @param savePath the path to save the screenshots
     * @param config ReceiverConfig to use for subscription
     */
    public CaptureDataClient(Context context, String savePath, ReceiverConfig config) {
        mContext = context.getApplicationContext();
        mSavePath = savePath;
        mReceiverConfig = config;

        bindService();
    }

    /**
     * Constructor for CaptureDataClient.
     * Uses default directory (files dir)
     *
     * @param context Application or Activity context
     * @param config ReceiverConfig to use for subscription
     */
    public CaptureDataClient(Context context, ReceiverConfig config) {
        this(context, context.getFilesDir().getAbsolutePath(), config);
    }

    /**
     * Constructor for CaptureDataClient. Uses default config (all)
     *
     * @param context Application or Activity context
     * @param savePath the path to save the screenshots
     */
    public CaptureDataClient(Context context, String savePath) {
        this(context, savePath, createDefaultConfig());
    }

    /**
     * Constructor for CaptureDataClient with default configuration.
     * Uses default directory (files dir), and default config (all)
     *
     * @param context Application or Activity context
     */
    public CaptureDataClient(Context context) {
        this(context, context.getFilesDir().getAbsolutePath());
    }

    public void setDoScreenshotDump(boolean doDump) {
        mDoScreenshotDump = doDump;
    }

    /**
     * Create a default ReceiverConfig that allows all captures through.
     * With no compression
     *
     * @return Default ReceiverConfig
     */
    private static ReceiverConfig createDefaultConfig() {
        return createUncompressedConfig();
    }

    /**
     * Create a ReceiverConfig that captures all applications.
     * Uses system UserId and HEVC codec for compression.
     *
     * @return ReceiverConfig configured to capture all applications with HEVC compression
     * + Uncompressed
     */
    private static ReceiverConfig createAllConfig() {
        ReceiverConfig config = new ReceiverConfig();
        config.userId = 0;
        config.appSelectionMode = "all";
        config.packageList = new String[0];
        config.compressionCodec = CompressionCodec.HEVC;
        return config;
    }

    /**
     * Create a ReceiverConfig that captures all applications without compression.
     * Uses system UserId and no compression codec.
     *
     * @return ReceiverConfig configured to capture all applications with no compression
     */
    private static ReceiverConfig createUncompressedConfig() {
        ReceiverConfig config = new ReceiverConfig();
        config.userId = 0;
        config.appSelectionMode = "all";
        config.packageList = new String[0];
        config.compressionCodec = CompressionCodec.UNKNOWN;
        return config;
    }

    /**
     * Set a listener to receive service connection state callbacks.
     *
     * @param listener The listener to set
     */
    public void setConnectionListener(ConnectionListener listener) {
        mConnectionListener = listener;
    }

    /**
     * Bind to the IDisplayCaptureData service. This should only be done via constructor
     *
     * @return true if binding was initiated successfully, false otherwise
     */
    private boolean bindService() {
        if (mIsBound) {
            Log.w(TAG, "Service already bound");
            return true;
        }

        try {
            int NUM_RETRIES = 5;
            int numAttempts = 1;
            while (numAttempts <= NUM_RETRIES) {
                // Get service binder
                mServiceBinder = ServiceManager.waitForService(SERVICE_ACTION);
                if (mServiceBinder != null) {
                    break;
                }
                Log.e(TAG, "Failed to get native service binder");

                if (numAttempts == NUM_RETRIES) {
                    Log.e(TAG, "Unable to get native service binder");
                    return false;
                }

                Log.d(TAG, "Retrying connection");
                ++numAttempts;
            }

            mCaptureDataService = IDisplayCaptureData.Stub.asInterface(mServiceBinder);
            mIsBound = true;

            // Register death recipient
            mServiceBinder.linkToDeath(mDeathRecipient, 0);

            Log.i(TAG, "Successfully connected to native display capture data service");
            mCaptureDataService.subscribe(captureCallback, mReceiverConfig);

            return true;
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException during connection", e);
            return false;
        }
    }

    /**
     * Unbind from the IDisplayCaptureData service.
     * Automatically unsubscribes if currently subscribed.
     */
    public void teardown() {
        if (mIsBound) {
            // Unsubscribe before unbinding
            if (mSessionId != INVALID_SESSION_ID) {
                try {
                    unsubscribe();
                } catch (Exception e) {
                    Log.e(TAG, "Error unsubscribing before unbind", e);
                }
            }

            mCaptureDataService = null;
            mIsBound = false;
            mSessionId = INVALID_SESSION_ID;
            Log.i(TAG, "Service unbound");
        }
    }

    /**
     * Update configuration for the current subscription.
     *
     * @param config Updated receiver configuration
     * @return Status structure containing success/error information
     * @throws RemoteException if the service call fails
     * @throws IllegalStateException if the service is not bound or not subscribed
     */
    public Status updateReceiverConfig(ReceiverConfig config) throws RemoteException {
        if (!mIsBound || mCaptureDataService == null) {
            throw new IllegalStateException("Service not bound");
        }
        if (mSessionId == INVALID_SESSION_ID) {
            throw new IllegalStateException("Not subscribed");
        }

        mReceiverConfig = config;
        return mCaptureDataService.updateReceiverConfig(mSessionId, config);
    }

    /******************************************************************
     * Unused function of data service, included only for completeness.
     ******************************************************************/
    /**
     * Unsubscribe from the data service
     * This is automatically invoked when unbinding the service.
     *
     * @return Status structure containing success/error information
     * @throws RemoteException if the service call fails
     * @throws IllegalStateException if the service is not bound or not subscribed
     */
    public Status unsubscribe() throws RemoteException {
        if (!mIsBound || mCaptureDataService == null) {
            throw new IllegalStateException("Service not bound");
        }
        if (mSessionId == INVALID_SESSION_ID) {
            throw new IllegalStateException("Not subscribed");
        }

        Status status = mCaptureDataService.unsubscribe(mSessionId);
        mSessionId = INVALID_SESSION_ID;
        return status;
    }

    private List<ScreenshotData> createScreenshotDataFrom(BufferInfo[] buffers) {
        List<ScreenshotData> screenshots = new ArrayList<>(buffers.length);
        Log.i(TAG, "Rcvd " + buffers.length + " BufferInfo");
        if (buffers == null || buffers.length == 0) {
            return screenshots;
        }

        for (BufferInfo bufferInfo : buffers) {
            if (bufferInfo == null) {
                Log.e(TAG, "NULL BUFFER INFO, skipping");
                continue;
            }

            ScreenshotData data = new ScreenshotData();
            data.timestamp = bufferInfo.timestamp;

            BufferEntry[] inEntries = bufferInfo.entries;
            if (inEntries == null || inEntries.length == 0) {
                Log.e(TAG, "NO ENTRIES IN INFO, skipping");
                continue;
            }

            data.entries = new ScreenshotData.Entry[inEntries.length];

            for (int i = 0; i < inEntries.length; ++i) {
                BufferEntry be = inEntries[i];

                ScreenshotData.Entry out = new ScreenshotData.Entry();
                data.entries[i] = out;

                if (be == null) {
                    Log.e(TAG, "NULL ENTRY, skipping...");
                    continue;
                }

                // Copy metadata
                out.type = be.type;
                out.width = be.width;
                out.height = be.height;
                out.format = be.format;
                out.codec = be.codec;

                if (be.fd != null) {
                    try {
                        out.fd = ParcelFileDescriptor.dup(be.fd.getFileDescriptor());
                    } catch (IOException dupError) {
                        Log.i(TAG, "FAILED TO DUP FD, skipping");
                        continue;
                    }
                } else {
                    Log.i(TAG, "NULL FD, skipping");
                    continue;
                }
            }

            screenshots.add(data);
        }

        return screenshots;
    }
}
