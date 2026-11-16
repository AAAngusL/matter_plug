# Reminders and Recovery Signals

Draft 3, 2026-11-16, for review. All three questions are answered (section 4). Nothing here is built yet. It changes `BATTERY-GUARD.md` section 7, `CELLULAR-CHECK.md` section 8 and `ALERT-API.md` sections 4 and 5 once approved.

## 1. The rule

Every alert follows the same four steps:

1. **Started.** The fault is confirmed. One `started` request is sent; it is kept and retried until delivered.
2. **Reminder.** While the fault lasts, one `reminder` every 60 minutes, at any hour, with the current readings. A failed reminder is not retried; the next one follows 60 minutes later.
3. **Recovered.** As soon as the alert's **recovery signal** is seen, reminders stop, and `recovered` is sent in the same run that saw the signal. It is kept and retried like `started`.
4. **Reset.** The alert and its 60-minute clock are cleared. If the fault comes back, it is a new alert: its own confirmation, a fresh `started`, and reminders 60 minutes from that new start.

An alert can also end without a fix, because the user turns a switch off. It then ends with **`cancelled`** instead of `recovered` (section 2.4), and resets the same way.

The rule is only as good as each alert's recovery signal. A signal must be:

- **Proof, not absence.** The fix must be seen. "The fault was not looked for at this check" must never count as recovered.
- **Checked often while the alert is on.** Reminders say "still broken" every hour; if the signal is checked less often, the server is told "still broken" after the fault is fixed.

Section 2 checks each alert against those two tests.

## 2. The recovery signal of each alert

| Alert | Started when | Recovery signal today | Proof? | Checked how often while on | Change |
|---|---|---|---|---|---|
| `plug_unreachable` | The plug not reached at two checks in a row | The plug answers: a switch is confirmed, or a read-only read answers | Yes | Every check (5 min) | None |
| `not_charging` | Plug turned on and confirmed, phone still not plugged in 30 s later | The phone reads **plugged in** at a check | Partly (2.1) | Every check | Also require the battery to take charge |
| `guard_degraded`, battery unreadable | The battery not read at two checks in a row | The battery is read | Yes | Every check | None |
| `guard_degraded`, exact alarms not allowed | Android refuses exact alarms at two checks in a row | Android allows them again | Yes | Every check; Android's permission broadcast starts a check 5 s later | None |
| `guard_degraded`, service could not start | The switching service refused to start at two checks in a row | **Any check without a fault**, including one that did not try to start the service | **No** (2.2) | Only checks that need a switch | Only a service that starts ends it |
| `cellular_down` | Two test SMS in a row fail | A test SMS passes | Yes | At once when the radio recovers; **once a day** for a fault only SMS shows (2.3) | Re-test with every reminder |

### 2.1 `not_charging`: plugged in, and the battery taking charge

The alert means "the plug was turned on to charge a low battery, and the phone is not getting power". Today it ends at the first check that reads **plugged in**.

Since the charging-status change (`7658cbe`), the app also has Android's battery status. Below the low threshold a charge limit cannot apply (Samsung's limit is 85%), so a phone that is plugged in but `not_charging` or `discharging` at 20% has a real problem: a worn cable, a weak charger, or a battery too hot to charge. Ending the alert there tells the server the phone is fine while it still drains.

**Proposed recovery signal:** `plugged_in` is true **and** `battery_status` is `charging` or `full`.

- `full` counts, because a full battery has stopped needing charge.
- `unknown` (the status could not be read) does not end the alert; the next check decides.
- The start condition is unchanged: still "not plugged in 30 s after the plug was turned on" (you kept the start as it was in the charging-status review).
- While the phone is plugged in but not taking charge, the reminders say so, with the power words of `ALERT-API.md` section 5: `Matter Plug (reminder): battery 18%, plugged in, not charging. The plug is on. Check the charger and cable.` Today's fixed "not charging" text becomes the power words.

### 2.2 `guard_degraded`: one signal per reason

The alert covers three faults of Battery Guard itself. Today one rule ends all three: the first check that finds none of them. That works for two reasons and fails for the third.

| Reason | Proof of recovery | Today |
|---|---|---|
| battery unreadable | The battery is read at a check | Correct |
| exact alarms not allowed | `canScheduleExactAlarms()` is true at a check | Correct, and already fast: when the user allows exact alarms, Android sends `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, and `BootReceiver` schedules a check 5 s later, which ends the alert. On Android 13 and later this fault cannot occur (`USE_EXACT_ALARM` is always granted); it applies to Android 12 only |
| service could not start | The service **starts** | **Wrong.** The service is started only when a switch is needed. A check that needs no switch does not try, finds no fault, and ends the alert. So after two refused starts at 20%, the next check at 21% (plug off, no switch needed) sends `recovered`, though nothing has shown the service can start |

**Proposed recovery signal:** the fault named in the alert is proved gone.

- **battery unreadable:** the battery is read. Unchanged.
- **exact alarms not allowed:** exact alarms are allowed at a check. Unchanged.
- **service could not start:** the service starts. A check that needs no switch says nothing about it: the alert stays on, and reminders continue. The next switch then proves or disproves it.
- **When the reason changes** while the alert is on (for example, the service starts again but the battery becomes unreadable), the alert stays on with the new reason, and the next reminder names it. `recovered` is sent only when no fault is left.

### 2.3 `cellular_down`: re-test with every reminder

The alert ends when a test SMS passes. When the fault shows in the radio (SIM absent, not registered, no signal), a test runs the moment the radio looks well again, so recovery is fast.

When the fault shows only in SMS (a barred SIM, an SMS-centre fault, the radio looks fine), there is nothing to watch. Today a test runs **once a day** at the SMS test time (default 10:00). A fault fixed at 11:00 is reported as still down by 22 more hourly reminders, and `recovered` arrives the next morning.

**Proposed recovery signal:** unchanged (a test SMS passes), but **tested more often** while the alert is on: **one test SMS just before each reminder**, so every reminder says "re-tested now and still failing" and a fixed fault is found within the hour.

- Cost: at most one test SMS an hour to the phone's own number while an SMS-only fault lasts. Faults the radio shows cost nothing extra: their tests fail on the phone at once without reaching the network, and the radio's own recovery still triggers a test at once.
- The sent count in Cellular Check's section shows them.
- **Run cellular check now** still re-tests by hand at any time.

### 2.4 `cancelled`: an alert closed by a switch

Today, turning a switch off ends an alert with nothing sent, so the server keeps it open. Four cases mislead the server:

1. **Left open.** The plug is fixed and Battery Guard turned off before a check sees it answer: no `recovered`, ever. (This is how the 2026-11-15 20:24 `plug_unreachable` ended at 08:10.)
2. **`recovered` thrown away.** A `recovered` waiting for an unreachable server is deleted when Battery Guard is turned off, because the waiting list is cleared.
3. **Reminders with no `started`.** With **Send alerts** off the app keeps tracking alerts but sends nothing. An alert that starts while alerts are off reaches the server as a stray `reminder` once alerts are back on; one that ends while they are off loses its `recovered`.
4. **Silence that looks like a dead phone.** With Battery Guard off its status reports stop too. `cancelled` does not solve this one (see the end of this section).

**The rule:** when a switch turns off while one of its alerts is on, the phone sends **one `cancelled` per open alert**, then resets those alerts.

| Switch turned off | Alerts closed | `cancelled_by` |
|---|---|---|
| **Keep battery between** | `not_charging`, `plug_unreachable`, `guard_degraded` | `guard_off` |
| **Send alerts** (Battery Guard) | The same three | `alerts_off` |
| **Check cellular service** | `cellular_down` | `cellular_off` |
| **Send cellular alerts** | `cellular_down` | `cellular_alerts_off` |

```json
{"alert":"plug_unreachable","state":"cancelled","cancelled_by":"guard_off",
 "message":"Matter Plug: Battery Guard was turned off. The plug unreachable alert is closed without a recovery. Battery 64%.",
 "battery":64,"charging":true,"plugged_in":true,"battery_status":"charging","time":"2026-11-16T14:05:10-06:00"}
```

- **Message:** `Matter Plug: <switch phrase>. The <alert name> alert is closed without a recovery. Battery <n>%.` The switch phrases: `Battery Guard was turned off`, `Battery Guard alerts were turned off`, `Cellular Check was turned off`, `Cellular alerts were turned off`. The alert names: `not charging`, `plug unreachable`, `Battery Guard not working`, `SMS`. `wanted_on` and the `cellular` object are carried as in the alert's other requests.
- **Delivery:** kept and retried like `started` and `recovered`. Requests already waiting are **sent first, not thrown away** (this fixes case 2): turning a switch off no longer clears the waiting list. The flush job keeps sending it after checks stop. `cancelled` is the last request about that alert; nothing is sent after it.
- **Send alerts off resets the alerts** (this fixes case 3). It no longer tracks them silently: the alert and its counts are cleared, as with Battery Guard off. When alerts are turned back on and the fault is still there, it is confirmed again and a fresh `started` follows.
- **Only open alerts.** With no alert on, turning a switch off sends nothing. A status report is never cancelled.
- **No valid alert address:** nothing can be sent, as for every request.
- **On the phone:** the alert's notification is removed.
- **For the server:** treat `cancelled` like `recovered` for closing the alert, but do not announce that anything was fixed. `cancelled_by` says which switch closed it.
- **Case 4 stays open:** `cancelled` closes the alerts, but the server's silence alarm still cannot tell "Battery Guard turned off" from "phone dead" when no further status report comes. Cellular Check's `cellular_status`, if on, keeps arriving.

## 3. What does not change

- The start conditions and confirmation counts of all four alerts.
- `recovered` goes out in the run that sees the signal, behind any older requests waiting, and is kept and retried.
- Notifications on the phone: one per alert kind; a reminder or a recovery replaces it.

## 4. Questions for review

1. **`not_charging`:** ~~end it only when the battery takes charge, or keep "plugged in"?~~ **Answered: when the battery takes charge** (`plugged_in` and `battery_status` `charging` or `full`), section 2.1.
2. **Turning a feature off:** ~~leave it silent, or send a closing request?~~ **Answered: send `cancelled`**, section 2.4.
3. **`cellular_down`:** ~~hourly or less often?~~ **Answered: every hour**, one test SMS just before each reminder while an SMS-only fault lasts, section 2.3.

## 5. Changes to build, once approved

| Step | Change | Proof |
|---|---|---|
| R1 | `AlertPolicy`: `not_charging` recovers on plugged in and charging or full; `guard_degraded` recovers per reason; a check that did not start the service does not count | Unit tests: plugged in at 20% `not_charging` keeps the alert and reminds; `charging` ends it; a no-switch check after two refused starts keeps `guard_degraded`; a started service ends it; a reason change keeps the alert with the new reason |
| R2 | `CheckEvent` gains "service started", so the policy can tell "started" from "not tried" | Unit test of `CheckDecision.alarmEvents` |
| R3 | `cellular_down`: a test SMS before each reminder while the radio looks fine | Unit test of the cellular plan; on the phone, with a wrong own number (an SMS-only fault): a test every hour, and `recovered` within the hour of fixing the number |
| R4 | `cancelled` (section 2.4): a new state and `cancelled_by` field; each of the four switches enqueues one per open alert and resets them; turning a switch off keeps the waiting list and schedules the flush job; **Send alerts** off resets the alert state | Unit tests: one `cancelled` per open alert with the right `cancelled_by`, none with no alert, the waiting list kept in order with `cancelled` last, alerts reset by Send alerts off, then a fresh `started`. On the phone: unpower the plug until `plug_unreachable` starts, turn Battery Guard off, and see `cancelled` reach the server |
| R5 | `BATTERY-GUARD.md` section 7, `CELLULAR-CHECK.md` sections 6 and 8, `ALERT-API.md` sections 3 to 5 (the `cancelled` state, `cancelled_by`, the switch rules) | Read through |

Each step goes test first, then the mutator, then one commit.
