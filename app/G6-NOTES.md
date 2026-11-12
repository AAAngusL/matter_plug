# G6 notes: HTTP delivery, status reports, flush job, test button, alert settings rules

## Files (all under app/)
New:
- `app/src/main/java/app/matterplug/guard/HttpAlerter.kt` (post, postTest, deliver, flush)
- `app/src/main/java/app/matterplug/guard/FlushJobService.kt` (JobService, id 7001, `schedule(ctx)`, `cancel(ctx)`)
- `app/src/main/java/app/matterplug/guard/logic/DeliveryPlan.kt` (pure: `PostResult`, `DeliveryError`, `TestOutcome`, `DeliveryPlan`)
- `app/src/test/java/app/matterplug/guard/logic/DeliveryPlanTest.kt` (25 tests)

Changed:
- `guard/GuardRunner.kt` (`finish`: the two TODO(G6) replaced by the status event and `HttpAlerter.deliver`)
- `guard/GuardStore.kt` (+ `enqueue`, `dequeueIfHead`)
- `guard/ui/GuardViewModel.kt` (`sendTestAlert`, `testState`, `setAlertAddress` flush scheduling, flush job cancel)
- `guard/ui/GuardSection.kt` (test button enabled, result text, delivery error text)
- `guard/logic/AlertAddress.kt` (+ `hostOf`, for log lines)
- `AndroidManifest.xml` (FlushJobService; staged from your phone-side repo, identical to my G5 copy before the edit; `RECEIVE_BOOT_COMPLETED` was already declared)
- `res/values/strings.xml` (9 strings; staged, identical to my copy before the edit)
Not touched: PlugClient, ChipController, Commissioner, pairing, `ActionService`, the Go receiver.

## Behaviour
- `HttpAlerter.post(address, body, timeoutMs)`: `HttpURLConnection` on `Dispatchers.IO`, POST, `Content-Type: application/json; charset=utf-8`, no auth header, `instanceFollowRedirects = false`, fixed-length body, response body read (max 64 KB) and discarded. Connect timeout = read timeout = `timeoutMs`; a watchdog coroutine also closes the connection after `timeoutMs`, so the whole request is capped (not only each phase). Never throws except coroutine cancellation. Errors become tokens (`no_connection`, `timeout`, `bad_address`, `tls`, `other:<Name>`); a non-2xx response is stored as `http:<code>`. The UI maps tokens to string resources.
- `postTest`: valid address required (else `bad_address`), works with alerts off, posts `AlertEvent(Test, Test, level, plugged)`, touches neither queue, alert state nor the delivery line. Result shown next to the button: "200 OK" for 200, "HTTP <code>" for any other response, or the error text ("no connection", "timed out", "invalid address", ...).
- `deliver(ctx, store, events, status, budgetMs)` (5.6, 5 steps): `statusOn = settings.statusEnabled && status != null`. Alerts on: Started/Recovered bodies are appended to the waiting list (one DataStore edit). Nothing to send (invalid address, or alerts and status both off) -> return. Alerts on: flush oldest first, `dequeueIfHead` after each success, stop at the first failure or when the budget is used, `recordDelivery` after each request. If the list is then empty: reminders (alerts on only) once each, then the status report once (never queued; status needs `statusEnabled` and a valid address, NOT alerts). List not empty: `FlushJobService.schedule`. Per-request timeout = `min(10 s, remaining budget)`, no request starts with less than 200 ms left. Logs show the host only.
- `FlushJobService`: job 7001, `NETWORK_TYPE_ANY`, persisted, default backoff; 30 s budget; steps 1 and 3 (`HttpAlerter.flush`); `jobFinished(params, needsReschedule = list still not empty and alerts on and address valid)`. `schedule` does nothing when job 7001 is already pending or running (scheduling again would cancel a running job).
- `GuardRunner.finish`: status event built when `settings.statusEnabled` (`nextCheckInMin = settings.intervalFor(plugged)`), then `HttpAlerter.deliver(..., budgetMs)`: 5 s from `onAlarm` (B11), 60 s from the service. `nothingToDo` already treats `statusEnabled` as work (test `statusOnMeansWork` in `CheckDecisionTest`), so a quiet check still sends the status report: verified, no change needed.
- Alert settings rules (BATTERY-GUARD.md section 7 table), checked against the code: alerts on needs a valid address (ViewModel); alerts off clears the queue (`GuardStore.update`, already in G2); Battery Guard off clears queue + alert state + `next_check_at` (G2); address change: queue holds finished bodies, so the next flush uses the new address, and the ViewModel schedules the flush job when the new address is valid and the list is not empty; test button independent of the alerts switch; status switch takes effect at the next check and clears nothing; restart: state and queue are in DataStore. No gap found in G2's store.
- Delivery line: `StatusText` (G2) already gives "Last alert delivered HH:MM." / "Last alert failed HH:MM (reason). N waiting." Completed: the stored token is now shown as text (G2 would have shown the raw token).

## Deviations from the document (all small)
1. `PostResult` is in `guard.logic` (DeliveryPlan.kt), not in HttpAlerter.kt, so it is unit-tested. Its `error` is a token, not display text (no user-visible text in Kotlin).
2. Alerts are put on the waiting list even when the address is currently invalid (the document returns before that, which would lose the Started/Recovered of an alert). They are sent when a valid address exists. The list is capped at 100 entries (oldest dropped).
3. `GuardStore.enqueue` only appends while `enabled` and `alertsEnabled` are both true inside the same edit, so a run still in flight cannot refill a list the settings rules just cleared. `dequeueIfHead` removes by comparing the first entry. Both are additions to the store API.
4. If a reminder or the status report fails, the rest of the extras is skipped (the network is probably down; saves the budget). The document says "send each once".
5. Status/reminder outcomes also go through `recordDelivery` ("for every outcome"), so a successful status report moves "Last alert delivered" to its time. Say if you would rather record only failures for status reports.
6. One flush at a time (`Mutex.tryLock`): if the job is flushing, a check skips its own flush instead of waiting.
7. `setAlertAddress` schedules the flush job after the field has been idle for 1.5 s, not on every keystroke (a half-typed `http://192.168.1.2` is already a "valid" address and would receive the waiting alerts).
8. `setEnabled(false)` and `setAlertsEnabled(false)` also cancel the flush job (the list is empty then).
9. `URI(address).toURL()` instead of `URL(String)` (the latter is deprecated on newer JDKs).
10. Not done: "Check now" stays disabled (G7).

## Verified / NOT verified
RAN here (plain `kotlinc` 2.0.21 from Gradle 8.14.3 + JUnit 4.13.2):
- All `guard.logic` sources + `GuardStatus` + `StatusText` + all tests: 128 tests, 0 failures (103 earlier + 25 `DeliveryPlanTest`). Not run through Gradle.
- The REAL `HttpAlerter.kt` compiled against tiny stubs for `android.util.Log`, `SystemClock`, `Context`, an in-memory `GuardStore` and `FlushJobService` (kotlinx-coroutines 1.6.4 jar from Gradle), and run against the real Go receiver (`alert-receiver/main.go` built here): `G6-receiver-check/RecvCheck.kt`, output `run.txt`, receiver log `recv.log` (all in /mnt/user-data/outputs/G6-receiver-check/). 25 checks, all PASS:
  - bodies from `AlertJson.encode` for started, reminder, guard_degraded started, recovered, status (with `next_check_in_min`) and test, each accepted with HTTP 200 and logged by the receiver (e.g. `status/status battery=18 plugged_in=false event_time=...`, `test/test`);
  - `postTest` (200; invalid address -> `bad_address`; queue untouched);
  - `deliver`: started+recovered flushed in order, reminder and status sent after; alerts off + status on sends only the status; alerts on + status off sends no status;
  - receiver stopped: Started and then Recovered stay queued (oldest first), `no_connection` recorded, flush job scheduled; receiver started again: `flush` delivers both with their ORIGINAL event times (13:10:00 and 13:15:00 in the log);
  - a 302 is not followed and not "delivered", 404 not delivered, 204 delivered; a server that accepts and never answers gives `timeout` after 1505 ms for a 1500 ms timeout; with a 2.5 s budget and three queued entries the run stops after 2504 ms with all three still waiting.
NOT compiled, NOT run (no Android SDK): `FlushJobService.kt`, `GuardRunner.kt`, `GuardStore.kt`, `GuardViewModel.kt`, `GuardSection.kt`, manifest, strings. I read them for imports, suspend scopes, smart casts, JobScheduler API (`getPendingJob` API 24, minSdk 28), and Compose. Likeliest compile spots: `GuardSection.kt` (`val (text, isError) = when ... TestState.Idle -> return` inside a @Composable; `errorText` composable called inside a `when` branch) and `FlushJobService.kt` (override signatures with non-null `JobParameters`). If the build complains, send me the error.
Not tested on a phone: everything Android-side. Nothing was seen working on the phone. The Go receiver's `SILENT`/`BACK` was not re-run by me (the document says it was run before).

## The (verify) items
- V5 (persisted job survives a restart, runs with the app closed):
  1. Stop the receiver, set alerts on, cause an alert (or use the unreachable-plug scenario) so one entry is waiting. `adb shell dumpsys jobscheduler | grep -B2 -A12 app.matterplug` should list job 7001 (`NETWORK_TYPE_ANY`, persisted).
  2. Reboot the phone, unlock it once, do NOT open the app. Start the receiver. Within a minute or so the waiting request should arrive (receiver log) with its original time. If it does not, run `adb shell cmd jobscheduler run -f app.matterplug 7001` to prove the job code works and report that the persistence failed.
  3. Note: with the receiver down the job runs at once (any network) and fails; it then retries with backoff (30 s, doubling). That is the document's design (B6); say if you want a minimum delay added.
- V8 (plain http to an IP address): Send test alert to `http://<LAN ip>:8080/battery`. "200 OK" in the app and a `test/test` line in the receiver proves it. A cleartext error would show as an `other:...` or "no connection" text; the network security config already allows cleartext for every host.
- V9 (status request from `CheckReceiver` inside goAsync, 5 s budget): status reports on, Battery Guard on, 5 minute intervals. `adb logcat -v time -s Guard:I`. For each check you should see the G3 line `level=.. plugged=.. action=.. fault=.. interval=.. next=..` followed within about 1 s by `deliver: status/status host=... code=200`. Then make the receiver slow: on the computer `sudo iptables -I INPUT -p tcp --dport 8080 -j DROP` (undo with `-D`); the next check must show `deliver: status/status ... error=timeout` about 5 s after the check line, and no ANR / "Broadcast of Intent ... timed out" in `adb logcat | grep -i -E "anr|timed out"`. Report the time stamps.

## Phone checks (G6 row of section 2; tests T7 to T11, T17, T19)
Setup, once:
1. On the Linux computer: `cd tools/alert-receiver && go build -o alert-receiver . && ./alert-receiver -port 8080 -log alerts.log -notify-cmd "notify-send 'Matter Plug' {message}"`. Add `-silence-min 1` for the status test T19 (silence limit of 1 minute; without it the limit is 3 intervals + 1 minute, at least 31 minutes).
2. Find the computer's address: `ip -4 addr` (the `192.168.x.y` of your Wi-Fi/LAN card). Allow the port in the firewall, for example `sudo ufw allow 8080/tcp` (or your firewall's equivalent), and remove it afterwards.
3. Phone and computer on the same Wi-Fi. Build/install as before (`tools/first-build.sh`, then install). Use wireless debugging, NOT the USB cable (USB power counts as plugged in): Settings > Developer options > Wireless debugging, `adb pair`, `adb connect`. Log: `adb logcat -v time -s Guard:I`.
4. In the app: Alert address `http://<that address>:8080/battery`. Press "Send test alert". Expect "200 OK" next to the button and a `test/test` line in the receiver. A wrong address shows "no connection" or "timed out".

T19 status reports and the silence alarm:
- Turn on "Send status after every check", Battery Guard on, both intervals 5 minutes (an idle phone may space alarms about 9 minutes apart). Expect one `status/status ... ` line in the receiver per check (the message "Matter Plug: status. Battery N%, plugged in.") and in the log `deliver: status/status host=... code=200`. The receiver learns `next_check_in_min` from it.
- Silence: with the receiver started with `-silence-min 1`, switch the phone to flight mode (or Wi-Fi off). After the next missed check plus a minute the receiver logs `SILENT` and runs notify-send, and repeats every 60 minutes. Switch Wi-Fi back on: the next status request makes it log `BACK`.
- Also: with "Send alerts" OFF and status ON the reports must still arrive (statuses do not need alerts).

T7 unreachable plug (needs the phone on the plug at a high level, see G5-NOTES T7): thresholds so that TurnOff is wanted (for example low 50, high 80 at a level of 85), then cut the plug's power / Wi-Fi. "Send alerts" ON. Expected in the receiver: nothing after the first failed check, `plug_unreachable/started` at the second failed check, a `plug_unreachable/reminder` about an hour later (optional), and `plug_unreachable/recovered` after the plug is back and the next switch succeeds. Same for T8 (`not_charging`: phone on battery at or below low, pull the CHARGER CABLE from the phone: `not_charging/started` about 30 s after the confirmed On; `recovered` after the cable is back) and T20 where the fault can be provoked.

T11 receiver stopped, then started (waiting queue):
1. Alerts ON. Stop the receiver (Ctrl-C). Cause an alert as in T7.
2. At the second failed check expect in the app: "Last alert failed HH:MM (no connection). 1 waiting." and in the log `flush: ... error=no_connection` and `flush job scheduled`.
3. Start the receiver again. Within about a minute (the job's backoff) or at the next check the request arrives; its `event_time=` is the ORIGINAL time of the alert, not the time of delivery. The app shows "Last alert delivered HH:MM." with no "waiting".
4. T9 (router off) is the same with the router instead of the receiver; T10 needs a public address (HTTPS service) and mobile data: leave the home Wi-Fi off and use a public URL.

T17 alert settings rules:
- Switch "Send alerts" OFF during an active alert: no reminder and no `recovered` arrives afterwards; the waiting count disappears ("N waiting" gone); the phone still shows notifications.
- Change the address while an entry is waiting (receiver A stopped, start receiver B on another port, e.g. `-port 8081`, and put `http://<address>:8081/battery` in the field): after about 2 s the flush job is scheduled (`flush job scheduled` in the log) and the entry arrives at B with its original time; it is not sent again.
- Press "Send test alert" with "Send alerts" OFF: a `test/test` line arrives, nothing else changes (alert state, waiting count).
- Restart the phone (wireless debugging reconnect afterwards) during an active alert: after unlock, no duplicate `started`; reminders keep the hourly spacing (G7 adds the boot receiver; until then the first check after you open the app continues the chain).
- Switch Battery Guard OFF during an active alert: no `recovered`, no reminders, waiting count empty.
Receiver down plus "Send status" on: status failures show "Last alert failed ... (no connection)" in the status area but nothing is queued for them.

Remember: allow port 8080 in the firewall (and remove the rule when you are done).

## Things to report back to the main session (memory candidates)
- The user runs the Go receiver on a Linux computer on the LAN (port 8080) for G6 phone tests; wireless debugging is used for phone tests, not USB.
