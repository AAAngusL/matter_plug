# Matter Plug app: source notes (first version)

**This code has not been compiled.** No Android SDK, no Matter jars and no network were available when it was written. Expect compile errors on the first build, mostly where a Matter library signature was written from memory (see section b).

## a. Files

Project root: `matter_plug_src/app/` (module `app/app/`).

Build: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties` (no wrapper jar or scripts), `.gitignore`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/libs/README.md`, `app/src/main/jniLibs/arm64-v8a/README.md`, `tools/copy-matter-libs.sh`.

Resources: `AndroidManifest.xml`, `res/xml/no_backup.xml`, `res/values/strings.xml`, `res/values/themes.xml`.

Kotlin (package `app.matterplug`): `MainActivity`, `matter/ChipController`, `matter/SetupCode`, `matter/Commissioner`, `matter/EndpointFinder`, `matter/PlugClient`, `data/Plug`, `data/PlugRepository`, `pairing/PairingViewModel`, `pairing/PairingScreen`, `pairing/QrScanner`, `plug/PlugViewModel`, `plug/PlugScreen`. Test: `src/test/.../matter/SetupCodeTest`.

Not written by hand (produced by the copy script): `matter/BleTransport.kt`, `matter/GenericChipDeviceListener.kt`.

## b. `// VERIFY:` items

| File | Check | Fallback |
|---|---|---|
| `app/build.gradle.kts` | Is `useLegacyPackaging = true` needed? Try without it first | Keep it if the `.so` fails to load |
| `matter/ChipController.kt` (get) | Where the default fabric keys are stored; that they survive restarts | none, spec says do nothing extra |
| `matter/ChipController.kt` (discardAfterClose) and `matter/Commissioner.kt` (cleanup) | What `ChipDeviceController.close()` does | If it only ends BLE, delete `discardAfterClose` and its call |
| `matter/Commissioner.kt` (mapStage) | Exact stage strings from `onCommissioningStatusUpdate` and `onCommissioningStageStart` | Log them on first run and adjust the mapping |
| `matter/SetupCode.kt` | For a manual code, `discriminator` is the 4-bit short value, as `BleTransport.getBluetoothDevice(..., true)` expects | Use `payload.getShortDiscriminatorValue()` |
| `matter/EndpointFinder.kt` | `ChipPathId.forWildcard()` and `ChipPathId.forId(long)` names | Spec 6.6 step 5: endpoint 1 (already the runtime fallback when the read fails) |
| `matter/PlugClient.kt` | `NodeState -> EndpointState.getClusterState(long) -> ClusterState.getAttributeState(long) -> AttributeState.getTlv()` | check names in `model/*.java` |
| `matter/PlugClient.kt` | `matter.tlv.TlvReader(ByteArray).getBool(AnonymousTag)` | check `libMatterTlv` sources |
| `matter/PlugClient.kt` | `matter.tlv.TlvWriter`: `startStructure`, `endStructure`, `getEncoded` | same |
| `matter/PlugClient.kt` (stop) | `shutdownSubscriptions()` with no arguments ends this controller's subscriptions | use `shutdownSubscriptions(fabricIndex, nodeId)` |
| `matter/PlugClient.kt` (leaveFabric) | Operational Credentials IDs: cluster `0x003E`, `CurrentFabricIndex` `0x0005`, `RemoveFabric` `0x0A` | check `ClusterIDMapping.OperationalCredentials` |
| `matter/PlugClient.kt` (leaveFabric) | `TlvWriter.put(Tag, UInt)` encodes the uint8 FabricIndex acceptably | try `UByte` or `ULong` |
| `matter/PlugClient.kt` (readFabricIndex) | `TlvReader.getULong(Tag)` reads a uint8 | `getUByte` or `getUInt` |
| `tools/copy-matter-libs.sh` | The class in `BluetoothManager.kt` is declared `class BluetoothManager` (the script fails loudly if the rename does not match) | rename by hand |

## c. Items from spec section 10 settled by reading the source at v1.6.1.0

- `releaseConnectedDevicePointer`: `public void releaseConnectedDevicePointer(long devicePtr)` in `ChipDeviceController`.
- `OnboardingPayload` properties (Kotlin class): `var setupPinCode: Long`, `var discriminator: Int`, `var hasShortDiscriminator: Boolean`. Also `getShortDiscriminatorValue(): Int` and `getLongDiscriminatorValue(): Int`.
- `onCommissioningStatusUpdate` exists: `void onCommissioningStatusUpdate(long nodeId, String stage, long errorCode)`. Also `void onCommissioningStageStart(long nodeId, String stage)`. Stage names were not settled (they are C++ strings).
- `ChipAttributePath.newInstance`: two overloads exist, `(ChipPathId endpointId, ChipPathId clusterId, ChipPathId attributeId)` and `(int endpointId, long clusterId, long attributeId)`. The `ChipPathId` overload is confirmed. **The spec's `newInstance(plug.endpoint.toLong(), 0x0006L, 0x0000L)` would not compile: the endpoint must be an `Int`.** The code uses `Int`.
- `InvokeElement.newInstance(int endpointId, long clusterId, long commandId, @Nullable byte[] tlv, @Nullable String jsonString)` (also a `ChipPathId` overload). Same fix: endpoint is `Int`, not `Long` as in spec 6.7.
- `NodeState`: `public Map<Integer, EndpointState> getEndpointStates()` and `@Nullable public EndpointState getEndpointState(int endpointId)`.
- Subscription shutdown: `shutdownSubscriptions()`, `shutdownSubscriptions(int fabricIndex)`, `shutdownSubscriptions(int fabricIndex, long peerNodeId)`, `shutdownSubscriptions(int fabricIndex, long peerNodeId, long subscriptionId)`. There is no `shutdownAllSubscriptions`.
- Other signatures read and used as found:
  - `subscribeToPath(SubscriptionEstablishedCallback, ResubscriptionAttemptCallback, ReportCallback, long devicePtr, List<ChipAttributePath>, List<ChipEventPath>, int minInterval, int maxInterval, boolean keepSubscriptions, boolean isFabricFiltered, int imTimeoutMs)` matches spec 6.7.
  - `readPath(ReportCallback, long, List<ChipAttributePath>, List<ChipEventPath>, boolean isFabricFiltered, int imTimeoutMs)`.
  - `invoke(InvokeCallback, long devicePtr, InvokeElement, int timedRequestTimeoutMs, int imTimeoutMs)`.
  - `pairDeviceThroughBLE(BluetoothGatt, int connId, long deviceId, long setupPincode, CommissionParameters)`.
  - `setDeviceAttestationDelegate(int failSafeExpiryTimeoutSecs, DeviceAttestationDelegate)`. `DeviceAttestationDelegate` is a single-method interface `onDeviceAttestationCompleted(long devicePtr, AttestationInfo attestationInfo, long errorCode)`, so the 3-argument lambda in spec 6.4 is valid.
  - `continueCommissioning(long devicePtr, boolean ignoreAttestationFailure)`.
  - `ReportCallback`: `onError(ChipAttributePath?, ChipEventPath?, Exception)`, `onReport(NodeState)`, default `onDone()`.
  - `InvokeCallback`: `onError(Exception)`, `onResponse(InvokeElement, long successCode)`, default `onDone()`.
  - `ResubscriptionAttemptCallback.onResubscriptionAttempt(long terminationCause, long nextResubscribeIntervalMsec)`.
  - `GenericChipDeviceListener` at the tag is an open class with empty overrides of all 14 `CompletionListener` methods; `ChipClient` platform constructor order matches spec 6.1; `GetConnectedDeviceCallback.onConnectionFailure(nodeId: Long, error: Exception)`.
- Not settled (fetch budget used): `useLegacyPackaging`, fabric key storage, `ClusterIDMapping` Operational Credentials IDs, `ChipPathId` factory names, the `matter.tlv` API, `ChipDeviceController.close()` semantics.

## d. Not implemented as written, or interpreted

1. **`controller.close()` kills the controller.** Spec 6.4 step 8 closes the controller at the end of every pairing, but `EndpointFinder` and `PlugClient` then need a working controller. `Commissioner` calls `close()` as specified and then `ChipController.discardAfterClose()`, so the next `ChipController.get()` builds a new controller on the same stored fabric. If `close()` turns out to only end the BLE link, remove the discard (VERIFY item).
2. **Pre-pairing problems stay on the Wi-Fi screen.** "Bluetooth off", "Location off or permission refused" (Android 9 to 11) are shown inline on the Wi-Fi entry screen, not as `Failed`, because the user can fix them without resetting the plug and nothing has started. Spec 7.1 does not say where they appear.
3. **Message added that is not in section 8:** `err_ble_permission_denied` ("Allow the Nearby devices permission to pair the plug.") for a refused Bluetooth permission on Android 12+. Also `pair_camera_denied` (camera refused or absent: "type the code instead"), `pairing_cancelled` (Cancel pressed), and `error_code_none` (shown when a generic failure has no error code).
4. **Timeouts and unmapped errors.** `PairingEvent.Failure` has only a stage and an optional code, so a 120-second timeout at `SecuringSession`, `JoiningWifi` or `FindingOnNetwork` shows that stage's specific message. The generic "Pairing failed at: <stage> (error <code>)" is shown only for `Commissioning` and `Finishing`.
5. **Location permission asked early on Android 9 to 11.** To make the Wi-Fi name prefill (A9) possible, Location is requested when the Wi-Fi screen opens, not only on Pair. Pair asks again if it was refused. Android 12 and above never ask for Location.
6. **Keep screen on** uses `View.keepScreenOn` (same effect as `FLAG_KEEP_SCREEN_ON`).
7. **Stage list.** `onCommissioningStageStart` is also used to advance progress; stages only move forward. If the status-update stage names do not match the `mapStage` guesses, progress shows `Commissioning` until finished, as spec 6.4 allows.
8. **Endpoint finder read failure.** A failed or timed-out wildcard read falls back to endpoint 1 (spec 6.6 step 5). An unreachable node after pairing throws, and pairing shows a failure at `FindingOnNetwork`.
9. **No endpoint found:** the fabric is dropped through `PlugClient.leaveFabric()` (it connects by itself), then "This device has no on/off switch." is shown.
10. **RemoveFabric errors.** Any error after the command is sent counts as success (spec 6.8 only names session errors). Failing to read `CurrentFabricIndex` is reported as failure to the caller, but `PlugViewModel.remove` forgets the plug anyway.
11. **Remove plug has no confirmation dialog** (spec has none).
12. **Connecting state** shows a disabled switch in the off position (spec says "Toggle disabled"). Offline shows no switch.
13. **The unreachable-removal notice** is an `AlertDialog` held in `MainActivity` state, because the plug screen leaves the composition the moment the plug is removed.
14. **Spec 6.7 `Long` endpoint** corrected to `Int` (section c).
15. **Gradle wrapper jar and scripts** are not included (cannot be generated here); only `gradle-wrapper.properties` is.
16. `ChipController.get` is called from `MainActivity.onCreate` on the main thread for the M1 check, which loads the JNI library and starts the platform at launch. Remove it after M1 if startup is slow.

## e. From this source to a first build

1. Build CHIPTool per `BUILD-CHIPTOOL.md` (M0) so `out/android-arm64-chip-tool/` exists in the connectedhomeip checkout at `v1.6.1.0`.
2. Run `tools/copy-matter-libs.sh /path/to/connectedhomeip`. It copies 8 jars, 2 `.so` files, writes `app/libs/MATTER_VERSION.txt`, and writes `BleTransport.kt` and `GenericChipDeviceListener.kt` into `app/src/main/java/app/matterplug/matter/`.
3. Open `matter_plug_src/app/` in Android Studio (JDK 17). If there is no `gradlew`, run `gradle wrapper --gradle-version 8.7` once, or let Android Studio use its own Gradle 8.7.
4. Sync and build. Fix compile errors, starting with the VERIFY items in section b. Run `SetupCodeTest` (T1).
5. Install on an arm64 phone (no x86 emulator). M1 check: the log shows "Matter controller:" and no `UnsatisfiedLinkError`.
