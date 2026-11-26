# G4 notes: PlugCommander, ActionService, GuardRunner.work (no alerts)

## Files (all under app/)
New: `app/src/main/java/app/matterplug/matter/PlugCommander.kt`, `app/src/main/java/app/matterplug/guard/ActionService.kt`.
Changed: `guard/GuardRunner.kt` (added `work`, `runWork`, `doSwitch`, `rescheduleAfterWork`; `onAlarm` now starts the service), `app/src/main/AndroidManifest.xml` (service block + WAKE_LOCK permission), `app/src/main/res/values/strings.xml` (3 strings: `guard_channel_work`, `guard_switching_title`, `guard_switching_text`).
Manifest and strings.xml were staged from your current files and were byte-identical to my copy before the edit. No PlugClient, ChipController, Commissioner or pairing file touched. No test added (no new pure logic).
Sanity check passed: ChipController has `getAndroidChipPlatform(context)`, no `discardAfterClose`; Commissioner has `failedStep = AtomicReference`.

## Behaviour
- `onAlarm` unchanged except: after the log line, `action != Nothing` calls `ActionService.start(ctx)`; true returns (the service does the rest); false logs a `Guard` warning line with `fault=service could not start` (start already wrote `recordRunError`). Action Nothing: no service, no Matter class touched.
- `ActionService`: `startForeground` first (even for an ignored duplicate start), channel `guard_work` (low, no sound), type connectedDevice on API 29+; a static AtomicBoolean ignores a start while a run is going; `GuardRunner.work` inside `withTimeout(120_000)`; partial wake lock `matterplug:guard`, 130 s backstop, released in `finally`; `stopSelf()` in `finally`.
- `work`: same Mutex as `onAlarm`. Settings (off: return), battery (B5), `GuardPolicy.decide`; TurnOn/TurnOff: first plug of `PlugRepository` (B10), `PlugCommander.switchTo`; Confirmed: `recordSwitch` + log; NotReached/Unconfirmed: log the reason. In a `finally` under `NonCancellable`: re-read the battery, `scheduleIn` with the interval of the plugged state read AFTER the switch, `recordCheck`, one `Guard` line: `work: level=.. plugged=.. action=.. fault=.. interval=..min next=.. outcome=switch off Confirmed`. Because it is in `finally`, the alarm chain continues after an exception or the 120 s timeout.
- `PlugCommander`: connect (25 s), invoke On/Off (10 s), `readPath` OnOff back (10 s), compare; `ChipController.release` in `finally`. Never Toggle. Tags: `PlugCommander` logs `requested=off readBack=false`.
- TODO(G5) markers: power wait, AlertPolicy.apply, GuardNotifier, SwitchFailed/Degraded events, "service could not start" Degraded event. TODO(G6): HttpAlerter.

## Deviations
1. `ActionService.start` is `suspend` (it calls the suspend `recordRunError`); both callers are suspend. The document says plain `fun`.
2. Manifest: added `android.permission.WAKE_LOCK` (a normal permission, not in the G2 list). Without it `newWakeLock().acquire` throws SecurityException. The code catches that and runs without a lock, but the permission is needed for rule 2.
3. Timeouts use `withTimeoutOrNull` and map null to `NotReached("timeout ...")`, instead of catching `TimeoutCancellationException` from `withTimeout`. Same result, and an outer cancellation (the service's 120 s limit) is never swallowed.
4. Foreground-start failure: caught as `IllegalStateException` (ForegroundServiceStartNotAllowedException is a subclass, only referenced behind an API 31 check) and `SecurityException`.
5. If `startForeground` itself throws inside the service, it records "service could not start", stops itself and returns; the next alarm already exists (B4).
6. A timeout of the whole run writes `recordRunError("switching timed out")`; other failures "check failed".
7. The interval after a TurnOff uses `BatteryReader` immediately after the confirmed switch. Android may still report "plugged" for a second or so, so the first check after a switch may use the charging interval. With both intervals at 5 minutes it makes no difference; G5 adds a wait for TurnOn anyway.
8. Small icon is the system `android.R.drawable.stat_notify_sync` (the app has no drawables yet).
9. Foreground service type `connectedDevice` on Android 14 needs the app to hold one of a list of permissions; from memory `CHANGE_WIFI_MULTICAST_STATE` (already in the manifest) is on that list. Part of V2.

## Verified / NOT verified
- Verified: sanity check; manifest and strings.xml are well-formed XML; the code was read through twice for imports, suspend scopes, smart casts (kept local vals), API levels.
- NOT verified: nothing was compiled (no Android SDK, Matter jars, coroutines here) and nothing ran. Likeliest compile spots: `Result<Unit>?` with `withTimeoutOrNull` in PlugCommander, the `return` inside `try` inside suspend functions, `android.app.ForegroundServiceStartNotAllowedException` reference (compileSdk must be 31+; it is 34), `R.string` import in ActionService.
- No unit tests were added or run for G4. The G1 to G3 tests are unchanged.

## (verify) items
- V2 (connectedDevice service started from the alarm receiver path, app closed, screen off, Android 13/14): the log shows `Guard` line, then (no "service could not start") `switch off confirmed ...`; `adb shell dumpsys activity services app.matterplug` during the run lists ActionService with isForeground=true; a refusal would show `service could not start (foreground start not allowed)` and the status line "Last problem: service could not start". Test on the actual phone with the screen off for at least one check.
- V3 (second connect while the plug screen is open): open the plug screen, leave it on, wait for a switch (or lower a threshold so one happens). Expected: `PlugCommander` logs `requested=off readBack=false`, the screen shows Off within a few seconds (subscription still alive), and a manual toggle on the screen afterwards still works. If the screen goes Offline after the switch, the release disturbed the shared session; report it. Late connect pointer: `ChipController.connect` loses a pointer that arrives after its own 10 s timeout (not released); not changed, as ChipController is off limits. Watch for repeated "Timed out connecting" lines.
- V4 (`getBool(AnonymousTag)` read-back): confirmed if `readBack=` shows true/false, not `unknown`.

## Phone check (G4 row; tests T3, T4, T5)
Rule: TurnOff when plugged in and level >= high (high 100 never). TurnOn when on battery and level <= low. Allowed: low 5..50, high 50..100, gap >= 10.

Setup: build and install as before (`tools/first-build.sh`, `adb install -r`). Phone on the charger plug, ~84%. Intervals 5 / 5. Start logging:
`adb logcat -v time -s Guard:I ChipController:* PlugCommander:*`
(`-s` with `ChipController:*` shows all levels of that tag; add `PlugClient:*` to see the screen's own session.)

A. Plain test as specified: high 85, low 80, switch Battery Guard on, close the app, screen off. When the level reaches 85 and the phone is plugged in, the next check logs `action=TurnOff`, `PlugCommander requested=off readBack=false`, `switch off confirmed at 85%` and a `work:` line with `outcome=switch off Confirmed`. The plug clicks off; if the plug screen is open it shows Off. The phone then drains; at 80 (or below, at the next check) the plug switches on: `action=TurnOn`, `requested=on readBack=true`. Real overshoot (T6): note the level in the log versus the threshold; with 5 minute checks while charging the phone may be a few percent above 85.

B. Fastest TURN-OFF test (no waiting): phone at 84%, plugged in. Set low 50 first, then high 80 (80 >= 50, gap 30). Level 84 >= 80 and plugged: the first check (about 1 second after switching on, or after changing a setting) gives TurnOff. Any high at or below the current level works as long as high >= 50 and low <= high - 10.

C. TURN-ON test, honestly: low can be at most 50, so TurnOn can only fire when the phone is on battery and at 50% or below. At 84% no setting triggers it (high is irrelevant while unplugged; the quick option of lowering both thresholds does not exist). Options:
   1. After test B the plug is off, so the phone drains by itself. Set low 50 (high 60 or more), keep the app closed, screen off, and wait until the phone reaches 50%: the next check logs `action=TurnOn` and switches on. Several hours at normal idle drain; leave it overnight, or speed it up by playing a video with the screen on (the screen being on does not matter to the check).
   2. If at any time the phone is already at 50% or less and on battery (for example after test A drained it, or a phone left to drain), set low to any value from the current level up to 50 (low must be >= level) and high to at least low + 10: it fires at the next check. Example: phone at 47%, unplugged: low 50, high 60.
   3. Pulling the charger cable from the phone (instead of the plug off) gives plugged=false but the same limit applies: the level must be <= low <= 50.
