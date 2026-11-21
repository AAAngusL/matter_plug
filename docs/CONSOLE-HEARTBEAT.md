# Console Heartbeat: Design

Status: draft 2. H1 built on 2026-11-21 (logic, store, hook, switch, picker); the unit tests pass. T8 verified on the SM-G780F (2026-11-20): with the console unreachable (wrong port), the 1st missed check sent nothing, the 2nd sent one start SMS to both numbers; a wrong path (HTTP 404) counted as a miss too; with the right address the next check sent one recovery SMS and the count went to 0. The user confirmed that both numbers received the start and the recovery SMS. T6 and T7 were not run on the phone; the user accepted them as passed on 2026-11-21 (not observed by the build log). It reuses Battery Guard's delivery code (`HttpAlerter`, `GuardStore`) and Fixed IP's SMS sender (`IpSms`) and numbers.

Changes in draft 2, from the grilling review of draft 1 (12 decisions, all in section 5):

- **One failure counts per check**, not per attempt, so a retry job cannot reach the limit in minutes (section 2).
- **The limit is a setting:** 2, 3 or 5 missed checks, default 2.
- **A check that makes no push changes nothing** (section 3).
- **The counter clears only on switch-on and on a delivered push**; it survives a restart and an interval change.
- **The SMS says "autodialer"**, not "alert console".

## 1. Goal

The app already pushes to the **alert address** (the autodialer console) at every **Check every** interval. Today the phone cannot tell whether anything is listening: a failed push waits in the list and the only sign is the "Last alert delivered" line on the screen.

The goal: the app detects that the autodialer console has stopped answering and says so by SMS, with the smallest possible change. This is idea 1 of three that were weighed (section 7).

## 2. How it works

1. **The status push is the heartbeat.** With **Send status after every check** on, one `status` request goes out at every interval (`ALERT-API.md`). Nothing new is sent.
2. **A counter of missed checks** (`missedChecks`) is kept in `GuardStore`. A **check** here is one run of the alarm chain's delivery (the call of `HttpAlerter.deliver` that the check makes). The **first push that call makes** decides the check:
   - delivered (2xx): the counter goes to 0;
   - failed (no connection, timeout, non-2xx): the counter goes up by 1, unless the phone is the cause (section 3);
   - no push made: nothing changes.
3. **The retry job and the waiting-list retries do not count.** Only the push made by a check does, so "2 missed checks" means 2 intervals.
4. **At the limit** (2, 3 or 5 missed checks; section 5) the app sends one SMS to the IP alert number and the backup number, and remembers that an autodialer alert is open (`consoleDown`).
5. **At the next delivered push** while `consoleDown` is set, the app sends one recovery SMS and clears it. One delivered push is enough.
6. No reminder SMS, as in Fixed IP: one SMS when it starts, one when it ends.

## 3. Which checks count

The question is "is the autodialer console broken", not "is the phone offline".

| What the check saw | Effect on the counter | Why |
|---|---|---|
| Push delivered (2xx) | Reset to 0; recovery SMS if `consoleDown` | The console answered |
| Non-2xx reply (for example 500) | +1 | The console is up but not working |
| Connection refused, host unreachable, timeout | +1 | The console or its network is down |
| Fixed IP reads `NoWifi`, or another network than the pinned one | None | The phone cannot reach any LAN address; Fixed IP already alerts about this |
| No push made (status switch off, nothing to send) | None | Nothing proved the console up or down |
| Test button | None | A manual action, not a check |

When Fixed IP is off, the app cannot tell a Wi-Fi drop from a dead console and counts the failure. This is accepted; the SMS says "has not answered", which is true either way.

## 4. The SMS

At most 100 characters, as in `MESSAGES.md`. Sent by `IpSms` to both numbers in parallel, over the mobile network, so it works when the console's LAN is down.

- Start: `Matter Plug: the autodialer has not answered 2 checks. Check it.` (the number follows the setting)
- Recovery: `Matter Plug: the autodialer answers again.`

## 5. Decisions (all confirmed in the review)

| # | Decision | Answer |
|---|---|---|
| 1 | What counts as one failure | At most one per check: the first push of the check decides (section 2) |
| 2 | How long before the SMS | A setting, **Missed checks before the SMS**: 2, 3 or 5 |
| 3 | Whose numbers | The Fixed IP numbers; the switch cannot turn on without a valid IP alert number |
| 4 | The switch | Own switch **Alert me if the autodialer console stops answering**, off by default, in the Battery Guard tab under the alert address. The screen says it needs "Send status after every check". It turns nothing else on |
| 5 | Fixed IP off, Wi-Fi drops | Accepted: counted as a missed check; the SMS wording stays true |
| 6 | Default of the setting | 2 (30 minutes at the 15-minute interval) |
| 7 | Checks that make no push | Change nothing (section 3) |
| 8 | When the counter clears | Only when the switch is turned on, and on a delivered push. It survives an app restart and an interval change |
| 9 | Delivered pushes to end the alert | One |
| 10 | Test button | Does not count and does not end the alert |
| 11 | "Send alerts" off, status on | The heartbeat still works; it depends only on its own switch and the status switch |
| 12 | Wording | Section 4; switch text above; picker text "Missed checks before the SMS" |

Also decided: switching the heartbeat off during an alert clears `consoleDown` with no SMS and no request to the server (the console is the thing that is down), and the heartbeat never sends anything to the server.

## 6. Limits

- It detects only while pushes are attempted. With **Send status after every check** off and no alerts, no push is made, so nothing is learned. The switch depends on the status switch.
- A console that answers 2xx but does nothing with the request is not detected. Reading a field in the reply body would cover it and is left for later.
- Android may stretch the interval when the phone is idle (about one exact alarm per 9 minutes), so the missed checks can take longer than the number of minutes suggests.
- At the 60-minute interval the default of 2 means 2 hours before the SMS.

## 7. Other ideas considered

- **Age of the last good delivery:** at each check, alarm if the last success is older than 2 intervals and the waiting list is not empty. No counter, but a console that is down with nothing queued goes unnoticed.
- **A separate ping at every check:** one tiny request to the console, read the reply. Detects a dead console on quiet days without the status switch, but needs a new request, path and answer from the console.

## 8. Build order and tests

Build in one step (H1): the counter and `consoleDown` in `GuardStore`, the hook in the delivery call, the SMS, the switch, the picker and their strings. Pure logic goes in a `heartbeat/logic/` package and is unit-tested on the JVM, as for Fixed IP.

| # | Test |
|---|---|
| T1 | 1 miss then a success resets to 0; the limit sends once; one more miss sends nothing |
| T2 | The limit follows the setting (2, 3, 5) |
| T3 | `NoWifi` or another network: counter unchanged |
| T4 | A check that makes no push: counter unchanged |
| T5 | A success while `consoleDown` sends one recovery and clears it |
| T6 | Switch off during an alert clears it, no SMS; switch on clears the counter |
| T7 | A retry-job failure and a test-button result do not move the counter |
| T8 | On the phone: stop the console, wait the set number of checks, expect one SMS to both numbers; start it, expect one recovery SMS |
