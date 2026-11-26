# G8 notes: optional bearer token for the alert requests (B13)

## Files (all under app/, committed to /home/aliu/work/matter_plug/app/)
New:
- `app/src/main/java/app/matterplug/guard/logic/AlertToken.kt`: pure `authorizationHeader(token): String?` (null for blank, `"Bearer <trimmed>"` otherwise), `resultForCode(code): PostResult` (401/403 -> `error = "rejected"`), `tokenOverPlainHttp(token, address): Boolean` (for the plain-http warning line).
- `app/src/test/java/app/matterplug/guard/logic/AlertTokenTest.kt` (8 tests).
Changed:
- `guard/logic/GuardSettings.kt`: `alertToken: String = ""` after `alertAddress`.
- `guard/logic/DeliveryPlan.kt`: `DeliveryError.REJECTED = "rejected"`, `Parsed.Rejected`, `forFailure` returns `"rejected"` for a rejected result (before the `http:<code>` rule), `parse("rejected")`, `describeTest` maps 401/403 (or error "rejected") to `TestOutcome.Failed(Rejected)`.
- `guard/GuardStore.kt`: key `alert_token` (string), read (trimmed) into the settings and written (trimmed) by `update`. No logging.
- `guard/HttpAlerter.kt`: `post(address, token, body, timeoutMs)`; sets `Authorization` only when `authorizationHeader(token)` is non-null; response goes through `resultForCode`. `postTest`, `deliver` (status and reminders) and `flush`/`flushQueue` read the token from the current settings at send time; the waiting list still holds only bodies. Redirects stay off. No log line prints the token (log lines carry host, code, error token only).
- `guard/ui/GuardViewModel.kt`: `tokenText` state, `setAlertToken(text)` (trim, `store.update`, clears the test result), loaded in `init`; `sendTestAlert` makes sure the typed token is stored before the request reads it (same as the address).
- `guard/ui/GuardSection.kt`: "Alert token" `OutlinedTextField` right under the address note: single line, `PasswordVisualTransformation` unless shown, `KeyboardOptions(keyboardType = KeyboardType.Password)`, text-only Show/Hide `TextButton` as trailing icon (no icon library), the note below it, and, when `tokenOverPlainHttp`, a red warning line. `errorText` maps `Parsed.Rejected` (used by the test result line and the delivery status line).
- `res/values/strings.xml` (applied to the user's current file, which was identical to the copy): `guard_alert_token`, `guard_token_note`, `guard_token_http_warning`, `guard_token_show`, `guard_token_hide`, `guard_err_rejected` ("Rejected: check the token").
- Tests: `SettingsCoercionTest` (token kept through `coerced()`), `GuardSettingsTest` (default empty). No test built GuardSettings positionally, so nothing else needed changing.

## Deviations / decisions
1. 401/403 is `PostResult(code, "rejected")` and `delivered` stays false. `DeliveryError.forFailure` checks `rejected` first, so the stored `last_delivery_error` is `rejected` (not `http:401`) and the status line reads "Last alert failed 14:02 (Rejected: check the token)."
2. A token typed with inner spaces is saved without the leading/trailing ones only; because the field is trimmed on every keystroke, a space cannot be typed at the end (bearer tokens have none). Smallest change; say if the trim should happen on save only.
3. `deliver` and `flush` read the settings once per run, so a token changed mid-run applies from the next run (a run is at most 30 s). The test button always reads the stored value.
4. Changing the token does not itself schedule a flush (the doc says only "the next request uses it"); waiting requests go out with the new token at the next flush job or check.
5. The Show/Hide state is not kept across a restart (the token is hidden again), deliberately.
6. The harness `kc.sh` now has the coroutines jar on its classpath; the receiver binary is built to `$RECV` (default /tmp/alert-receiver-g8) because the outputs folder is not executable.

## Verified
- RAN: all `guard/logic` sources and all `guard/logic` tests compiled with Kotlin 2.0.21 (Gradle 8.14.3's jars) and run with JUnit 4.13.2: 135 tests, 0 failures (127 earlier plus 8 new). Not run through Gradle; `StatusTextTest` (ui) not part of this run.
- RAN: the REAL `HttpAlerter.kt` plus real `guard/logic` against stubs (Log, SystemClock, Context, in-memory GuardStore, FlushJobService) and the real Go receiver built from `alert-receiver/main.go` (started with `-token s3cret`): `G8-receiver-check/RecvCheck.kt`, output `run.txt`, receiver log `recv.log`, `build-and-run.sh`. 25 checks, ALL PASS:
  - (a) right token -> 200 delivered (also with spaces/newline around it, trimmed);
  - (b) wrong token -> code 401, `error = rejected`, not delivered;
  - (c) empty and blank token against `-token` receiver -> 401, rejected;
  - (d) token set, receiver without a token -> 200 delivered (and no token, none needed);
  - (e) no `s3cret`, wrong token or `Bearer` in anything logged through `android.util.Log` (captured by the stub: 6 Guard lines);
  - test button mapping: wrong/empty -> `Failed(Rejected)`, right -> `Ok`;
  - deliver with a wrong token leaves 1 waiting and stores `rejected`; the waiting body has no token; after the token is corrected, `flush` delivers it;
  - a 307 redirect is not followed (the target saw no request; the first server got `Bearer s3cret`); a 403 from any server maps to rejected.
- Receiver log (`recv.log`; the token itself is not in it):
```
listening on 127.0.0.1:18083
test/test  battery=18 ...  "Matter Plug: test alert."          (a)
test/test  battery=18 ...  "Matter Plug: test alert."          (a2)
REJECTED (bad or missing token) from 127.0.0.1:48902           (b)
REJECTED (bad or missing token) ...                            (c)
REJECTED (bad or missing token) ...                            (c2)
REJECTED (bad or missing token) ...                            (postTest wrong token)
REJECTED (bad or missing token) ...                            (postTest empty token)
test/test  battery=64 plugged_in=true ...                      (postTest right token)
REJECTED (bad or missing token) ...                            (deliver, wrong token)
not_charging/started ...                                       (flush after fixing the token)
status/status ...
---- receiver restarted without -token ----
test/test ...  (d)    test/test ...  (d2)
```
  (full file: `G8-receiver-check/recv.log`)

## NOT verified
- The Android code (GuardStore, GuardViewModel, GuardSection, strings, FlushJobService unchanged) was NOT compiled: no Android SDK here. Read carefully by eye only. The riskiest lines for the first Gradle build: the `OutlinedTextField` with `visualTransformation`, `keyboardOptions`, `trailingIcon` and the new imports (`KeyboardOptions`, `KeyboardType`, `PasswordVisualTransformation`, `VisualTransformation`) in `GuardSection.kt`.
- Whether the phone's keyboard really offers no suggestions for the field (Android treats `KeyboardType.Password` as no-suggestions; check on the phone).
- DataStore read/write of `alert_token` (only the in-memory stub ran).

## Phone check (T21), wireless debugging, not USB
Your own service: it requires `Authorization: Bearer <secret>` on `POST /api/phone/battery`. Alert address `https://<your host>/api/phone/battery`, Alert token `<secret>`, press Send test alert: "200 OK" (an https address shows no plain-http warning).

Local test with the Go receiver:
1. On the computer: `./alert-receiver -port 8083 -token s3cret` (allow port 8083 through the firewall).
2. In the app: Alert address `http://<LAN ip>:8083/api/phone/battery`. Leave the token empty and press Send test alert: "Rejected: check the token". The receiver logs `REJECTED`.
3. Enter a wrong token (e.g. `nope`): "Rejected: check the token". With any non-blank token and an http:// address the red line "The token is sent over plain http." shows; that is expected here.
4. Enter `s3cret`: "200 OK"; the receiver logs the test alert. Use Show/Hide to check what you typed.
5. Log check, over wireless debugging: `adb connect <phone ip>:<port>` then `adb logcat -v time -s Guard:I | grep -i s3cret` while pressing the button and running Check now. It must print nothing (while `adb logcat -v time -s Guard:I` alone shows the `test alert host=... code=200` lines).
6. Optional: stop and restart the receiver with a different token; the next check's delivery line shows "(Rejected: check the token)" and the request waits; fix the token and press Check now: delivered.
