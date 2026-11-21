# Messages: Every Text the App Sends

Status: built 2026-11-20. This replaces the wording in `BATTERY-GUARD.md`, `CELLULAR-CHECK.md`, `SIGNAL-STRENGTH.md` and `FIXED-IP.md` where they differ. The fields of each request are in `ALERT-API.md`.

## Rules

1. One or two short sentences: what is wrong, then what it means or what to do. Battery last.
2. The cause is said once. Plain words: "IP" for the address, no "closed without a recovery".
3. **At most 100 characters**, at the worst case: `Battery 100%` and two 15-character addresses. A variable part (a fault detail, an Android error name) is cut with `…` to fit (`AlertMessages.fit`). A unit test checks every kind, phase and cause.
4. A reminder is the started text with `Reminder: ` in place of `Matter Plug: `.
5. All `cancelled` texts use one wording and name no alert: the request's `alert` field does.
6. The battery sentence of a Fixed IP text is left out when the battery cannot be read.

## Where each text goes

| Channel | What |
|---|---|
| SMS | Fixed IP's four texts (to the IP alert number and the backup number); Cellular Check's loopback `MPCHK <token>` (to the phone's own number) |
| HTTP to the alert address | Every alert, in the `message` field |
| Phone notification | The same text as the HTTP `message` |

Battery Guard sends no SMS.

## Texts

`<n>` is the battery percent.

| Kind | State | Text |
|---|---|---|
| `not_charging` | started | `Matter Plug: not charging though plug is on. Check charger and cable. Battery <n>%.` |
| `not_charging` | recovered | `Matter Plug: charging again. Battery <n>%.` |
| `plug_unreachable` | started, off | `Matter Plug: cannot reach charger plug to turn it off. Battery <n>%.` |
| `plug_unreachable` | started, on | `Matter Plug: cannot reach charger plug to turn it on. Battery <n>%.` |
| `plug_unreachable` | started, plug read | `Matter Plug: cannot reach charger plug. Battery <n>%.` |
| `plug_unreachable` | recovered | `Matter Plug: charger plug reachable again. Battery <n>%.` |
| `guard_degraded` | started | `Matter Plug: Battery Guard stopped: <detail>. Battery <n>%.` |
| `guard_degraded` | recovered | `Matter Plug: Battery Guard working again. Battery <n>%.` |
| `status` | status | `Matter Plug: status. Battery <n>%, <power>.` |
| `test` | test | `Matter Plug: test alert.` |
| `cellular_down` | started, passive fault | `Matter Plug: SMS unavailable: <reason>. Battery <n>%.` |
| `cellular_down` | started, SMS only | `Matter Plug: SMS not working, signal fine: <result>. Battery <n>%.` (`<result>`: `not sent, <why>` or `sent, not received back`) |
| `cellular_down` | recovered | `Matter Plug: SMS works again. Battery <n>%.` |
| `cellular_status` | status | `Matter Plug: cellular: SIM <s>, registered <s>, signal <s>, SMS <s>. Battery <n>%.`; before any reading `Matter Plug: cellular: not tested yet. Battery <n>%.` |
| `ip_drift` | started, address | `Matter Plug: IP changed from <A> to <B>. Dialer may lose it. Battery <n>%.` |
| `ip_drift` | started, gateway | `Matter Plug: IP gateway changed. IP is <B>. Dialer may not reach it. Battery <n>%.` |
| `ip_drift` | started, no Wi-Fi | `Matter Plug: no Wi-Fi for 1 minute. Dialer cannot reach it. Battery <n>%.` |
| `ip_drift` | recovered | `Matter Plug: IP is back at <A>. Battery <n>%.` |
| any | reminder | The started text with `Reminder: ` in place of `Matter Plug: ` |
| any | cancelled | `Matter Plug: <cause>. Open alert closed; problem may remain. Battery <n>%.` |

`<cause>` by `cancelled_by`: `guard_off` Battery Guard turned off; `alerts_off` Battery Guard alerts turned off; `cellular_off` Cellular Check turned off; `cellular_alerts_off` Cellular alerts turned off; `ip_off` Fixed IP turned off; `ip_unpinned` Fixed IP network unpinned.

## Worst cases (characters)

| Text | Length |
|---|---|
| Fixed IP address changed, two 15-character addresses | 98 |
| Fixed IP reminder, the same | 95 |
| Cancelled, Battery Guard alerts turned off | 98 |
| `cellular_status`, SIM fail and SMS not tested | 98 |
| `cellular_down`, SMS only, `sending limit reached` | 89 |

The SMS limit is 160 characters, so every Fixed IP SMS is one segment.

## What the server must know

Only the `message` text and one `cancelled_by` value (`ip_unpinned`) changed; see `ALERT-API.md` section 0a.
