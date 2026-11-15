# Signal Strength Reporting: Design

Status: draft 5, 2026-11-14, for approval. Replaces draft 4 (approved; S1 and S2 built). Changes `CELLULAR-CHECK.md` section 8 as well.

Changes in draft 5, a new requirement after S2:

- **Two senders, each with its own messages.** Battery Guard, when on, sends only Battery Guard's status report and alerts, with no cellular data. Cellular Check, when on, sends its own status report and its own alerts (`cellular_down`, `weak_signal`), whether Battery Guard is on or off (section 6).
- **Its own type:** Cellular Check's status report is `"alert":"cellular_status"`; Battery Guard's stays `"alert":"status"`. With both on, a server receives two status reports per check, one of each type.
- **Its own switches:** Cellular Check's screen gets **Send cellular alerts** and **Send cellular status after every check**. The alert address and token stay shared, set in Battery Guard's section (section 9).
- **The waiting list follows each sender's switches:** a request is queued only if its sender's alert switch is on; switching a sender's alerts off, or the sender off, drops only that sender's waiting requests (section 6).
- Battery Guard's status report no longer carries the `cellular` object (it did since S2). The cellular-first order on the shared alarm stays, so both reports of a check carry the same moment.

Changes in draft 4, from the review of draft 3:

- **Signal goes only in status reports and Cellular Check's own alerts** (`cellular_down`, `weak_signal`), and only while Cellular Check is on. Battery Guard's own alerts and the test alert do not carry it.
- So the **`signal` object moves inside the `cellular` object** (section 5), and the weak-signal alert becomes part of Cellular Check: switching Cellular Check off ends it (section 7).
- **The server keeps the history:** the receiver only prints; no CSV file.
- **Reading at each check only:** no live signal callbacks (section 1).
- **New:** Battery Guard's alarm runs the cellular run before its own check, so its status report carries that check's reading, not the previous one (section 6).

Changes in draft 3: LTE only; fixed thresholds; two readings in a row; RSRP or SINR; nothing while both features are off. Changes in draft 2: HTTP requests only; Cellular Check's own status report while Battery Guard is off; the `weak_signal` alert.

## 1. Goal

While Cellular Check is on, send the phone's LTE signal strength out through the existing interface (the JSON requests to the alert address) as **numbers**, so the receiving server can store and chart them, and raise an alert when the signal stays weak, before SMS fails.

### What is there today

| Item | Today |
|---|---|
| Values read | RSRP, RSRQ, SINR and the 0–4 level, for LTE or 5G NR, at every cellular run (`CellularReader`) |
| What is kept | Only a text line, `"LTE, RSRP -97 dBm, RSRQ -11 dB, SINR 8 dB"`: the numbers are formatted away where they come in |
| What goes out | That text, as `signal_detail` inside the `cellular` object, in Battery Guard's status report and in `cellular_down` alerts |
| When it goes out | Only while **both** Battery Guard (with **Send status after every check**) and Cellular Check are on |

### Out of scope

- **5G (NR).** Not considered in this version. On 5G non-standalone the LTE anchor cell is reported and judged. On 5G standalone, with no LTE values, no `signal` object is sent and the signal is not judged. Cellular Check's Signal layer and its screen line keep showing NR as today.
- **Live signal callbacks.** Android can call an app back on every signal change, but only while its process runs; catching changes at any time would need a service running all the time (a permanent notification, more battery). The signal is read once per check instead, so a weak-signal alert starts about 15 minutes after the signal turns weak.
- **Signal while Cellular Check is off,** and in Battery Guard's own alerts or the test alert.
- History in the app or the receiver: the server keeps it.
- Anything new on the app's screens; settings for the thresholds (they are fixed).
- Cell identity, neighbouring cells or location: these need the location permission and say where the phone is.

## 2. Terms

| Term | Meaning |
|---|---|
| **Signal reading** | One sample of the serving LTE cell's signal: level and the measured values |
| **Signal object** | The `signal` JSON object that carries one reading, inside the `cellular` object |
| **Weak signal** | A reading with RSRP below −110 dBm, or SINR below 0 dB |

These go into `CONTEXT.md` once the design is accepted.

## 3. Decision summary

| Decision | Choice |
|---|---|
| Interface | The HTTP requests to the alert address only |
| When | Only while Cellular Check is on |
| Which requests | Cellular Check's own requests only: `cellular_status`, `cellular_down` and `weak_signal` |
| Senders | Battery Guard sends Battery Guard's messages; Cellular Check sends Cellular Check's. Neither carries the other's data |
| Cellular Check's switches | **Send cellular alerts** and **Send cellular status after every check**, on its screen; address and token shared |
| Radio | LTE only |
| Shape | A `signal` object of numbers inside the `cellular` object; `signal_detail` stays for people |
| Data first | `CellularReader` keeps the reading as a structured value; the text is made from it |
| Missing values | Left out of the object, never `0` or a sentinel |
| Cellular status report | `"alert":"cellular_status"`, at every cellular run, with **Send cellular status after every check** on, whether Battery Guard is on or off |
| Weak signal | RSRP below −110 dBm **or** SINR below 0 dB, fixed |
| Start and end | Two weak readings in a row start `weak_signal`; two good readings in a row end it |
| A check with no reading | Not judged: it neither counts as weak or good nor breaks a run |
| Weak signal and SMS | No test SMS is sent because of weak signal |
| Sampling | One reading per check; no live callbacks |
| Receiver | Prints the numbers and handles `weak_signal`; no history file |

## 4. The signal reading

Parsed once in `CellularReader`, a pure type in `cellular/logic`:

```kotlin
data class SignalReading(
    val level: Int?,        // 0..4, Android's bars for the LTE cell
    val rsrpDbm: Int?,      // CellSignalStrengthLte.rsrp
    val rsrqDb: Int?,       // CellSignalStrengthLte.rsrq
    val sinrDb: Int?,       // CellSignalStrengthLte.rssnr
    val at: Long,           // when it was read
)
```

- It is made from the `CellSignalStrengthLte` entry only. With no LTE entry (no SIM, radio off, or 5G standalone) there is no reading.
- A value Android reports as `CellInfo.UNAVAILABLE` becomes `null` at the boundary; nothing after it sees the sentinel.
- Values outside the LTE ranges in 3GPP TS 36.133 (RSRP −140..−44 dBm, RSRQ −34..3 dB, SINR −20..30 dB) are treated as unavailable.
- `SignalDetail.format` takes the reading for LTE and keeps today's NR text, so the Cellular Check screen does not change.
- The reading is stored with the run's results, so a status report or alert built later carries the same reading.

## 5. The signal object

It sits inside the `cellular` object, which every request below already carries while Cellular Check is on:

```json
"cellular": {"sim":"pass","registration":"pass","signal":"pass","sms":"pass",
             "signal_detail":"LTE, RSRP -97 dBm, RSRQ -11 dB, SINR 8 dB",
             "signal_reading":{"rat":"lte","level":3,"rsrp_dbm":-97,"rsrq_db":-11,"sinr_db":8,
                               "read_at":"2026-11-14T20:10:50-06:00"}}
```

It is named `signal_reading`, because `signal` inside `cellular` is already the Signal layer's result (`"pass"`).

| Field | Type | Present |
|---|---|---|
| `rat` | always `"lte"` in this version | Always; a later version can add 5G without changing the other fields |
| `level` | 0–4 | When known |
| `rsrp_dbm`, `rsrq_db`, `sinr_db` | integer | Each only when Android gave a valid value |
| `read_at` | ISO 8601 with the UTC offset | Always: the reading may be older than the request (a queued alert) |

With no reading, `signal_reading` is left out; the rest of the `cellular` object is unchanged.

## 6. Two senders

| Sender | Sends | Switched by |
|---|---|---|
| Battery Guard (while on) | `status`, `not_charging`, `plug_unreachable`, `guard_degraded`, `test` | Battery Guard's **Send alerts** and **Send status after every check** |
| Cellular Check (while on) | `cellular_status`, `cellular_down`, `weak_signal` | Cellular Check's **Send cellular alerts** and **Send cellular status after every check** |

Both use the alert address and token set in Battery Guard's section, and the same request shape. Neither carries the other's data: Battery Guard's requests have no `cellular` object; Cellular Check's carry it, with `signal_reading`.

| Request | `cellular` with `signal_reading` |
|---|---|
| `cellular_status` | Yes: this run's results and reading |
| `cellular_down` started, reminder, recovered | Yes: the run that raised it |
| `weak_signal` started, reminder, recovered | Yes: the reading that decided it |
| Battery Guard's `status`, its alerts, `test` | No |

### The cellular status report

Sent at every cellular run while Cellular Check is on and **Send cellular status after every check** is on, whether Battery Guard is on or off:

```json
{"alert":"cellular_status","state":"status",
 "message":"Matter Plug: cellular status: SIM pass, registered pass, signal pass, SMS pass. Battery 84%.",
 "battery":84,"charging":true,"time":"2026-11-14T20:10:50-06:00","next_check_in_min":15,
 "cellular":{"sim":"pass","registration":"pass","signal":"pass","sms":"pass","signal_detail":"LTE, RSRP -97 dBm, …",
             "signal_reading":{"rat":"lte","level":3,"rsrp_dbm":-97,"rsrq_db":-11,"sinr_db":8,"read_at":"2026-11-14T20:10:50-06:00"}}}
```

- `battery` and `charging` stay in it: every request has them, and a server's handling of the shape does not change.
- `next_check_in_min` lets the receiver's silence alarm use it, as it uses Battery Guard's `status`.
- The SMS layer in it is the last test SMS's result, as on the screen.

### One alarm, cellular first

While Battery Guard is on, its alarm brings both runs (`CheckReceiver`): the cellular run first, then Battery Guard's check. Each sends its own requests. A failure in the cellular run never stops Battery Guard's check. While Battery Guard is off, Cellular Check's own alarm brings its runs.

### The waiting list

Both senders share one waiting list for alerts that could not be delivered. Each request in it belongs to its sender, and the list follows each sender's own switches:

| Event | Waiting requests dropped |
|---|---|
| Battery Guard's **Send alerts** switched off, or Battery Guard switched off | Battery Guard's only |
| **Send cellular alerts** switched off, or Cellular Check switched off | Cellular Check's only |

A request is queued only while its sender's alert switch is on, and the list is sent whenever the address is valid. Until now the whole list followed Battery Guard's **Send alerts**.

## 7. The weak-signal alert

### The rule

A reading is **weak** when **RSRP < −110 dBm** or **SINR < 0 dB**. A value that is missing is not judged: a reading with no SINR is weak only on RSRP. A check with no reading, or a reading with neither value, is not judged: it neither counts as weak or good nor breaks a run.

| Alert | Starts when | Ends when |
|---|---|---|
| **Weak signal** (`weak_signal`) | Two readings in a row are weak | Two readings in a row are not weak |

Readings come once per check (every **Check every** minutes, 15 by default), so the alert starts about 15 minutes after the signal turns weak and ends about 15 minutes after it recovers.

It runs in every cellular run, whether that run comes from Battery Guard's alarm or Cellular Check's own. Reminders follow the existing 60-minute rule. It travels like Cellular Check's other alert: the shared address, token and waiting list, with **Send cellular alerts** on, and a phone notification.

| `state` | Example `message` |
|---|---|
| `started` | `Matter Plug: weak cellular signal: RSRP -114 dBm (below -110), SINR -2 dB (below 0). Battery 84%.` |
| `started`, one value | `Matter Plug: weak cellular signal: SINR -3 dB (below 0). Battery 84%.` |
| `reminder` | `Matter Plug (reminder): weak cellular signal: RSRP -113 dBm (below -110). Battery 84%.` |
| `recovered` | `Matter Plug: cellular signal is good again: RSRP -98 dBm, SINR 7 dB. Battery 84%.` |

The request carries the `cellular` object with the deciding `signal_reading`, and a `weak` object naming what was below its threshold:

```json
"weak": {"rsrp_below":-110,"sinr_below":0,"rsrp_weak":true,"sinr_weak":true}
```

### State and switching off

The alert state (the run of weak or good readings, and whether the alert is on) is kept in the cellular store, next to `cellular_down`'s. Switching Cellular Check off ends a `weak_signal` alert without a request and drops its waiting requests, exactly as for `cellular_down`; switching Battery Guard off keeps them.

### What it does not do

- It does not send a test SMS. A weak signal can still carry SMS; the SMS test stays tied to the SIM, registration and 0 bars.
- It does not change Cellular Check's Signal layer (fail at 0 bars).

The rule is a pure function (`cellular/logic/WeakSignalPolicy.kt`), driven in unit tests by scripted readings, as `LoopbackPlanTest` does for the loopback plan.

## 8. Receiver

- It learns `cellular_status` and counts it for the silence alarm, as it counts `status`: any valid request is a sign of life, and `next_check_in_min` from either sets the expected gap.
- The log line gains `signal=lte/3 rsrp=-97 rsrq=-11 sinr=8` when the `cellular` object has a `signal_reading`.
- It learns the `weak_signal` alert, prints its `weak` object, and runs the notification command for started and reminder, as for the other alerts.
- Nothing is stored beyond its existing log; the server keeps the history.
- Requests without `signal_reading` (an older app, or Cellular Check off) are handled as now.

## 9. Settings screen

The Cellular Check screen gets two switches, below **Check cellular service**:

| Control | Type | Default |
|---|---|---|
| **Send cellular alerts** | Switch | Off |
| **Send cellular status after every check** | Switch | Off |

With a note: "Sent to the alert address set in Battery Guard." Nothing changes in Battery Guard's section: its switches now cover Battery Guard's messages only. Your phone today sends Cellular Check's alerts through Battery Guard's **Send alerts** (on); after this change they need **Send cellular alerts** switched on.

## 10. Risks

| # | Risk | Mitigation |
|---|---|---|
| 1 | A server reads a missing value as 0 | Missing values are left out, never 0 |
| 2 | Signal swings start and end the alert repeatedly | Two readings in a row on both sides; reminders only every 60 minutes |
| 3 | SINR below 0 dB is common at a cell edge where SMS still works | The alert says "weak signal", not "SMS down"; the SMS test remains the proof |
| 4 | 5G standalone reports no LTE values | No `signal_reading` and no judgement; stated in section 1 |
| 5 | A phone maker reports nonsense values | Out-of-range values are dropped at the boundary, so they are never judged weak |
| 6 | Swapping the order delays Battery Guard's check | The cellular run is a passive read of milliseconds; its test SMS runs in its own service |
| 7 | A server that reads every `status` as Battery Guard's | Cellular Check's report has its own type, `cellular_status` |
| 8 | Two reports per check double the requests a server receives | Each sender's status switch is separate; switch either off |

## 11. Build order

| Step | Scope | Done when |
|---|---|---|
| S1 | `SignalReading` (pure, with tests: unavailable values, LTE ranges, no LTE entry); `CellularReader` and `SignalDetail` use it; the reading is stored with the run | The screen and `signal_detail` are unchanged on the phone |
| S2 | `signal_reading` in the `cellular` object; the cellular run before Battery Guard's check | A status report from the phone carries this check's reading |
| S3 | Two senders: Cellular Check's switches; `cellular_status` at every cellular run; the `cellular` object out of Battery Guard's `status`; the waiting list by sender | With both on, a `status` without `cellular` and a `cellular_status` with `signal_reading` arrive every interval; with Battery Guard off, only the `cellular_status` |
| S4 | `WeakSignalPolicy` (pure, with tests: two in a row, missing values, unjudged checks, recovery) and the `weak_signal` alert, ended by switching Cellular Check off | A weak spot starts the alert after two checks and a good spot ends it after two |
| S5 | Receiver: `cellular_status` for the silence alarm, the log line, `weak_signal` | All show on the receiver from the phone |

## 12. Test plan

| # | Test | Expected |
|---|---|---|
| ST1 | Normal LTE, both features on, both status switches on | Each check: a `status` with no `cellular`, and a `cellular_status` with `signal_reading`, `"rat":"lte"` and all three values, `read_at` equal to this check |
| ST2 | Airplane mode | No `signal_reading`; no weak-signal judgement |
| ST3 | A cell where Android reports no SINR | `sinr_db` left out; weak judged on RSRP only |
| ST4 | Cellular Check off, Battery Guard on | Only `status`, without `cellular` |
| ST5 | Battery Guard off, Cellular Check on | Only `cellular_status`, with `signal_reading`, every interval |
| ST5a | **Send cellular alerts** off, Battery Guard's **Send alerts** on, a `cellular_down` starts | No `cellular_down` request; Battery Guard's alerts still sent |
| ST5b | A `cellular_down` waiting, then **Send cellular alerts** off | That request dropped; a waiting Battery Guard alert kept |
| ST6 | A weak spot (for example a basement, or the phone in a metal tin) for 30 minutes, then back | `weak_signal` started after the second weak check, recovered after the second good one |
| ST7 | One weak reading between good ones | No alert |
| ST8 | Airplane mode in the middle of a weak spell | The airplane-mode checks are not judged; the run of weak readings carries on after it |
| ST9 | Cellular Check switched off during a `weak_signal` alert | The alert ends with no request; its waiting requests are dropped |
| ST10 | A queued alert delivered late | `read_at` is the time of the reading, not the delivery time |

## 13. Decisions from review

No open questions remain.

| Question | Decision | Draft |
|---|---|---|
| Which interface | The HTTP requests only | 2 |
| Cellular Check status report while Battery Guard is off | Yes | 2 |
| Weak-signal alert | RSRP below −110 dBm or SINR below 0 dB | 2 |
| Thresholds | Fixed | 3 |
| Readings to start and end | Two in a row | 3 |
| Both or either | Either | 3 |
| 5G | Not considered in this version | 3 |
| Both features off | No watching | 3 |
| Signal in Battery Guard's alerts | No: status reports and Cellular Check's alerts only | 4 |
| Signal with Cellular Check off | No | 4 |
| Receiver history | None: the server keeps it | 4 |
| Live callbacks | No: one reading per check | 4 |
| Who sends what | Battery Guard its messages, Cellular Check its own; neither carries the other's data | 5 |
| Cellular status report type | `cellular_status` | 5 |
| Cellular Check's switches | Its own **Send cellular alerts** and **Send cellular status**; address and token shared | 5 |
