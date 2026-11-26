# G3 notes: reading, scheduling and the logged check

## Files (all under app/)
New: `app/src/main/java/app/matterplug/guard/BatteryReader.kt`, `CheckScheduler.kt`, `CheckReceiver.kt`, `GuardRunner.kt` (only `onAlarm`), `guard/logic/CheckLog.kt`; test `app/src/test/java/app/matterplug/guard/logic/CheckLogTest.kt`.
Changed: `guard/ui/GuardViewModel.kt` (it was already an `AndroidViewModel`, so the constructor and `GuardSection` are untouched), `app/src/main/AndroidManifest.xml` (only the `CheckReceiver` line, applied to your current copy staged from the phone-side folder; the file was identical to my copy apart from that).
`strings.xml` not changed: nothing user-visible was added. No G1/G2 logic file, no build file, no `matter` file touched.

## Behaviour
- Switch on: store.update, then alarm in 1 s. Switch off: store.update (clears next-check time, alert state), then alarm cancelled. Low/high/interval change while on: store.update, then alarm in 1 s. Nothing else asks for permissions or shows dialogs.
- Each alarm: `onAlarm` (under a Mutex) reads settings (off: one line "check skipped", no new alarm), reads the battery, schedules the next alarm FIRST using `intervalFor(plugged)` (unknown state: on-battery interval), `recordCheck`, computes fault and `GuardPolicy` action, logs one line, and stops. TODO(G4)/TODO(G5)/TODO(G6) comments mark the service start, the events and `finish`.
- Log line (tag `Guard`): `level=57 plugged=false action=Nothing fault=none interval=5min next=14:05:03`. Unknown battery: `level=unknown plugged=unknown ... fault=battery unreadable`, and `recordRunError("battery unreadable")`.

## Deviations
1. `CheckLog` (pure, in `guard/logic`) holds `degradationReason(batteryReadable, exactAllowed)` and the log line, so both are unit-tested; `GuardRunner` supplies the inputs (rule B1/section 9).
2. `CheckScheduler.scheduleIn` catches `SecurityException` from the exact call and falls back to the inexact alarm (permission revoked between the check and the call). The document does not say this.
3. Retry alarm: if the settings cannot be read at all, or something throws before the next alarm was set, `onAlarm` sets an alarm 5 minutes out so the chain cannot end. A later run that reads "off" stops it. Not in the document.
4. `CheckReceiver` also catches Throwable around `onAlarm` (logs) so `pending.finish()` is always reached; it passes `applicationContext` captured before launching.
5. `recordRunError` is written only on failures; nothing clears it yet when later checks succeed, so a stale "battery unreadable" stays in the store until a later milestone decides when to clear it.
6. Small race: a check running while the switch is turned off can set one more alarm after `cancel`. That alarm finds "off" and ends harmlessly.
7. `BatteryReader` uses `registerReceiver(null, ...)` exactly as the document: no export flag needed because no receiver is registered. `canScheduleExactAlarms` is guarded by `SDK_INT >= S` (minSdk 28).

## Verified, and how
- `CheckLog.kt` plus all `guard/logic` files compiled with kotlinc 2.0.21 (from Gradle 8.14.3) and `CheckLogTest` (7 tests) and `GuardPolicyTest` (11) run with JUnit 4.13.2: 18 tests, 0 failures. Not run through Gradle. The other test files were not re-run (no logic file changed).
- Manifest parses as well-formed XML; diff against your copy is the one receiver block.

## NOT verified
- `BatteryReader`, `CheckScheduler`, `CheckReceiver`, `GuardRunner`, `GuardViewModel` were NOT compiled (no Android SDK, coroutines-android, DataStore here). They were read through twice for imports, suspend scopes and API levels. Likeliest compile spots: `getApplication<Application>()` calls, the `inline` private `guarded` function in the ViewModel, the nested try-as-expression in `GuardRunner`.
- Nothing ran on a phone.

## Phone check (G3 row)
1. Build and install as in G2 (`tools/first-build.sh`, then `adb install -r`). Open the app, set low 60 / high 70 (or close to the current level), both intervals 5 minutes, switch Battery Guard on.
2. Watch: `adb logcat -s Guard` (add `-v time` for timestamps). Within about a second you should see one line from the 1 s alarm, then one line per check.
3. Close the app (swipe it away), turn the screen off, leave the phone. Expect one line per interval with the right level, plugged state and action: on battery at or below low: `action=TurnOn`; plugged at or above high: `action=TurnOff`; otherwise `Nothing`. Nothing is switched in G3; it only logs. `next=` is the wall-clock time of the following check.
4. An idle phone on battery may space the alarms 9 or more minutes apart even for a 5 minute setting (Doze limits `setExactAndAllowWhileIdle` to about once per 9 minutes per app; the check is still delivered, just later). On a charger or with the screen on the spacing is close to 5 minutes. The `next=` time is what was requested, not what Android promised.
5. Plug the charger in and wait: the next line shows `plugged=true` and `interval=` of the charging interval; unplug and it shows the on-battery interval. To see it quickly set the two intervals to different values (5 and 15). Plugging in does not trigger a check by itself; the new interval applies from the next check.
6. Fault test: `adb shell cmd appops set app.matterplug SCHEDULE_EXACT_ALARM deny` (Android 12 and 12L only; on 13+ the exact permission cannot be revoked this way) then wait for a line: `fault=exact alarms not allowed`. Restore with `... allow`.
7. Does the alarm survive swipe-away: after swiping the app away, `adb shell dumpsys alarm | grep -B2 -A6 app.matterplug` should still list a pending alarm of package `app.matterplug` with an `ELAPSED_REALTIME_WAKEUP` entry; and the log lines keep coming (step 3). If lines stop only after a swipe, the phone's maker kills the app and cancels alarms ("force stop" does cancel them): then check battery settings for the app ("Unrestricted"), and note it for G7. Also `adb shell am force-stop app.matterplug` must stop the lines (alarms are removed by a force stop); that is expected and is what the boot/relaunch handling of G7 deals with.
8. Switch Battery Guard off: one more line may appear, then `check skipped: Battery Guard is off` at most once, and `dumpsys alarm` no longer lists the app's alarm.
