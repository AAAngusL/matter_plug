# Fixed IP: Design

Status: draft 5. F1 to F4 built and verified on the phone (section 12 lists what was accepted without a run). Follows the structure of `CELLULAR-CHECK.md` and reuses Battery Guard's alarm chain, alert requests and receiver, and Cellular Check's SMS sending.

Changes in draft 5, from the review of F4's plan:

- **A resident foreground service replaces the alarms** (section 8). Android lets an idle app have only about one exact alarm per 9 minutes, and the app already keeps one alarm chain for Battery Guard and Cellular Check, so a 60-second alarm could slip to 9 minutes. The service holds an ordinary Wi-Fi network callback; the 1-minute wait and the boot grace are timers in it. It shows a permanent low-priority notification while Fixed IP is on. The `PendingIntent` callback idea is dropped.
- **Switching off or unpinning with an alert open** sends one `cancelled` (`cancelled_by: ip_off` for the switch, `ip_unpinned` for Unpin) to the alert server and **no SMS** (section 7).
- **The no-Wi-Fi HTTP alert waits for Wi-Fi.** The alert address is a LAN address, so it cannot go over mobile data. The SMS goes first; the request stays in the waiting list and is sent when Wi-Fi returns (section 7).
- **State survives the process:** the wait start, the open alert and the last SMS are kept in the store (section 11).

Changes in draft 4, from the grilling review of draft 3:

- **Fixed IP is independent of Battery Guard.** It has its own switch and its own periodic read at the Check interval (section 8).
- **A 3-minute grace after boot** before the no-Wi-Fi timer starts, so a reboot sends no SMS (section 7).
- **Way 2 (static IP on the phone) is dropped**, with the address probe, the clash risk and the Mode line. The guide covers only the router reservation (section 5).
- **A "Reconnect Wi-Fi" link** on the screen opens Android's Wi-Fi settings (section 10).
- **Only the master switch silences alerts.** There is no pause and no separate alerts switch (section 7).
- **No status report.** Fixed IP sends `ip_drift` and its recovery only (section 7).
- **Pinning requires the SSID permission**, so a phone on another network never raises a false drift (section 9).
- **A changed Wi-Fi MAC is a screen warning only**, not an alert (section 4).
- **One pinned network.** To move, unpin and pin again (section 2).
- Mesh or several access points are assumed to keep the address; there is no roaming rule (section 13).

Changes in draft 3, from the review of draft 2:

- **The app does not check the router.** There is no "Verify" button and no reservation status line. The guide only shows the values to enter.
- **An optional backup number** receives every SMS the main number receives (section 6).
- **A router restart that lasts over 1 minute sends the SMS** (decided).
- **A recovery SMS is sent** (decided).

Changes in draft 2, from the review of draft 1:

- **No port check.** The phone only needs its address to stay the same.
- **No Wi-Fi is alerted after 1 minute, by SMS** (was 10 minutes, HTTP only). The SMS goes over the mobile network, so it works exactly when Wi-Fi is gone.
- **A drift also sends an SMS**, and so does the recovery. A setting holds the number to send to.
- **The router reservation is the way to lock the address**, because you control the router.

## 1. Goal

The phone is an SMS gateway. The dialer that sends it work finds the phone by its IP address, so that address must not change. Fixed IP lets the user:

1. **See** the phone's current Wi-Fi address and the values that go with it.
2. **Lock** the current DHCP address, by a router reservation the app guides.
3. **Be told**, by SMS and by the alert server, when the address changes or Wi-Fi is lost, before the dialer fails.

### Out of scope

- Logging in to the router, or checking it, from the app. The app shows exact values to enter; the user enters them.
- Setting the IP from the app, or a static IP on the phone. Android does not allow the first (section 3); the second is dropped (review decision).
- Mobile data addresses. Only the Wi-Fi address matters to the dialer.
- A port check, a status report, a pause button and several pinned networks (review decisions).

## 2. Terms

| Term | Meaning |
|---|---|
| **Pinned address** | The address, gateway, SSID and Wi-Fi MAC the user chose to keep. One network only. Saved in the app |
| **Current address** | What the phone has on Wi-Fi right now |
| **Match** | The current address and gateway equal the pinned ones, on the pinned SSID |
| **Drift** | The phone is on the pinned SSID but its address differs from the pinned one |
| **Reservation** | A router setting that always gives this phone's MAC the same address |
| **IP alert number** | The phone number that receives the SMS for a drift or a lost Wi-Fi |
| **Backup number** | An optional second number that receives the same SMS |
| **Boot grace** | The first 3 minutes after the phone boots, during which a missing Wi-Fi is not counted |

## 3. Decision summary

| # | Decision | Why |
|---|---|---|
| 1 | **The app cannot write the static IP.** Since Android 10 an ordinary app cannot change the IP settings of a saved Wi-Fi network | No public API; a device-owner route needs a factory reset and is not worth it for one setting |
| 2 | **The lock is a router reservation.** The app pins the current values and shows what to enter on the router | The router owns the pool, so no clash; survives reboots; the phone stays on plain DHCP |
| 3 | **The app watches** and alerts on drift or lost Wi-Fi, by SMS and by the alert server | Pinning alone protects nothing; the alert is the safety net |
| 4 | **No Wi-Fi alerts after 1 minute, by SMS**, after a 3-minute boot grace; the HTTP request waits for Wi-Fi | The mobile network is still up when Wi-Fi is not; the alert address is a LAN address; reboots are routine |
| 5 | **Drift alerts at once, by SMS and by the alert server** | Review decision |
| 6 | **A new type `ip_drift` on the existing alert path**, with the Alert address and token | One receiver to maintain |
| 7 | **A resident foreground service watches while the switch is on** (section 8) | Timers in the service keep the 1-minute wait and the boot grace; alarms cannot be relied on in Doze |
| 8 | **Fixed IP has its own switch and screen**, a third tab beside Battery Guard and Cellular Check (as built in F2; Cellular Check is a tab, not a ⋮ menu entry), and runs with Battery Guard on or off | The gateway's main job is to be reachable |

## 4. What the screen shows

Read with `ConnectivityManager` (`getLinkProperties`, `getNetworkCapabilities`) and `WifiManager`. `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` are already declared.

| Field | Source | Note |
|---|---|---|
| Address and prefix | `LinkProperties.linkAddresses`, first IPv4 | e.g. `192.168.1.37/24` |
| Gateway | `LinkProperties.routes`, the default route | |
| DNS | `LinkProperties.dnsServers` | |
| Network name | `WifiInfo.ssid`, quotes stripped | Needs the permission of section 9 |
| Wi-Fi MAC | **Verify:** the real per-network MAC is read from `NetworkInterface` for `wlan0`; `WifiInfo.macAddress` returns a placeholder | The router needs exactly this MAC. If it cannot be read, the screen says to copy it from Android's Wi-Fi settings, "Wi-Fi MAC address" |
| MAC type | Randomized or device, from the network's privacy setting if readable | Shown with the advice in section 5 |

**MAC change warning.** Each read compares the current MAC with the pinned one. A difference shows a warning line on the screen ("The Wi-Fi MAC changed. A router reservation made for the old one no longer applies."). It sends no alert: the drift alert covers the case where the address then changes.

## 5. Locking the address

The **Lock this address** button first needs the SSID permission (section 9); without it the button asks for it and does not pin. It then saves the pinned address and opens the guide:

1. **Use the phone's real MAC for this network.** In Android's Wi-Fi settings, open the network, then **Privacy**, and choose **Use device MAC**. A random MAC changes if the network is forgotten and re-added, and the reservation would then stop matching. The guide's button opens the Wi-Fi settings; the app then shows the MAC it reads.
2. **Reconnect** to the network once, so the MAC shown in step 3 is the one in use.
3. **On the router:** open its DHCP reservation (also called static lease or address binding) page and add: MAC = the value shown, IP = the pinned address. Each value has a copy button.
4. **Reconnect the phone** (Wi-Fi off and on) so it takes the reserved address.

**The app does not check the router.** It cannot see whether a reservation exists and does not try to. The guide ends at step 4. The Fixed IP alert (section 7) is what protects the user if the reservation is missing or later lost.

## 6. The SMS number

Two settings on the Fixed IP screen: **IP alert number** (required) and **Backup number** (optional, may stay empty). Both are separate from Cellular Check's **Own number**: that one is the phone's own number for its loopback test, and an SMS to it would only come back to this phone.

- Same rule as Own number: digits and one leading plus; at least 7 digits (`CellularSettings.MIN_DIGITS`). An empty backup number is valid and means "none".
- **Every SMS goes to both numbers** when a backup number is set: two separate messages, one send each. A failed send to one number does not stop the other.
- The backup number must differ from the IP alert number; if equal, it is treated as empty.
- The Fixed IP switch cannot be turned on until a network is pinned, the IP alert number is valid and the SMS permissions are held. `SEND_SMS` is already declared; `LoopbackSender.canSend` is the existing permission test.
- The send goes through the same single place that touches `SmsManager`, so the default SMS subscription is used as for the loopback. The gateway software only sends, so a message to another number needs no filtering on this phone (`CELLULAR-CHECK.md` section 7).

## 7. States and alerts

```
NoWifi ──joins pinned SSID──► Match ◄──address restored── Drift
   ▲                            │                          ▲
   └──── leaves Wi-Fi / other SSID                         │
                                └──── address changes ─────┘
```

| State | Meaning | HTTP alert | SMS |
|---|---|---|---|
| `NotPinned` | Nothing pinned yet | none | none |
| `Match` | Current equals pinned | ends an open `ip_drift` | one recovery SMS to each number when an alert had started |
| `Drift` | On the pinned SSID, address differs | `ip_drift`, `reason: address_changed` or `gateway_changed`, **at once** | one SMS to each number **at once** |
| `OtherNetwork` | Wi-Fi on a different SSID | none; shown as "Not on <ssid>" | none |
| `NoWifi` | Mobile data only or Wi-Fi off, for **1 minute**, counted from the end of the boot grace if the phone just booted | `ip_drift`, `reason: no_wifi`, queued in the waiting list and sent when Wi-Fi is back (the alert address is on the LAN) | one SMS to each number after the minute |

Rules:

- **The switch is the only silence.** Turning the Fixed IP switch off stops the watch and every alert. There is no pause. A deliberate Wi-Fi-off or router change while the switch is on sends the SMS.
- **An open alert ends only on proof**: a fresh reading that is `Match`. Leaving Wi-Fi does not end a drift (the same rule as Battery Guard's R1 and R2).
- **Boot grace.** After a boot the no-Wi-Fi timer starts no earlier than 3 minutes after boot. If Wi-Fi is still missing then, the SMS goes 1 minute later (4 minutes after boot). A drift is not delayed.
- **SMS limits.** One SMS (per number) when an alert starts and one when it ends. **No SMS for reminders**: the gateway line should not be filled by one fault. The alert server still gets its reminders on Battery Guard's cadence.
- **A recovery SMS is always sent** when an alert ends. If the start SMS failed (for example, no SIM), the recovery SMS is still tried.
- **A Wi-Fi flap shorter than 1 minute sends nothing.** A router restart that keeps Wi-Fi down for more than 1 minute **does send** the SMS and the recovery SMS; this is accepted, because the dialer fails during it too.
- **No status report.** The alert server receives `ip_drift` messages (start, reminders, recovered) and nothing else from Fixed IP.
- **The SMS is sent first**, so it never waits behind the HTTP request.
- **The HTTP alert needs an Alert address.** Without one it is skipped; the SMS is still sent.
- **Switching off or Unpin with an alert open** sends one `cancelled` to the alert server (`cancelled_by: ip_off` for the switch, `ip_unpinned` for Unpin), behind the waiting requests, and **no SMS**: the person who switched it off knows (Battery Guard's R4).
- **SMS texts:**
  - `Matter Plug: IP changed from 192.168.1.37 to 192.168.1.52. Dialer may lose it. Battery 61%.`
  - `Matter Plug: IP gateway changed. IP is 192.168.1.52. Dialer may not reach it. Battery 61%.`
  - `Matter Plug: no Wi-Fi for 1 minute. Dialer cannot reach it. Battery 61%.`
  - `Matter Plug: IP is back at 192.168.1.37. Battery 61%.`
  - All at most 100 characters (`docs/MESSAGES.md`); the battery sentence is left out when the battery cannot be read.
- **If the SMS send fails** (no SIM, airplane mode) the failure is logged and shown on the screen. The HTTP alert is not held back by it, and neither depends on the other.

### Alert fields

`type: ip_drift`, plus an `ip` object:

```json
"ip": { "pinned": "192.168.1.37", "current": "192.168.1.52", "ssid": "Home", "reason": "address_changed", "sms": "sent" }
```

`reason` is `address_changed`, `gateway_changed` or `no_wifi`; `sms` is `sent` or `failed`. `ALERT-API.md` gets one table row and one field description when this is built.

## 8. Watching, and how the 1 minute is kept

Android lets an idle app have only about one exact alarm per 9 minutes (`CellularScheduler` explains why the app keeps one alarm chain). A separate 60-second alarm could therefore slip to 9 minutes, which is the case the 1-minute rule exists for. So Fixed IP uses a service, not alarms:

| Part | What it does |
|---|---|
| **`IpWatchService`** | A foreground service of type `connectedDevice`, running while the Fixed IP switch is on, with a permanent low-priority notification "Watching the Wi-Fi address". It holds an ordinary `NetworkCallback` for Wi-Fi: `onAvailable`, `onLost` and `onLinkPropertiesChanged` each trigger one read (events in a row are one read) |
| **The 1-minute wait and the boot grace** | Timers inside the service. The wait start is stored (`noWifiSince`), so a process that is ended mid-wait resumes it. While a wait is pending the service holds a partial wake lock, because a timer does not run while the CPU sleeps; otherwise it holds one only for each read |
| **Own periodic read** | At Battery Guard's **Check every** value, best effort (it does not run while the CPU sleeps) |
| **The two alarm chains** | `CheckReceiver` (Battery Guard) and `CellularReceiver` (Cellular Check, while Battery Guard is off) call `IpRunner.onChain`: a guaranteed read, and the service started again if Android ended it |
| **The backstop job** | `IpBackstopJobService`, a persisted periodic job (id 7002, every 15 minutes, Android's shortest), scheduled while the switch is on and cancelled when it is turned off or the network is unpinned. It calls `IpRunner.onChain`: the service started again if Android ended it, and one read, so a lost Wi-Fi or a drift is found even where no alarm chain runs (Battery Guard and Cellular Check both off). It does not survive "Force stop" in Android's settings, which cancels every job of the app until it is opened again; the screen and a reboot bring it back |
| **After boot** | `BootReceiver` starts the service and schedules the job again; so does opening the app |
| **Check now** button | One read, shows the result |

A service started from a receiver in the background needs the app on the battery-optimisation allowlist: the SM-G780F has it (`dumpsys deviceidle whitelist`), and the existing alarm chains are exempt anyway. If **both** Battery Guard and Cellular Check are off and Android ends the service, only a restart of the phone, an update, or opening the app starts it again (risks, section 13).

Reads cost nothing on the network. Alerts are sent only on a state change (and the alert server's reminders).

## 9. Android requirements

- No new permission for the address and the watch. `SEND_SMS` and `READ_PHONE_STATE` are already declared and already asked for by Cellular Check.
- **The SSID needs location permission (`ACCESS_FINE_LOCATION`) on every Android version from 10**, and **pinning requires it**: the Lock button asks for it first and does not pin without it. Verified on the SM-G780F (Android 13): with the permission the name reads, without it the screen says "not allowed"; nearby-Wi-Fi is not needed. Android's Location switch must also be on. The manifest declares the permission for every version; pairing still asks for it only on Android 9 to 11. This is so the app can always tell `OtherNetwork` from `Drift`.
- If the permission is revoked after pinning, the screen shows "Network name: not allowed" and the app compares address and gateway only; it keeps alerting, and the alert text omits the SSID.
- The alarm uses the exact alarm permissions Battery Guard declares.
- The mobile network must be up to send the SMS: that is true after a Wi-Fi loss and is exactly why SMS is the channel for `no_wifi`.

## 10. The Fixed IP screen

```
Fixed IP                          [ on ]   ⋮
────────────────────────────────────────────
Status:   ● Match                 Check now

Current address    192.168.1.37 / 24
Gateway            192.168.1.1
DNS                192.168.1.1
Network            Home
Wi-Fi MAC          a4:12:34:56:78:9a   (device MAC)

[ Lock this address ]    [ Reconnect Wi-Fi ]
Pinned: 192.168.1.37 on Home

IP alert number    +6591234567
Backup number      +6597654321   (optional)
Last SMS           sent 14:02, address changed

[ Unpin ]
```

**Reconnect Wi-Fi** opens Android's Wi-Fi settings (`Settings.ACTION_WIFI_SETTINGS`); the app cannot turn Wi-Fi off and on itself. A drift shows in red with the old and new address. A MAC change shows the warning of section 4. The SMS line shows `failed` and the reason when a send did not work.

## 11. Files

| Path | Role |
|---|---|
| `fixedip/logic/IpSettings.kt` | `FixedIpSettings(enabled, pinned, ssid, mac, smsNumber, backupNumber)` and `LinkReading` |
| `fixedip/logic/IpVerdict.kt` | Pure function `(settings, reading, noWifiSince, bootAt, now) -> IpState`; no Android types, unit-tested on the JVM |
| `fixedip/logic/IpAlert.kt` | State change to alert start, SMS start, recovery |
| `fixedip/IpReader.kt` | Reads `LinkProperties`, `WifiInfo` and the MAC into a `LinkReading` |
| `fixedip/IpWatchService.kt` | The resident service: the Wi-Fi callback and the timers |
| `fixedip/IpRunner.kt` | One read: judge, move the alert on, send the SMS then the HTTP request; the cancel on switch-off |
| `fixedip/IpSms.kt` | Sends the alert SMS to every number, in parallel, through `sms/SmsSupport` (the `SmsManager` choice, now shared with Cellular Check's loopback) |
| `fixedip/IpStore.kt` | DataStore, same pattern as `CellularStore` |
| `fixedip/ui/FixedIpScreen.kt`, `FixedIpViewModel.kt` | The screen and the guide |

`IpStore` keeps the wait start (`noWifiSince`), the open alert (`startedAt,lastSentAt,reason`) and the last SMS, so none of them is lost when the process ends.

## 12. Build order and tests

| Step | Content | Done when |
|---|---|---|
| F1 | `IpVerdict` and its unit tests | Every row of section 7 is covered, including the 60-second rule and the boot grace |
| F2 | `IpReader`, store, read-only screen with **Check now** | Real values show on the phone; the MAC read is verified |
| F3 | Permission gate, pin, guide, **Reconnect Wi-Fi** link, number settings (**done**, verified on the SM-G780F except Unpin and the backup-number rules) | The guide shows the right values and copy buttons; pinning is refused without the permission; a valid number and an optional backup are accepted |
| F4 | Service with the Wi-Fi callback and timers, runner, `ip_drift` alert, SMS, switch, `ALERT-API.md` | T1 to T16 pass. **Built; the unit tests pass. Verified on the SM-G780F (2026-11-19):** T6 (SMS to both numbers at 60 s, recovery SMS at the return), T15 (`cancelled` with `ip_off`, no SMS, notification gone), T7 by a crash (Android restarted the service in 1 s and a 70-second loss then alerted). **Not run on the phone, accepted as passed by the user on 2026-11-21 (not observed):** T3 (drift), T2/T3 boot grace, T9 to T14 and the 1-hour reminder. T16's delivery was seen later: the queued requests were delivered once the alert server answered |

| # | Test | Expect |
|---|---|---|
| T1 | Pin, change nothing | `Match`, no alert, no SMS |
| T2 | Restart the phone with Wi-Fi back inside 3 minutes | `Match` after boot; no SMS; the callback is registered again |
| T3 | Restart the phone with the router off for 5 minutes | One `no_wifi` alert and SMS about 4 minutes after boot; recovery when the router is back |
| T4 | Give the phone another address (set a different static address in Android's Wi-Fi settings) | `Drift`; HTTP alert and SMS to both numbers within seconds; address set back, `recovered` and one recovery SMS to each number |
| T5 | Turn Wi-Fi off 30 seconds, then on | Nothing sent |
| T6 | Turn Wi-Fi off 70 seconds | `no_wifi` alert and SMS at 60 seconds; both end when the pinned address is back, with the recovery SMS |
| T7 | End the app's process (`am kill`), then turn Wi-Fi off | The service is gone, so nothing is sent until the next chain read or restart; **this is the known gap** (section 13). With Battery Guard on, the next Check starts the service and the alert follows at that read |
| T8 | Join another SSID | `OtherNetwork`, nothing sent |
| T9 | Remove the SIM, then force a drift | HTTP alert sent, SMS shows `failed` with its reason |
| T10 | Backup number set; a send to the main number fails | The backup number still receives its SMS |
| T11 | Backup number equal to the main number | Treated as empty; one SMS only |
| T12 | Try to pin with the SSID permission denied | The button asks for the permission; nothing is pinned if it is refused |
| T13 | Battery Guard off, Fixed IP on, force a drift with the app's process ended | The alert still arrives; the periodic read runs |
| T14 | Switch Fixed IP off, then force a drift and a 70-second Wi-Fi loss | Nothing sent |
| T15 | Switch Fixed IP off with an alert open | One `cancelled` (`ip_off`) to the server, no SMS, the notification goes |
| T16 | Wi-Fi lost for 70 seconds with the alert address on the LAN | SMS at 60 seconds; the HTTP `started` arrives when Wi-Fi is back, followed by `recovered` |

## 13. Risks

| Risk | Handling |
|---|---|
| The user does not do the router step, or the reservation is lost | Not checked by the app (decision); the alert is the protection |
| The MAC changes (forgotten network, random MAC) | The guide's first step; a changed MAC shows a warning on the screen (section 4) |
| Android ends the service | `START_STICKY`, the two alarm chains, boot and opening the app start it again; with both chains off a gap stays until one of those. **Verify** with `am kill` (T7) |
| A timer in the service is late while the CPU sleeps | A wake lock is held for the 1-minute wait and the boot grace; the periodic read is best effort and the chains are the guaranteed read |
| A permanent notification is shown | Low priority, no sound; it is the price of seeing a loss within a minute (decision) |
| A router restart sends an SMS | Accepted (decision): the dialer fails during it too |
| SMS cost or limits on the gateway line | One SMS to start, one to end, per number; no reminders (section 7) |
| Router changes the subnet | `Drift` with `gateway_changed`; the user unpins and pins again |
| A mesh or an extender gives a different address on roaming | Assumed not to happen (decision); if it does, a short wait before a drift counts is the first fix |
| The SSID permission is revoked later | Section 9: address and gateway are still compared |

## 14. Decisions from review

| # | Question | Decision |
|---|---|---|
| 1 | Port check | None (draft 2) |
| 2 | Wait before a no-Wi-Fi alert | 1 minute, by SMS (draft 2) |
| 3 | SMS on a drift | Yes, at once (draft 2) |
| 4 | Number to send to | One required IP alert number and one optional backup number; both get every SMS (draft 3) |
| 5 | Router restart over 1 minute | Sends the SMS; accepted (draft 3) |
| 6 | Recovery SMS | Yes, always (draft 3) |
| 7 | Checking the router | The app does not check or verify it (draft 3) |
| 8 | Independence from Battery Guard | Independent: own switch, own periodic read at the Check interval (draft 4) |
| 9 | Reboot | A fixed 3-minute boot grace before the no-Wi-Fi timer starts (draft 4) |
| 10 | Mesh or several access points | Assumed to keep the address; no roaming rule (draft 4) |
| 11 | Static IP on the phone (Way 2) | Dropped, with the address probe and the clash risk (draft 4) |
| 12 | Repairing a drift | Alert only, plus a "Reconnect Wi-Fi" link (draft 4) |
| 13 | Silencing alerts | The master switch only; no pause (draft 4) |
| 14 | Status report | None (draft 4) |
| 15 | SSID unreadable when pinning | Pinning requires the permission (draft 4) |
| 16 | MAC change | Screen warning only (draft 4) |
| 17 | Several pinned networks | One; unpin and pin again to move (draft 4) |
| 18 | How a lost Wi-Fi is noticed within 1 minute | A resident foreground service, with a permanent notification (draft 5) |
| 19 | Switch off or Unpin with an alert open | One `cancelled` to the server (`ip_off`, or `ip_unpinned` for Unpin), no SMS (draft 5; Unpin value added 2026-11-20) |
| 20 | HTTP alert for a lost Wi-Fi | The SMS first; the request waits for Wi-Fi (draft 5) |
