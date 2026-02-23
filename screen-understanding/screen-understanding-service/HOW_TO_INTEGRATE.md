# How ScreenUnderstanding Integrates with Other Components

## Quick Reference

### Who Calls ScreenUnderstanding? (Upstream Callers)

**OEM AI Assistant App** (AIDL Client)

- Via AIDL Interface: `IDisplayCaptureControl.aidl`
- Location: Android VM (`/system/app`)
- Operations: `Start (Config), Stop, Delete Capture`
- Required Permission: `com.qualcomm.qaior.permission.SCREEN_CAPTURE`
- Note: This is the only upstream caller as per HLD architecture

### Who Does ScreenUnderstanding Call? (Downstream Callees)

1. **QAIOR Display Capture Service** (Native Process)
  - Via Stable AIDL: `displayCaptureService.aidl`
  - Location: `/vendor/app`
  - Call Method: Through `NativeBridge` component

2. **Smart Selection Pipeline** (Native C++ Component)
  - Long-running Android display-capture service component
  - Performs frame deduplication and selection
  - Emits only the most relevant frames to clients
  - Supports JSON-driven configuration and reconfiguration
  - Note: DisplayCwbManager.so and CodecManager.so are internal
    components of QAIOR Display Capture Service and are not directly
    called by Screen Understanding

---

## Smart Selection Pipeline

The Smart Selection Pipeline is a native C++ component designed for
long-running Android display-capture services. It ingests frames,
performs deduplication and selection, and emits only the most relevant
frames to the client.

### Features

- Multiple internal queues (per app, per user, per surface)
- JSON-driven configuration and reconfiguration
- Deduplication and threshold-based selection
- Flushing and deletion of frames by JSON filters
- Returning deleted frame handles (including file descriptors) to the client
- Thread-safe operation across multiple capture threads
- Clean lifecycle management via an opaque SmartSelectionContext

**Note**: Queue identifiers are internal to the library. Clients interact
only through JSON configuration and metadata.

### Architecture

```text
Capture Threads
    │
    ▼
EnqueueForDeduplication(frame, cookie)
    │
    ▼
Internal Queue Routing (based on metadataJson + config)
    │
    ▼
Deduplication Engine
    │
    ├── EmitResult → Callback
    │
    ├── FlushByConfig / FlushAll → Callback
    │
    └── DeleteByConfig / DeleteAll → Deleted FrameHandles (FDs returned)
```

The pipeline maintains one or more internal queues. Each frame is routed
to a queue based on metadata and configuration. Deduplication logic
determines whether a frame should be emitted.

### Data Structures

#### FrameHandle

A unified representation of a captured frame. FrameHandle abstracts
over multiple underlying frame types used by Android display capture
pipelines.

```cpp
struct FrameHandle {
    enum class Type {
        kGraphicBuffer,          ///< Frame backed by android::GraphicBuffer
        kParcelFileDescriptor,   ///< Frame backed by a file descriptor
        kOther                   ///< OEM/custom frame type
    };

    Type type;  ///< The underlying representation type.
    android::sp<android::GraphicBuffer> graphicBuffer; ///< Valid for kGraphicBuffer
    int parcelFd = -1;                                  ///< Valid for kParcelFileDescriptor
    void* otherHandle = nullptr;                        ///< Valid for kOther

    /**
     * @brief Metadata describing the frame.
     *
     * This JSON string may include:
     *   - app name
     *   - user ID
     *   - timestamp
     *   - width/height/format
     */
    std::string metadataJson;
};
```

**Ownership Rules**:

- For file descriptors (`kParcelFileDescriptor`):
  - The pipeline duplicates (dup) the FD internally.
  - The caller retains ownership of the original FD.
  - When a FrameHandle is returned in `DeleteByConfig`/`DeleteAll`, the
    caller MUST `close()` the FD.
- For GraphicBuffer (`kGraphicBuffer`):
  - Managed via `android::sp<>` reference counting.
- For `kOther`:
  - OEM-defined ownership semantics.

#### EmitResult

Result emitted by the Smart Selection Pipeline. EmitResult is delivered
asynchronously via the callback registered during `Init()`.

```cpp
struct EmitResult {
    /**
     * @brief Frames that were selected by the pipeline.
     *
     * Each FrameHandle includes metadataJson describing the frame
     * (app name, user ID, timestamp, deduplication score, etc.).
     */
    std::vector<FrameHandle> selectedFrames;

    /**
     * @brief Frames that were rejected by the pipeline.
     *
     * These frames did not meet selection thresholds or were removed
     * during deduplication. The caller may use this information for
     * logging, debugging, or alternative processing.
     */
    std::vector<FrameHandle> rejectedFrames;
};
```

#### SmartSelectionContext

Opaque, thread-safe context representing a single pipeline instance.
A SmartSelectionContext is created by `Init()` and must be destroyed only
through `Deinit()`. The caller owns the context via `std::unique_ptr`.

```cpp
class SmartSelectionContext {
public:
    virtual ~SmartSelectionContext() = default;
};
```

### API Reference

#### Init

Initialize a new Smart Selection Pipeline instance.

```cpp
/**
 * @brief Initialize a new Smart Selection Pipeline instance.
 *
 * @param jsonConfig  Initial configuration in JSON format.
 * @param callback    Callback invoked when a frame is selected/emitted.
 *                    Must be thread-safe. Called from worker threads.
 *
 * @return A non-null SmartSelectionContext on success, nullptr on failure.
 */
virtual std::unique_ptr<SmartSelectionContext> Init(
    const std::string& jsonConfig,
    const std::function<void(const EmitResult&, void* cookie)>& callback) = 0;
```

#### Reconfigure

Apply a new JSON configuration to an existing context.

```cpp
/**
 * @brief Apply a new JSON configuration to an existing context.
 *
 * @param ctx         The context returned by Init().
 * @param jsonConfig  New configuration JSON.
 *
 * @return true on success, false if parsing or applying the config fails.
 */
virtual bool Reconfigure(SmartSelectionContext* ctx,
                         const std::string& jsonConfig) = 0;
```

#### EnqueueForDeduplication

Submit a frame for deduplication and potential selection.

```cpp
/**
 * @brief Submit a frame for deduplication and potential selection.
 *
 * @param ctx    The active SmartSelectionContext.
 * @param frame  FrameHandle containing the frame and metadataJson.
 * @param cookie Opaque pointer returned unchanged in EmitResult.
 *
 * Queue selection:
 *   - The library determines the internal queue based on frame.metadataJson
 *     and the active configuration.
 *
 * @return true if the frame was accepted, false otherwise.
 */
virtual bool EnqueueForDeduplication(
    SmartSelectionContext* ctx,
    const FrameHandle& frame,
    void* cookie) = 0;
```

#### FlushByConfig

Emit all frames matching the given JSON filter.

```cpp
/**
 * @brief Emit all frames matching the given JSON filter.
 *
 * @param ctx             The active context.
 * @param flushConfigJson JSON describing which queues to flush.
 *
 * Behavior:
 *   - For each emitted frame, the callback is invoked.
 *
 * @return true on success, false if no matching queues exist.
 */
virtual bool FlushByConfig(SmartSelectionContext* ctx,
                           const std::string& flushConfigJson) = 0;
```

#### FlushAll

Emit all frames across all internal queues.

```cpp
/**
 * @brief Emit all frames across all internal queues.
 *
 * @param ctx The active context.
 *
 * @return true on success, false on error.
 */
virtual bool FlushAll(SmartSelectionContext* ctx) = 0;
```

#### DeleteByConfig

Delete (drop) frames matching the given JSON filter.

```cpp
/**
 * @brief Delete (drop) frames matching the given JSON filter.
 *
 * @param ctx               The active context.
 * @param deleteConfigJson  JSON describing which frames/queues to delete.
 * @param deletedFrames     Output vector receiving FrameHandle objects
 *                          representing the deleted frames.
 *
 * Ownership:
 *   - For kParcelFileDescriptor frames, the caller MUST close() the FD.
 *
 * @return true if matching queues were found and cleared, false otherwise.
 */
virtual bool DeleteByConfig(SmartSelectionContext* ctx,
                            const std::string& deleteConfigJson,
                            std::vector<FrameHandle>* deletedFrames) = 0;
```

#### DeleteAll

Delete all frames across all internal queues.

```cpp
/**
 * @brief Delete all frames across all internal queues.
 *
 * @param ctx            The active context.
 * @param deletedFrames  Output vector of deleted frames.
 *
 * @return true on success, false on error.
 */
virtual bool DeleteAll(SmartSelectionContext* ctx,
                       std::vector<FrameHandle>* deletedFrames) = 0;
```

#### Deinit

Deinitialize the context and release all resources.

```cpp
/**
 * @brief Deinitialize the context and release all resources.
 *
 * Behavior:
 *   - Flushes (emits) all remaining frames via callback.
 *   - Releases all internal queues, buffers, and worker threads.
 *
 * @param ctx  The context to destroy. Must be passed as std::unique_ptr.
 *
 * @return true on success, false if final flush encounters an error.
 */
virtual bool Deinit(std::unique_ptr<SmartSelectionContext> ctx) = 0;
```

### Configuration Format

The Smart Selection Pipeline uses JSON configuration to control behavior:

```json
{
  "activeuserId": "user-6789",
  "smartSelection": [
    {
      "appName": "ExampleApp1",
      "selector": {
        "accept_threshold": 0.05,
        "remove_threshold": 0.05,
        "max_size": 128
      },
      "extractor": {
        "top_k": 4096,
        "height": 640,
        "detection_threshold": 0.05,
        "is_path": false
      },
      "matcher": {
        "min_cossim": 0.82
      }
    },
    {
      "appName": "ExampleApp2",
      "selector": {
        "accept_threshold": 0.05,
        "remove_threshold": 0.05,
        "max_size": 128
      },
      "extractor": {
        "top_k": 4096,
        "height": 640,
        "detection_threshold": 0.05,
        "is_path": false
      },
      "matcher": {
        "min_cossim": 0.82
      }
    }
  ]
}
```

**Configuration Fields**:

- `activeuserId`: Active user ID for queue routing
- `smartSelection`: Array of app-specific configurations
  - `appName`: Application name for queue routing
  - `selector`: Selection thresholds and limits
    - `accept_threshold`: Threshold for accepting frames
    - `remove_threshold`: Threshold for removing duplicate frames
    - `max_size`: Maximum queue size
  - `extractor`: Feature extraction configuration
    - `top_k`: Number of top features to extract
    - `height`: Frame height for processing
    - `detection_threshold`: Detection threshold
    - `is_path`: Whether to use path-based extraction
  - `matcher`: Matching configuration
    - `min_cossim`: Minimum cosine similarity for matching

---

## Detailed Integration Guide

### 1. How to Call ScreenUnderstanding

**Flow**: OEM AI Assistant App (AIDL Client) → QAIOR Screen Understanding
Process (Java) → QAIOR Display Capture Service (Native Process)

According to the HLD architecture diagram, the integration flow is:

```text
OEM AI Assistant App (AIDL Client)
    ↓ [AIDL: IDisplayCaptureControl.aidl]
    ↓ Start (Config), Stop, Delete Capture (labeled 1)
QAIOR Screen Understanding Process (Java)
    ↓ Native AIDL Client (DisplayCaptureTrigger)
    ↓ [JNI] (labeled 3)
    ↓ Java Component (Capture Trigger)
    ↓ Permission Checker / Authentication (labeled 2)
    ↓ createControlSession(callback, config) (labeled 4)
QAIOR Display Capture Service (Native Process)
    ↓ Capture Manager
    ↓ SmartSelection.so - EnqueueForDeduplication (labeled 6)
    Note: DisplayCwbManager.so and CodecManager.so are internal
    components of QAIOR Display Capture Service, not directly called
    by Screen Understanding
```

**Note**: Complete code examples will be provided in the reference sample
app, which will be part of the release package.

### 2. Interface Definitions

According to the HLD architecture diagram, the following AIDL interfaces are used:

#### IDisplayCaptureControl.aidl (Control Path - labeled 4)

```aidl
package com.qualcomm.qaior.screen_understanding.display_capture;

interface IDisplayCaptureControl {
    // Create control session with callback and configuration
    void createControlSession(IDisplayCaptureControlCallback callback, String config);

    // Stop capture
    void stopCapture(String sessionId);

    // Delete capture data
    void deleteCapture(String deleteConfigJson);
}
```

#### IDisplayCaptureControlCallback.aidl (Control Callback)

```aidl
package com.qualcomm.qaior.screen_understanding.display_capture;

interface IDisplayCaptureControlCallback {
    // Notify capture status
    void onCaptureStatus(String sessionId, boolean enabled);

    // Notify error
    void onError(String sessionId, int errorCode, String errorMessage);
}
```

### 3. Complete Integration Flow (Per HLD Architecture Diagram)

```text
┌─────────────────────────────────────────────────────────┐
│ 1. OEM AI Assistant App (AIDL Client)                   │
│    Location: Android VM (/system/app)                    │
│    ↓ Start (Config), Stop, Delete Capture (labeled 1)   │
│    ↓ [AIDL: IDisplayCaptureControl.aidl]                │
└─────────────────────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────────────────────┐
│ 2. QAIOR Screen Understanding Process (Java)             │
│    - Native AIDL Client (DisplayCaptureTrigger)          │
│    - Java Component (Capture Trigger)                   │
│    ↓ [JNI] (labeled 3)                                   │
│    ↓ Permission Checker / Authentication (labeled 2)    │
│    ↓ createControlSession(callback, config) (labeled 4)   │
└─────────────────────────────────────────────────────────┘
              ↓
┌─────────────────────────────────────────────────────────┐
│ 3. QAIOR Display Capture Service (Native Process)        │
│    Location: Android VM (/vendor/app)                    │
│    - Capture Manager                                     │
│      Note: DisplayCwbManager.so and CodecManager.so are  │
│      internal components, not directly called by Screen  │
│      Understanding                                       │
│    - SmartSelection.so                                   │
│      ↓ EnqueueForDeduplication(ctx, frame, cookie) (labeled 6) │
└─────────────────────────────────────────────────────────┘
```

---

## Architecture Flow Mapping (Per HLD Diagram)

According to the HLD architecture diagram:

| Label | Flow | Description | Interface/Protocol |
|-------|------|------------|-------------------|
| 1 | OEM AI Assistant App → QAIOR Screen Understanding Process | Start (Config), Stop, Delete Capture | AIDL: IDisplayCaptureControl.aidl |
| 2 | Java Component → Permission Checker | Permission Check / Authentication | JNI |
| 3 | Native AIDL Client → Java Component | DisplayCaptureTrigger interaction | JNI |
| 4 | Native AIDL Client → QAIOR Display Capture Service | createControlSession(callback, config) | Stable AIDL: IDisplayCaptureControl.aidl |
| 5 | Capture Manager → DisplayCwbManager.so | Init(callback), screenCapture() | Internal to QAIOR Display Capture Service (not called by Screen Understanding) |
| 6 | Capture Manager → SmartSelection.so | EnqueueForDeduplication(ctx, frame, cookie) | Native API |
| 7 | Capture Manager → CodecManager.so | triggerCodecCapture(uncompressedImageFD) | Internal to QAIOR Display Capture Service (not called by Screen Understanding) |

---

## Permission Requirements

### For Callers

- **Required Permission**: `com.qualcomm.qaior.permission.SCREEN_CAPTURE`
- **Signature Level**: Signature-level permission (only system-signed apps)
- **Declaration**: Add to AndroidManifest.xml

```xml
<uses-permission android:name="com.qualcomm.qaior.permission.SCREEN_CAPTURE" />
```

### For Service

- **System Signature**: Must use platform signature
- **Service Export**: Exported service with signature permission protection
- **Manifest Declaration**: Properly declared in AndroidManifest.xml

---

## Metadata Format

Metadata is passed as JSON string:

```json
{
    "timestamp": 1234567890,
    "eventType": "accessibility_event",
    "packageName": "com.whatsapp",
    "className": "com.whatsapp.MainActivity",
    "action": "click",
    "x": 100,
    "y": 200
}
```

**Fields**:

- `timestamp`: Event timestamp (milliseconds since epoch)
- `eventType`: Type of event (e.g., "accessibility_event")
- `packageName`: Package name of the source app
- `className`: Class name of the source activity
- `action`: User action (click, swipe, etc.)
- `x`, `y`: Coordinates (if applicable)

---

## Security Considerations

### Buffer Security

- **OEM Responsibility**: OEM is responsible for securely maintaining
  and mapping buffers
- **Secure VM**: If using Secure VM, OEM must handle secure mapping
- **GraphicBuffer**: Converted to FD in Native AIDL Server

### Permission Model (Per HLD Architecture)

- **Signature-Level**: Only system-signed applications can use
- **OEM AI Assistant App**: Must have
  `com.qualcomm.qaior.permission.SCREEN_CAPTURE` permission
- **Permission Checker**: Located in QAIOR Screen Understanding Process
  (Java), validates permissions (labeled 2 in HLD diagram)
- **System Signature**: Required for all components in the capture
  pipeline

---

## Testing Integration

### Test Client Example

**Note**: Complete test client code examples will be provided in the
reference sample app, which will be part of the release package.

---

## Common Integration Patterns (Per HLD Architecture)

### Integration Pattern: OEM AI Assistant App Direct Integration

According to the HLD architecture diagram, the integration follows this pattern:

```text
OEM AI Assistant App (AIDL Client)
    Location: Android VM (/system/app)
    ↓ Start (Config), Stop, Delete Capture
    ↓ [AIDL: IDisplayCaptureControl.aidl]
QAIOR Screen Understanding Process (Java)
    - Display Capture Trigger Control Service (extends accessibility service)
    - Native AIDL Client (DisplayCaptureTrigger)
    - Java Component (Capture Trigger)
    ↓ Permission Checker / Authentication
    ↓ createControlSession(callback, config)
    ↓ [Stable AIDL: IDisplayCaptureControl.aidl]
QAIOR Display Capture Service (Native Process)
    Location: Android VM (/vendor/app)
    - Capture Manager
    - SmartSelection.so (EnqueueForDeduplication)
    Note: DisplayCwbManager.so and CodecManager.so are internal
    components of QAIOR Display Capture Service, not directly
    called by Screen Understanding
```

**Key Points**:

- Only OEM AI Assistant App calls ScreenUnderstanding (no Adaptation Service)
- Control path uses `IDisplayCaptureControl.aidl`
- Smart Selection Pipeline (`SmartSelection.so`) performs deduplication before data emission

---

## Troubleshooting Integration

### Issue: Service Not Found

```bash
# Check service registration
adb shell dumpsys package com.qualcomm.qaior.screenunderstanding | grep DisplayCaptureNativeService

# Check service status
adb shell dumpsys activity services | grep DisplayCaptureNativeService
```

### Issue: Permission Denied

```bash
# Check permission
adb shell dumpsys package <your_package> | grep SCREEN_CAPTURE
```

---
