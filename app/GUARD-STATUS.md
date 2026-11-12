# Battery Guard: status after G7 (2026-11-11)

All seven milestones are written. None of the Android code has been compiled or run: there is no Android SDK here. The only things that ran are the pure-logic unit tests (127 tests, Kotlin 2.0.21 from Gradle 8.14.3 with JUnit 4.13.2, not through Gradle) and, in G6, the real `HttpAlerter.kt` against stubs and the real Go receiver. The G1 to G7 notes record no result from a phone for any milestone; if you ran any check, tell me and I will update this file.

## Milestones

| # | Built | Verified on a phone (per the notes) | Still unverified |
|---|---|---|---|
| G1 | `guard.logic`: rule, alert policy, messages, JSON, address check, codecs; tests | Nothing | Gradle `:app:testDebugUnitTest` (logic tests ran with plain kotlinc) |
| G2 | `GuardStore`, manifest and resources, settings section, status lines, `StatusText`; PlugScreen edit given as a patch (you applied it by hand) | Nothing recorded | Compile; settings survive restart; sliders and address refusal; lint on both exact-alarm permissions; network security config |
| G3 | `BatteryReader`, `CheckScheduler`, `CheckReceiver`, `GuardRunner.onAlarm`, log line per check | Nothing recorded | Log line at each interval; alarm survives swipe-away; Doze spacing |
| G4 | `ActionService`, `PlugCommander.switchTo`, `GuardRunner.work` | Nothing recorded | T3, T4, T5, T6; V2, V3, V4 |
| G5 | Failed and faulty-check counts, 30 s power wait, `AlertPolicy` wired in, `GuardNotifier` | Nothing recorded | T7, T8, T20 with notifications |
| G6 | `HttpAlerter`, status reports, `FlushJobService`, test button, waiting list, alert settings rules, Go receiver | Nothing recorded on a phone. Logic and HTTP layer ran here against the real receiver (25 checks) | T7 to T11, T17, T19 on the phone; V5, V8, V9 |
| G7 | `BootReceiver`, `GuardPermissions`, permission prompts, Check now, exact-alarm warning wired, disable dialog with `PlugCommander.readOnOff` | Nothing | Compile; T12, T13, T18; prompts; dialog (steps in G7-NOTES.md) |

After G7: T14 (overnight on battery), T15 (days), T16 (charge limit) on the real gateway phone.

## The (verify) items of BATTERY-GUARD-IMPLEMENTATION.md section 10

| # | Item | Status according to the notes |
|---|---|---|
| V1 | Both exact-alarm permissions build with lint at targetSdk 34; granted by default on Android 12, 12L, 13 | Not verified; G2 lists the lint question as open, G5 says `USE_EXACT_ALARM` cannot be revoked on 13 so the fault may not be provokable |
| V2 | `connectedDevice` service from the alarm receiver on Android 13 and 14, app closed, screen off | Not verified; steps in G4-NOTES |
| V3 | Second `ChipController.connect` while the plug screen is open, and its release | Not verified; steps in G4-NOTES. Note: G7 adds a second use (`readOnOff` when disabling). `ChipController.connect` also loses a pointer that arrives after its own timeout (ChipController is off limits) |
| V4 | `TlvReader.getBool(AnonymousTag)` reads OnOff | Not verified; confirmed when the log shows `readBack=true/false` (G4-NOTES) |
| V5 | Persisted JobScheduler job survives a restart and runs with the app closed | Not verified; steps in G6-NOTES |
| V6 | Whether the battery-optimisation exemption relaxes the 9-minute alarm spacing | Not verified, no notes; belongs to T14. G7 now asks for the exemption, so T14 can be run |
| V7 | What `EXTRA_PLUGGED` reports at the phone's own charge limit | Not verified in the notes; the design file records one observation on the S20 FE (status 4 at 85%, USB powered). Belongs to T16 |
| V8 | Plain http to an IP address with the base-config setting | Not verified on a phone; steps in G6-NOTES |
| V9 | Status request from `CheckReceiver` inside goAsync with the 5 s budget completes on the S20 FE (Android 13) | Not verified; steps in G6-NOTES (slow-receiver test with iptables) |
| V10 | The receiver's notify commands on Windows (`msg`, PowerShell) | Not verified; the notes do not mention it, and G6 says the Go receiver's SILENT/BACK was not re-run by the G6 author |

## Open follow-ups
1. `BUILD-CHIPTOOL.md` still has the v1.5 numbers (project file; not readable from the build container).
2. `IMPLEMENTATION.md` section 6.7 (`PlugClient`) shows `plug.endpoint.toLong()` in the `ChipAttributePath.newInstance` and `InvokeElement.newInstance` examples (lines 393 and 412 of the copy here); the endpoint must be an `Int`.
3. Found while building, `BATTERY-GUARD-IMPLEMENTATION.md` still says the other thing in these places (each is recorded in the milestone notes, the code follows the notes): 5.4/5.5 `ActionService.start` is `suspend` (G4); 5.5 `onAlarm` emits at most one `Degraded` event per check (G5); 5.6 if a reminder or the status fails, the remaining extras are skipped (G6); 5.9 `setEnabled(true)` stores first and asks for permissions after, and `setEnabled(false)` reads the plug state itself with `PlugCommander.readOnOff` instead of taking it from `PlugViewModel`, and a failed "Turn on" keeps Battery Guard on (G7).
4. G2-PlugScreen.patch: you applied it to your PlugScreen.kt by hand; it has never been compared with the real file from here (files deeper than 7 folders cannot be staged).
