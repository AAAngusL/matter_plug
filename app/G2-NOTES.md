# G2 notes: GuardStore and the Battery Guard settings section

## Files (all under app/)
New: `app/src/main/java/app/matterplug/guard/GuardStore.kt`, `guard/GuardStatus.kt`, `guard/logic/SettingsCoercion.kt`, `guard/ui/GuardViewModel.kt`, `guard/ui/GuardSection.kt`, `guard/ui/StatusText.kt`, `app/src/main/res/xml/network_security_config.xml`; tests `app/src/test/java/app/matterplug/guard/logic/SettingsCoercionTest.kt` and `guard/ui/StatusTextTest.kt`.
Changed: `AndroidManifest.xml` (7 `<uses-permission>` lines and `android:networkSecurityConfig`), `res/values/strings.xml` (all `guard_*` strings).
Changed but NOT committed to your folder: `plug/PlugScreen.kt`, delivered as `app/G2-PlugScreen.patch` (see deviation 1). Apply with `cd app && patch -p1 < G2-PlugScreen.patch`, or make the four edits by hand (two imports for scrolling, one import for GuardSection, `.verticalScroll(rememberScrollState())` on the inner Column, one `GuardSection(Modifier.padding(top = 24.dp))` call after the `message?.let` block).
No G1 file was edited. No library or gradle file was touched.

## Deviations
1. **PlugScreen.kt could not be fetched.** `device_stage_files` refuses files nested deeper than 7 folders, so `plug/PlugScreen.kt`, `plug/PlugViewModel.kt`, `data/PlugRepository.kt` and `data/Plug.kt` could not be compared with the user's folder. The edit was made on my copy and given as a patch instead of overwriting your file. MainActivity.kt, AndroidManifest.xml, strings.xml, build.gradle.kts and libs.versions.toml were fetched and are identical to my copy.
2. **Placement.** "Rename / Remove plug" is in the top-right menu, not below the toggle. The call sits below the toggle and its message, inside the centred column. That column now scrolls (`verticalScroll`) so the section fits, which means the toggle is at the top instead of vertically centred. This is the one change beyond the single call that the section needs.
3. `GuardStatus` has an extra field `alertState` (the alert state line needs it) and lives in its own file `GuardStatus.kt` (no Android import, so StatusText is testable).
4. `StatusText.lines` returns data (`StatusLine`), not sentences: Kotlin may hold no user-visible text. `GuardSection` turns each line into text from strings.xml. The unit test checks the data, not English wording; the sentences in strings.xml follow section 10.
5. New `guard/logic/SettingsCoercion.kt` (`GuardSettings.coerced()`), used by GuardStore on read and write, so it is unit-tested. Rule: a field out of range gets its default; if high < low + 10 both get defaults; intervals not in 5/10/15/30/60 get 15. The store also coerces what `update` writes.
6. The address text is held in the ViewModel (`addressText`) and saved on every keystroke, so typing is not disturbed by the DataStore round trip. The address error shows when the address is invalid and a switch was refused, or alerts or status are already on.
7. Sliders write when the finger is lifted, not on every move. Drop-downs use `DropdownMenu` + button (no experimental API).
8. "Send test alert" and "Check now" are disabled buttons with TODO comments naming G6 and G7. Exact-alarm warning is wired (StatusText parameter) but always passes "allowed" (TODO G7).
9. `GuardSection()` takes no plug argument: it creates its own ViewModel (default factory). The "Turn it on before disabling?" dialog (G7) will need the plug state; add a parameter then.
10. In the doc, `GuardSection(...)` is a function of the plug screen; the Battery Guard switch is disabled while `PlugRepository.plugs` is empty (B10), and "Pair a plug first" is shown. In practice the plug screen only exists when a plug exists.
11. Strings that begin with a space (`guard_status_next_check`, `guard_delivery_waiting`) are quoted in strings.xml so Android keeps the space.

## Verified, and how
- Compiled with kotlinc 2.0.21 (from Gradle 8.14.3) and ran with JUnit 4.13.2: all G1 files and tests, `GuardStatus.kt`, `StatusText.kt`, `SettingsCoercion.kt` and the two new tests. Result: **88 tests, 0 failures** (75 G1 + 6 SettingsCoercionTest + 7 StatusTextTest). Not run through Gradle.
- Manifest, strings.xml and network_security_config.xml parse as well-formed XML.

## NOT verified
- `GuardStore.kt`, `GuardViewModel.kt`, `GuardSection.kt`, the PlugScreen edit and the manifest were NOT compiled (no Android SDK, Compose or DataStore here). They were read through twice. Likely places for a compile error: `DateFormat.getTimeFormat`, `supportingText` lambda, `viewModel()` default arguments.
- No behaviour on a phone: restart persistence, sliders, address refusal, lint on the two exact-alarm permissions, whether `network_security_config` builds.
- Not checked against the user's PlugScreen.kt (deviation 1).

## Manifest entries still to add (inside `<application>`)
- G3: `<receiver android:name=".guard.CheckReceiver" android:exported="false" />`
- G4: `<service android:name=".guard.ActionService" android:exported="false" android:foregroundServiceType="connectedDevice" />`
- G6: `<service android:name=".guard.FlushJobService" android:exported="false" android:permission="android.permission.BIND_JOB_SERVICE" />`
- G7: `<receiver android:name=".guard.BootReceiver" android:exported="true"><intent-filter>` with actions `android.intent.action.BOOT_COMPLETED`, `android.intent.action.MY_PACKAGE_REPLACED`, `android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`.

## TODOs left in code
GuardViewModel: G3 (schedule/cancel and reschedule on setting change), G7 (permissions, "Turn it on before disabling?", checkNow), G6 (flush job on address change, sendTestAlert). GuardSection: G6/G7 buttons, G7 exact-alarm flag.

## How to check G2 on the phone (G2 row of section 2)
1. Build with tools/first-build.sh, install with adb, open the app with a plug paired: the Battery Guard section is below the toggle (scroll if needed).
2. Set low 30, high 70, charging interval 30, battery interval 5, switch Battery Guard on, type an address, close the app completely (swipe away) and reopen: all values are still there.
3. Drag "Turn on at" up: its top end stops 10 below "Turn off at" (or 50); drag "Turn off at" down: it stops 10 above "Turn on at" (or 50). Try low 45 and high 55: neither slider can cross.
4. The two drop-downs offer 5, 10, 15, 30, 60.
5. Type `192.168.1.5` (no http://) and switch Send alerts on: it stays off and the error shows. Same for Send status. Type `http://192.168.1.20:8080/battery`: both switches can be turned on. Switch Send alerts off and on again: still fine.
6. Remove the plug (menu, Remove plug): the pairing screen shows; the section is not there. (With no plug the Battery Guard switch is disabled with "Pair a plug first"; this is not visible on the plug screen.)
7. "Send test alert" and "Check now" are greyed out; the status lines show "No check yet.", "No active alerts", "No alert sent yet." Nothing runs in the background.
