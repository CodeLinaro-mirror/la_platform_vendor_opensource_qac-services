# ScreenUnderstanding Sample App - Test Scenarios

## Overview

The Test Scenarios feature allows QA testers to run predefined test scenarios through a simple UI
interface. Test scenarios are sequences of operations (start, update, stop, delete) that are
executed automatically to verify the ScreenUnderstanding service functionality.

## Test Scenarios

### Available Scenarios

The sample app includes the following predefined test scenarios:

#### 1. **Start and Stop**

- **Description**: Basic start then stop flow
- **Operations**:
  - `start` - Start a capture session
  - `stop` - Stop the capture session
- **Purpose**: Verify basic session lifecycle (start and stop)
- **Expected Result**: Session should start successfully and stop cleanly

#### 2. **Full Flow**

- **Description**: Start, update, stop, then delete session
- **Operations**:
  - `start` - Start a capture session
  - `update` - Update the capture configuration
  - `stop` - Stop the capture session
  - `delete` - Delete the capture session
- **Purpose**: Verify complete workflow including configuration updates
- **Expected Result**: All operations should complete successfully in sequence

#### 3. **Update without Start**

- **Description**: Negative path - update before start (should log failure)
- **Operations**:
  - `update` - Attempt to update without an active session
- **Purpose**: Verify error handling when operations are called out of order
- **Expected Result**: Operation should fail gracefully with appropriate error logging

## How to Test Scenarios

### Prerequisites

1. **Install the Sample App**: Build and install the `qaior_screen_understanding_sample_app` APK
2. **Install the Service**: Ensure `ScreenUnderstandingService` is installed and running
3. **Grant Permissions**: The app requires
   `com.qualcomm.qaior.permission.SCREEN_CAPTURE` permission (requested automatically on first
   launch)

### Step-by-Step Testing Instructions

1. **Launch the Sample App**
  - Open the `ScreenUnderstandingSampleActivity` on your device
  - The app will automatically load test scenarios from `assets/test_scenarios.json`

2. **Bind to the Service**
  - Click the **"Bind Service"** button at the top
  - Wait for the status text to show "Service connected"
  - All test buttons will become enabled once the service is bound

3. **Select a Test Scenario**
  - Scroll down to the "Test Scenarios" section
  - Use the **Spinner** dropdown to select a test scenario
  - Available scenarios are loaded from `test_scenarios.json`

4. **Run the Scenario**
  - Click the **"Run Scenario"** button
  - The scenario will execute all operations in sequence automatically
  - Wait for execution to complete (usually takes less than 1 second)

5. **View Results**
  - Execution results appear in the **Scenario Result** text area at the bottom
  - Each operation shows operation number and type (e.g., `#1 start`), additional
    information (e.g., session ID), and success status: `[OK]` or `[FAIL]`

### Example Output

When running the "Full Flow" scenario, you should see output like:

```text
Running: Full Flow
Desc: Start, update, stop, then delete session
#1 start session=scenario_12345678-1234-1234-1234-123456789abc [OK]
#2 update session=scenario_12345678-1234-1234-1234-123456789abc [OK]
#3 stop  [OK]
#4 delete session=scenario_12345678-1234-1234-1234-123456789abc [OK]
```

For the "Update without Start" scenario (negative test):

```text
Running: Update without Start
Desc: Negative path: update before start (should log failure)
#1 update session=missing_session [FAIL]
```

### What to Verify

After running each scenario, verify:

1. **Success Indicators**:
  - All operations show `[OK]` status
  - Session ID is generated for `start` operation
  - No error messages in the result output

2. **Service Behavior**:
  - Service responds to operations
  - Configuration is applied correctly
  - Sessions are created and cleaned up properly

3. **Error Handling** (for negative tests):
  - Operations fail gracefully with `[FAIL]` status
  - Appropriate error messages are logged
  - Service state remains consistent

## Defining Custom Test Scenarios

### Scenario File Location

Test scenarios are defined in JSON format at:

```text
src/main/assets/test_scenarios.json
```

### Scenario JSON Format

Each scenario is a JSON object with the following structure:

```json
{
    "name": "Scenario Name",
    "description": "Brief description of what this scenario tests",
    "operations": [
        {
            "op": "start"
        },
        {
            "op": "wait",
            "waitMs": 1000
        },
        {
            "op": "update"
        },
        {
            "op": "stop"
        },
        {
            "op": "delete"
        }
    ]
}
```

### Supported Operations

| Operation | Description | Parameters | Notes |
|-----------|------------|------------|-------|
| `start` | Start a new capture session | None | Creates a new session with a unique ID |
| `update` | Update capture configuration | None | Requires an active session (will fail if no session) |
| `stop` | Stop the current capture session | None | Clears the active session |
| `delete` | Delete a capture session | None | Removes session data |
| `wait` | Wait for specified milliseconds | `waitMs` (integer) | Useful for timing-dependent tests |

### Example: Adding a New Scenario

To add a new scenario, edit `test_scenarios.json`:

```json
[
    {
        "name": "Start and Stop",
        "description": "Basic start then stop flow",
        "operations": [
            {"op": "start"},
            {"op": "stop"}
        ]
    },
    {
        "name": "My Custom Scenario",
        "description": "Test with delay between operations",
        "operations": [
            {"op": "start"},
            {"op": "wait", "waitMs": 2000},
            {"op": "update"},
            {"op": "stop"}
        ]
    }
]
```

**Note**: After modifying `test_scenarios.json`, rebuild and reinstall the app for changes to take
effect.

## Configuration

Test scenarios use configuration parameters from `assets/config.json`. The following configuration
sections are used:

- **startConfig**: Used by the `start` operation (width, height, format, framerate, appList,
  smartConfig)
- **updateConfig**: Used by the `update` operation (enabled flag, appList changes)
- **deleteConfig**: Used by the `delete` operation (deleteAll flag)

Configuration is loaded automatically at app startup. To modify configuration, edit `config.json`
and rebuild/reinstall the app.

## Troubleshooting

### Scenario Not Appearing in Spinner

- **Check**: Ensure `test_scenarios.json` is valid JSON
- **Check**: Verify the file is in `src/main/assets/` directory
- **Solution**: Check logcat for errors: `adb logcat | grep ScreenUnderstandingScenario`

### All Operations Fail

- **Check**: Service is bound (status shows "Service connected")
- **Check**: Service has required permissions
- **Check**: Service is running and accessible
- **Solution**: Try clicking "Bind Service" again

### Update/Delete Operations Fail

- **Check**: A `start` operation was executed first
- **Check**: Session ID is valid (check logcat for session creation)
- **Note**: Some scenarios intentionally test error paths (e.g., "Update without Start")

### Scenario Execution Hangs

- **Check**: Service is responsive (try manual operations first)
- **Check**: No network or I/O operations blocking
- **Solution**: Check logcat for service errors: `adb logcat | grep ScreenUnderstanding`

### Service Binding Fails

- **Check**: Service is installed: `adb shell pm list packages | grep screen_understanding`
- **Check**: Service is running
- **Solution**: Reinstall the service APK if needed

## Viewing Logs

Test scenario execution is logged with the tag `ScreenUnderstandingScenarioExec`. To view logs:

```bash
# All scenario-related logs
adb logcat | grep ScreenUnderstandingScenarioExec

# Save logs to file
adb logcat | grep ScreenUnderstandingScenarioExec > scenario_logs.txt
```

Key log messages:

- `Running: <scenario_name>` - Scenario execution started
- `Op failed: <operation>` - Operation failed with exception
- Session IDs are logged for tracking

## See Also

- `TestScenario.java` - Scenario data model
- `TestScenarioLoader.java` - Scenario loading logic
- `TestScenarioExecutor.java` - Scenario execution engine
- `ScreenUnderstandingSampleActivity.java` - UI integration
- `config.json` - Configuration parameters used by scenarios
- `test_scenarios.json` - Test scenario definitions
