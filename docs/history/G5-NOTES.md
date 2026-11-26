# G5 notes: alerts wired in, GuardNotifier, power wait (notifications only, no HTTP)

## Files (all under app/)
New: `app/src/main/java/app/matterplug/guard/GuardNotifier.kt`, `app/src/main/java/app/matterplug/guard/logic/CheckDecision.kt` (pure: `alarmEvents`, `nothingToDo`, `notificationId`), `app/src/test/java/app/matterplug/guard/logic/CheckDecisionTest.kt` (14 tests).
Changed: `guard/GuardRunner.kt`, `app/src/main/res/values/strings.xml` (5 strings, applied to your current file; it was byte-identical to my copy before the edit).
Not changed: `AndroidManifest.xml` (staged and compared, nothing needed: POST_NOTIFICATIONS, WAKE_LOCK are there). No PlugClient, ChipController, Commissioner or pairing file touched. `ActionService.kt` not changed (it still creates channel `guard_work`).

## Behaviour
- `GuardNotifier.post(ctx, events)`: channel `guard_alerts` (importance high) created on each post (idempotent); one notification per event, text = `AlertMessages.text(event)`, title by kind (Recovered: "Battery Guard: recovered"); id from `CheckDecision.notificationId(kind)` (4201 not charging, 4202 plug unreachable, 4203 not working), so a reminder replaces the earlier one and a recovery replaces it. Android 13+ without POST_NOTIFICATIONS: logs one `Guard` line and returns. Never throws. Icon: system `stat_notify_error`.
- `GuardRunner.onAlarm`: events from `CheckDecision.alarmEvents`; `nothingToDo` (pure) ends the run with no service, no Matter, no notification work; otherwise `finish(..., budgetMs = 5_000)`. A TurnOn/TurnOff check still starts `ActionService` and returns; the service does `finish`.
- `GuardRunner.work`: NoSwitchNeeded / SwitchConfirmed (+ `PowerWait(waitForPower(30 s))` for TurnOn) / SwitchFailed, then `Degraded(reason)`, then `final = BatteryReader.read ?: reading` (read AFTER the power wait), `finish(..., 60_000)`, then reschedule using that same final reading (the G4 re-read happens only if no final reading was taken, e.g. after an exception).
- `waitForPower`: reads at once, then every 2 s with `delay`, true as soon as plugged in, false at 30 s; cancellable; inside the 120 s service limit (25+10+10+30 = 75 s worst case).
- `finish`: level/plugged from the reading or the last stored values (plugged defaults false, level 0 when nothing is stored), `AlertPolicy.apply`, `writeAlertState`, one `Guard` log line per resulting event (`alert: kind=not_charging phase=started battery=.. plugged=..`), `GuardNotifier.post`. `TODO(G6)` marks where the status event and `HttpAlerter.deliver` go.
- `recordRunError(null)` at the end of a run with no fault (no Degraded reason, no exception, next alarm scheduled): both in `onAlarm` (also on the nothing-to-do path) and in `work`.
- Mutex, B4 (alarm first), reschedule in `finally` under `NonCancellable`: unchanged.

## Deviations
1. `onAlarm` emits at most ONE `Degraded` event. The document says Degraded(fault) plus Degraded("service could not start"); `AlertPolicy` counts every Degraded event as one faulty check, so two in one call would trigger the third alert after a single check. "service could not start" wins over the other reason. Pure and tested (`alarmEvents`).
2. `degradationReason(ctx, reading)` delegates to the existing pure `CheckLog.degradationReason` (G3).
3. In `work`, a null reading is handled in the same flow (events = `[Degraded("battery unreadable")]` via `degradationReason`) instead of a separate early return; the result is the same, including the reschedule.
4. When `reading` is null, `finish` takes the last stored level/plugged from `store.status`. `onAlarm` has already called `recordCheck(null, null)`, which removes those values, so an unreadable battery will normally report "battery 0%, not charging" in a Degraded message. Left as specified; only matters for the "battery unreadable" case.
5. `finish` creates its own `GuardStore(ctx)` (cheap wrapper over the one DataStore) so the signature is exactly the document's.
6. If the 120 s service limit cancels `work` before `finish`, no alert is evaluated for that run (the next alarm still exists). Cannot happen with the worst case above unless Matter calls hang beyond their own timeouts.
7. Notification has no tap action (no PendingIntent to the app).
8. `queueEmpty` in `nothingToDo` comes from `store.readQueue()`; in G5 the queue is always empty.

## Verified / NOT verified
- RAN: the 11 source files and 11 test files of `guard.logic` (the earlier 10 test files plus `CheckDecisionTest`) compiled with the Kotlin 2.0.21 compiler bundled in Gradle 8.14.3 and run with JUnit 4.13.2: OK (103 tests). Only `guard/logic` and its tests were compiled.
- NOT compiled, NOT run: `GuardNotifier.kt`, `GuardRunner.kt` (no Android SDK, Matter jars here). I read them for imports, suspend scopes, smart casts and API levels (minSdk 28, so channels need no version check; `POST_NOTIFICATIONS` check only from API 33). Likeliest compile spots: `@SuppressLint` import is `android.annotation.SuppressLint` (present), `events += ...` on a `MutableList` parameter inside `when` branches, `R.string.*` names. strings.xml is well-formed XML.
- No notification was seen on a phone.

## Phone checks (G5 row; T7, T8, T20, notifications only)
Setup for all: build, install (`tools/first-build.sh`, `adb install -r`), allow notifications for Matter Plug (Android 13: the permission prompt does not exist yet, G7 adds it; grant it in Settings > Apps > Matter Plug > Notifications, or `adb shell pm grant app.matterplug android.permission.POST_NOTIFICATIONS`). Use wireless debugging (USB counts as plugged in). Log:
`adb logcat -v time -s Guard:I PlugCommander:I`
Both intervals 5 minutes (Android may space alarms about 9 minutes on an idle phone). "Send alerts" stays off (no HTTP yet; notifications are posted anyway). Lines to look for: `alert: kind=... phase=started|reminder|recovered`.

Counting rule: the alert comes at the SECOND consecutive bad check, not the first.

### T7 Plug unreachable
1. Phone on the charger plug at, say, 85%. Set low 50 (high 60 or more), then high at or below the current level, e.g. 80: the next check wants TurnOff (plugged and level >= high). To test the On side you need the phone on battery at <= 50%, so TurnOff is the practical test; the notification then reads "The charger plug cannot be reached to turn it off".
2. Remove the plug's mains power (or switch Wi-Fi off on the router) BEFORE the first check after you set the thresholds. Expect in the log: `action=TurnOff`, then `switch off failed: ...` (connect timeout about 25 s) and `work:` line with `outcome=switch off NotReached(...)`. No `alert:` line and no notification after this first failed check.
3. Next check (5 to 9 minutes later): same failure, then `alert: kind=plug_unreachable phase=started` and a notification "Battery Guard: plug unreachable".
4. Keep it unreachable for an hour: `phase=reminder` about 60 minutes after the start (replaces the earlier notification; it is checked at each failed check, so it comes at the first check at or after 60 minutes). To save time you may skip this step.
5. Restore power/Wi-Fi. At the next check the switch succeeds (`switch off confirmed`), then `alert: kind=plug_unreachable phase=recovered` and the notification text becomes "Matter Plug: recovered. Battery N%, ...". Note: a TurnOff that succeeds ends the NotCharging/plug alert, the phone stays unplugged afterwards until the level falls to low.

### T8 Not charging
Needs action TurnOn: the phone must be on battery at a level <= low <= 50.
1. Get the phone to 50% or less on battery (or, if you are already there, it works directly). Set low = current level up to 50 (for example the phone is at 47%: low 50, high 60). Pull the CHARGER CABLE from the phone (not from the wall), so the plug is on but the phone has no power.
2. Next check: `action=TurnOn`, `switch on confirmed at 47%` (the plug is already on; the command is still sent and read back), `power wait: plugged=false` about 30 s later, then `alert: kind=not_charging phase=started` and the notification "Battery Guard: not charging" about 30 s after the confirmed On. (No second check is needed: the power wait is the whole condition.)
3. Plug the cable back in. At the next check the phone is plugged in; level > low so the action is Nothing, events `NoSwitchNeeded`, and because an alert is active the check does not stop early: `alert: kind=not_charging phase=recovered`, notification text becomes the recovery text. (Recovery is also evaluated at the end of any run that sees the phone plugged in.)
4. While the cable stays out, each later check repeats TurnOn and the power wait; no second `started`; a `reminder` after one hour.

### T20 Battery Guard not working
Fault conditions: "exact alarms not allowed" and "battery unreadable". Honest limits: on Android 13+ the app declares `USE_EXACT_ALARM`, which is granted automatically and cannot be revoked by the user, so `appops ... SCHEDULE_EXACT_ALARM deny` may have no effect on your phone (Android 13). Try it anyway:
`adb shell cmd appops set app.matterplug SCHEDULE_EXACT_ALARM deny` and check with `adb shell cmd appops get app.matterplug SCHEDULE_EXACT_ALARM`. If `canScheduleExact` then reports false the log line shows `fault=exact alarms not allowed`. If the log still says `fault=none`, the fault cannot be provoked this way on Android 13; the same applies to "battery unreadable" (cannot be forced on a normal phone). There is no other way to provoke this fault without a code change; if it cannot be provoked, report that T20 was not run.
Expected if the fault appears: first faulty check: log line with `fault=exact alarms not allowed`, no `alert:` line. Second faulty check: `alert: kind=guard_degraded phase=started` and the notification "Battery Guard: not working" with the reason. Undo with `adb shell cmd appops set app.matterplug SCHEDULE_EXACT_ALARM allow` (or `default`): the next check with `fault=none` and anything that makes the run non-trivial gives `phase=recovered`.
Important: a faulty check does not stop the early exit logic from running again: while a count or an alert is stored, every check goes through the alert policy, so recovery is evaluated at the first healthy check.

## Known behaviour to expect
- The first check after the app is installed with no stored alert state and a healthy battery still ends silently (nothing-to-do path): no notification, no service.
- Stale "Last problem" lines on the settings screen now disappear after the next fault-free run.
