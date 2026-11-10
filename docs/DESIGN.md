# Matter Plug Android App: Design

Status: draft 1, 2026-11-10. Scope: personal use first, distribution later.

## 1. Goal

An Android app that turns a Matter Wi-Fi smart plug on and off.

Requirements:

- **Local only.** Control goes directly from the phone to the plug over the home Wi-Fi. No cloud service is in the control path.
- **No iPhone dependency.** The app pairs and controls the plug by itself. Apple Home is not needed at pairing time or afterwards.
- **Easy pairing.** Pairing should feel like the iPhone flow: scan the QR code on the plug and wait.
- **No Google dependency (chosen route).** The app does not use Google Play services for pairing or control.

Non-goals for the first version: remote access from outside the home, schedules and automations, Thread devices, device types other than on/off plugs.

## 2. Decision summary

| Decision | Choice | Reason |
|---|---|---|
| Who controls the plug | The app is its own Matter controller with its own fabric | Local, no cloud, no hub |
| Who runs pairing | The app itself (fully in-app pairing) | No Google Play services; the Matter library has to be built from source either way |
| Matter library | Android controller built from `connectedhomeip` at a release tag | Google's packaged "demo SDK" is from early 2024 and its sample repository was archived in May 2025 |
| Starting point | CHIPTool, the Matter project's Android reference app | Already scans a QR code, commissions a device and sends on/off |
| Device verification | Skipped in the personal version; bundled certificate list if distributed | Keeps the first version small |

Routes considered and set aside:

- **Google Home Mobile SDK pairing.** Easiest pairing screens, but it depends on Play services, adds the phone's "local Android fabric" to the plug, and may require a Google account.
- **Google Home APIs.** Control goes through Google's ecosystem and account.
- **Home server bridge** (for example Home Assistant). Needs an always-on machine.

## 3. Architecture

```
+----------------------------- Android app ------------------------------+
|  UI (Compose)                                                           |
|    PairScreen   : scan QR / enter code, Wi-Fi password, progress        |
|    DeviceScreen : on/off toggle, online state                           |
|                                                                         |
|  ViewModels     : pairing state, device state                           |
|                                                                         |
|  DeviceRepository : saved plugs (node ID, name, last known state)       |
|                                                                         |
|  Pairing                         Control                                |
|    CodeCapture  (camera + parser)  MatterClient (OnOff commands,        |
|    BleTransport (scan, GATT)         attribute subscription)            |
|    WifiCredentialStore                                                  |
|            \                         /                                  |
|             +---- ChipController ---+   wrapper over the Matter         |
|                        |                 controller library (JNI)       |
|  FabricStore : root certificate, operational keys, fabric ID            |
+------------------------|------------------------------------------------+
                         |
     pairing: Bluetooth LE        control: Matter over Wi-Fi LAN
                         |          (mDNS discovery, encrypted session)
                   +-----v------+
                   | Matter plug|
                   +------------+
```

### Components

| Component | Responsibility | Source |
|---|---|---|
| CodeCapture | Read the QR code or the 11-digit manual code; parse it into passcode and discriminator | CameraX + ZXing for the camera; the Matter library's payload parser |
| BleTransport | Find the plug by the Matter Bluetooth service (`0xFFF6`) and its discriminator, connect, hand the connection to the controller | Lifted from CHIPTool's Bluetooth manager |
| WifiCredentialStore | Hold the SSID and password the plug should join | New; encrypted storage |
| ChipController | Single owner of the Matter controller instance; exposes pairing, device sessions, and callbacks as coroutines | Adapted from CHIPTool's controller wrapper |
| MatterClient | On, off, toggle; read and subscribe to the OnOff attribute | New, thin layer over the cluster classes |
| FabricStore | The app's own fabric identity: root certificate and keys | Library defaults at first; Android Keystore later |
| DeviceRepository | List of paired plugs and their last known state | New; DataStore |

## 4. Flows

### Pairing (one time per plug)

1. The plug is in pairing mode: new out of the box, or after a factory reset with its button.
2. The user taps **Add plug** and scans the QR code on the plug.
3. The user enters the Wi-Fi password the first time. Later plugs reuse the stored one.
4. The app finds the plug over Bluetooth and opens a secure session using the passcode from the QR code.
5. The controller issues the plug a credential on the app's fabric and sends it the Wi-Fi credentials.
6. The plug joins Wi-Fi; the controller confirms it is reachable on the LAN and completes commissioning.
7. The app saves the node ID, asks for a name, and shows the toggle.

Steps 4 to 6 are one library call (`pairDevice` in CHIPTool); the app supplies the Bluetooth connection, the passcode and the Wi-Fi credentials, then waits for the completion callback.

### Control (every time)

1. The app resolves the plug by node ID over mDNS and opens an encrypted session.
2. The toggle sends an OnOff cluster command to endpoint 1.
3. A subscription on the OnOff attribute keeps the UI in step with the plug's physical button.

## 5. Security and keys

- The app's fabric keys are the only means of controlling the plug. If the app's data is lost, the plug must be factory-reset and paired again.
- Personal version: library default key storage; device verification skipped.
- Distributed version: keys generated on the device and held in Android Keystore; device verification against a bundled list of manufacturer root certificates (PAAs) from the Matter Distributed Compliance Ledger; key export and import.
- The Wi-Fi password is stored encrypted and used only during pairing.

## 6. Constraints

- The phone must be on the same network as the plug. There is no remote access.
- Bluetooth and its permissions are needed during pairing only.
- Most plugs are 2.4 GHz only, so the Wi-Fi network given to the plug must offer 2.4 GHz.
- Android does not let apps read saved Wi-Fi passwords, so the user types it once.
- A plug currently paired to another ecosystem must be factory-reset before the printed QR code works again.
- On/off is assumed on endpoint 1. A multi-outlet strip needs endpoint discovery.

## 7. Risks

| Risk | Mitigation |
|---|---|
| The library build fails on the build machine | Use the project's Android build container; see `BUILD-CHIPTOOL.md` |
| CHIPTool cannot commission this particular plug | Step 2 of the plan is a go/no-go test before any app code is written |
| Bluetooth behaviour differs by phone brand | Personal version targets one phone |
| Maintenance when Matter versions change | Rebuild the library from a newer release tag |
| Certification if distributed with Matter branding | Connectivity Standards Alliance membership and controller certification; not needed for personal use |

## 8. Plan

1. **Build CHIPTool** from a `connectedhomeip` release tag. See `BUILD-CHIPTOOL.md`.
2. **Prove it on the plug**: factory-reset, pair with CHIPTool, toggle. Go/no-go.
3. **Create the app**: new Android Studio project; copy in the built libraries and CHIPTool's Bluetooth and controller wrapper classes.
4. **Two screens**: pairing and device toggle; save the node ID.
5. **Clean up**: encrypted Wi-Fi password, device name, re-pairing after reset.

Later, for distribution: device verification, Keystore-backed keys, polished pairing and error screens, testing across phones.

## 9. Open items

- Which release tag to pin (latest stable at the time of writing is in the v1.5 line).
- Exact names of the Android controller API methods and delegates; confirm against the tag that is built.
- Whether the plug accepts commissioning from a controller built at that tag (answered by plan step 2).

## References

- Matter SDK: https://github.com/project-chip/connectedhomeip
- Android build guide: `docs/guides/android_building.md` in that repository
- Google's archived sample (reference only): https://github.com/google-home/sample-apps-for-matter-android
- Matter attestation primer: https://developers.home.google.com/matter/primer/attestation
