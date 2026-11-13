# Matter Plug

An Android app that turns a Matter Wi-Fi smart plug on and off over your home network, with no cloud, no iPhone and no Google services. The app is its own Matter controller, built on the [connectedhomeip](https://github.com/project-chip/connectedhomeip) libraries (tag `v1.6.1.0`).

It also includes **Battery Guard**: keep an Android phone that sits permanently on a charger (for example an SMS gateway) between two battery levels by switching its charger plug, and send alerts to your own server when something goes wrong.

Status: work in progress. Pairing and switching have been tested on a Galaxy S20 FE (Android 13) with two Matter Wi-Fi plugs. Battery Guard is built; long unattended runs are not verified yet.

## Features

- Pair a factory-reset Matter Wi-Fi plug over Bluetooth, using the QR code or the 11-digit manual code printed on it. The Wi-Fi password is typed at each pairing and never stored.
- On/off toggle that shows only the state the plug reports.
- Rename or remove the plug.
- **Battery Guard** (optional):
  - turns the charger plug on at a low battery level (default 20%) and off at a high level (default 85%), checked at an interval you choose (5, 10, 15, 30 or 60 minutes)
  - works with the app closed and the screen off, using scheduled alarms rather than an always-running service
  - optional alerts and status reports as HTTP POST requests (JSON, optional bearer token) to an address you set, with a small receiver program included in `tools/alert-receiver/`

## Requirements

- An Android 9 or newer arm64 phone with Bluetooth Low Energy
- A standard Matter Wi-Fi on/off plug on a 2.4 GHz network
- To build: a Linux computer with Docker (or Podman), git, `adb`, about 30 GB of free disk space and a network connection. The first build takes a long time and downloads several GB.

## Build

The Matter native libraries are not stored in this repository. `tools/first-build.sh` builds them, copies them into the app, and builds a debug APK, all inside the project's published build container.

```
cd app
bash tools/first-build.sh
```

What it does:

1. Clones `connectedhomeip` at `v1.6.1.0` to `~/src/connectedhomeip` (outside this repository) and builds CHIPTool and the Matter libraries in the `ghcr.io/project-chip/chip-build-android` container.
2. Runs `tools/copy-matter-libs.sh` to copy the jars, native libraries and two CHIPTool source files into the app.
3. Creates the Gradle wrapper if it is missing and runs `./gradlew assembleDebug` in the same container.

The script runs as you, never as root, and installs nothing on the host. Settings such as the checkout folder, image tag, container runtime and cache folder are environment variables; they are listed at the top of `app/tools/first-build.sh`.

When it finishes it prints the APK path. Install it on the phone with:

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Unit tests

The decision logic of Battery Guard (`app/app/src/main/java/app/matterplug/guard/logic/`) has no Android dependencies. To run all unit tests in the build container, run the Gradle task `:app:testDebugUnitTest` the way `first-build.sh` runs Gradle in step 3. A ready-made command is in `app/G1-NOTES.md`.

## Alert receiver (optional)

A single Go file that receives the alerts and status reports from Battery Guard, prints and logs them, runs a notification command, and raises its own alarm when the phone goes silent.

```
cd tools/alert-receiver
go build -o alert-receiver .
./alert-receiver -port 8080 -log alerts.log -token YOUR_SECRET
```

In the app, set the alert address to `http://<computer's address>:8080/battery` and the alert token to the same secret. Use `https` for any address outside your home network. Options are described at the top of `tools/alert-receiver/main.go`.

## Phone settings for Battery Guard

- Give the app notification permission and exempt it from battery optimisation (the app asks when you first switch Battery Guard on).
- Set no screen lock on the gateway phone: after a reboot, Android keeps the app's data unreadable until the phone is unlocked, so nothing would run.
- Keep the phone's charger on the Matter plug and nothing else powering the phone. Android counts any power source, including a USB cable to a computer, as "plugged in".

## Documentation

- `docs/DESIGN.md`: the original design
- `docs/BUILD-CHIPTOOL.md`: building the Matter libraries (parts still describe an older tag)
- `docs/IMPLEMENTATION.md`: programming instructions for the app
- `docs/BATTERY-GUARD.md`: Battery Guard design
- `docs/BATTERY-GUARD-IMPLEMENTATION.md`: Battery Guard programming instructions
- `CONTEXT.md`: glossary of the terms used

## License

Licensed under the Apache License 2.0; see `LICENSE`.

Two source files in the app (`BleTransport.kt` and `GenericChipDeviceListener.kt`) are copied from CHIPTool in connectedhomeip at build time by `tools/copy-matter-libs.sh`. connectedhomeip is Copyright (c) Project CHIP Authors and is also licensed under the Apache License 2.0.
