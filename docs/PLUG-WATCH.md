# Plug Watch: Design (draft 1, for review)

Battery Guard checks that the charger plug can still be reached at every check, not only when it needs to switch the plug, and alerts when it cannot. Written 2026-11-15 from the code at commit `5738ae7`.

## 1. The problem

On 2026-11-15 the charger plug was off power for a long time and no alert came.

- Each check contacts the plug only when a switch is needed: at 20% or below to turn it on, and at 85% or above to turn it off (`BATTERY-GUARD.md` sections 4 and 6).
- Between those levels the check logs `action=Nothing`, and nothing talks to the plug. The battery stayed at 70–77%, so the plug was never tried.
- Worse, a check with no switch needed counts as a recovery: `AlertPolicy` treats `NoSwitchNeeded` like a successful switch. It resets the failure count and ends an active `plug_unreachable` alert, although the plug was never reached.
- A dead plug is found only when the battery reaches 20% and the turn-on fails twice. By then the phone has hours left at best. On a phone that charges from another source (such as a USB cable), the plug's absence is never noticed.

## 2. Goal

- Learn that the charger plug cannot be reached within about two check intervals (about 10 minutes at a 5-minute interval), whatever the battery level.
- Send the existing `plug_unreachable` alert for it, with its reminders every 60 minutes, and `recovered` only once the plug really answers again.
- No new notification on the phone at each check, and no new server request type.

Out of scope:
- Checking the plug while Battery Guard is off, or when no plug is paired. With no charger plug there is nothing to watch; Battery Guard switches off when its plug is removed, as now.
- Checking plugs other than the charger plug.
- Acting on what the plug reports (for example, turning it on when it was found off). Battery Guard's rule is unchanged.

## 3. Terms

| Term | Meaning |
|---|---|
| **Plug read** | A connect to the charger plug, a read of its On/Off state, and a release. It changes nothing on the plug. This is the existing `PlugCommander.readOnOff`, with its 8-second limit. |
| **Reached** | The plug read returned On or Off. A timeout, a connect failure or a failed read is **not reached**. |
| **Missed check** | A check where the plug was not reached, by a plug read or by a switch. |

## 4. The rule

At every Battery Guard check:

| The check | What contacts the plug | Counts as |
|---|---|---|
| A switch is needed | The switch, as now (connect, command, read-back) | Reached when the switch is confirmed; missed when it fails or is not confirmed (as now) |
| No switch is needed | **New:** one plug read | Reached or missed |
| The battery cannot be read | **New:** one plug read | Reached or missed |

Then the alert, using the existing counter `failedChecks` and the existing `FAILS_BEFORE_ALERT = 2`:

| State | When |
|---|---|
| `plug_unreachable` **started** | The second missed check in a row |
| **reminder** | Every 60 minutes while it lasts, as for every alert |
| **recovered** | The first check where the plug is reached |

- "No switch needed" no longer resets the counter or ends the alert. Only reaching the plug does. This removes the false recovery described in section 1.
- A switch failure and a read failure count the same. One alert covers both, so a plug that dies at 70% and is still dead at 20% gives one alert, not two.
- When the alert started from a plug read (no switch needed), its message says the plug cannot be reached, without "to turn it off" and without "not charging" (the phone may be charging from another source):
  `Matter Plug: battery 72%. The charger plug cannot be reached.`
  The two existing messages stay for alerts that started from a failed switch.
- The request is unchanged: `plug_unreachable` with `wanted_on` left out (it is already optional). A server that handles `plug_unreachable` today needs no change.

## 5. Where the plug read runs

In the check itself, inside the alarm receiver (`CheckReceiver` → `GuardRunner.onAlarm`), not in `ActionService`:

- `ActionService` is a foreground service with its own notification. Starting it every 5 minutes would flash a notification at every check.
- The receiver already runs its work off the main thread with `goAsync`. The plug read is limited to 8 seconds, and the HTTP delivery that follows is limited to 5 seconds. Together with Cellular Check's run, that stays well inside Android's limit for a receiver.
- The plug read runs after the next alarm is set and the battery is recorded (as today), so a slow or failed read cannot break the chain of checks.
- Battery Guard's status report and any alert go out after the read, so they carry its result.

**Risk:** with the app closed, the Matter library has to start inside the receiver for the read (today it starts only in the service or on screen). Starting it and connecting may take longer than 8 seconds on the first check after the app was closed. Unit P1 measures this on the phone before the rest is built. If the start is too slow, the fallback is to run the read in `ActionService` at a slower rate (section 8, Q1).

## 6. What the requests carry

Battery Guard's `status` request gets one new optional object, so the server can show the plug's state at every check, not only when an alert starts:

```json
"plug": {"reached": true, "on": true, "read_at": "2026-11-15T19:05:09-06:00"}
```

| Field | Type | Meaning |
|---|---|---|
| `reached` | boolean | Whether this check reached the plug |
| `on` | boolean, optional | The plug's On/Off state; left out when not reached |
| `read_at` | string | When the plug was read or switched, in the phone's time zone |

Only Battery Guard's requests carry it, following the rule in `ALERT-API.md` that neither sender carries the other's data. `cellular_status` is unchanged. This object is a proposal; see Q2.

## 7. On the phone

- The Battery Guard tab gets one line under the status lines: `Plug: reached at 19:05` or `Plug: not reached since 18:55`.
- No new switch. The plug read is part of Battery Guard, on while Battery Guard is on.
- Wi-Fi cost: one short local exchange with the plug at each check (a few small packets on the home network). No cellular data.

## 8. Questions for you

- **Q1. How often to read the plug.** At every check (every 5 minutes at your interval; recommended, and the alert comes after about 10 minutes), or at every third check (about every 15 minutes; the alert after about 30 minutes, with a third of the Wi-Fi traffic)?
- **Q2. The `plug` object in `status`.** Add it (recommended, so the server can show the plug at every check), or leave `status` as it is and rely on `plug_unreachable` alone?
- **Q3. The plug found Off.** If the plug is reached but reports Off while the battery is between the two levels, that is normal for Battery Guard (it turns the plug off at 85%). Should the app do anything about it? Recommended: no, just report `on: false`.

## 9. Build order

| Unit | What | Proved by |
|---|---|---|
| P1 | Measure on the phone, with the app closed, how long a plug read takes inside the alarm receiver | A log line with the time at three checks; decides section 5 |
| P2 | `AlertPolicy`: a new `PlugRead(reached)` check event; "no switch needed" no longer recovers; the new message | Unit tests first (two misses start, one reach recovers, no switch needed with no read keeps the alert, a switch failure and a read failure share the count) |
| P3 | The plug read in `GuardRunner.onAlarm`, the line on the Battery Guard tab | On the phone: pull the plug's power, see `plug_unreachable` after two checks; restore it, see `recovered` |
| P4 | The `plug` object in `status`, `ALERT-API.md` and the receiver in `tools/alert-receiver/` (if Q2 is yes) | The object arrives at your server |

## 10. Changes to other documents

- `BATTERY-GUARD.md` sections 4, 6 and 7: the plug is read at every check; the recovery rule changes.
- `ALERT-API.md`: the `plug_unreachable` description and the new message; the `plug` object (if Q2 is yes).
