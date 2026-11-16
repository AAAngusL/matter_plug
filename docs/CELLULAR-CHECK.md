# Cellular Check: Design

Status: built, 2026-11-14 (draft 5, approved; steps C1 to C4 done).

Verified on the SM-G780F: a normal passive run; airplane mode (registration fails, SIM passes); daily and manual loopbacks (sent, delivered, received back in under 2 s); a wrong own number (fails at "not received back"); and a `cellular_down` started and recovered alert reaching the receiver. Also verified: after Android ended the app's process (`am kill` with the app in the background), the next alarm started a new process and ran; with the SIM removed, the run failed SIM, skipped registration and signal, and the confirmation SMS failed at once without reaching the network; with the SIM back, a manual loopback passed and cleared the failure. With the SIM removed again, the second failed loopback started `cellular_down` (failed layer `sim`) and the user's server accepted it (200); with the SIM back, a passing loopback sent the recovered alert (200). Nothing here is built yet. Adds to `BATTERY-GUARD.md` and reuses its alarm chain, alert requests, status report and receiver.

Changes in draft 5, from the review of draft 4:

- **The gateway software only sends**, so the loopback needs no filter and stays on the phone's own number (section 7). The two gateway risks are gone.
- **Cellular Check has its own screen**, opened from the ⋮ menu on the plug screen (section 10).
- **A fault only SMS shows is re-tested once a day** at the test time, as proposed.
- **Signal fails below 1 bar.** This is a fixed rule now: the **Signal floor** setting is removed.
- **The passive layers reuse Battery Guard's Check every value.** The Cellular Check screen shows it, and it is changed in the Battery Guard section.

Changes in draft 4, from the review of draft 3:

- **No re-confirming while a passive layer still fails.** Once the alert has started, no loopback is sent until the passive layers pass again; then one loopback proves the recovery (former question 3). A four-hour outage now sends three test SMS, not six.
- **A confirmation loopback follows the first bad reading** (former question 4, kept as proposed).
- **An SMS-only fault** (the daily loopback failed while the passive layers look fine) is re-tested once a day at the test time until a loopback passes, because no passive change will ever signal its recovery. Question 3 asks whether that is often enough.
- **Why the gateway software matters** is now explained in section 7, and question 1 asks what the gateway does with incoming SMS.

Changes in draft 3:

- **The SMS loopback is the confirmation.** When a passive layer (SIM, registration, signal) fails, a loopback SMS runs at once to confirm that SMS service is really unavailable. A passive failure alone never raises an alert (section 6).
- **Otherwise, one loopback a day.** While every passive layer passes, a loopback runs once a day at the set time, to catch SMS faults the radio cannot show.
- The SMS layer is no longer skipped when a passive layer fails: it is exactly what runs then.
- One alert rule for every case: **two failed loopbacks in a row, 15 minutes apart**, start the alert. **Recovered** needs a passing loopback (section 8).
- The SMS test is part of the check, not a separate switch: **Own number** is required to turn the check on.

Changes in draft 2: the internet and voice tests were removed; four layers remain.

## 1. Goal

The gateway phone exists to send and receive SMS. Battery Guard keeps it powered; nothing yet proves that it can still reach the mobile network. **Cellular Check** watches the phone's mobile service and proves an SMS fault with a real SMS before it raises an alert.

Signal strength alone proves little. A phone can show full bars and still be unable to send SMS (a barred SIM, an expired plan, an SMS centre fault). And a passive reading can look bad for a moment while SMS still works. So the passive layers are the early warning, and the loopback SMS is the proof in both directions.

### Out of scope

- Mobile data and internet reachability.
- Voice calls.
- Reading the content of SMS that are not the app's own test messages.
- Fixing the problem (toggling airplane mode, restarting the modem). The app only reports.
- Phones with more than one active SIM, in version 1. The check uses the default SMS subscription (section 9).

## 2. Terms

| Term | Meaning |
|---|---|
| **Cellular Check** | The feature: passive readings of the phone's mobile service, proven by loopback SMS |
| **Layer** | One thing that can fail on its own: SIM, registration, signal, SMS |
| **Passive layer** | SIM, registration or signal: read from Android without sending anything, so it costs nothing |
| **Loopback SMS** | A test SMS the phone sends to its own number, then waits to receive |
| **Confirmation loopback** | A loopback run because a passive layer failed, to confirm that SMS is really unavailable |
| **Daily loopback** | A loopback run once a day while every passive layer passes |
| **Cellular run** | One pass over the passive layers, with a loopback when one is due |

These go into `CONTEXT.md` once the design is accepted.

## 3. Decision summary

| Decision | Choice | Why |
|---|---|---|
| Which layers | SIM, registration, signal, SMS, each with its own result | A pass on one layer never stands in for another |
| Passive layers | Read at every cellular run, every **Check every** minutes: the value Battery Guard uses (default 15) | Free and instant: the early warning. One interval for the whole app |
| When a passive layer fails | A confirmation loopback at once, after the first bad reading | Fastest; a passive reading can be wrong for a moment, and only an SMS proves SMS is down |
| While every passive layer passes | One daily loopback at a set time | Catches SMS faults the radio cannot show (barred SIM, SMS centre) |
| Alert rule | Two failed loopbacks in a row, 15 minutes apart | One rule for both triggers; one bad SMS is not enough |
| Recovered | A loopback passes | "Recovered" means SMS proven again, not only good bars |
| During a fault the passive layers can see | No loopback while a passive layer still fails; one loopback when they all pass again | Nothing can have recovered while the radio still reports the fault, and a long outage sends no SMS |
| During a fault only SMS shows | One loopback a day at the test time until one passes | The passive layers pass all along, so they cannot announce the recovery |
| Signal | Fails at 0 bars (level 0 of 0–4); 1 bar or more passes. A fixed rule, no setting | A phone with no bars cannot be trusted with SMS; anything above that is reported, not judged |
| Loopback target | The phone's own number, no filter | The gateway software only sends, so an incoming test SMS changes nothing (section 7) |
| Schedule | Its own alarm, independent of Battery Guard, at Battery Guard's interval | Works when Battery Guard is off; same pattern as `CheckScheduler` |
| Where it is shown | Its own **Cellular Check** screen, from the ⋮ menu | The plug screen is already long, and the check is about the phone, not a plug |
| Reporting | A new alert `cellular_down` plus a `cellular` object in the status report | Uses the existing receiver and delivery queue |

## 4. The layers

| # | Layer | Pass when | Android interface | Kind |
|---|---|---|---|---|
| 1 | SIM ready | The default SMS subscription's SIM state is `SIM_STATE_READY` | `SubscriptionManager.getDefaultSmsSubscriptionId()`, `TelephonyManager.getSimState(slot)` | Passive |
| 2 | Registered | Registered home or roaming in the voice domain (`DOMAIN_CS`), or IMS-registered, which is what SMS uses. Roaming is a pass, but reported. Data registration is reported only | `ServiceState.getNetworkRegistrationInfoList()`, `getState() == STATE_IN_SERVICE` | Passive |
| 3 | Signal | A level is readable and is 1 or more (of 0–4): fails at 0 bars | `SignalStrength.getCellSignalStrengths()`: LTE `rsrp` / `rsrq` / `rssnr`, NR `ssRsrp` / `ssRsrq` / `ssSinr`, plus `getLevel()` | Passive |
| 4 | SMS | The loopback SMS is **sent** (`RESULT_OK`) and **received** back within 5 min; the delivery report is recorded too | `SmsManager.sendTextMessage` with sent and delivery `PendingIntent`s; `SMS_RECEIVED` broadcast | Active (one SMS) |

Signal is reported as numbers in every result. At 0 bars it counts as a passive failure, so it triggers a confirmation loopback; it never raises an alert by itself.

## 5. The result

The data shape comes first; the logic is pure functions over it (`logic/CellularVerdict.kt` and `logic/LoopbackPlan.kt`, unit-tested like `GuardPolicy`).

```kotlin
enum class Layer { Sim, Registration, Signal, Sms }

sealed interface LayerResult {
    data class Pass(val detail: String) : LayerResult           // "LTE, RSRP -97 dBm, RSRQ -11 dB, SINR 8 dB"
    data class Fail(val reason: String) : LayerResult           // "SIM absent", "not received back in 5 min"
    data class Skipped(val because: Layer) : LayerResult        // a passive layer it needs failed
    data object NotDue : LayerResult                            // no loopback in this run; the last result stays shown
}

enum class LoopbackTrigger { Daily, Confirmation, Manual }

data class CellularRun(
    val at: Long,
    val results: Map<Layer, LayerResult>,
    val loopbackTrigger: LoopbackTrigger?,                      // null when no loopback ran
)
```

**Dependency rule, passive layers only.** Registration needs SIM; Signal needs Registration. A passive layer whose prerequisite failed is `Skipped`, so a missing SIM gives one passive failure, not three. The SMS layer is never skipped: a passive failure is the reason it runs.

**What a loopback proves.**

| Passive layers | Loopback | Meaning | Shown as |
|---|---|---|---|
| All pass | Pass (daily) | SMS works | Healthy |
| All pass | Fail (daily) | SMS is broken although the radio looks fine | A failed loopback (counts towards the alert) |
| One fails | Pass (confirmation) | The passive reading was misleading; SMS works | **Warning**: "SIM/registration/signal looked down, but SMS works". No alert |
| One fails | Fail (confirmation) | SMS service is unavailable | A failed loopback (counts towards the alert) |

## 6. When runs happen

| Trigger | What runs |
|---|---|
| Cellular alarm, every **Check every** minutes (Battery Guard's value, default 15; it applies even when Battery Guard is off) | Layers 1–3 |
| A passive layer fails, and no loopback has failed in this fault yet | Layer 4 at once, as a **confirmation loopback** |
| The first confirmation loopback failed | One more loopback at the next run, 15 minutes later: two failures start the alert |
| The alert is active and a passive layer still fails | No loopback. Nothing is sent until the passive layers pass again |
| The alert is active and every passive layer passes again | A loopback at once, to prove the recovery. If it fails, the fault has become one only SMS shows (next row) |
| The alert is active, every passive layer passes, and the last loopback failed | One loopback a day after the **SMS test time**, until one passes |
| Every passive layer passes, no alert, after the **SMS test time** (default 10:00) and no loopback passed today | Layer 4 as the **daily loopback** |
| **Run cellular check now** button | Layers 1–4, whatever the state: the way to re-test by hand |

A day with no fault therefore sends exactly one SMS. A fault the passive layers can see sends three, however long it lasts: two to confirm it, one to prove recovery. A fault only SMS shows sends one a day until it is fixed.

The same passive fault does not trigger a second pair of confirmations while it lasts. A new pair is sent only after a recovery, or when a different passive layer fails.

When the SIM is absent or the radio is off, the send fails on the phone at once (`RESULT_ERROR_NO_SERVICE` or `RADIO_OFF`) and nothing reaches the network, so these confirmations cost nothing.

The alarm is a second exact alarm, scheduled the same way as Battery Guard's (`CheckScheduler`), so the idle-phone limits in `BATTERY-GUARD.md` section 9 apply. Layers 1–3 run inside the alarm receiver (no service). A loopback runs in a foreground service, because it waits up to 5 minutes for the message to come back. A run never starts a second loopback while one is still waiting.

## 7. The SMS loopback

1. The **Own number** setting holds the phone's number. Android often cannot read it reliably, so it is typed once by the user. The check cannot be turned on without it.
2. Body: `MPCHK <random 6 characters>`, so the receiving side can recognise and drop it.
3. Send with a sent and a delivery `PendingIntent`. Three steps are recorded separately:

| Step | Pass | Fail reason |
|---|---|---|
| Sent | Sent intent `RESULT_OK` within 60 s | `RESULT_ERROR_NO_SERVICE`, `RADIO_OFF`, `GENERIC_FAILURE` (with the error code), or no answer |
| Delivered | Delivery report with status "complete" within 5 min | Report says failed, or no report (some networks never send one: recorded as `no delivery report`, which is a **warning**, not a fail) |
| Received | An `SMS_RECEIVED` with the same token within 5 min | `sent but not received back` |

The loopback passes when **Sent** and **Received** pass. A message that comes back is stronger proof than a delivery report, so a missing delivery report alone does not fail it.

**The gateway software.** The phone is an SMS gateway, so its gateway software also sees every incoming SMS, including the loopback. It only sends and ignores incoming messages, so the loopback changes nothing there: it stays in the phone's inbox. No filter is needed, and the loopback goes to the phone's own number.

The `MPCHK` prefix stays, so a person reading the inbox can tell the test messages apart. If the gateway software is ever changed to forward, answer or count incoming SMS, it must drop messages that start with `MPCHK`, or each test would reach the server, trigger a reply to the phone itself, or be counted (risk 1).

## 8. Alerts and status report

> **Changed by `SIGNAL-STRENGTH.md` draft 5:** Cellular Check sends its own status report (`cellular_status`) and its alerts under its own switches, **Send cellular alerts** and **Send cellular status after every check**, sharing only the alert address and token. Battery Guard's status report no longer carries the `cellular` object. Where this section says otherwise, that draft wins.

A fourth alert joins the three in `BATTERY-GUARD.md` section 7, with the same reminder rules:

| Alert | Starts when | Ends when |
|---|---|---|
| **Cellular down** | Two loopbacks in a row fail, 15 minutes apart, whatever triggered them | A loopback passes |

Reminders follow the existing 60-minute rule. Each reminder carries the latest passive readings and the latest loopback result.

```json
{
  "alert": "cellular_down",
  "state": "started",
  "message": "Matter Plug: SMS service unavailable: not registered (out of service); the test SMS was not sent: no service. Battery 64%.",
  "battery": 64,
  "charging": true,
  "plugged_in": true,
  "battery_status": "charging",
  "time": "2026-11-14T10:20:12-05:00",
  "cellular": {
    "trigger": "confirmation",
    "failed_layer": "registration",
    "reason": "not registered",
    "sim": "pass", "registration": "fail", "signal": "skipped",
    "sms": "fail", "sms_reason": "not sent: no service",
    "signal_detail": "none"
  }
}
```

| `trigger` | `failed_layer` | Message |
|---|---|---|
| any | A passive layer | `Matter Plug: SMS service unavailable: <reason>; the test SMS was <sms reason>. Battery 64%.` For registration the reason reads `not registered (<reason>)` |
| any | `sms` | `Matter Plug: SMS is not working although the signal is fine; the test SMS was <sms reason>. Battery 64%.` |
| any | (recovered) | `Matter Plug: SMS works again. Battery 64%.` |

As built (C4), the text follows the failed layer rather than the trigger, and `trigger` can also be `recovery` or `manual`.

- The status report gains the same `cellular` object, so the receiver shows the last result even when nothing has failed. A **warning** (a passive failure that a passing loopback disproved) appears there, not as an alert.
- The alert travels by the same queue and the same alert address as the others, over whatever network works. A cellular fault usually leaves Wi-Fi up, so the alert still arrives. If both are down, the receiver's silence alarm is the cover, as today.
- The receiver (`tools/alert-receiver`) learns the new `alert` value and prints the `cellular` object. It needs no other change.

## 9. Android requirements

| Need | Permission or setting | Note |
|---|---|---|
| SIM state, service state, signal | `READ_PHONE_STATE` (runtime) | VERIFY on Android 13 whether `getServiceState()` also needs location for the registration fields; cell identity is not used |
| Send SMS | `SEND_SMS` (runtime) | Allowed for a sideloaded app that is not the default SMS app |
| Receive the loopback | `RECEIVE_SMS` (runtime) | Every app with the permission gets `SMS_RECEIVED`; the default SMS app still stores the message |
| Service for the loopback | `FOREGROUND_SERVICE_DATA_SYNC`, type `dataSync` | VERIFY: Android 14 limits `dataSync` to 6 hours a day, far more than needed |
| Subscription | Default SMS subscription | On a dual-SIM phone the default may change; the result names the subscription it tested |

All three runtime permissions are asked when the user turns the check on, never at start-up, as Battery Guard does. Without `SEND_SMS` and `RECEIVE_SMS` the check cannot confirm anything, so it stays off.

## 10. The Cellular Check screen

> **Changed 2026-11-15:** Cellular Check is now the second tab under the plug switch, beside the Battery Guard tab, with the same controls, on every plug. The ⋮ menu item and the back button are gone. The text below is the original design.

Its own screen, opened from a new **Cellular Check** item in the ⋮ menu of the plug screen (next to Rename and Remove plug). It is the same screen whichever plug is shown, since the check is about the phone. The back button returns to the plug screen.

| Control | Type | Default |
|---|---|---|
| **Check cellular service** | Switch: needs **Own number** and the three permissions | Off |
| **Own number** | Text field (phone keypad) | Empty |
| **Daily SMS test at** | Time picker | 10:00 |
| **Check every** | Shown, not editable: "Every 15 minutes, set in Battery Guard" | Battery Guard's value |
| **Run cellular check now** | Button | |

Below them, one line per layer with its last result and time, then the loopback count:

```
SIM          Pass   10:15
Registered   Pass   10:15   LTE, home
Signal       Pass   10:15   RSRP -97 dBm, RSRQ -11 dB, SINR 8 dB
SMS          Pass   10:01   daily: sent, delivered, received back
Test SMS sent: 1 today, 9 in the last 7 days
```

## 11. Risks

| # | Risk | Mitigation |
|---|---|---|
| 1 | The gateway software is later changed to forward, answer or count incoming SMS | The `MPCHK` prefix; section 7 says what the gateway must then drop |
| 2 | Test SMS cost money | One a day when healthy; three per fault the passive layers can see, however long it lasts; most outage confirmations fail on the phone and cost nothing; the count is shown |
| 2a | A recovery the passive layers cannot see is noticed late | Only for faults only SMS shows: re-tested daily, or by hand with **Run cellular check now** |
| 3 | A brief passive dip sends a needless SMS | Accepted in review: it is one SMS, shown as a warning, not an alert. Signal only fails at 0 bars, which avoids most dips |
| 4 | Some networks never send delivery reports | A missing report is a warning; the received loopback is the proof |
| 5 | Registration looks fine but SMS still fails (barred SIM, SMS centre fault) | The daily loopback |
| 6 | Android limits or permission wording change | The VERIFY items in section 9 are checked on the SM-G780F first (test C1) |

## 12. Build order

| Step | Scope | Done when |
|---|---|---|
| C1 | `CellularVerdict` and `LoopbackPlan` (pure, with tests), passive layers 1–3, log only | The log shows a correct run with the SIM removed, in airplane mode and normally; the plan says when a loopback is due |
| C2 | Own alarm at Battery Guard's interval and stored results; the Cellular Check screen from the ⋮ menu, with its layer lines | Results survive a restart; runs continue with the app closed and with Battery Guard off |
| C3 | SMS loopback in the foreground service, for the daily and confirmation triggers | Sent, delivered and received recorded separately; a wrong own number fails at Received |
| C4 | `cellular_down` alert, status report field, receiver update | The receiver prints a started, a reminder and a recovered alert |

## 13. Test plan

| # | Test | Expected |
|---|---|---|
| CT1 | Remove the SIM | SIM fails; a confirmation loopback fails at Sent at once; another 15 minutes later; the alert starts |
| CT2 | Airplane mode, then off again | Registration fails; two confirmations fail; alert; after airplane mode is off, a loopback passes and the alert is recovered |
| CT3 | A passive failure that SMS survives (a spot at the edge of coverage where the phone sometimes shows 0 bars) | Signal fails; the confirmation loopback passes; a warning, no alert |
| CT4 | Normal day | Passive runs every 15 minutes; exactly one SMS at the test time |
| CT5 | Wrong own number, everything else normal | The daily loopback fails at Received; the retry 15 minutes later fails; the alert names SMS with the "signal is fine" message |
| CT6 | Airplane mode kept for 4 hours, then off | Exactly three test SMS: two to confirm (both fail on the phone), none during the 4 hours, one passing after airplane mode is off |
| CT6a | Wrong own number for 2 days, then corrected | One failed loopback on each day's test time; the first one after the correction passes and the alert is recovered |
| CT7 | Normal loopback | The test message stays in the inbox; the gateway software sends nothing because of it |
| CT8 | Change Battery Guard's **Check every** to 30 minutes | Cellular runs follow at 30 minutes; the Cellular Check screen shows the new value |

## 14. Decisions from review

No open questions remain.

| Question | Decision | Draft |
|---|---|---|
| Re-confirm during a fault the passive layers can see | No; one loopback when they all pass again | 4 |
| What counts as a passive failure | A single bad reading | 4 |
| What the gateway software does with incoming SMS | Only sends: no filter, loopback to the phone's own number | 5 |
| Where Cellular Check lives | Its own screen, from the ⋮ menu | 5 |
| Re-testing a fault only SMS shows | Once a day at the test time | 5 |
| Signal | Fails below 1 bar; fixed, no setting | 5 |
| Interval for the passive layers | Battery Guard's **Check every** value | 5 |
