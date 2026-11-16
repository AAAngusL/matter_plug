# Battery Guard: Design

Status: draft 7, 2026-11-11. Replaces draft 6. Adds to `DESIGN.md` and `IMPLEMENTATION.md`. Nothing here is built yet.

Changes in draft 7, from the second grill session:

- The gateway phone is the Galaxy S20 FE (SM-G780F, Android 13). Tests T14 and T16 run on it.
- **Status report.** An optional small request after every check, so the receiver can tell when the phone has gone silent. The receiver raises its own **silence alarm** (section 7).
- A third alert, **Battery Guard not working**, for the cases where Battery Guard itself cannot do its job: exact alarms not allowed, battery unreadable, or the service cannot start (sections 6, 7).
- Reminders stay fixed at 60 minutes with no quiet hours. A home-network alert address is accepted as it is; the silence alarm is the cover for a dead router. No plain-text body in version 1.
- A high threshold of 100 means "never turn off" (section 4).
- The receiver is a Go program.

Changes on 2026-11-16:

- `charging` in every request now follows Android's battery status (the battery is taking charge). Two fields are added: `plugged_in`, the old meaning, and `battery_status`. The switching rule and the "not charging" alert still go by plugged in (section 3).

Changes in draft 6, from the design review:

- "Charging" now means the phone is **plugged in** to power, not that the battery is actively charging (section 4).
- Retries after 1 and 2 minutes and the 2-minute follow-up are removed, because Android cannot run alarms that close together on an idle phone. "Plug unreachable" is sent when the same failure is seen at two checks in a row, and "not charging" is sent 30 seconds after the plug is turned on if the phone is still not plugged in (sections 4, 6, 7).
- The Matter start-up setting is dropped (section 8 is now "Power cuts").
- The gateway phone must have no screen lock (section 6, section 9).
- The plug's on-screen toggle stays usable, and Battery Guard overrides it at the thresholds (section 4).
- Alerts go to a small receiver program on the computer; there is no access token and no plain-text body option (section 7).
- Battery Guard switches one plug, the **Charger plug**: the first plug until another is chosen. The app itself can hold several plugs; removing the Charger plug switches Battery Guard off.
- Platform details from research on alarms, foreground services, reboot, battery state and plain `http` (section 9).

Earlier drafts: 4 changed the alerts from SMS to an HTTP request; 5 added the rules for the alert settings.

## 1. Goal

The phone runs as an SMS gateway. Its charger is plugged into the plug permanently, and the phone stays connected to that charger. The app keeps the phone's battery inside a band by switching the plug:

- Turn the plug **on** when the battery is at or below the low threshold (default 20%).
- Turn the plug **off** when the battery is at or above the high threshold (default 85%).
- Check the battery at a fixed interval chosen from 5, 10, 15, 30 or 60 minutes (default 15).
- Send an alert, as an HTTP request to a web address, when the phone is not plugged in at a low battery after the plug was turned on, or when the plug cannot be reached.
- Thresholds, intervals and the alert address are settings.

It must work unattended, with the app closed and the screen off, and use very little battery itself.

### What follows from "always on the charger"

- Plug on means the phone charges; plug off means it runs on battery. There is no other power source to allow for.
- The phone is unattended, so a notification on its screen may go unseen for days. Alerts go to a program on another machine.
- **The dangerous failure is the plug stuck off:** the phone drains, shuts down, and the SMS gateway stops. The plug stuck on only means the battery sits at 100%, which is what happens today without Battery Guard. Where the design has a choice, it fails towards on.

### What this changes in the main design

| Earlier decision | Now |
|---|---|
| The app talks to the plug only while it is on screen | A scheduled check also talks to the plug: to switch it when a switch is needed, otherwise a read-only check that it answers (`PLUG-WATCH.md`) |
| Schedules and automations are a non-goal | This one automation is in scope; general automations are still a non-goal |
| The app talks only to the plug | The app also sends HTTP requests to one address the user sets, for alerts |

### Out of scope

- Switching more than one plug. Battery Guard switches only the Charger plug.
- Sharing the plug with other apps or ecosystems.
- Any automation other than the battery band.

## 2. Terms

| Term | Meaning |
|---|---|
| **Battery Guard** | The feature: keeping the phone's battery inside a band by switching the charger plug |
| **Charger plug** | The plug that Battery Guard switches; the phone's charger is plugged into it |
| **Plugged in** | The phone is connected to a power source. The switching rule uses this, not whether the battery is charging |
| **Charging** | Android reports that the battery is taking charge (its battery status). A plugged-in phone can be not charging: full, or held at a charge limit such as Samsung's 85% "Protect battery" |
| **Low threshold** | Battery percentage at or below which the charger plug is turned on |
| **High threshold** | Battery percentage at or above which the charger plug is turned off |
| **Band** | The range between the two thresholds, where Battery Guard does nothing |
| **Check** | One scheduled look at the battery level, followed by a switch if the rule asks for one |
| **Check interval** | The time between checks. One value, used whether the phone is plugged in or on battery |
| **Alert** | A message sent when Battery Guard cannot keep the battery in the band |
| **Alert address** | The web address (URL) that receives alerts as HTTP requests |
| **Receiver** | The program on the computer that listens at the alert address |
| **Status report** | A short request the phone sends after every check, when switched on, to show that Battery Guard is alive |
| **Silence alarm** | The receiver's own alarm, raised when it hears nothing from the phone for too long |

"Phone" is the word used in anything the user reads; the glossary calls the same thing the **Device**.

## 3. Decision summary

| Decision | Choice | Reason |
|---|---|---|
| How the battery is watched | A scheduled check at the check interval; no always-running service | The app is not running between checks, so it uses almost no battery, and there is no service for the phone to kill |
| What a check costs | Wake, read the battery level, schedule the next check, exit; plus one small request if status reports are on | Most checks find the battery inside the band and do nothing else |
| When the Matter library is loaded | Only in a check that needs a switch | Loading it starts network discovery, which is the expensive part |
| What the switching rule reads | Whether the phone is plugged in to power, not whether the battery is charging | Independent of any charge limit the phone applies to itself: at 84% under an 85% limit the phone is plugged in but not charging, and must not count as on battery |
| What `charging` reports | Android's own battery status, with `plugged_in` and `battery_status` beside it (`ALERT-API.md` section 3) | The receiver sees what `dumpsys battery` shows, and can still tell "on power" from "taking charge" |
| What triggers a switch | Battery level and plugged-in state together (section 4) | Makes every check safe to repeat and avoids pointless commands |
| Manual use of the plug | The on-screen toggle always works; Battery Guard overrides it at the thresholds | The gateway's uptime comes first. To keep the plug off, switch Battery Guard off |
| Check interval | One drop-down list, 5/10/15/30/60 minutes, default 15, for every check | One setting is easier to understand; the overshoot above the high threshold (section 4) is set by it |
| Retries | None between checks. A failure is retried at the next check | Android runs alarms on an idle phone at least about 9 minutes apart, so shorter retry times cannot be met |
| How the user is told | An HTTP POST with a JSON body to the alert address, plus a notification on the phone | The server's response tells the app whether the alert arrived, so it can retry |
| What receives alerts | A small receiver program on the computer | No access token or body-format setting is needed |
| Alert address | Any http or https address: the receiver on the home network, or a public service | A public address also works over mobile data when the home Wi-Fi is down |
| Power cuts | The app does nothing special (section 8) | The checks already turn the plug on at the low threshold |
| Screen lock on the gateway phone | None | A locked phone does not run the app after a reboot until someone unlocks it |
| Failure direction | Towards on (section 6) | A plug stuck off stops the gateway |
| Which plug | The Charger plug: the first plug until the user taps "Use this plug for Battery Guard" on another. Removing the Charger plug switches Battery Guard off | Guard must never move to another plug (a lamp, say) by itself |
| Silence detection | An optional status report after every check; the receiver raises the silence alarm | The worst failures (dead phone, killed app, dead router) send no alert at all, so silence is the only signal |
| Alerts for Battery Guard's own faults | A third alert, raised at the second faulty check in a row | A phone nobody looks at needs to say when the guard is not working |
| Reminders | Fixed at 60 minutes, no quiet hours | Silence at 3 a.m. is the dangerous case for a gateway |

## 4. The rule

At each check the app reads two values from Android's battery state:

- `level`: battery percentage (0 to 100).
- `pluggedIn`: whether the phone is connected to a power source.

| Condition | Action |
|---|---|
| `level <= low` and not plugged in | Turn the charger plug **on** |
| `level >= high` and plugged in | Turn the charger plug **off** |
| Anything else | Do nothing |

Properties of this rule:

- **Safe to repeat.** If a switch did not work, the same condition is still true at the next check and the command is sent again. No memory of earlier checks is needed to switch.
- **Nothing inside the band** (except the manual use below).
- **No command when it would change nothing.** At a low level with the phone already plugged in, or a high level with it not plugged in, nothing is sent.
- **Independent of the phone's charge limit.** A phone that holds its charge at a limit is still plugged in, so the plug is turned off when the level reaches the high threshold. A limit set below the high threshold keeps the plug on, which is harmless.

Threshold limits: low 5 to 50, high 50 to 100, and high at least 10 above low. A high threshold of 100 means "never turn off": the second row of the table then never applies, and the plug stays on, as it does today without Battery Guard.

### Manual use of the plug

The on-screen toggle always works. Battery Guard looks at the battery again at the next check:

- Inside the band it leaves the plug as you set it.
- At or below the low threshold it turns the plug on again, even if you just turned it off.
- To keep the plug off for longer, switch Battery Guard off. If the plug is off at that moment the app asks: "The plug is off. Turn it on before disabling?"

There is no pause setting.

### When checks run

The time to the next check is the **check interval**, whether the phone is plugged in or on battery.

A check also runs at once when Battery Guard is enabled, when a setting changes, when **Check now** is pressed, and after the phone restarts.

After a switch **on** is confirmed, the same service run waits up to 30 seconds for the phone to report that it is plugged in (section 6). There are no other follow-up checks.

### How far past a threshold the battery can go

The plug is only switched at a check, so the battery passes the threshold first.

| Side | Set by | Effect |
|---|---|---|
| High threshold | Charging speed × the check interval | Can be large. A fast charger adds very roughly 1% per minute in the middle of the range, so 15 minutes can end several percent to well over 10% above the threshold, and 60 minutes can reach 100% |
| Low threshold | Drain rate × the check interval | Small. An idle gateway phone drains slowly, so even 60 minutes costs a few percent. On an idle phone Android spaces alarms at least about 9 minutes apart, so a 5-minute interval behaves as roughly 10 |

These figures are estimates; the real numbers come from test T6. To hold the top of the band tightly, choose a short check interval or lower the high threshold by the overshoot you measure.

## 5. Architecture

```
+------------------------------- Android app -------------------------------+
|  UI                                                                        |
|    PlugScreen : existing toggle, plus the Battery Guard settings section   |
|                                                                            |
|  CheckScheduler --(alarm at the check interval)--> CheckReceiver           |
|                                                        |                   |
|                                BatteryReader ---> GuardPolicy              |
|                                (level, pluggedIn)  (the rule, pure logic)  |
|                                                        |                   |
|                              nothing to do <-----------+-----> switch or   |
|                              (schedule next, exit)             send alert  |
|                                                                   |        |
|                                                          ActionService     |
|                                                          (runs for seconds)|
|                                                            |          |    |
|                                    AlertPolicy       PlugCommander    |    |
|                                        |                   |          |    |
|                                    HttpAlerter        ChipController  |    |
|                                        +------------------------------+    |
|                                                                            |
|  GuardSettingsStore (DataStore)   GuardNotifier   BootReceiver             |
+----------------------------------------------------------------------------+
```

| Component | Responsibility | New or existing |
|---|---|---|
| `GuardSettingsStore` | Enabled flag, thresholds, the check interval, alert address, the last check result, the count of failed checks in a row, the current alert state and the list of alerts not yet delivered | New; DataStore |
| `CheckScheduler` | Sets the next alarm (section 4, "When checks run"); cancels it when Battery Guard is disabled | New |
| `CheckReceiver` | Runs when the alarm fires: read, decide, schedule the next check, and start `ActionService` only if a switch is needed or an alert must be sent | New |
| `BatteryReader` | Reads the current level and plugged-in state from Android's battery state in one call. If the state cannot be read, the check does nothing and is retried at the next one | New |
| `GuardPolicy` | The rule in section 4 as a pure function: `(level, pluggedIn, settings) -> TurnOn, TurnOff or Nothing` | New; unit-tested without a phone |
| `ActionService` | A short foreground service that lives only for the seconds a switch or an alert send takes, including the 30-second wait for power after a switch on | New |
| `PlugCommander` | One switch: connect to the plug, send On or Off, read the state back, release the session | New, in the `matter` package; reuses the calls in `PlugClient` |
| `AlertPolicy` | Decides when an alert starts, repeats and ends (section 7), as a pure function | New; unit-tested without a phone |
| `HttpAlerter` | Sends one alert or status report as an HTTP POST, and keeps alerts that failed to send until they are delivered | New |
| `GuardNotifier` | The on-phone notification that mirrors each alert, and the brief "switching" notification | New |
| `BootReceiver` | Schedules the checks again after a restart or an app update, and runs one check at once | New |
| `ChipController` | Unchanged. Created only when `PlugCommander` or the plug screen needs it | Existing |

Two rules keep battery use low:

1. `CheckReceiver` must not touch `ChipController` or anything else in the `matter` package unless a switch is needed, and must not use the network unless an alert, a waiting request or a status report is to be sent.
2. No wake lock is held except while `ActionService` runs.

The plug screen keeps its own subscription while it is visible, so a switch made by Battery Guard appears there like a press of the plug's button.

## 6. Failure handling

Every case is re-evaluated at the next check, because the rule is safe to repeat.

| Situation | What Battery Guard does | Alert |
|---|---|---|
| A switch is needed and the plug cannot be reached | Counts a failed check and tries again at the next check, on both sides of the band | **Plug unreachable**, at the second failed check in a row |
| No switch is needed and the plug does not answer the read (`PLUG-WATCH.md`) | Counts a failed check, in the same count as a failed switch | **Plug unreachable**, at the second failed check in a row |
| A command was sent but not confirmed | Treated as "the plug cannot be reached" | As above |
| Battery low, plug turned on and confirmed, phone still not plugged in 30 seconds later | Sends On again at each later check while the phone is not plugged in | **Not charging**, at once |
| A check that needs no switch, or a successful switch | Resets the count of failed checks | None |
| Exact alarms not allowed, battery unreadable, or the service cannot start | The check does what it can; the fault is counted | **Battery Guard not working**, at the second faulty check in a row |
| A check with none of those faults | Resets the count of faulty checks | None |
| Phone restarted, or app updated | Checks are scheduled again and one runs at once | None |
| Battery Guard disabled by the user | Checks stop. If the plug is off, the app asks: "The plug is off. Turn it on before disabling?" | None |

The "plug unreachable" alert therefore arrives about one check interval after the first failure: at least about 9 minutes when the phone is idle on battery. At a low threshold of 20% there are hours of battery left, so this is acceptable.

### Limits the app cannot remove

| Limit | Consequence | What reduces it |
|---|---|---|
| Home Wi-Fi or the router is down while the plug is off | The phone cannot reach the plug and drains until it shuts down. **An alert to an address on the home network cannot be sent either**, because the same network is down. It is kept and delivered when the network returns | Set the alert address to a public address (for example a hosted push service), which the phone reaches over mobile data. A higher low threshold gives more time to act. With status reports on, the receiver's silence alarm also tells you |
| The alert server is off or unreachable | Alerts are not delivered | The failure is shown in the settings status line, and alerts are kept and delivered when the server returns (section 7) |
| The phone shuts down or the app is force-stopped while the plug is off | Nothing turns the plug on until someone presses its button, and no alert can be sent | Exempt the app from battery optimisation; do not force-stop it. With status reports on, the silence alarm tells you |
| The phone reboots while a screen lock is set | Android keeps the app's data unreadable until someone unlocks the phone, so no check runs and no alert is sent | The gateway phone has no screen lock (section 9) |
| A mains power cut | The plug returns in whatever state it decides | Harmless; see section 8 |

## 7. Alerts by HTTP request

### When an alert is sent

| Alert | Starts when | Ends when |
|---|---|---|
| **Not charging** | The battery is at or below the low threshold, the plug was turned on and confirmed, and the phone is still not plugged in 30 seconds later | The phone is plugged in |
| **Plug unreachable** | The plug could not be reached at two checks in a row, by a switch (either direction) or, with no switch needed, by the read-only check (`PLUG-WATCH.md`) | The plug is reached again: a switch succeeds or a read answers. A check with no switch needed no longer ends it |
| **Battery Guard not working** | A check finds a fault of Battery Guard itself (exact alarms not allowed, battery unreadable, service could not start) at two checks in a row | A check finds none of those faults |

The first condition is not "battery low and not plugged in" at the moment the threshold is reached: that is true for a few seconds in every normal cycle, just before the plug is turned on. The alert is for when turning the plug on did not power the phone.

### How often

| Event | Request |
|---|---|
| An alert starts | One request at once |
| The alert is still active | One reminder every 60 minutes, with the current battery level |
| The alert ends | One "recovered" request |

Each alert type is tracked separately. There is no quiet period: a reminder is sent every 60 minutes at any hour. The alert state is stored, so a restart does not send duplicates.

### The request

- Method **POST** to the alert address, `Content-Type: application/json`, a 10-second timeout.
- The body is a small JSON object:

```json
{
  "alert": "plug_unreachable",
  "state": "started",
  "message": "Matter Plug: battery 18%, not charging. The charger plug cannot be reached.",
  "battery": 18,
  "charging": false,
  "plugged_in": false,
  "battery_status": "discharging",
  "time": "2026-11-11T09:47:12-05:00"
}
```

| Field | Values |
|---|---|
| `alert` | `not_charging`, `plug_unreachable`, `guard_degraded`, `status`, or `test` |
| `state` | `started`, `reminder`, `recovered`, `status`, or `test` |
| `message` | A ready-to-display sentence (the texts below) |
| `battery` | Battery percentage when the alert was raised |
| `charging` | Whether the battery was taking charge (Android's battery status is charging) |
| `plugged_in` | Whether the phone was plugged in to power |
| `battery_status` | Android's battery status: `charging`, `discharging`, `not_charging`, `full` or `unknown` |
| `time` | When the event happened, with the UTC offset. This matters when a request is delivered late |
| `next_check_in_min` | Only in a status report: minutes until the next check, so the receiver knows when to expect the next one |

- A response with a status from **200 to 299** means delivered. Anything else, a timeout or no connection means not delivered.
- Redirects are not followed.
- No access token is sent, and the body is always JSON.

### Message text

| Case | `message` |
|---|---|
| Not charging | `Matter Plug: battery 18%, not charging. The plug is on. Check the charger and cable.` |
| Plug unreachable, low | `Matter Plug: battery 18%, not charging. The charger plug cannot be reached.` |
| Plug unreachable, high | `Matter Plug: battery 87%. The charger plug cannot be reached to turn it off.` |
| Battery Guard not working | `Matter Plug: Battery Guard is not working: exact alarms not allowed. Battery 18%.` (the reason is one of `exact alarms not allowed`, `battery unreadable`, `service could not start`) |
| Reminder | The same text, starting `Matter Plug (reminder):` |
| Recovered | `Matter Plug: recovered. Battery 24%, charging.` (the power words as in the status report) |
| Status report | `Matter Plug: status. Battery 64%, charging.`, `... plugged in, full.`, `... plugged in, not charging.`, `... plugged in, discharging.`, `... plugged in.` (status unknown) or `... on battery.` |

### Status reports

- A switch, **Send status after every check**, off by default. It needs a valid alert address and works whether or not **Send alerts** is on.
- When on, every check ends with one status request (`alert` and `state` both `status`, plus `next_check_in_min`).
- A status report is not kept if it fails: a late one is worthless. A failure is shown in the status line, and does not raise an alert, because the receiver's silence alarm is the cover for it.
- A status report makes one small network request per check; this is the cost of knowing the phone is alive.

### When a request fails

- A request that is not delivered is kept, in order, with its original `time`.
- Kept requests are sent again at the next check, and also when the phone joins a Wi-Fi network. Each is sent oldest first, and the sending stops at the first one that fails.
- Reminders are not kept: a late reminder is out of date. A `started` and a `recovered` request are both kept, so the receiver sees what happened.
- The settings status line shows the last failure and how many requests are waiting.

### The receiver

A small Go program for the computer, written as part of this project (build step G6). It listens at the alert address, prints each request it receives, and runs a notification command for `started` and `reminder` requests. It is also the tool for testing: pointing the app at it proves the whole path.

**Silence alarm.** Once a status report has told the receiver when the next check is due, it expects some request from the phone within three intervals (at least three times 10 minutes, because an idle phone spaces alarms at least about 9 minutes apart), plus one minute. If nothing arrives it logs `SILENT`, runs the notification command, and repeats every 60 minutes until a request arrives; it then logs `BACK` and notifies once more. Any valid request counts as a sign of life. A limit in minutes can be set by hand instead.

### Settings and behaviour

- **Send alerts** switch, **Send status after every check** switch and an **Alert address** field. The address must start with `http://` or `https://`. The switch cannot be turned on without an address.
- A **Send test alert** button posts a request with `alert` and `state` both `test` and shows the response status, so the address and the receiver are proven before they are needed.
- Every alert is also posted as a notification on the phone.
- The address can be the receiver on the home network, for example `http://192.168.1.20:8080/battery`, or a public address. A public address sends the alert text outside the home network; it contains only the battery level and the plug's state.

### Rules for the alert settings

| Action | What happens |
|---|---|
| **Send alerts** switched on | An alert address is required. Nothing is sent just because the switch was turned on; alerts are raised from the next check |
| **Send alerts** switched off | New alerts and reminders stop. No "recovered" request is sent. The list of waiting alerts is cleared. Notifications on the phone continue |
| **Alert address** changed | Waiting alerts are sent to the new address. Alerts that were already delivered are not sent again |
| **Send test alert** pressed | Works whether or not **Send alerts** is on, as long as the address is valid. It posts a `test` request, shows the response status or the failure, and does not change the alert state or the waiting list |
| Phone restarted | The alert state, the count of failed checks, the time of the last reminder and the waiting list are restored from storage, so no duplicate alert is sent and reminders keep their hourly spacing |
| **Send status after every check** switched on or off | Takes effect at the next check. Nothing waiting is cleared. Turning it off stops the reports, and the receiver's silence alarm will then fire; tell the receiver by stopping it, or expect the alarm |
| **Keep battery between** switched off | There are no checks, so no new alerts or status reports. An alert that is active at that moment is cleared: no "recovered" request is sent, reminders stop, and the list of waiting alerts is emptied |

### Network rules on Android

- Android blocks plain `http` by default. The app's network configuration allows it for every host, because the address is chosen by the user at run time, the configuration is fixed when the app is built, and it cannot match address ranges. Use `https` for any address outside the home network.
- Without the battery-optimisation exemption, Android cuts network access for an idle app, so the exemption (section 9) also matters for alerts.
- A request made while the phone is on mobile data reaches a public address but not a home-network address.

## 8. Power cuts

A power cut stops the router and the plug; the phone keeps running on its battery. When power returns, the plug comes back in whatever state the plug decides, and the app does not try to change that. It does not need to: the checks keep running, and at the low threshold the plug is turned on whatever its state.

- A plug that comes back off while the battery is above the low threshold only delays charging.
- A plug that comes back on charges the phone early, and is turned off again at the high threshold.

The Matter start-up setting, which can make a plug come back on, is not used. It belongs to the Lighting feature of the on/off cluster, which many plugs lack, and it adds no protection the checks do not already give.

## 9. Android requirements

| Requirement | Detail |
|---|---|
| Alarms at the check interval | Exact alarms that are allowed while the phone is idle, each one scheduling the next. Declare `USE_EXACT_ALARM`: Android grants it automatically, and the Play Store rule that restricts it does not apply to a sideloaded app. If it is revoked, exact alarms are cancelled, so the app checks at start-up and shows a warning in the status line. (Verify the default grant on Android 12 and 13.) |
| Idle limit | On an idle phone running on battery, Android allows one such alarm per app about every 9 minutes, so an on-battery interval of 5 minutes behaves as roughly 10. A phone that is plugged in is not in this state, so the chosen interval is kept. Whether the battery-optimisation exemption relaxes the limit is not documented and is tested in T14 |
| Alarms and restarts | Alarms are cancelled when the phone shuts down, so `BootReceiver` sets them again. It needs `RECEIVE_BOOT_COMPLETED`, and the app must have been opened once since it was installed |
| Short foreground service | For a switch or an alert send only. Type `connectedDevice`, with the `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_CONNECTED_DEVICE` permissions; the app's existing `CHANGE_WIFI_MULTICAST_STATE` permission satisfies the type's prerequisite. A service started by an exact alarm, or by an app on the battery-optimisation allowlist, is allowed to start from the background on Android 12 and above. `shortService` is not used: it is cut off after about 3 minutes and the app crashes if the service does not stop in time. Below Android 14 no type is needed |
| Reading the battery | The sticky battery-changed intent, read with `registerReceiver(null, filter)` in the receiver. Level is `EXTRA_LEVEL` × 100 / `EXTRA_SCALE`. Plugged in means `EXTRA_PLUGGED` is not 0 (tested as a bit mask). The intent can be null, which `BatteryReader` treats as "cannot read" |
| Network | The `INTERNET` permission, already needed. Plain-`http` rule in section 7 |
| Battery-optimisation exemption | Ask for it when Battery Guard is first enabled. It lets the check use the network while the phone is idle and lets the service start from the background |
| Notification permission | Android 13 and above: ask for `POST_NOTIFICATIONS` when Battery Guard is first enabled |
| Nothing from Google | No Play services; unchanged from the main design |

No SMS permission is needed.

### Phone settings for the gateway phone

- **No screen lock** (no PIN, pattern or password). With a lock, Android keeps the app's data unreadable after a reboot until someone unlocks the phone, so after a power cut nothing would run.
- Exempt the app from battery optimisation, and on Samsung add it to "Never sleeping apps".
- The phone's own charge limit (Samsung "Protect battery") no longer stops the plug being turned off, but a limit below the high threshold keeps the plug on. What the battery state reports at the limit is not documented, so it is recorded on the real phone (T16). First observation on the Galaxy S20 FE (2026-11-11, USB powered at 85%): `status` was 4 (not charging) while a power source was connected, and `level` stayed at 85. The rule uses only "plugged in", so it works at the limit.
- **Nothing else may power the phone.** Android counts any power source as "plugged in", including the USB cable to a computer. With another power source connected, the phone stays plugged in after the charger plug is switched off, so Battery Guard keeps sending Off at every check and the battery never drains. Only the charger on the charger plug stays connected. For testing, use wireless debugging instead of the USB cable.
- Keep Wi-Fi on while the screen is off.
- Open the app once after installing it, so that it receives the restart signal.

## 10. Settings screen

A "Battery Guard" section on the Charger plug's screen. On any other plug the section is replaced by one button, **Use this plug for Battery Guard**:

| Control | Type | Values | Default |
|---|---|---|---|
| **Keep battery between** | Switch | On, off | Off |
| **Turn on** | Number box, shown as "Turn on [20]%" at the left of one row | 5% to 50% | 20% |
| **Turn off** | Number box, shown as "Turn off [85]%" at the right of the same row | 50% to 100% | 85% |
| **Check every** | Drop-down list | 5, 10, 15, 30, 60 minutes | 15 minutes |
| **Send alerts** | Switch | On, off | Off |
| **Send status after every check** | Switch | On, off | Off |
| **Alert address** | Text field | An `http://` or `https://` address | Empty |
| **Send test alert** | Button | | |
| **Check now** | Button | | |

Below the controls:

- Status: "Last check 21:40: battery 64%, charging. Next check 21:55." The power words are those of the status report: "charging", "plugged in, not charging", "on battery" and so on. and "Last switch: off at 87%, 21:10."
- Alert state: "No active alerts" or "Plug unreachable since 20:15, last alert sent 21:15."
- Delivery: "Last alert delivered 21:15" or "Last alert failed 21:15 (no connection). 2 waiting."
- Test result: shown next to the test button, for example "200 OK" or "no connection". The button works with **Send alerts** off.
- Warning, when it applies: "Exact alarms are not allowed. Checks may be late."
- The number boxes take digits only. A value is saved when the user presses Done or leaves the box, and only when it is in range and keeps the 10-point gap between the thresholds; otherwise the box turns red with the allowed range ("Enter 5 to 50.") and nothing is saved.
- Note at the bottom: "The phone's charger must stay plugged into this plug. If the phone switches off while the plug is off, press the plug's button to charge it."

## 11. Risks

| # | Risk | Mitigation |
|---|---|---|
| 1 | **The plug is stuck off and the gateway phone dies** (section 6 limits) | Fail towards on; alerts; no screen lock; status reports and the receiver's silence alarm |
| 2 | **The phone maker blocks the alarms or kills the app** | Battery-optimisation exemption and "Never sleeping apps". Alarms restart the app even after it is killed, which a service cannot do |
| 3 | **Overshoot above the high threshold** with a long check interval | Measured in T6; choose a short interval or a lower high threshold |
| 4 | **Checks drift or stop** after an Android update, a force-stop, or a permission change | The status line shows the last and next check, so a stopped schedule is visible; a check also runs whenever the app is opened |
| 5 | **An alert is not delivered** (home network down, receiver off, wrong address) | The test button; undelivered alerts are kept and sent later; the failure is shown in the status line; a public alert address avoids the home-network dependency |
| 6 | **A home-network alert address fails exactly when it is needed** (the router that is down is also the route to the receiver) | State in the settings screen; the silence alarm catches a dead router when status reports are on |
| 7 | **Plain `http` is allowed for any host** | Use `https` outside the home network; the alert contains only the battery level and plug state |
| 8 | **Each switch starts the Matter library from cold** and takes several seconds | Acceptable: a switch happens about twice per charge cycle |
| 9 | **Deep discharge cycles wear the battery** more than holding a narrow band | The thresholds are the user's choice; the defaults are as requested |
| 10 | **The "plugged in" reading differs on this phone** (for example at a charge limit) | Recorded on the real phone in T16 before relying on it |
| 11 | **Android 16 and later add a "local network" permission** that affects both the Matter connection and a home-network alert address. It is not enforced yet | Watch for it when raising the target Android version; the app is built for the version in the main design |
| 12 | **Exact alarms are cancelled if the permission is revoked** | Checked at every start; warning in the status line |

## 12. Build order

| # | Step | Done when |
|---|---|---|
| G1 | `GuardPolicy`, `AlertPolicy` and their unit tests | Every row of the rule table, the threshold limits, the failed-check count and the alert start, repeat and end rules pass |
| G2 | `GuardSettingsStore` and the settings section | Values survive an app restart; invalid combinations cannot be set |
| G3 | `BatteryReader`, `CheckScheduler`, `CheckReceiver`, logging decisions only | With the app closed, the log shows checks at the right interval for each state, with the right decision; no plug commands yet |
| G4 | `PlugCommander` and `ActionService` wired in | The plug switches at both thresholds with the app closed and the screen off |
| G5 | Failed-check counting, the 30-second power wait, on-phone notifications | Section 6 behaves as written |
| G6 | `HttpAlerter` wired to `AlertPolicy`, status reports, the test button, the undelivered-alert list, and the Go receiver program for the computer with its silence alarm | Each alert in section 7 arrives once, repeats hourly, is followed by a recovered request, is delivered late after a failure, and the silence alarm fires and clears |
| G7 | `BootReceiver`, exemption prompt, "Check now", status line | Works after a restart without opening the app |

To test without waiting for the battery, set the thresholds close to the current level (for example low 60, high 70) and the check interval to 5 minutes. Run the receiver on the computer and point the alert address at it.

## 13. Test plan

| # | Test | Expected |
|---|---|---|
| T1 | Unit: `GuardPolicy` over the rule table | Correct action in every case |
| T2 | Unit: `AlertPolicy` over start, reminder, recovery, the failed-check count and the undelivered list | Correct request decisions, no duplicates after a simulated restart, reminders not kept |
| T3 | App closed, screen off, plugged in past the high threshold | Plug turns off at the first check after the threshold |
| T4 | App closed, screen off, draining to the low threshold | Plug turns on at the first check after the threshold; the phone is plugged in within 30 seconds; no alert |
| T5 | Switch the plug by hand inside the band; then switch it off by hand at or below the low threshold | Inside the band Battery Guard does not undo it; at or below the threshold it turns the plug on again at the next check |
| T6 | Each check interval (5, 15, 60 minutes) over a full cycle | The levels at which the plug actually switched are recorded, giving the real overshoot |
| T7 | Plug unpowered at the low threshold | A `plug_unreachable` request at the second check in a row; a reminder an hour later; a `recovered` request once the plug is powered and the phone is plugged in |
| T8 | Charger cable pulled out at the low threshold | A `not_charging` request about 30 seconds after the plug is turned on; a `recovered` request after the cable is back |
| T9 | Router off while the plug is off and the battery reaches the low threshold, with a home-network alert address | No alert can be sent; the status line shows the failure; the plug turns on at the first check after Wi-Fi returns, and the waiting alerts are delivered with their original times |
| T10 | The same as T9, with a public alert address | The alert arrives over mobile data while the router is off |
| T11 | Receiver stopped, then an alert raised, then the receiver started | Failure shown; the alert is delivered at the next check after the receiver returns |
| T12 | Cut and restore power to the plug while it is off and the battery is inside the band | Checks continue; the plug is turned on at the low threshold whatever state it returned in |
| T13 | Restart the phone with Battery Guard enabled and no screen lock | Checks resume without opening the app; no duplicate alerts |
| T14 | Overnight on battery, phone idle, with the exemption granted | Checks keep arriving, and the spacing between them is recorded (does the exemption relax the 9-minute limit?); the plug turns on at the low threshold |
| T15 | Several days unattended as the gateway | The battery stays inside the band plus the measured overshoot; the app's share of battery use is small |
| T16 | On the real phone, with its own charge limit on and off, read what the battery state reports while plugged in at and below the limit | The reading is recorded; the plug is turned off at the high threshold; a limit below the high threshold leaves the plug on |
| T17 | Alert settings rules (section 7): switch **Send alerts** off during an active alert; change the address while alerts are waiting; press the test button with alerts off; restart the phone during an active alert; switch **Keep battery between** off during an active alert | Reminders stop, no "recovered" request is sent and the waiting list is empty (in both the alerts-off and the Battery Guard-off cases); the waiting alerts arrive at the new address; a `test` request arrives and nothing else changes; no duplicate alert is sent and the hourly reminders continue |
| T18 | Restart the phone with a screen lock set | Documented behaviour: nothing runs until the phone is unlocked |
| T19 | Status reports on; let checks run, then switch the phone off (or switch Battery Guard off) | Status requests arrive every interval; after about three intervals plus a minute the receiver logs `SILENT` and notifies, repeats hourly, and logs `BACK` when reports resume |
| T20 | Revoke exact alarms (where Android allows it), or block the service, for two checks in a row | A `guard_degraded` request arrives with the reason; a `recovered` request follows when the fault is gone |

## 14. Open items

Settled in draft 7: the gateway phone (Galaxy S20 FE, Android 13), silence detection, the third alert, fixed hourly reminders with no quiet hours, the home-network address limit, and the receiver language (Go).

Things to verify on the real phone:

1. Whether the battery-optimisation exemption relaxes the 9-minute alarm limit (T14).
2. What the battery state reports at the phone's charge limit (T16).
3. Whether Android accepts an IP address as the host with plain `http` allowed for every host (not needed while `http` is allowed everywhere).
4. The default grant of the exact-alarm permission on Android 12 and 13. The gateway phone is on Android 13, so this one is tested directly.
5. Whether a status request made from the alarm's receiver, without the service, finishes inside the receiver's time limit on this phone (G6).
