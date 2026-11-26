# G7 notes: BootReceiver, permission prompts, Check now, exact-alarm warning, disable dialog

Paths are relative to `app/` (the Gradle root). Files marked (new) did not exist; the others were changed.

## Files
- (new) `app/src/main/java/app/matterplug/guard/BootReceiver.kt`: on BOOT_COMPLETED, MY_PACKAGE_REPLACED and `android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`: `goAsync()` plus a coroutine; if `enabled`, `CheckScheduler.scheduleIn(ctx, 5_000)` (B3: no service from here); if the waiting list is not empty, `FlushJobService.schedule(ctx)`. Any other action is ignored. One log line `boot: action=... enabled=... waiting=...` (tag `Guard`).
- (new) `app/src/main/java/app/matterplug/guard/GuardPermissions.kt`: `notificationsGranted` (always true below Android 13), `ignoringBatteryOptimizations`, `ignoreBatteryOptimizationsIntent` (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `Uri.parse("package:" + packageName)`), `canScheduleExact` (delegates to `CheckScheduler`). `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is declared in the manifest since G2: verified by reading it.
- (new) `app/src/main/java/app/matterplug/guard/logic/PermissionPrompts.kt`: pure `PermissionPrompts.due(sdk, notificationsGranted, askedNotifications, exemptionHeld, askedExemption)` and `needsTurnOnDialog(plugOn: Boolean?)` (true only for `false`), enum `PermissionPrompt`.
- (new) `app/src/test/java/app/matterplug/guard/logic/PermissionPromptsTest.kt`: 6 tests.
- `guard/GuardStore.kt`: keys `asked_notifications`, `asked_exemption` with `readAsked...` / `markAsked...`. Not cleared when Battery Guard is switched off (asked once, ever; clearing app data re-asks).
- `matter/PlugCommander.kt`: `suspend fun readOnOff(plug: Plug): Boolean?` (connect + read inside one `withTimeoutOrNull(8_000)`, pointer released in `finally`, null on any failure or timeout, cancellation propagates). It overloads the existing private `readOnOff(plug, pointer)`. `switchTo` is not changed.
- `guard/ui/GuardViewModel.kt`: `setEnabled` split into enable and disable. New: `disableState` (`DisableState`: Idle, Reading, AskTurnOn, TurningOn, TurnOnFailed), `prompt` / `promptConsumed()` / `promptDone()`, `disableTurnOn()`, `disableLeaveOff()`, `disableCancel()`, `checkNow()`. The `GuardSection()` signature is unchanged, so `PlugScreen.kt` needs no change.
- `guard/ui/GuardSection.kt`: permission launcher and effects (placed before the early `return`), exact-alarm check refreshed on every ON_RESUME and after each check, enabled "Check now" (only while Battery Guard is on), progress notes, the AlertDialog.
- `res/values/strings.xml`: `guard_disable_dialog_text`, `guard_disable_turn_on`, `guard_disable_leave_off`, `guard_disable_checking`, `guard_disable_turning_on`, `guard_disable_turn_on_failed` (Cancel reuses `cancel`). Applied to the user's staged copy (identical to my copy before the edit).
- `AndroidManifest.xml`: `BootReceiver` entry exactly as in 3.1 (exported=true, the three actions). Applied to the user's staged copy (identical to my copy before the edit).
- `StatusText.kt` needed no change: G2 already built `ExactAlarmWarning` ("Exact alarms are not allowed. Checks may be late.") and its test; G7 only feeds it the real `canScheduleExact`.

## How it works
- Enable: the switch turns on at once (`enabled=true`, check in 1 s), THEN the prompts: `due()` decides, the asked flag is stored when a prompt is handed to the screen, the screen launches `RequestPermission(POST_NOTIFICATIONS)` (Android 13+ only) and, after its result (granted or not), starts the battery-optimisation intent (`ActivityNotFoundException` caught and logged). Neither blocks anything.
- Disable: the switch is disabled while the plug is read. `readOnOff` false -> dialog; true or null (or no plug) -> disabled at once. Turn on -> `switchTo(plug, true)`, if Confirmed then disabled; Leave off -> disabled; Cancel or tap outside -> nothing changes, the switch stays on (it is bound to the stored value). "Disabled" is `store.update{enabled=false}` (clears queue and alert state) + `CheckScheduler.cancel` + flush job cancel.
- Check now: `scheduleIn(1_000)` only if `enabled` in the store.

## Deviations (smallest fix where the document is wrong or silent)
1. 5.9 says `setEnabled(true)` asks for permissions and then updates the store. Here the store is updated and the check scheduled first and the prompts follow, so a slow or refused prompt can never delay or block the switch ("Neither prompt blocks enabling").
2. 5.9 takes the plug state from `PlugViewModel`; the task says PlugScreen cannot be edited, so the view model reads the plug itself with `PlugCommander.readOnOff` (8 s). Side effect: one extra short Matter session (same V3 question as in G4: a second `connect` while the plug screen is open).
3. Turn on failed (not `Confirmed`): the document does not say. Battery Guard stays on and a red line "The plug could not be turned on. Battery Guard stays on." is shown (extra string, extra state `TurnOnFailed`); the user can try again. Disabling anyway would leave the plug off with nothing watching it.
4. The permission prompt is a one-shot event (`promptConsumed()` clears it as it is launched) so a screen rotation cannot launch the system dialog twice. If the app is closed while a prompt is pending, the second prompt is not shown until it is asked again, but its asked flag is already set. Accepted: asked once.
5. `BootReceiver` ignores any action other than the three (defensive; exported=true).
6. Dialog buttons: Turn on is the confirm button; Leave off and Cancel are in the dismiss slot as a Row. If the system lays out three buttons badly on a narrow phone, that is cosmetic.
7. A stray empty file `/x` was created in the container's root by a typo of mine; deleting it was blocked by the safety check. It is outside the project and nothing refers to it.

## Verified
- RAN: all `guard/logic` sources and all tests (`guard/logic` test dir: the earlier files plus `PermissionPromptsTest`) compiled with Kotlin 2.0.21 from Gradle 8.14.3 and run with JUnit 4.13.2: 127 tests, 0 failures (121 earlier plus 6 new). The `StatusTextTest` (ui) was not part of this run. Not run through Gradle.
- The manifest and strings.xml were edited on the user's staged copy; I did not re-parse them with an XML parser in this run beyond reading them (edits are small, well-formed by reading).
- Syntax-only look: I fed the changed Android files to the Kotlin compiler without the SDK; it reports only unresolved Android/Compose symbols, no syntax errors in my code. That is NOT a compile.

## NOT verified
- Nothing Android-side was compiled or run. Likeliest compile spots: `LifecycleEventEffect` (lifecycle-runtime-compose 2.8.6 has it, no opt-in expected), `rememberLauncherForActivityResult` inside `GuardSection` before the early return, the `when (p)` with try-expressions as branches, the overload `readOnOff(plug)` vs `readOnOff(plug, pointer)` in `PlugCommander`, `AlertDialog` with a Row in `dismissButton`.
- Everything about behaviour on the phone (see checks below). T12, T13, T18 and the permission prompts and the dialog have not been seen working.

## Phone checks
Use wireless debugging, not USB (USB power counts as plugged in). The computer runs the Go receiver with status reports on, so you can prove checks without opening the app: `./alert-receiver -port 8080 -log alerts.log ...` and the app's "Send status after every check" on, alert address set. After the reboot adb drops; do not rely on logcat, read `alerts.log` on the computer.

### T13: checks resume after a restart (no screen lock)
1. Settings > Security: no screen lock (none/swipe). Open the app once (the restart signal needs the app not to be "stopped"; never force-stop it afterwards).
2. Battery Guard on, both intervals 5 minutes, "Send status after every check" on, address set, "Send test alert" gives 200 OK. Wait for one status line in `alerts.log` (note its time). Optional: "Check now" must give a status line within a few seconds.
3. `adb reboot` (or reconnect and run it). Do NOT unlock, touch or open the app.
4. Expect in `alerts.log`: status lines with times AFTER the reboot, about 5 s after the phone has finished booting and joined Wi-Fi, then one per interval (an idle phone may space them about 9 minutes). The very first one can be missing if Wi-Fi was not up 5 s after boot (a status is never queued); the next one proves it. No duplicate `started` alert lines if an alert was active (the stored alert state survives).
5. Optional proof with adb after reconnecting: `adb logcat -d -s Guard:I | grep boot:` shows `boot: action=android.intent.action.BOOT_COMPLETED enabled=true waiting=...`. Update path (not proven here): after `adb install -r`, the log should show a `boot: action=android.intent.action.MY_PACKAGE_REPLACED` line and a check about 5 s later.
### T18: restart with a screen lock
Set a PIN, same setup, `adb reboot`, do not unlock. Expect NOTHING in `alerts.log` after the reboot: Android keeps the credential-protected storage closed, so neither the boot receiver nor the DataStore works (the document's limit; no Direct Boot workaround is built). Unlock once: BOOT_COMPLETED is delivered then, and the first check follows about 5 s later; status lines resume. Then remove the screen lock again.
### T12: plug mains cut and restored while the plug is off
1. Battery Guard on, intervals 5, status reports on, phone on battery, level inside the band (for example low 50, high 80, level 65). Turn the plug off from the plug screen (or let Battery Guard turn it off at the high threshold: set high at or below the level while plugged in, then low back down).
2. Pull the plug out of the wall for more than 10 s and put it back. Note what state it comes back in (many plugs return ON): the phone may be charging again.
3. Expect `alerts.log` status lines to keep arriving. If it came back ON and the phone charges past high, the next check turns it OFF again. If it came back OFF, nothing happens inside the band (the manual-off rule). A check that falls in the minute while the plug is rejoining Wi-Fi counts one failed check; an alert comes only at a second in a row.
4. Let the phone drain to the low threshold (to speed up, use the phone; the lowest allowed low is 50). Expect at the first check at or below low: `switch on confirmed`, the phone plugged in within 30 s, no alert, whatever state the plug returned in. Log: `adb logcat -v time -s Guard:I` while connected, or the status lines (battery level and plugged in) in `alerts.log`.
### Permission prompts
- First enable on Android 13+: the notification dialog, then the system dialog "Allow ... to always run in the background?". Below Android 13 only the second. Nothing blocks: the switch is already on and a check runs.
- Retest: `adb shell pm revoke app.matterplug android.permission.POST_NOTIFICATIONS` (revokes the permission but the stored "asked" flag stays, so it will not ask again), then to get the questions again: Settings > Apps > Matter Plug > Storage > Clear data (this also removes the pairing, the settings and the flags `asked_notifications` / `asked_exemption` in the guard store) or `adb shell pm clear app.matterplug`. Remove the exemption: Settings > Battery > Matter Plug > Optimized (or Restricted). A second Android denial of the notification permission shows no dialog at all (the system suppresses it).
- Exact-alarm warning: `adb shell cmd appops set app.matterplug SCHEDULE_EXACT_ALARM deny` (may have no effect on Android 13+ where USE_EXACT_ALARM is granted automatically; see G5 notes); return to the app: the red line "Exact alarms are not allowed. Checks may be late." appears while Battery Guard is on.
### Disable dialog
1. Plug off (from the plug screen), Battery Guard on: turn the Battery Guard switch off. The switch is greyed and "Checking the plug…" shows for up to 8 s, then the dialog "The plug is off. Turn it on before disabling?". Turn on: the plug turns on, Battery Guard goes off. Leave off: Battery Guard off, plug stays off. Cancel (or tap outside): Battery Guard and the switch stay on.
2. Plug on: turn Battery Guard off: no dialog, it goes off after the short read.
3. Plug unreachable (mains out): the read times out after 8 s and Battery Guard simply switches off (no dialog; document's "null").
### Other
Check now: with Battery Guard off the button is greyed; on, a press gives a check line within about a second in the log (and a status line at the receiver).
After G7 the real-phone tests T14, T15, T16 remain (overnight, days, charge limit).
