# Radio Type Gaps: 3G, 4G and 5G

How the requests in `ALERT-API.md` behave when the phone is on 3G, 4G (LTE) or 5G, and what is missing on each. Written 2026-11-15 from the code at commit `b60f4d6`. Only 4G (LTE) has been tested, on the Galaxy S20 FE (SM-G780F, Android 13); the other columns are read from the code.

## 1. Summary

The SIM check, the registration check, the signal pass/fail, the test SMS, and every request type and its transport work the same on every radio type. What differs is the signal **as numbers** (`signal_reading`) and the planned `weak_signal` alert: both are LTE only, by the decision in `SIGNAL-STRENGTH.md` ("5G is not considered in this version").

## 2. Per radio type

| Field in the request | 3G (UMTS/HSPA) | 4G (LTE) | 5G non-standalone (5G with an LTE anchor) | 5G standalone (5G only) |
|---|---|---|---|---|
| `cellular.sim` | Works | Works | Works | Works |
| `cellular.registration` | Works; the passing text says `3G` | Works (tested) | Works; the passing text says `5G` | **Uncertain** (gap 2) |
| `cellular.signal` (pass/fail) | Works: judged on Android's 0–4 bars | Works | Works | Works |
| `cellular.signal_detail` | **Only `level N`**: no measured values | `LTE, RSRP … RSRQ … SINR …` | `NR, RSRP … RSRQ … SINR …` (the 5G cell) | `NR, …` |
| `cellular.signal_reading` | **Absent** | Present | Present, **with the LTE anchor's values**, not 5G's (gap 3) | **Absent** |
| `weak_signal` (designed, not built) | **Never judged** | Judged | Judged on the LTE anchor | **Never judged** |
| `cellular.sms` (test SMS) | Works | Works (tested) | Works | Works, where the network carries SMS on 5G |
| `signal_reading.rat` | — | `lte` | `lte` | — |

2G (GSM/EDGE) behaves like the 3G column: the passing text says `2G`, `signal_detail` is `level N`, and there is no `signal_reading`.

## 3. The gaps

### Gap 1: signal numbers and weak-signal alerts are LTE only

- On 3G, 2G and 5G standalone, `signal_reading` is left out and `weak_signal` can never start.
- The server then sees the signal only as `cellular.signal` (`pass` or `fail` at 0 bars) and the bars inside `signal_detail`.
- 3G is measured differently (RSCP and Ec/No, from `CellSignalStrengthWcdma`); the app does not read those at all.
- **Effect for the server:** a missing `signal_reading` does not mean no signal; check `cellular.signal` and `signal_detail`.

### Gap 2: registration on 5G standalone is untested and may be wrong

- The app counts the phone as registered from the **voice (CS) domain** of Android's registration info (`NetworkRegistrationInfo`, `DOMAIN_CS`). On LTE this also covers VoLTE, and it was verified on the phone.
- A 5G standalone network has no CS domain. Android may then report the CS domain as not registered although SMS works over IMS.
- **Possible effect:** `registration` is `fail` with reason `not registered`; one confirmation test SMS is sent, which should pass, so the screen shows a warning and no `cellular_down` alert follows. The reading stays `fail` while the phone is on 5G standalone, without further test SMS.
- **Needs:** a test on a 5G standalone network before relying on it there.

### Gap 3: on 5G non-standalone, the text and the numbers describe different cells

- `signal_detail` shows the 5G (NR) cell, because Android lists it first.
- `signal_reading` holds the LTE anchor cell, because only LTE is reported as numbers.
- So the RSRP in `signal_detail` can differ from `signal_reading.rsrp_dbm`. Both are correct; they are two different cells. `signal_reading.rat` (`lte`) says which one the numbers are.

### Gap 4: the radio name follows mobile data, not voice

- The radio type in the passing `registration` text (`LTE, home`, `5G, home`, `3G, home`) comes from the **data** connection (`dataNetworkType`).
- With mobile data switched off, the text can read `registered, home` instead of the radio type. The pass/fail result is not affected.
- **Fix (small):** take the radio type from the voice network (`voiceNetworkType`) first, then from data.

### Gap 5: 3G may not exist

Many carriers have switched their 3G networks off. On those, a phone that loses 4G falls back to 2G (as the 3G column) or to no service (`registration` `fail`, reason `out of service`). The test SMS shows which.

## 4. What is not affected

- Every request type, its fields, its switches and its delivery (`ALERT-API.md` sections 1 to 5).
- The SIM layer, the Signal layer's 0-bar rule, the test SMS and `cellular_down` on every radio type.
- Battery Guard: it does not use the cellular network at all (its plug is reached over Wi-Fi).

## 5. Options to close the gaps

| Gap | Option | Size | Needs |
|---|---|---|---|
| 1 | Report 5G NR values in `signal_reading` (`rat:"nr"`, NR ranges), and judge `weak_signal` on them | Medium: a design change to `SIGNAL-STRENGTH.md` | A decision on 5G thresholds (NR RSRP and SINR differ from LTE) |
| 1 | Report 3G values (`rat:"wcdma"`, RSCP and Ec/No) | Medium | A decision on whether 3G matters where the phone is used |
| 2 | Count IMS or PS-domain registration as registered when there is no CS domain | Small code change | A test on 5G standalone first |
| 3 | Make `signal_detail` describe the same cell as `signal_reading` on non-standalone | Small | A decision: show 5G or LTE in the text |
| 4 | Radio type from `voiceNetworkType` first | Small | None |

None of these is built. Gap 4 is the only one that needs no decision or test first.
