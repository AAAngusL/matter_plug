# Matter Plug

An Android app that turns a Matter Wi-Fi smart plug on and off over your home network, with no cloud, no iPhone and no Google services. The app is its own Matter controller, built on the [connectedhomeip](https://github.com/project-chip/connectedhomeip) libraries (tag `v1.6.1.0`).

It also includes two features for a phone that works as an SMS gateway:

- **Battery Guard** keeps a phone that sits permanently on a charger between two battery levels by switching its charger plug.
- **Cellular Check** proves the phone can still send and receive SMS, with a real test message, and raises an alert when it cannot.

Both send alerts to your own server when something goes wrong.

Status: work in progress. Pairing and switching have been tested on a Galaxy S20 FE (Android 13) with Matter Wi-Fi plugs. Battery Guard and Cellular Check are built and tested on that phone; long unattended runs are not verified yet.

## Features

- Pair a factory-reset Matter Wi-Fi plug over Bluetooth, using the QR code or the 11-digit manual code printed on it. The Wi-Fi password is typed at each pairing and never stored.
- On/off toggle that shows only the state the plug reports.
- Several plugs: the plug name at the top of the screen opens a switcher, with **Add plug** at the end. Rename or remove a plug from its ⋮ menu.
- **Battery Guard** (optional):
  - turns the charger plug on at a low battery level (default 20%) and off at a high level (default 85%), checked at an interval you choose (5, 10, 15, 30 or 60 minutes)
  - works with the app closed and the screen off, using scheduled alarms rather than an always-running service
  - switches one plug, the **charger plug**: the first plug until you tap **Use this plug for Battery Guard** on another. Removing the charger plug switches Battery Guard off
  - optional alerts and status reports as HTTP POST requests (JSON, optional bearer token) to an address you set, with a small receiver program included in `tools/alert-receiver/`
- **Cellular Check** (optional, ⋮ menu → **Cellular Check**):
  - reads the SIM, the network registration and the signal (RSRP, RSRQ, SINR) at Battery Guard's check interval, at no cost
  - sends a test SMS to the phone's own number once a day, and at once when one of those readings fails, to confirm whether SMS really works
  - raises a `cellular_down` alert after two failed test SMS in a row, and a recovered alert when one passes again
  - runs whether Battery Guard is on or off

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

The decision logic of Battery Guard (`app/app/src/main/java/app/matterplug/guard/logic/`) and of Cellular Check (`…/cellular/logic/`) has no Android dependencies. To run all unit tests in the build container, run the Gradle task `:app:testDebugUnitTest` the way `first-build.sh` runs Gradle in step 3. A ready-made command is in `app/G1-NOTES.md`.

## Alert receiver (optional)

A single Go file that receives the alerts and status reports from Battery Guard and Cellular Check, prints and logs them, runs a notification command, and raises its own alarm when the phone goes silent.

```
cd tools/alert-receiver
go build -o alert-receiver .
./alert-receiver -port 8080 -log alerts.log -token YOUR_SECRET
```

In the app, set the alert address to `http://<computer's address>:8080/battery` and the alert token to the same secret. Use `https` for any address outside your home network. Options are described at the top of `tools/alert-receiver/main.go`.

## Messages Cellular Check sends

Turning Cellular Check on adds these messages to the ones Battery Guard already sends. The rules behind them are in `docs/CELLULAR-CHECK.md` (sections 6 to 8).

**Test SMS.** A text to the phone's own number, such as `MPCHK 913d9c`. The phone waits up to 5 minutes for it to come back, which proves SMS works in both directions. It records three steps separately: sent, delivered (a missing delivery report is only a warning) and received back. Your gateway software sees it in the inbox; it needs to ignore messages that start with `MPCHK` only if it forwards, answers or counts incoming SMS.

| When | Test SMS |
|---|---|
| Once a day at **Daily SMS test at** (10:00 by default) while all readings pass | 1 a day |
| A reading fails (SIM, registration or signal) | 2 to confirm. With no SIM or no service they fail on the phone and reach no network |
| The readings pass again while the alert is on | 1, to prove the recovery |
| SMS fails although the readings pass | 1 a day until one passes |
| **Run cellular check now** | 1 per tap |
| Turning the check on after the test time, if none has passed that day | 1 |

A healthy day sends one test SMS. A fault that the readings show sends three, however long it lasts. The screen shows how many were sent today and in the last 7 days.

**Alert requests.** Sent to the alert address with **Send alerts** on, even when Battery Guard is off, using the same address, token and waiting list as Battery Guard's alerts:

| `state` | When | Example `message` |
|---|---|---|
| `started` | Two test SMS in a row failed | `Matter Plug: SMS service unavailable: SIM absent; the test SMS was not sent: no default sms app (code 32). Battery 84%.` |
| `reminder` | Every 60 minutes while the alert stays on | `Matter Plug (reminder): SMS service unavailable: …` |
| `recovered` | A test SMS passes again | `Matter Plug: SMS works again. Battery 84%.` |

When the readings pass but SMS fails, the started text reads `Matter Plug: SMS is not working although the signal is fine; the test SMS was sent, no delivery report, not received back. …`

Each request has `"alert":"cellular_down"` and a `cellular` object with the layer that failed and every layer's result:

```json
"cellular": {"trigger":"manual","failed_layer":"registration","reason":"airplane mode",
             "sim":"pass","registration":"fail","signal":"skipped","sms":"fail",
             "sms_reason":"not sent: radio off","signal_detail":"none"}
```

A `started` or `recovered` request that cannot be delivered waits and goes out when the network is back. Switching Cellular Check off ends the alert without a request and drops its waiting requests; switching Battery Guard off keeps them.

**Status report.** Not a new message: while Cellular Check is on, Battery Guard's status report (**Send status after every check**) carries the same `cellular` object, so the receiver shows SMS health even when nothing has failed. The receiver in `tools/alert-receiver/` prints the object on its log line.

**Phone notifications.** **Cellular Check: SMS not working** and **Cellular Check: SMS works again** appear with the started and recovered alerts, even with **Send alerts** off. While a test SMS waits for its reply (usually a second or two, at most about 6 minutes) a quiet notification, **Sending the test SMS**, is shown.

## Phone settings for Battery Guard

- Give the app notification permission and exempt it from battery optimisation (the app asks when you first switch Battery Guard on).
- Set no screen lock on the gateway phone: after a reboot, Android keeps the app's data unreadable until the phone is unlocked, so nothing would run.
- Keep the phone's charger on the Matter plug and nothing else powering the phone. Android counts any power source, including a USB cable to a computer, as "plugged in".

## Phone settings for Cellular Check

- Enter the phone's own number (at least 7 digits) in **Own number**: the test SMS go to it, and the check cannot be turned on without it.
- Allow the phone and SMS permissions when the app asks. It reads the SIM and the signal, and sends and receives the test SMS; it reads no other messages.
- Test SMS may cost money on your plan: one a day when healthy.

## Documentation

- `docs/DESIGN.md`: the original design
- `docs/BUILD-CHIPTOOL.md`: building the Matter libraries (parts still describe an older tag)
- `docs/IMPLEMENTATION.md`: programming instructions for the app
- `docs/BATTERY-GUARD.md`: Battery Guard design
- `docs/BATTERY-GUARD-IMPLEMENTATION.md`: Battery Guard programming instructions
- `docs/CELLULAR-CHECK.md`: Cellular Check design, and what was verified on the phone
- `CONTEXT.md`: glossary of the terms used

## License

Licensed under the Apache License 2.0; see `LICENSE`.

Two source files in the app (`BleTransport.kt` and `GenericChipDeviceListener.kt`) are copied from CHIPTool in connectedhomeip at build time by `tools/copy-matter-libs.sh`. connectedhomeip is Copyright (c) Project CHIP Authors and is also licensed under the Apache License 2.0.
