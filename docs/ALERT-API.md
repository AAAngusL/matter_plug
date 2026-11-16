# Alert API: Requests the Phone Sends

For anyone programming the server at the **alert address**. It lists every request the app sends, from Battery Guard and from Cellular Check: each type, each field, and which fields are optional. It describes the app as built on 2026-11-14, in the same commit as this document (signal strength step S3), with the charging fields of 2026-11-16: `charging` now follows Android's battery status, and `plugged_in` carries its old meaning (section 3); and the recovery signals and `cancelled` state of `RECOVERY-SIGNALS.md` (sections 4 and 5). One type, `weak_signal`, is designed but not built yet; it is listed at the end so the server can be ready for it.

## 1. Transport

| Item | Value |
|---|---|
| Method | `POST` to the alert address, exactly as entered in the app (`http://` or `https://`) |
| Body | One JSON object, UTF-8 |
| Headers | `Content-Type: application/json; charset=utf-8`. `Authorization: Bearer <token>` only when an alert token is set in the app; no `Authorization` header otherwise |
| Timeout | 10 seconds for connect and for read; less when the phone's time budget is shorter |
| Redirects | Not followed. A `3xx` counts as not delivered |
| Delivered | Any response from `200` to `299`. The response body is read and ignored |
| Rejected | `401` or `403`: the app shows "Rejected: check the token" |
| Anything else | Not delivered (any other status, no connection, timeout, TLS failure) |

Send `2xx` only once the request is stored. A request that the phone counts as not delivered may be sent again (section 4).

## 2. Who sends what

Two senders in the same app. They share the alert address and token (set in Battery Guard's section) and nothing else.

| Sender | Types | Sent while | Switched by |
|---|---|---|---|
| Battery Guard | `status`, `not_charging`, `plug_unreachable`, `guard_degraded`, `test` | Battery Guard is on (`test` at any time, by its button) | **Send alerts** and **Send status after every check**, in Battery Guard's section |
| Cellular Check | `cellular_status`, `cellular_down` (later `weak_signal`) | Cellular Check is on, whether Battery Guard is on or off | **Send cellular alerts** and **Send cellular status after every check**, on the Cellular Check screen |

Neither sender carries the other's data: only Cellular Check's requests have the `cellular` object.

With both features on and both status switches on, a server receives **two status requests per check**, a `status` and a `cellular_status`, normally within a second of each other.

## 3. Fields

### Fields of every request

| Field | Type | Always present | Meaning |
|---|---|---|---|
| `alert` | string | yes | The request type (section 5) |
| `state` | string | yes | `started`, `reminder`, `recovered`, `cancelled`, `status` or `test` |
| `message` | string | yes | A ready-to-display sentence in English (texts in section 5). Do not parse it: every value in it is also in a field |
| `battery` | integer 0–100 | yes | Battery percentage when the event happened. `0` when the battery could not be read |
| `charging` | boolean | yes | Whether the battery is taking charge: Android reports its battery status as charging (`battery_status` is `charging`). `false` when the phone is plugged in but the battery is full, held at a charge limit, or not charging for another reason |
| `plugged_in` | boolean | yes | Whether the phone is connected to power (AC, USB, wireless or dock), whether or not the battery takes charge. Battery Guard switches the plug by this |
| `battery_status` | string | yes | Android's battery status, as `adb shell dumpsys battery` shows it: `charging`, `discharging`, `not_charging`, `full` or `unknown`. `unknown` also when the battery could not be read at that moment |
| `time` | string | yes | When the event happened, ISO 8601 with the phone's UTC offset, whole seconds, e.g. `2026-11-14T20:10:50-06:00`. Use it, not the arrival time: queued requests arrive late |

### Extra fields

| Field | Type | Present in | Meaning |
|---|---|---|---|
| `cancelled_by` | string | `cancelled` | The switch that closed the alert: `guard_off` (Keep battery between), `alerts_off` (Send alerts), `cellular_off` (Check cellular service) or `cellular_alerts_off` (Send cellular alerts) |
| `next_check_in_min` | integer | `status`, `cellular_status` | Minutes until the next check (5, 10, 15, 30 or 60). If no further request arrives well after that, the phone has gone silent |
| `cellular` | object | `cellular_status`, `cellular_down` | Cellular Check's results (below) |

No other fields are sent. Treat an unknown field as something to ignore, not an error: later versions may add fields.

### The `cellular` object

| Field | Type | Present | Meaning |
|---|---|---|---|
| `trigger` | string | optional: absent until the first test SMS | Why the last test SMS was sent: `daily`, `confirmation` (a reading failed), `recovery` (proving the alert has ended), `manual` (Run now) |
| `failed_layer` | string | optional: only when a layer failed | The first layer that failed: `sim`, `registration`, `signal` or `sms` |
| `reason` | string | optional: with `failed_layer` | That layer's reason, in words (section 6) |
| `sim` | string | always | `pass` or `fail` |
| `registration` | string | always | `pass`, `fail` or `skipped` |
| `signal` | string | always | `pass`, `fail` or `skipped` (the Signal layer fails only at 0 bars) |
| `sms` | string | always | `pass`, `fail`, or `not_due` (no test SMS result yet) |
| `sms_reason` | string | optional: only when `sms` is `fail` | Why the SMS layer failed, in words (section 6) |
| `signal_detail` | string | always | The signal as text for people, e.g. `LTE, RSRP -97 dBm, RSRQ -11 dB, SINR 8 dB`; `none` when the Signal layer did not pass |
| `signal_reading` | object | optional: only with an LTE cell | The signal as numbers (below) |

`skipped` means a layer it depends on failed: registration needs the SIM, and signal needs registration. The SMS layer is never skipped: it is the result of the last test SMS, which may be older than the other layers.

### The `signal_reading` object

| Field | Type | Present | Meaning |
|---|---|---|---|
| `rat` | string | always | `lte`. This version reports LTE only; 5G is not considered |
| `level` | integer 0–4 | optional | Android's signal bars for the LTE cell |
| `rsrp_dbm` | integer, −140 to −44 | optional | Reference Signal Received Power, dBm |
| `rsrq_db` | integer, −34 to 3 | optional | Reference Signal Received Quality, dB |
| `sinr_db` | integer, −20 to 30 | optional | Signal to Interference plus Noise Ratio, dB |
| `read_at` | string | always | When the reading was taken, ISO 8601 with the UTC offset |

**Radio types:** `signal_reading` is reported for LTE only. On 3G, 2G and 5G standalone it is always left out, and on 5G non-standalone it holds the LTE anchor cell, not the 5G cell. See `docs/4G_5G_gap.md`.

A value is **left out** when Android did not give it, or gave one outside the range above. It is never sent as `0`, `null` or a placeholder, so a missing `sinr_db` means "unknown", not "0 dB". With no LTE cell (no SIM, radio off, 5G standalone) the whole `signal_reading` object is left out.

## 4. Delivery and repeats

| Request | Kept and retried if not delivered |
|---|---|
| `started`, `recovered`, `cancelled` (every alert type) | Yes: kept in a waiting list on the phone and sent, oldest first, when the network is back. The list holds at most 100; the oldest are dropped beyond that |
| `reminder` | No: sent once, only when nothing older is waiting |
| `status`, `cellular_status` | No: sent once, only when nothing older is waiting |
| `test` | No: sent once, the result is shown in the app |

What a server must allow for:

- **Late arrival.** A `started` may arrive hours after `time`. Order by `time`, not by arrival.
- **Repeats.** If the server stores a request but its `2xx` does not reach the phone (a timeout, a dropped connection), the phone sends the same request again. Treat a request with the same `alert`, `state` and `time` as the same event.
- **Gaps.** A `reminder` that fails is not sent again; the next one follows 60 minutes later. A `status` or `cellular_status` that fails is not sent again; the next one follows at the next check.
- **An end with no recovery.** When Battery Guard or Cellular Check is switched off, or its alert switch is switched off, each of its open alerts ends with one `cancelled` request (`cancelled_by` names the switch), sent after the requests already waiting, which are kept. Close the alert, but do not announce that anything was fixed. Nothing more is sent about that alert; if the fault is still there when the switch is turned back on, it is confirmed again and a new `started` follows.
- **Silence.** A phone that is dead, offline or has lost the app sends nothing at all. Expect a `status` or `cellular_status` every `next_check_in_min` minutes while the status switches are on; three intervals with nothing is a reasonable alarm (the bundled receiver uses that, at least 31 minutes).

## 5. The request types

### Battery Guard

#### `status` (state `status`)

After every Battery Guard check, with **Send status after every check** on.

```json
{"alert":"status","state":"status","message":"Matter Plug: status. Battery 84%, plugged in, not charging.",
 "battery":84,"charging":false,"plugged_in":true,"battery_status":"not_charging","time":"2026-11-14T20:10:50-06:00","next_check_in_min":15}
```

Message: `Matter Plug: status. Battery <n>%, <power>.` where `<power>` is:

| `plugged_in` | `battery_status` | `<power>` |
|---|---|---|
| `false` | any | `on battery` |
| `true` | `charging` | `charging` |
| `true` | `full` | `plugged in, full` |
| `true` | `not_charging` | `plugged in, not charging` |
| `true` | `discharging` | `plugged in, discharging` |
| `true` | `unknown` | `plugged in` |

#### `not_charging` (states `started`, `reminder`, `recovered`, `cancelled`)

The battery was at or below the low threshold, the charger plug was switched on and confirmed, and the phone was still not plugged in 30 seconds later. Ends when the battery takes charge: `plugged_in` and `battery_status` `charging` or `full`. Plugged in but not taking charge keeps it on.

| State | Message |
|---|---|
| `started` | `Matter Plug: battery 18%, <power>. The plug is on. Check the charger and cable.` (`<power>` as in `status`: usually `on battery`) |
| `reminder` | The same text, starting `Matter Plug (reminder): `, for example `Matter Plug (reminder): battery 18%, plugged in, not charging. The plug is on. Check the charger and cable.` |
| `recovered` | `Matter Plug: recovered. Battery 24%, <power>.` (`<power>` as in `status`) |

#### `plug_unreachable` (states `started`, `reminder`, `recovered`, `cancelled`)

The charger plug could not be reached at two checks in a row: by a switch, or, when no switch was needed, by a read-only check of the plug (made at every check since `PLUG-WATCH.md`). Ends when the plug is reached again. `wanted_on` is left out when the alert started from a read.

| State | Message |
|---|---|
| `started`, needed on | `Matter Plug: battery 18%, not charging. The charger plug cannot be reached.` |
| `started`, needed off | `Matter Plug: battery 87%. The charger plug cannot be reached to turn it off.` |
| `started`, no switch needed | `Matter Plug: battery 72%. The charger plug cannot be reached.` |
| `reminder` | The same text, starting `Matter Plug (reminder): ` |
| `recovered` | `Matter Plug: recovered. Battery <n>%, <power>.` (`<power>` as in `status`) |

#### `guard_degraded` (states `started`, `reminder`, `recovered`, `cancelled`)

Battery Guard found a fault of its own at two checks in a row. Ends when the fault is proved gone: the battery is read (`battery unreadable`), exact alarms are allowed (`exact alarms not allowed`), or the switching service starts (`service could not start`; a check that needs no switch does not try, so it does not end it). If a different fault appears while it is on, it stays on and its reminders name the new reason.

| State | Message |
|---|---|
| `started` | `Matter Plug: Battery Guard is not working: <reason>. Battery <n>%.` where `<reason>` is `exact alarms not allowed`, `battery unreadable` or `service could not start` |
| `reminder` | The same text, starting `Matter Plug (reminder): ` |
| `recovered` | `Matter Plug: recovered. Battery <n>%, <power>.` (`<power>` as in `status`) |

#### `test` (state `test`)

Sent when **Send test alert** is tapped, whatever the switches. Never kept or retried.

```json
{"alert":"test","state":"test","message":"Matter Plug: test alert.","battery":84,"charging":true,"plugged_in":true,"battery_status":"charging","time":"2026-11-14T20:15:02-06:00"}
```

#### `cancelled` (every alert)

A switch closed the alert without a recovery. Kept and retried like `recovered`.

```json
{"alert":"plug_unreachable","state":"cancelled",
 "message":"Matter Plug: Battery Guard was turned off. The plug unreachable alert is closed without a recovery. Battery 64%.",
 "battery":64,"charging":true,"plugged_in":true,"battery_status":"charging","time":"2026-11-16T14:05:10-06:00","cancelled_by":"guard_off"}
```

Message: `Matter Plug: <switch>. The <alert> alert is closed without a recovery. Battery <n>%.` where `<switch>` is `Battery Guard was turned off`, `Battery Guard alerts were turned off`, `Cellular Check was turned off` or `Cellular alerts were turned off`, and `<alert>` is `not charging`, `plug unreachable`, `Battery Guard not working` or `SMS`.

#### Reminders

Every alert that stays on sends a `reminder` every 60 minutes, at any hour, until it ends with `recovered` or `cancelled`.

### Cellular Check

#### `cellular_status` (state `status`)

After every cellular run, with **Send cellular status after every check** on, whether Battery Guard is on or off.

```json
{"alert":"cellular_status","state":"status",
 "message":"Matter Plug: cellular status: SIM pass, registered pass, signal pass, SMS pass. Battery 84%.",
 "battery":84,"charging":true,"plugged_in":true,"battery_status":"charging","time":"2026-11-14T20:10:50-06:00","next_check_in_min":15,
 "cellular":{"trigger":"daily","sim":"pass","registration":"pass","signal":"pass","sms":"pass",
             "signal_detail":"LTE, RSRP -97 dBm, RSRQ -11 dB, SINR 8 dB",
             "signal_reading":{"rat":"lte","level":3,"rsrp_dbm":-97,"rsrq_db":-11,"sinr_db":8,"read_at":"2026-11-14T20:10:50-06:00"}}}
```

Message: `Matter Plug: cellular status: SIM <s>, registered <s>, signal <s>, SMS <s>. Battery <n>%.` where each `<s>` is `pass`, `fail`, `skipped`, or for SMS also `not tested`.

#### `cellular_down` (states `started`, `reminder`, `recovered`, `cancelled`)

Two test SMS in a row failed, whatever triggered them. Ends when a test SMS passes. With **Send cellular alerts** on. While it is on and the readings pass (a fault only SMS shows), a test SMS runs once an hour, just before each reminder, so each reminder follows a fresh test.

| State | When | Message |
|---|---|---|
| `started` | A reading (SIM, registration, signal) failed too | `Matter Plug: SMS service unavailable: <reason>; the test SMS was <sms reason>. Battery <n>%.` For registration, `<reason>` reads `not registered (<reason>)` |
| `started` | All readings passed | `Matter Plug: SMS is not working although the signal is fine; the test SMS was <sms reason>. Battery <n>%.` |
| `reminder` | Every 60 minutes while on; when the readings pass, right after a failed re-test | The started text, starting `Matter Plug (reminder): ` |
| `recovered` | A test SMS passed | `Matter Plug: SMS works again. Battery <n>%.` |

```json
{"alert":"cellular_down","state":"started",
 "message":"Matter Plug: SMS service unavailable: SIM absent; the test SMS was not sent: no default sms app (code 32). Battery 84%.",
 "battery":84,"charging":true,"plugged_in":true,"battery_status":"charging","time":"2026-11-14T19:42:10-06:00",
 "cellular":{"trigger":"manual","failed_layer":"sim","reason":"SIM absent","sim":"fail","registration":"skipped",
             "signal":"skipped","sms":"fail","sms_reason":"not sent: no default sms app (code 32)","signal_detail":"none"}}
```

### `weak_signal` (designed, not built yet)

Planned as step S4 of `docs/SIGNAL-STRENGTH.md`. Not sent by the current app. When built:

- states `started`, `reminder`, `recovered`, with **Send cellular alerts** on;
- `started` after two readings in a row with RSRP below −110 dBm or SINR below 0 dB; `recovered` after two in a row with neither;
- the `cellular` object with `signal_reading`, and a new `weak` object:
  `{"rsrp_below":-110,"sinr_below":0,"rsrp_weak":true,"sinr_weak":false}`;
- messages such as `Matter Plug: weak cellular signal: RSRP -114 dBm (below -110). Battery 84%.` and `Matter Plug: cellular signal is good again: RSRP -98 dBm, SINR 7 dB. Battery 84%.`

## 6. Reason texts

Reasons are words for people. A server may show or store them, but should decide on the fields (`failed_layer`, the layer results), not on the words.

| Layer | Possible `reason` |
|---|---|
| `sim` | `SIM absent`, `SIM locked (PIN)`, `SIM locked (PUK)`, `SIM network-locked`, `SIM not ready`, `SIM disabled`, `SIM card error`, `SIM restricted`, `SIM state unknown`, `no phone permission`, `no telephony` |
| `registration` | `airplane mode`, `out of service`, `emergency calls only`, `radio off`, `not registered`, `service state unknown` |
| `signal` | `no signal bars`, `signal unreadable` |

`sms_reason` takes one of these forms:

| Form | Meaning |
|---|---|
| `sent, <delivery>, not received back` | Sent, but the message did not come back to the phone within 5 minutes. `<delivery>` is `delivered`, `delivery failed` or `no delivery report` |
| `not sent: <error>` | The phone could not send it: `radio off`, `no service`, `generic failure`, `no message data`, `sending limit reached`, `no answer`, or Android's own name with its code, e.g. `no default sms app (code 32)` |
| `own number not set`, `SMS permission missing`, `test SMS could not start`, `test SMS could not run` | The check could not test SMS at all. These never count towards `cellular_down` |

## 7. Checklist for the server

1. Accept `POST` JSON at the path you gave the app; answer `2xx` once stored.
2. If you set a token in the app, check `Authorization: Bearer <token>`; answer `401` otherwise.
3. Key events on `alert` + `state` + `time`; ignore repeats.
4. Order by `time`; expect late `started` and `recovered`.
5. Keep `signal_reading` numbers as numbers, with missing values as missing, not zero.
6. Raise your own alarm when neither `status` nor `cellular_status` has arrived for three times `next_check_in_min`.
7. Ignore unknown fields and unknown `alert` types rather than failing, so a newer app does not break the server.
