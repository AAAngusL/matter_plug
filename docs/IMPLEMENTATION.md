# Matter Plug Android App: Programming Instructions

Status: draft 1, 2026-11-10. Companion to `DESIGN.md` (what and why) and `BUILD-CHIPTOOL.md` (building the library). This file says what to program, in what order, and how to tell each step is done. Terms follow `CONTEXT.md`.

Pinned library version: `connectedhomeip` tag **`v1.6.1.0`** (the latest release on 2026-11-10). API names below were checked against CHIPTool's source at that tag unless marked **(verify)**, which means "from memory, confirm in the source before relying on it".

## 1. Decisions this document follows

### Settled

| Decision | Choice |
|---|---|
| Product pairing flow | Reset route: factory-reset plug, Bluetooth, printed code |
| Shared route | Used for the first go/no-go test only; not built into the app |
| Target plugs | Any standard Matter Wi-Fi plug |
| Target phones | Android 9 and above |
| Number of plugs | One plug on screen, stored as a list of one |
| Wi-Fi password | Typed at each pairing, never stored |
| Android backup | Off |
| When the app talks to the plug | Only while the app is on screen |
| Device verification | Skipped |
| Distribution | Out of scope |
| If the go/no-go test fails | Bypass verification, then try the shared route, then one newer tag, then stop |

### Assumed (never confirmed; each is my recommendation)

Change any of these and the section named in the last column changes with it.

| # | Assumption | Affects |
|---|---|---|
| A1 | Android 9 itself is included: `minSdk 28` | 4.2, 4.4 |
| A2 | The on/off endpoint is discovered at pairing, not assumed to be 1 | 6.6 |
| A3 | Two go/no-go tests with CHIPTool before app code: shared route, then reset route | M0 |
| A4 | Build in the project's container first; native build is the fallback | M0 |
| A5 | The toggle shows only the state the plug reports; a press shows "pending" until the report arrives, with an error after 5 seconds | 6.7, 7.2 |
| A6 | A failed pairing always restarts from the beginning | 7.1 |
| A7 | "Remove plug" tells the plug to drop the fabric if it is reachable; otherwise it only forgets the plug | 6.8 |
| A8 | Both the QR code and the 11-digit manual code are accepted | 6.2 |
| A9 | The Wi-Fi name is prefilled only on Android 9 to 11, where the location permission is already granted for Bluetooth; on Android 12 and above the user types it | 6.3 |
| A10 | Sharing the plug with other ecosystems after pairing is a non-goal | none |

## 2. Corrections to `BUILD-CHIPTOOL.md`

That file was written from memory around the v1.5 line. The build guide at `v1.6.1.0` differs:

| Item | `BUILD-CHIPTOOL.md` says | `v1.6.1.0` guide says |
|---|---|---|
| Tag | `v1.5.1.0` | `v1.6.1.0` |
| Guide path | `docs/guides/android_building.md` | `docs/platforms/android/android_building.md` |
| SDK platform | API 26 | API 34 |
| NDK | 23.2.8568313 | 28.2.13676358 |
| Kotlin compiler | 1.8.10 | 2.1.10 |
| JDK | "check" | 17 (Gradle 8.7, Android Gradle plugin 8.5.1) |

The build command and the APK path are unchanged.

## 3. Milestones

Each milestone ends with a check you can run on the phone. Do not start a milestone until the previous check passes.

| # | Milestone | Done when |
|---|---|---|
| M0 | Build CHIPTool; run both go/no-go tests | CHIPTool toggles the plug by the shared route, then by the reset route. Plug brand, model and anything odd are recorded in `DESIGN.md` |
| M1 | Empty app that loads the library | The app starts on the phone and logs a controller instance without crashing |
| M2 | Pairing with hard-coded inputs | With the code, Wi-Fi name and password typed into the source, the app commissions a reset plug and logs the node ID |
| M3 | Control with a bare screen | One button turns the plug on and off; pressing the plug's own button changes the text on screen |
| M4 | Real pairing screens | Scan or type the code, type the password, see progress, name the plug |
| M5 | Storage and lifecycle | Close and reopen the app: the plug is still there and reconnects. Leaving the app drops the session |
| M6 | Failure handling and removal | Every row in section 8 shows its message. "Remove plug" works with the plug online and offline |

### M0 details

1. Build per `BUILD-CHIPTOOL.md` with the corrections in section 2, in the container (A4).
2. **Test 1, shared route.** Leave the plug in Apple Home. In the Home app open the plug's settings, turn on pairing mode, and note the setup code. In CHIPTool use the on-network provisioning option with that code. Toggle the plug. Confirm the iPhone still controls it.
3. **Test 2, reset route.** Factory-reset the plug. In CHIPTool provision with Wi-Fi by scanning the printed QR code. Toggle the plug.
4. If CHIPTool shows "Device Attestation failed … continue pairing?", tap Continue and record that it appeared.
5. Keep the build output. M1 copies files from it.

After test 2 the plug belongs to CHIPTool's fabric. It is reset once more in M2, because the app has its own fabric.

## 4. Project setup (M1)

### 4.1 Create the project

- Android Studio, "Empty Activity" (Compose), Kotlin, package `app.matterplug`.
- JDK 17, Android Gradle plugin 8.5.1, Gradle 8.7, Kotlin 2.1.10, to match the library build.
- Keep the project in `~/work/matter_plug/app/` so docs and code share the git repository.

### 4.2 `app/build.gradle.kts` essentials

```kotlin
android {
    namespace = "app.matterplug"
    compileSdk = 34
    defaultConfig {
        applicationId = "app.matterplug"
        minSdk = 28            // A1
        targetSdk = 34
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { jniLibs { useLegacyPackaging = true } }   // (verify) needed only if the .so fails to load
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:<current>")
    implementation("androidx.datastore:datastore-preferences:<current>")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:<current>")
    implementation("androidx.camera:camera-camera2:<current>")
    implementation("androidx.camera:camera-lifecycle:<current>")
    implementation("androidx.camera:camera-view:<current>")
    implementation("com.google.zxing:core:<current>")
    // Compose BOM, material3, activity-compose as generated by the template
}
```

Use ZXing for decoding. CHIPTool uses Google ML Kit for this; ZXing keeps Google libraries out of the app.

### 4.3 Copy the library files

From the build output `out/android-arm64-chip-tool/`:

| Copy from | Copy to |
|---|---|
| `lib/src/controller/java/CHIPController.jar` | `app/libs/` |
| `lib/src/controller/java/CHIPInteractionModel.jar` | `app/libs/` |
| `lib/src/controller/java/CHIPClusters.jar` | `app/libs/` |
| `lib/src/controller/java/CHIPClusterID.jar` | `app/libs/` |
| `lib/src/controller/java/OnboardingPayload.jar` | `app/libs/` |
| `lib/src/controller/java/libMatterTlv.jar` | `app/libs/` |
| `lib/src/controller/java/libMatterJson.jar` | `app/libs/` |
| `lib/src/platform/android/AndroidPlatform.jar` | `app/libs/` |
| `lib/jni/arm64-v8a/libCHIPController.so` | `app/src/main/jniLibs/arm64-v8a/` |
| `lib/jni/arm64-v8a/libc++_shared.so` | `app/src/main/jniLibs/arm64-v8a/` |

The same files are already in `examples/android/CHIPTool/app/libs/` after the build, which is a convenient single source. Add a script `tools/copy-matter-libs.sh` that does this copy, and record the tag in `app/libs/MATTER_VERSION.txt`. Commit the jars and `.so` files; they are the pinned library.

### 4.4 `AndroidManifest.xml`

```xml
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
<uses-feature android:name="android.hardware.camera" android:required="false" />

<!-- Android 9 to 11 -->
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
<!-- Android 12 and above -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />

<application android:allowBackup="false"
             android:dataExtractionRules="@xml/no_backup"
             android:fullBackupContent="false" ...>
```

`res/xml/no_backup.xml` excludes everything from both cloud backup and device transfer.

Runtime permission requests:

| Android version | Ask before scanning for the plug | Ask before opening the camera |
|---|---|---|
| 9 to 11 | `ACCESS_FINE_LOCATION`; also check that Location is switched on | `CAMERA` |
| 12 and above | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` | `CAMERA` |

### 4.5 Files lifted from CHIPTool

Copy these from `examples/android/CHIPTool/app/src/main/java/com/google/chip/chiptool/` at the tag, keep their Apache 2.0 licence headers, and change the package:

| CHIPTool file | Becomes | Changes |
|---|---|---|
| `bluetooth/BluetoothManager.kt` | `matter/BleTransport.kt` | Package only |
| `GenericChipDeviceListener.kt` | `matter/GenericChipDeviceListener.kt` | Package only |
| `ChipClient.kt` | `matter/ChipController.kt` | See 6.1 |

### 4.6 M1 check

In `MainActivity.onCreate`, call `ChipController.get(applicationContext)` and log the result. The app must start with no `UnsatisfiedLinkError`.

## 5. Package layout

```
app.matterplug
├── MainActivity.kt            single activity, Compose navigation
├── matter/
│   ├── ChipController.kt      owns the controller instance (6.1)
│   ├── BleTransport.kt        lifted Bluetooth scan and connect (4.5)
│   ├── GenericChipDeviceListener.kt
│   ├── SetupCode.kt           parse QR and manual codes (6.2)
│   ├── Commissioner.kt        runs one pairing (6.4)
│   ├── EndpointFinder.kt      finds the on/off endpoint (6.6)
│   └── PlugClient.kt          on, off, subscribe, drop fabric (6.7, 6.8)
├── data/
│   ├── Plug.kt                data class
│   └── PlugRepository.kt      DataStore (6.5)
├── pairing/
│   ├── PairingViewModel.kt    state machine 7.1
│   ├── PairingScreen.kt
│   └── QrScanner.kt           CameraX + ZXing
└── plug/
    ├── PlugViewModel.kt       state machine 7.2
    └── PlugScreen.kt
```

Rule: only the `matter` package imports `chip.*` and `matter.*` library classes. Screens and view models see Kotlin types and coroutines only. When the library is rebuilt from a newer tag, only that package changes.

## 6. Component instructions

### 6.1 `ChipController`

A Kotlin `object`, adapted from CHIPTool's `ChipClient`.

```kotlin
object ChipController {
    @Synchronized fun get(context: Context): ChipDeviceController
    suspend fun connect(context: Context, nodeId: Long): Long      // returns a device pointer
    fun release(devicePointer: Long)
}
```

Creation, once per process:

1. `ChipDeviceController.loadJni()`.
2. Build `AndroidChipPlatform` with, in order: `AndroidBleManager(context)`, `AndroidNfcCommissioningManager()`, `PreferencesKeyValueStoreManager(context)`, `PreferencesConfigurationManager(context)`, `NsdManagerServiceResolver(context, NsdManagerServiceResolver.NsdManagerResolverAvailState())`, `NsdManagerServiceBrowser(context)`, `ChipMdnsCallbackImpl()`, `DiagnosticDataProviderImpl(context)`.
3. `ChipDeviceController(ControllerParams.newBuilder().setControllerVendorId(0xFFF4).setEnableServerInteractions(true).build())`.

Differences from `ChipClient`:

- Always pass the application context, never an activity.
- Remove the `ExampleAttestationTrustStoreDelegate` line and the ICD check-in delegate. Verification is skipped in `Commissioner` (6.4).
- `connect` wraps `getConnectedDevicePointer(nodeId, GetConnectedDeviceCallback)` in `suspendCancellableCoroutine`, as `ChipClient.getConnectedDevicePointer` does, with a 10-second `withTimeout` around it.
- `release` calls `releaseConnectedDevicePointer(devicePointer)` **(verify)**. CHIPTool never releases pointers and notes this as a leak.

The fabric identity (root key, fabric ID) is whatever the library creates by default and keeps in the app's private preferences **(verify where)**. Do nothing extra. Clearing app data destroys it.

### 6.2 `SetupCode`

```kotlin
data class SetupCode(val passcode: Long, val discriminator: Int, val isShortDiscriminator: Boolean)

sealed interface SetupCodeResult {
    data class Ok(val code: SetupCode) : SetupCodeResult
    data object NotAMatterCode : SetupCodeResult
}

fun parseSetupCode(text: String): SetupCodeResult
```

- Trim the text. Strip spaces and dashes from manual codes.
- If it starts with `MT:` call `OnboardingPayloadParser().parseQrCode(text)`; otherwise `OnboardingPayloadParser().parseManualPairingCode(text)` (package `matter.onboardingpayload`).
- Catch `UnrecognizedQrCodeException` and any other exception; return `NotAMatterCode`.
- Map the payload's `setupPinCode`, `discriminator` and `hasShortDiscriminator` **(verify property names)**. A manual code always gives a short discriminator.

Unit-test this with the example QR string from the Matter specification and one 11-digit code. It needs the jars but not a phone.

### 6.3 Wi-Fi name and password

No storage class. The pairing screen holds two text fields in view-model memory, and the password is cleared when pairing ends, whatever the outcome.

Prefill (A9): on Android 9 to 11, after the location permission is granted, read the current network name from `WifiManager.connectionInfo.ssid` and strip the quotes. On Android 12 and above leave the field empty. If the phone's current link frequency is above 3000 MHz, show the hint "Your phone is on 5 GHz. Most plugs need a 2.4 GHz network with this name."

### 6.4 `Commissioner`

Runs one pairing attempt and reports stages.

```kotlin
enum class PairingStage { FindingPlug, Connecting, SecuringSession, Commissioning, JoiningWifi, FindingOnNetwork, Finishing }

sealed interface PairingEvent {
    data class Progress(val stage: PairingStage) : PairingEvent
    data class Success(val nodeId: Long) : PairingEvent
    data class Failure(val stage: PairingStage, val errorCode: Long?) : PairingEvent
}

class Commissioner(private val context: Context) {
    fun pair(code: SetupCode, ssid: String, password: String, nodeId: Long): Flow<PairingEvent>
}
```

Implement `pair` as a `callbackFlow`:

1. Emit `FindingPlug`. `val ble = BleTransport()`; `val device = ble.getBluetoothDevice(context, code.discriminator, code.isShortDiscriminator)`. The lifted code scans for service `0000FFF6-0000-1000-8000-00805F9B34FB` for 10 seconds. `null` means failure at `FindingPlug`.
2. Emit `Connecting`. `val gatt = ble.connect(context, device)`; `val connId = ble.connectionId`.
3. `controller.setCompletionListener(listener)`, where `listener` extends `GenericChipDeviceListener` (step 6).
4. Skip verification:
   ```kotlin
   controller.setDeviceAttestationDelegate(600) { devicePtr, _, _ ->
       controller.continueCommissioning(devicePtr, true)
   }
   ```
5. Emit `SecuringSession`, then start:
   ```kotlin
   val params = CommissionParameters.Builder()
       .setCsrNonce(null)
       .setNetworkCredentials(
           NetworkCredentials.forWiFi(NetworkCredentials.WiFiCredentials(ssid, password)))
       .setICDRegistrationInfo(null)
       .build()
   controller.pairDeviceThroughBLE(gatt, connId, nodeId, code.passcode, params)
   ```
6. Listener callbacks:

   | Callback | Action |
   |---|---|
   | `onPairingComplete(code: Long)` | `0`: emit `Commissioning`. Otherwise: failure at `SecuringSession` |
   | `onCommissioningStatusUpdate(nodeId, stage: String, errorCode)` **(verify it exists and its stage names)** | Map the stage name to `JoiningWifi` or `FindingOnNetwork` and emit it. A non-zero `errorCode` is a failure at the mapped stage |
   | `onCommissioningComplete(nodeId: Long, errorCode: Long)` | `0`: emit `Finishing`, then `Success(nodeId)`. Otherwise: failure at the last stage emitted |
   | `onError(error: Throwable?)` | Failure at the last stage emitted |
   | others | Log only |

   If `onCommissioningStatusUpdate` is not available at the tag, emit `Commissioning` only and report every later failure as "commissioning failed" with the error code.
7. Wrap the whole attempt in a 120-second timeout. A timeout is a failure at the last stage emitted.
8. In `awaitClose` and on every exit path: `controller.setDeviceAttestationDelegate(0, <empty delegate>)` and `controller.close()`, which ends the Bluetooth connection. This matches CHIPTool's teardown.

Callbacks arrive on the library's thread. Use `trySend` in the flow and collect on the main dispatcher in the view model.

Node ID: `PlugRepository.nextNodeId()` returns a counter that starts at 1 and is incremented and saved **before** `pairDeviceThroughBLE` is called, so a failed attempt never reuses an ID.

### 6.5 `Plug` and `PlugRepository`

```kotlin
data class Plug(val nodeId: Long, val endpoint: Int, val name: String)

class PlugRepository(context: Context) {
    val plugs: Flow<List<Plug>>
    suspend fun add(plug: Plug)
    suspend fun rename(nodeId: Long, name: String)
    suspend fun remove(nodeId: Long)
    suspend fun nextNodeId(): Long
}
```

Preferences DataStore named `plugs`, with two keys: `plugs_json` (a JSON array of plugs) and `next_node_id`. Do not store the last on/off state; the screen shows only what the plug reports (A5).

Version 1 screens read `plugs.first()`. An empty list shows the "Add plug" screen. More than one entry cannot happen in version 1, because "Add plug" is offered only when the list is empty.

### 6.6 `EndpointFinder`

Run once, straight after `Success(nodeId)`, before saving the plug.

1. `val ptr = ChipController.connect(context, nodeId)`.
2. Read the OnOff attribute on every endpoint in one request:
   ```kotlin
   val path = ChipAttributePath.newInstance(
       ChipPathId.forWildcard(),          // endpoint
       ChipPathId.forId(0x0006),          // OnOff cluster
       ChipPathId.forId(0x0000))          // OnOff attribute
   controller.readPath(reportCallback, ptr, listOf(path), null, false, 0)
   ```
   **(verify the `ChipPathId` overload of `newInstance`.)**
3. In `ReportCallback.onReport(nodeState)`, take the lowest key of `nodeState.endpointStates` **(verify accessor)**. That is the plug's endpoint.
4. No endpoint in the report: pairing fails with "This device has no on/off switch", and the app drops the fabric (6.8) so the device is left clean.
5. If the wildcard read does not compile or fails at this tag, fall back to endpoint 1 and log a warning.

### 6.7 `PlugClient`

One instance per plug while its screen is visible.

```kotlin
sealed interface PlugState {
    data object Connecting : PlugState
    data class Online(val isOn: Boolean) : PlugState
    data object Offline : PlugState
}

class PlugClient(private val context: Context, private val plug: Plug) {
    val state: StateFlow<PlugState>
    suspend fun start()                 // connect and subscribe
    suspend fun set(on: Boolean): Result<Unit>
    fun stop()                          // drop subscription and session
    suspend fun leaveFabric(): Result<Unit>   // 6.8
}
```

**`start`**

1. State `Connecting`. `ptr = ChipController.connect(context, plug.nodeId)`. Failure or timeout gives `Offline`.
2. Subscribe:
   ```kotlin
   val path = ChipAttributePath.newInstance(plug.endpoint.toLong(), 0x0006L, 0x0000L)
   controller.subscribeToPath(
       { _ -> /* established */ },
       { _, _ -> _state.value = PlugState.Offline },   // resubscription attempt: the plug stopped answering
       reportCallback, ptr, listOf(path), null,
       0,      // minimum interval, seconds
       30,     // maximum interval, seconds
       false,  // keepSubscriptions
       false,  // isFabricFiltered
       0)      // imTimeoutMs
   ```
3. `ReportCallback.onReport(nodeState)`: read `nodeState.getEndpointState(endpoint).getClusterState(0x0006).getAttributeState(0x0000).tlv`, decode with `TlvReader(tlv).getBool(AnonymousTag)`, and set `Online(isOn)`. The first report arrives as soon as the subscription is established, and it is what moves the state out of `Connecting`.
4. `ReportCallback.onError`: set `Offline`.

The library resubscribes by itself after a drop. A report after `Offline` moves the state back to `Online`.

**`set(on)`**

1. Build an empty command payload: `TlvWriter().startStructure(AnonymousTag).endStructure()`.
2. `InvokeElement.newInstance(plug.endpoint.toLong(), 0x0006L, if (on) 0x01L else 0x00L, tlv.getEncoded(), null)`. Command IDs: Off `0x00`, On `0x01`. Send On or Off, never Toggle, so a repeated press cannot flip the plug twice.
3. `controller.invoke(invokeCallback, ptr, element, 0, 0)`.
4. Return success on `onResponse`, failure on `onError`. Do not change `state` here; only reports change it (A5).

**`stop`**

Cancel the subscription with the controller's subscription shutdown call **(verify name: `shutdownSubscriptions` family)**, then `ChipController.release(ptr)`. Called when the screen stops.

### 6.8 Remove plug (A7)

1. If the state is `Online`:
   1. Read the app's fabric index on the plug: attribute `CurrentFabricIndex` (`0x0005`) of the Operational Credentials cluster (`0x003E`) on endpoint 0.
   2. Invoke `RemoveFabric` (command `0x0A`) on that cluster with one field, the fabric index, at context tag 0 **(verify IDs against `ClusterIDMapping.OperationalCredentials`)**.
   3. The plug closes the session. Treat a session error after sending as success.
2. `PlugRepository.remove(nodeId)` in every case.
3. If the state was not `Online`, show: "The plug was removed from this app. It could not be reached, so factory-reset it before pairing it again."

## 7. State machines

### 7.1 Pairing (`PairingViewModel`)

```
EnterCode ──code ok──► EnterWifi ──submit──► Running(stage) ──Success──► NamePlug ──save──► done
    ▲                                            │
    └────────────── "Try again" ◄── Failed(stage, message)
```

- `EnterCode`: camera preview with a "Type the code instead" link. A scanned or typed value goes through `parseSetupCode`. `NotAMatterCode` shows an inline error and stays here.
- `EnterWifi`: Wi-Fi name and password. Submit is disabled while either is empty.
- `Running`: a list of stages with the current one marked. The back button is disabled; a Cancel button stops the flow and shows `Failed` with the reset instruction.
- `Failed`: the message from section 8 for that stage, plus "Reset the plug, then try again." "Try again" returns to `EnterCode` with nothing kept (A6).
- `NamePlug`: runs `EndpointFinder`, then asks for a name (default "Plug") and saves.

Keep the screen on during `Running` (`FLAG_KEEP_SCREEN_ON`), and keep the phone on the same Wi-Fi network as the one given to the plug.

### 7.2 Plug screen (`PlugViewModel`)

UI state is `PlugState` plus one flag, `pending: Boolean?` (the requested value, or null).

| Event | Action |
|---|---|
| Screen starts (`ON_START`) | `client.start()` |
| Screen stops (`ON_STOP`) | `client.stop()`; clear `pending` |
| Toggle pressed while `Online` and not pending | `pending = !isOn`; call `client.set(pending)`; start a 5-second timer |
| Report arrives with `isOn == pending` | Clear `pending` |
| `set` fails, or the timer ends first | Clear `pending`; show "The plug did not respond." |
| State becomes `Offline` | Clear `pending` |

Display:

| State | Toggle | Text |
|---|---|---|
| `Connecting` | Disabled | "Connecting…" |
| `Online`, not pending | Enabled, shows `isOn` | "On" or "Off" |
| `Online`, pending | Disabled, shows `isOn`, spinner | "Turning on…" or "Turning off…" |
| `Offline` | Disabled, no position shown | "Offline. Check that the plug has power and your phone is on the home Wi-Fi." |

The screen menu has Rename and Remove plug.

## 8. Failure messages

| Where | Cause | Message |
|---|---|---|
| Code entry | Not a Matter code | "This is not a Matter setup code." |
| Before scan | Bluetooth off | "Turn on Bluetooth to pair the plug." |
| Before scan, Android 9 to 11 | Location off or permission refused | "Android needs Location turned on to find the plug over Bluetooth." |
| `FindingPlug` | No plug found in 10 seconds | "No plug found. Check that it is powered, close by, and in pairing mode." |
| `FindingPlug` | No plug matched, but a 5-second second scan saw a Matter device in pairing mode with another discriminator | "A plug in pairing mode is nearby, but its code is different. Check that the code is the one on this plug. If the plug is still in another app, use the pairing code that app shows." |
| `Connecting` | Bluetooth connection failed | "Could not connect to the plug over Bluetooth." |
| `SecuringSession` | Wrong code, or plug already paired elsewhere | "The plug rejected the code. Check the code, and reset the plug if it was paired before." |
| `JoiningWifi` | Wrong password, wrong name, 5 GHz only | "The plug could not join Wi-Fi. Check the name and password, and that the network offers 2.4 GHz." |
| `FindingOnNetwork` | Phone on another network, or the router blocks device-to-device traffic | "The plug joined Wi-Fi but the phone cannot find it. Put the phone on the same network and turn off guest or isolation mode." |
| Any stage | Timeout or other error | "Pairing failed at: <stage> (error <code>)." |
| After pairing | No on/off endpoint | "This device has no on/off switch." |
| Plug screen | Command not confirmed in 5 seconds | "The plug did not respond." |

Every pairing failure adds: "Reset the plug, then try again."

## 9. Test plan

| # | Test | Expected |
|---|---|---|
| T1 | Unit: `parseSetupCode` with a valid QR string, a valid manual code, random text | Ok, Ok, NotAMatterCode |
| T2 | Pair a reset plug by QR code | Toggle screen appears; the plug answers |
| T3 | Pair a reset plug by manual code | Same as T2 |
| T4 | Pair with a wrong Wi-Fi password | `JoiningWifi` message; nothing saved |
| T5 | Pair with the plug unpowered | `FindingPlug` message after about 10 seconds |
| T6 | Press the toggle ten times, waiting for each | State matches the plug each time |
| T7 | Press the plug's own button | Screen follows within a second or two |
| T8 | Unplug the plug while the screen is open | "Offline" within about a minute; toggle disabled |
| T9 | Power the plug again | Returns to On or Off without restarting the app |
| T10 | Phone on mobile data, Wi-Fi off | "Offline" |
| T11 | Force-stop and reopen the app | Plug still listed and reconnects |
| T12 | Remove plug while online, then pair again | The printed code works again without a manual reset |
| T13 | Remove plug while offline | The "factory-reset it" message; list is empty |
| T14 | Android 9 to 11 phone and Android 12+ phone, if both are available | Correct permission prompts on each |

## 10. Items to confirm in the source at `v1.6.1.0`

These are the **(verify)** marks collected in one place. Check them at the start of the milestone shown.

| Item | Where to look | Milestone |
|---|---|---|
| Whether `useLegacyPackaging` is needed | Try without it first | M1 |
| `releaseConnectedDevicePointer` name | `src/controller/java/src/chip/devicecontroller/ChipDeviceController.java` | M3 |
| Where the default fabric keys are stored | `ControllerParams.java` and the example credentials issuer | M1 |
| `OnboardingPayload` property names | `src/controller/java/src/matter/onboardingpayload/OnboardingPayload.kt` | M2 |
| `onCommissioningStatusUpdate` and its stage names | `ChipDeviceController.CompletionListener`; CHIPTool's `GenericChipDeviceListener.kt` | M2 |
| `ChipPathId` wildcard overload | `src/controller/java/src/chip/devicecontroller/model/ChipAttributePath.java` | M3 |
| `NodeState` endpoint accessor | `model/NodeState.java` | M3 |
| Subscription shutdown method | `ChipDeviceController.java` | M5 |
| Operational Credentials IDs | `ClusterIDMapping.java` | M6 |

## References

- Matter SDK at the pinned tag: https://github.com/project-chip/connectedhomeip/tree/v1.6.1.0
- Android build guide at the tag: `docs/platforms/android/android_building.md`
- CHIPTool sources used: `ChipClient.kt`, `provisioning/DeviceProvisioningFragment.kt`, `bluetooth/BluetoothManager.kt`, `clusterclient/OnOffClientFragment.kt`, `setuppayloadscanner/BarcodeFragment.kt`, `app/build.gradle`, all under `examples/android/CHIPTool/app/`
- Library file list: `scripts/build/builders/android.py`
