# Battery Guard: Programming Instructions

Status: draft 2, 2026-11-11. Draft 2 follows `BATTERY-GUARD.md` draft 7: status reports, the silence alarm, the third alert, a Go receiver. Companion to `BATTERY-GUARD.md` (what and why, draft 7), `IMPLEMENTATION.md` (the existing app) and `CONTEXT.md` (terms). This file says what to program, in what order, and how to tell each step is done. Nothing here is built yet.

Existing app: package `app.matterplug`, `minSdk 28`, `targetSdk 34`, Kotlin, Jetpack Compose, DataStore, no new libraries needed. Sections marked **(verify)** are from memory of the Android or Matter APIs: confirm against the source or on the phone before relying on them, and report what you found.

## 1. Decisions this document follows

### Settled by `BATTERY-GUARD.md`

The rule, the thresholds, both check intervals, the two alerts and their wording, the hourly reminder, the failed-check count of two, the 30-second power wait, the stored alert state and waiting list, the alert settings rules, and the settings screen. This file does not restate them; where a step depends on one it names the section.

### Implementation choices made here

Change any of these and the section named in the last column changes with it.

| # | Choice | Affects |
|---|---|---|
| B1 | All decision logic (rule, alert policy, message text, JSON, address check, state codecs) lives in a package with **no Android imports**, so it runs in plain unit tests | 5.1, 7 |
| B2 | Alert state and the waiting list are stored as plain text in DataStore, with a hand-written codec; no JSON library, no new dependency | 5.1, 5.2 |
| B3 | Every wake-up goes through an exact alarm, including "Check now", a changed setting and the check after a restart. A foreground service is then started from the alarm's receiver, which Android allows. The app never starts the service from a boot receiver or from the settings screen directly | 5.3, 5.8 |
| B4 | `CheckReceiver` schedules the next alarm **first**, before doing anything else, so a crash cannot end the chain. The service schedules it again at the end, using the new plugged-in state | 5.3, 5.5 |
| B5 | The service re-reads the battery and the settings itself and does not trust anything passed to it | 5.5 |
| B6 | "Delivered late when the network returns" (`BATTERY-GUARD.md` section 7) is done with a `JobScheduler` job that needs any network, scheduled whenever the waiting list is not empty. It also runs on mobile data, which is a harmless superset of "joins a Wi-Fi network" | 5.6 |
| B7 | A high threshold of 100 means "never turn off", as `BATTERY-GUARD.md` section 4 says, so `GuardPolicy` never returns `TurnOff` when `high == 100`. The table in section 4 of that file would otherwise turn the plug off at 100% | 5.1 |
| B8 | The receiver program is a single Go file with no dependencies, which builds to one binary for Linux and Windows (section 6) | 6 |
| B9 | `PlugCommander` does not change `PlugClient`. It repeats the few helper calls it needs, so the working pairing and toggle code stays untouched | 5.4 |
| B11 | Status reports and the third alert are produced and sent from `CheckReceiver`'s own run (inside `goAsync`), with a 5-second budget for network calls, so a check that needs no switch never starts the foreground service. The service is started only when a switch is needed | 5.3, 5.5 |
| B12 | A status report is not queued: it is sent once per check and a failure is only recorded. Alerts keep the queue rules of `BATTERY-GUARD.md` section 7 | 5.6 |
| B10 | Battery Guard acts on the first plug in the repository (`PlugRepository.plugs.first().firstOrNull()`). With no plug, the Battery Guard switch is disabled and says "Pair a plug first" | 5.9 |

## 2. Milestones

Each milestone ends with a check. Do not start a milestone until the previous check passes. G1 to G3 need no plug.

| # | Build | Done when |
|---|---|---|
| G1 | Section 5.1 in full, with the unit tests in section 7 | `:app:testDebugUnitTest` passes, every case in section 7 is present |
| G2 | `GuardStore` (5.2), manifest and resource changes (section 3), and the settings section with the Battery Guard switch, sliders, drop-downs, alert fields and the status lines (5.9). The buttons are present but do nothing yet | Values survive an app restart; a slider cannot produce an invalid pair; an invalid address is refused |
| G3 | `BatteryReader`, `CheckScheduler`, `CheckReceiver`, `GuardRunner.onAlarm` (5.3), logging the decision only (tag `Guard`). No service yet | With the app closed and the screen off, `adb logcat -s Guard` shows a line at each interval for the right state, with level, plugged-in state, decision and the next check time |
| G4 | `ActionService`, `PlugCommander`, `GuardRunner.work` without alerts (5.4, 5.5) | With thresholds set close to the current level, the plug switches on and off at the right levels with the app closed and the screen off; the plug screen follows if it is open |
| G5 | The failed-check count, the faulty-check count, the 30-second power wait, `AlertPolicy` wired in, `AlertNotifier`, notifications on the phone (5.5, 5.7) | Section 6 of `BATTERY-GUARD.md` behaves as written with the plug unpowered, with the charger cable pulled, and with exact alarms revoked (the third alert); notifications appear; no HTTP yet |
| G6 | `HttpAlerter`, status reports, `FlushJobService`, the test button, the waiting list, the alert settings rules, and the Go receiver with its silence alarm (5.6, section 6) | Each alert arrives once at the receiver, repeats hourly, is followed by a recovered request, a request that failed arrives later with its original time, status requests arrive every interval, and the silence alarm fires and clears |
| G7 | `BootReceiver`, the permission prompts, "Check now", the exact-alarm warning (5.8, 5.9) | After restarting the phone, checks resume without opening the app |

To test without waiting for the battery, set the thresholds close to the current level (for example low 60, high 70) and both intervals to 5 minutes, as `BATTERY-GUARD.md` section 12 says. Remember Android spaces alarms on an idle phone at least about 9 minutes apart.

## 3. Changes to existing files

### 3.1 `AndroidManifest.xml`

Add inside `<manifest>`:

```xml
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

(verify) `USE_EXACT_ALARM` exists only from Android 13 and is ignored below it; `SCHEDULE_EXACT_ALARM` covers Android 12 and 12L. Lint may complain about declaring both: suppress it with a comment explaining why. `INTERNET`, `ACCESS_NETWORK_STATE`, `BLUETOOTH_CONNECT` and `CHANGE_WIFI_MULTICAST_STATE` are already declared; the last two satisfy the prerequisite of the `connectedDevice` service type.

Add on `<application>`: `android:networkSecurityConfig="@xml/network_security_config"`.

Add inside `<application>`:

```xml
<receiver android:name=".checks.CheckReceiver" android:exported="false" />
<receiver android:name=".checks.BootReceiver" android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
        <action android:name="android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" />
    </intent-filter>
</receiver>
<service android:name=".checks.ActionService" android:exported="false"
    android:foregroundServiceType="connectedDevice" />
<service android:name=".alerts.FlushJobService" android:exported="false"
    android:permission="android.permission.BIND_JOB_SERVICE" />
```

### 3.2 New resource files

- `res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- The alert address is typed by the user at run time, so the host cannot be listed here.
         Plain http is allowed for every host; use https outside the home network. -->
    <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```

- `res/values/strings.xml`: add every user-visible string used in section 5.9 and 5.7 (labels, status lines, notification titles). No hard-coded text in Kotlin except log messages and the alert `message` texts, which are fixed wire text (5.1).

### 3.3 `PlugScreen.kt`

Add one call to `GuardSection(...)` below the existing toggle and above "Rename / Remove plug" (5.9). Do not change anything else in the file. The existing screen, `PlugViewModel` and `PlugClient` must behave exactly as before when Battery Guard is off.

## 4. Package layout

New code is a sub-tree. Only `PlugCommander` goes in the existing `matter` package.

```
app/matterplug/
  battery/                      BatteryReader, BatteryMath, Power (no Android imports except BatteryReader)
  alerts/                       the alert pipeline, shared by every feature
    logic/                      <- no Android imports (B1)
      AlertEvent.kt             (AlertKind, AlertPhase, AlertEvent, AlertExtra)
      AlertMessages.kt, AlertJson.kt (encode + ISO time), AlertAddress.kt, AlertToken.kt
      DeliveryPlan.kt, QueueCodec.kt, CancelledBy.kt, SwitchOff.kt, NotificationIds.kt
    AlertOutbox.kt              (the waiting list, address, token and delivery line, in the "guard" DataStore file)
    AlertChannel.kt, Sender.kt, HttpAlerter.kt, AlertNotifier.kt, FlushJobService.kt
  guard/                        Battery Guard only
    logic/
      GuardSettings.kt
      GuardPolicy.kt            (GuardAction, GuardPolicy)
      AlertTypes.kt             (ActiveAlert, AlertState, CheckEvent)
      AlertPolicy.kt, GuardSwitchOff.kt, CheckDecision.kt
      Codecs.kt                 (AlertState text codec)
    GuardStore.kt               (DataStore)
    CheckScheduler.kt, CheckSchedule.kt
    GuardRunner.kt              (the check and the work, 5.5)
    PlugStep.kt
    GuardPermissions.kt         (exemption, notification and exact-alarm helpers)
    ui/
      GuardSection.kt
      GuardViewModel.kt
  checks/                       what runs at each check, above every feature
    CheckChain.kt, CheckReceiver.kt, CellularReceiver.kt, BootReceiver.kt, ActionService.kt, GuardSwitch.kt
  matter/
    PlugCommander.kt            (new)
tools/alert-receiver/           (main.go, go.mod; section 6)
app/src/test/java/app/matterplug/guard/logic/   (section 7)
```

## 5. Component instructions

### 5.1 The logic package (`guard.logic`)

Write this first. Every class here is a plain Kotlin class or object with no `android.*` import; that is what makes G1 testable on the computer.

**`GuardSettings.kt`**

```kotlin
package app.matterplug.guard.logic

data class GuardSettings(
    val enabled: Boolean = false,
    val low: Int = 20,
    val high: Int = 85,
    val chargingIntervalMin: Int = 15,
    val batteryIntervalMin: Int = 15,
    val alertsEnabled: Boolean = false,
    val statusEnabled: Boolean = false,        // status report after every check
    val alertAddress: String = "",
) {
    fun intervalFor(pluggedIn: Boolean): Int = if (pluggedIn) chargingIntervalMin else batteryIntervalMin

    companion object {
        val INTERVALS = listOf(5, 10, 15, 30, 60)
        const val MIN_GAP = 10
        fun lowRange(high: Int): IntRange = 5..minOf(50, high - MIN_GAP)
        fun highRange(low: Int): IntRange = maxOf(50, low + MIN_GAP)..100
    }
}
```

The sliders use `lowRange(current high)` and `highRange(current low)`, so an invalid pair cannot be produced (G2 check). `GuardStore` also coerces what it reads, so a damaged value falls back to the default.

**`GuardPolicy.kt`**

```kotlin
enum class GuardAction { TurnOn, TurnOff, Nothing }

object GuardPolicy {
    fun decide(level: Int, pluggedIn: Boolean, s: GuardSettings): GuardAction = when {
        level <= s.low && !pluggedIn -> GuardAction.TurnOn
        s.high < 100 && level >= s.high && pluggedIn -> GuardAction.TurnOff   // B7
        else -> GuardAction.Nothing
    }
}

object BatteryMath {
    /** Null when the values are unusable. */
    fun percent(level: Int, scale: Int): Int? =
        if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)

    /** EXTRA_PLUGGED is a bit mask (AC 1, USB 2, wireless 4, dock 8): any bit means plugged in. */
    fun isPluggedIn(extraPlugged: Int): Boolean = extraPlugged != 0
}
```

**`AlertTypes.kt`**

```kotlin
enum class AlertKind(val wire: String) {
    NotCharging("not_charging"), PlugUnreachable("plug_unreachable"), GuardDegraded("guard_degraded"),
    Status("status"), Test("test")
}
enum class AlertPhase(val wire: String) {
    Started("started"), Reminder("reminder"), Recovered("recovered"), Status("status"), Test("test")
}

/** wantedOn: for PlugUnreachable, true if the switch that failed was On (battery low), false if Off (battery high); null otherwise.
 *  detail: for GuardDegraded, the reason ("exact alarms not allowed", "battery unreadable", "service could not start"); null otherwise. */
data class ActiveAlert(
    val kind: AlertKind, val startedAtMs: Long, val lastSentAtMs: Long,
    val wantedOn: Boolean?, val detail: String? = null,
)

data class AlertState(
    val failedChecks: Int = 0,          // consecutive checks where a needed switch failed
    val degradedChecks: Int = 0,        // consecutive checks that found a fault of Battery Guard itself
    val active: List<ActiveAlert> = emptyList(),
)

/** What one check observed, in order. A switch On that is confirmed is followed by a PowerWait. */
sealed interface CheckEvent {
    data object NoSwitchNeeded : CheckEvent
    data class SwitchConfirmed(val on: Boolean) : CheckEvent
    data class SwitchFailed(val wantedOn: Boolean) : CheckEvent      // not reached, or not confirmed
    data class PowerWait(val pluggedIn: Boolean) : CheckEvent         // result of the 30-second wait
    data class Degraded(val reason: String) : CheckEvent              // a fault of Battery Guard itself
}

data class AlertEvent(
    val kind: AlertKind, val phase: AlertPhase,
    val battery: Int, val pluggedIn: Boolean, val wantedOn: Boolean?, val atMs: Long,
    val detail: String? = null,
    val nextCheckInMin: Int? = null,    // status reports only
)
```

`AlertPolicy` produces only the first three kinds. Status and test events are built by the caller (5.5, 5.9) and never pass through `AlertPolicy`.

**`AlertPolicy.kt`** (the rules of `BATTERY-GUARD.md` section 7 "When an alert is sent" and "How often")

```kotlin
data class AlertResult(val state: AlertState, val events: List<AlertEvent>)

object AlertPolicy {
    const val REMINDER_MS = 60L * 60 * 1000
    const val FAILS_BEFORE_ALERT = 2          // the same count for the faulty-check alert

    fun apply(
        state: AlertState, checkEvents: List<CheckEvent>,
        battery: Int, pluggedIn: Boolean, nowMs: Long,
    ): AlertResult {
        var failed = state.failedChecks
        var degraded = state.degradedChecks
        var sawDegraded = false
        val active = state.active.toMutableList()
        val out = mutableListOf<AlertEvent>()

        fun isActive(k: AlertKind) = active.any { it.kind == k }
        fun start(k: AlertKind, wantedOn: Boolean?, detail: String? = null) {
            active += ActiveAlert(k, nowMs, nowMs, wantedOn, detail)
            out += AlertEvent(k, AlertPhase.Started, battery, pluggedIn, wantedOn, nowMs, detail)
        }
        fun recover(k: AlertKind) {
            val a = active.firstOrNull { it.kind == k } ?: return
            active.remove(a)
            out += AlertEvent(k, AlertPhase.Recovered, battery, pluggedIn, a.wantedOn, nowMs, a.detail)
        }

        for (e in checkEvents) when (e) {
            CheckEvent.NoSwitchNeeded, is CheckEvent.SwitchConfirmed -> { failed = 0; recover(AlertKind.PlugUnreachable) }
            is CheckEvent.SwitchFailed -> {
                failed += 1
                if (failed >= FAILS_BEFORE_ALERT && !isActive(AlertKind.PlugUnreachable)) start(AlertKind.PlugUnreachable, e.wantedOn)
            }
            is CheckEvent.PowerWait -> if (!e.pluggedIn && !isActive(AlertKind.NotCharging)) start(AlertKind.NotCharging, null)
            is CheckEvent.Degraded -> {
                sawDegraded = true
                degraded += 1
                if (degraded >= FAILS_BEFORE_ALERT && !isActive(AlertKind.GuardDegraded)) start(AlertKind.GuardDegraded, null, e.reason)
            }
        }
        if (!sawDegraded) { degraded = 0; recover(AlertKind.GuardDegraded) }
        if (pluggedIn) recover(AlertKind.NotCharging)

        // Reminders: only for alerts that were not started or recovered in this call (a start sets lastSentAtMs = now).
        for (i in active.indices) {
            val a = active[i]
            if (nowMs - a.lastSentAtMs >= REMINDER_MS) {
                active[i] = a.copy(lastSentAtMs = nowMs)
                out += AlertEvent(a.kind, AlertPhase.Reminder, battery, pluggedIn, a.wantedOn, nowMs, a.detail)
            }
        }
        return AlertResult(AlertState(failed, degraded, active), out)
    }
}
```

`AlertPolicy` produces events whether or not alerts are switched on in the settings. The caller decides what to do with them (5.5): notifications always, HTTP only when "Send alerts" is on. A call whose events contain no `Degraded` counts as a healthy check, so a call with an empty event list resets and recovers the third alert; callers always pass at least one event.

**`AlertMessages.kt`** (fixed wire text, `BATTERY-GUARD.md` section 7 "Message text")

```kotlin
object AlertMessages {
    fun text(e: AlertEvent): String = when (e.phase) {
        AlertPhase.Recovered -> "Matter Plug: recovered. Battery ${e.battery}%, " + (if (e.pluggedIn) "charging." else "not charging.")
        AlertPhase.Test -> "Matter Plug: test alert."
        AlertPhase.Status -> "Matter Plug: status. Battery ${e.battery}%, " + (if (e.pluggedIn) "plugged in." else "on battery.")
        AlertPhase.Started -> body(e)
        AlertPhase.Reminder -> body(e).replaceFirst("Matter Plug: ", "Matter Plug (reminder): ")
    }
    private fun body(e: AlertEvent): String = when (e.kind) {
        AlertKind.NotCharging -> "Matter Plug: battery ${e.battery}%, not charging. The plug is on. Check the charger and cable."
        AlertKind.PlugUnreachable ->
            if (e.wantedOn == false) "Matter Plug: battery ${e.battery}%. The charger plug cannot be reached to turn it off."
            else "Matter Plug: battery ${e.battery}%, not charging. The charger plug cannot be reached."
        AlertKind.GuardDegraded -> "Matter Plug: Battery Guard is not working: ${e.detail ?: "unknown fault"}. Battery ${e.battery}%."
        AlertKind.Status, AlertKind.Test -> "Matter Plug: test alert."   // not reached: Status and Test use their phase's text above
    }
}
```

**`AlertJson.kt`**

```kotlin
object AlertJson {
    /** ISO 8601 with the UTC offset and whole seconds, e.g. 2026-11-11T09:47:12-05:00. */
    fun isoTime(atMs: Long, zone: java.time.ZoneId): String =
        java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
            .format(java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(atMs), zone).withNano(0))

    /** Keys in this order: alert, state, message, battery, charging, time, and next_check_in_min only when it is not null (status reports). */
    fun encode(e: AlertEvent, message: String, time: String): String =
        """{"alert":${q(e.kind.wire)},"state":${q(e.phase.wire)},"message":${q(message)},""" +
        """"battery":${e.battery},"charging":${e.pluggedIn},"time":${q(time)}""" +
        (e.nextCheckInMin?.let { ""","next_check_in_min":$it""" } ?: "") + "}"

    /** JSON string with quotes, escaping backslash, quote and control characters. */
    fun q(s: String): String { /* escape \" \\ \n \r \t, other chars below 0x20 as \u00XX */ }
}
```

`charging` carries the plugged-in state. `java.time` is available from API 26; `minSdk` is 28.

**`AlertAddress.kt`**

```kotlin
object AlertAddress {
    fun isValid(text: String): Boolean {
        val t = text.trim()
        if (!(t.startsWith("http://") || t.startsWith("https://"))) return false
        return try { !java.net.URI(t).host.isNullOrBlank() } catch (e: Exception) { false }
    }
}
```

**`Codecs.kt`** (B2: plain text, no JSON)

- `AlertStateCodec.encode(AlertState): String` and `decode(String?): AlertState`. Format: first line `F,<failedChecks>,<degradedChecks>`, then one line per active alert `A,<kindWire>,<startedAtMs>,<lastSentAtMs>,<1|0|->,<detail>` (`-` in the fourth field means null; the detail is Base64 URL-safe without padding, or `-` for null). `decode` also accepts an `F` line with only the first count (degraded = 0) and an `A` line without the detail field. `decode` returns the default state for null, empty or any malformed text, and skips a malformed line without failing.
- `QueueCodec.encode(List<String>): String` and `decode(String?): List<String>`. Each body is Base64 (`java.util.Base64`, URL-safe, no padding), entries joined by `\n`. The list holds the finished JSON bodies of `started` and `recovered` requests, oldest first.

### 5.2 `GuardStore.kt`

A class like `PlugRepository`, with its own DataStore file named `guard` (`preferencesDataStore(name = "guard")`). Nothing is stored in `plugs`.

| Key | Type | Content |
|---|---|---|
| `enabled`, `low`, `high`, `charging_interval`, `battery_interval`, `alerts_enabled`, `status_enabled`, `alert_address` | Boolean, Int, String | The settings, defaults as in `GuardSettings` |
| `alert_state` | String | `AlertStateCodec` text |
| `queue` | String | `QueueCodec` text |
| `last_check_at`, `last_level`, `last_plugged`, `next_check_at` | Long, Int, Boolean, Long | Status line |
| `last_switch_at`, `last_switch_on`, `last_switch_level` | Long, Boolean, Int | Status line |
| `last_delivery_ok_at`, `last_delivery_fail_at`, `last_delivery_error` | Long, Long, String | Delivery line |
| `last_run_error` | String | Shown in the status line when a check could not run (service refused to start, battery unreadable) |

API:

```kotlin
class GuardStore(context: Context) {
    val settings: Flow<GuardSettings>
    val status: Flow<GuardStatus>               // everything under "Status line" and "Delivery line", plus queue size
    suspend fun readSettings(): GuardSettings
    suspend fun update(transform: (GuardSettings) -> GuardSettings)
    suspend fun readAlertState(): AlertState;  suspend fun writeAlertState(s: AlertState)
    suspend fun readQueue(): List<String>;     suspend fun writeQueue(q: List<String>)
    suspend fun recordCheck(atMs: Long, level: Int?, pluggedIn: Boolean?, nextAtMs: Long?)
    suspend fun recordSwitch(atMs: Long, on: Boolean, level: Int)
    suspend fun recordDelivery(okAtMs: Long?, failAtMs: Long?, error: String?)
    suspend fun recordRunError(text: String?)
}
```

Behaviour tied to the alert settings rules (`BATTERY-GUARD.md` section 7):

| Call | Extra effect |
|---|---|
| `update` that turns `alertsEnabled` from true to false | Clear `queue` in the same edit |
| `update` that turns `enabled` from true to false | Clear `queue`, reset `alert_state` to the default (no recovered request is produced; this also clears the failed-check and faulty-check counts), and clear `next_check_at` |
| `update` that changes `alertAddress` | Nothing extra; the queue holds finished bodies, so the next flush goes to the new address |

Do these inside one `DataStore.edit` so a partial state is never stored. The `GuardViewModel` (5.9) is responsible for the follow-up actions (cancel or schedule the alarm, schedule the flush job).

### 5.3 Reading and scheduling

**`BatteryReader.kt`**

```kotlin
data class BatteryReading(val level: Int, val pluggedIn: Boolean)

object BatteryReader {
    fun read(context: Context): BatteryReading? {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val pct = BatteryMath.percent(i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)) ?: return null
        return BatteryReading(pct, BatteryMath.isPluggedIn(i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)))
    }
}
```

Passing `null` as the receiver returns the sticky value without registering anything, so nothing needs unregistering. One call gives level and plugged-in state together.

**`CheckScheduler.kt`**

```kotlin
object CheckScheduler {
    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(context).canScheduleExactAlarms()

    /** Returns the wall-clock time the check will run at, for the status line. Replaces any earlier alarm. */
    fun scheduleIn(context: Context, delayMs: Long): Long {
        val am = alarmManager(context)
        val at = SystemClock.elapsedRealtime() + delayMs
        if (canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(context))
        else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pending(context))   // inexact fallback; the status line warns
        return System.currentTimeMillis() + delayMs
    }
    fun cancel(context: Context) { alarmManager(context).cancel(pending(context)) }

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 1, Intent(context, CheckReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
```

There is exactly one alarm: the same `PendingIntent` replaces the previous one, so "Check now" and a changed setting simply move the next check. The interval after a check is `settings.intervalFor(pluggedIn) * 60_000L`; "now" is `1_000L` (B3).

**`CheckReceiver.kt`**

```kotlin
class CheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { GuardRunner.onAlarm(context.applicationContext) } finally { pending.finish() }
        }
    }
}
```

`GuardRunner.onAlarm` must finish well inside the receiver's time limit: it never touches `ChipController` or any class in `matter`, and its network calls (5.5, `finish`) have a total budget of 5 seconds. Anything longer is left to the flush job (5.6).

### 5.4 `PlugCommander.kt` (package `matter`)

One switch, then release everything. It does not subscribe, and it is not tied to a screen (B9).

```kotlin
sealed interface SwitchResult {
    data object Confirmed : SwitchResult
    data class NotReached(val reason: String) : SwitchResult      // connect or command failed or timed out
    data object Unconfirmed : SwitchResult                        // command accepted, read-back differs
}

class PlugCommander(context: Context) {
    suspend fun switchTo(plug: Plug, on: Boolean): SwitchResult
}
```

Steps:

1. `ChipController.connect(context, plug.nodeId)` inside `withTimeout(25_000)`. Failure or timeout returns `NotReached`.
2. Send On (`0x01`) or Off (`0x00`) on cluster `0x0006`, endpoint `plug.endpoint`, with the same `InvokeElement.newInstance(endpoint, cluster, command, emptyStructureTlv, null)` and `controller.invoke(...)` call that `PlugClient.sendCommand` uses, inside `withTimeout(10_000)`. Failure returns `NotReached`. Never use Toggle.
3. Read `OnOff` (cluster `0x0006`, attribute `0x0000`) with `controller.readPath(...)` as `PlugClient.readFabricIndex` does, decoding with `TlvReader(tlv).getBool(AnonymousTag)`, inside `withTimeout(10_000)`. If the value equals `on`, return `Confirmed`; if it differs or cannot be read, return `Unconfirmed`.
4. In a `finally`: `ChipController.release(pointer)` for every pointer obtained.

Copy the helper code and constants from `PlugClient` into private members; do not edit `PlugClient`. Total worst case is about 45 seconds, inside the service limit in 5.5.

(verify) A second `connect` to the same node while `PlugScreen` already holds a session: it should return a separate pointer, and releasing it must not disturb the screen's subscription. Test this in G4 by switching with the plug screen open. (verify) After a timed-out `connect`, a pointer that arrives late is released: if `ChipController.connect` does not do that itself, add it.

### 5.5 The check and the work (`GuardRunner.kt`)

Three functions. `onAlarm` and `work` share one `Mutex` so only one run happens at a time.

**`degradationReason(ctx, reading)`** returns the first of: `"battery unreadable"` (reading is null), `"exact alarms not allowed"` (`!CheckScheduler.canScheduleExact(ctx)`), or null. `"service could not start"` is added by the caller (below).

**`onAlarm(ctx)`** (from `CheckReceiver`, inside `goAsync`)

```
settings = store.readSettings();  if (!settings.enabled) return
reading  = BatteryReader.read(ctx)
interval = settings.intervalFor(reading?.pluggedIn == true)        // unknown state: use the on-battery interval
nextAt   = CheckScheduler.scheduleIn(ctx, interval * 60_000L)      // B4: first
store.recordCheck(now, reading?.level, reading?.pluggedIn, nextAt)
fault    = degradationReason(ctx, reading)
action   = if (reading == null) Nothing else GuardPolicy.decide(reading.level, reading.pluggedIn, settings)
log("Guard", "level=.. plugged=.. action=.. fault=.. next=..")
if (action != Nothing) {                                           // G3: skip this block until G4
    if (ActionService.start(ctx)) return                           // the service does the rest, including 'finish'
    fault = "service could not start"
}
events = (if (action == Nothing && reading != null) [NoSwitchNeeded] else []) + (fault?.let { [Degraded(it)] } ?: [])
if (nothingToDo(settings, store, events)) return
finish(ctx, settings, reading, events, budgetMs = 5_000)           // B11
```

`nothingToDo` is true when `events` is exactly `[NoSwitchNeeded]` and the stored alert state has no failed or faulty checks and no active alerts, the waiting list is empty, and `settings.statusEnabled` is false. That is the common case: a check that finds the battery inside the band ends here with no service, no Matter and no network (rule 1 of `BATTERY-GUARD.md` section 5).

**`work(ctx)`** (from `ActionService`, inside `withTimeout(120_000)`)

```
settings = store.readSettings();  if (!settings.enabled) return
reading  = BatteryReader.read(ctx)                                               // B5
if (reading == null) { finish(ctx, settings, null, [Degraded("battery unreadable")], 60_000); reschedule; return }
action   = GuardPolicy.decide(reading.level, reading.pluggedIn, settings)
events   = mutableListOf<CheckEvent>()
when (action) {
  Nothing  -> events += NoSwitchNeeded
  TurnOn, TurnOff -> {
      plug = PlugRepository(ctx).plugs.first().firstOrNull()
      result = if (plug == null) NotReached("no plug") else PlugCommander(ctx).switchTo(plug, action == TurnOn)
      if (result is Confirmed) {
          store.recordSwitch(now, action == TurnOn, reading.level)
          events += SwitchConfirmed(action == TurnOn)
          if (action == TurnOn) events += PowerWait(waitForPower(ctx, 30_000))    // poll BatteryReader every 2 s
      } else events += SwitchFailed(action == TurnOn)
  }
}
degradationReason(ctx, reading)?.let { events += Degraded(it) }
final = BatteryReader.read(ctx) ?: reading
finish(ctx, settings, final, events, budgetMs = 60_000)
nextAt = CheckScheduler.scheduleIn(ctx, settings.intervalFor(final.pluggedIn) * 60_000L)
store.recordCheck(now, final.level, final.pluggedIn, nextAt)
```

**`finish(ctx, settings, reading, events, budgetMs)`** (shared)

```
level/plugged = reading, or the last stored values when reading is null (plugged defaults to false)
result  = AlertPolicy.apply(store.readAlertState(), events, level, plugged, now)
store.writeAlertState(result.state)
AlertNotifier.post(ctx, result.events)                                           // always
status  = if (settings.statusEnabled) AlertEvent(Status, Status, level, plugged, null, now, nextCheckInMin = settings.intervalFor(plugged)) else null
HttpAlerter.deliver(ctx, store, result.events, status, budgetMs)                 // 5.6
```

Notes:

- `waitForPower` returns true as soon as `BatteryReader.read` shows `pluggedIn`, false after 30 seconds.
- The failed-check and faulty-check counts live in `AlertState`, so they survive restarts. `NoSwitchNeeded` and `SwitchConfirmed` reset the first; a check with no `Degraded` event resets the second.
- A count of 1 produces no event and no notification; the next check tries again.
- Wrap each step so an exception is logged, written with `recordRunError`, and does not skip the `scheduleIn`.

**`ActionService.kt`**

- `companion fun start(ctx): Boolean` calls `ContextCompat.startForegroundService` and returns true. Catch `ForegroundServiceStartNotAllowedException` and `IllegalStateException`: log, `recordRunError("service could not start")`, return false.
- `onStartCommand`: build the "switching" notification (channel `guard_work`, importance low, no sound), call `startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)` immediately, then launch `GuardRunner.work` in a coroutine; in `finally` call `stopSelf()`. If a run is already going, ignore the new start.
- The work takes a partial wake lock only while it runs (`PowerManager.PARTIAL_WAKE_LOCK`, tag `matterplug:guard`, released in `finally`, with a timeout of 130 seconds as a backstop). No wake lock is held anywhere else (rule 2 of `BATTERY-GUARD.md` section 5). `onAlarm` takes no wake lock: the alarm's `goAsync` window covers its 5 seconds.

### 5.6 Delivery (`HttpAlerter.kt`, `FlushJobService.kt`)

```kotlin
data class PostResult(val code: Int?, val error: String?) { val delivered get() = code in 200..299 }

object HttpAlerter {
    /** Plain HttpURLConnection. POST, Content-Type: application/json; charset=utf-8, connect and read timeout min(10 s, what is left of the budget),
     *  instanceFollowRedirects = false. Reads and discards the response body. Never throws: errors go into PostResult. */
    suspend fun post(address: String, body: String, timeoutMs: Int = 10_000): PostResult

    /** Used by the test button: posts an AlertEvent(Test, Test, ...) and returns the result. Works whether or not alerts are on. */
    suspend fun postTest(ctx: Context, store: GuardStore, level: Int, pluggedIn: Boolean): PostResult

    suspend fun deliver(ctx: Context, store: GuardStore, events: List<AlertEvent>, status: AlertEvent?, budgetMs: Long)
}
```

`deliver` (the budget is the total time all network calls in one run may take; calls that cannot start inside it are skipped):

1. Read the settings. If the address is invalid, return. `alertsOn = settings.alertsEnabled`; `statusOn = status != null`. If neither alerts nor a status report can be sent and the queue is empty, return.
2. If `alertsOn`: build each event's body with `AlertJson.encode(e, AlertMessages.text(e), AlertJson.isoTime(e.atMs, ZoneId.systemDefault()))`; append the bodies of `Started` and `Recovered` events to the queue (reminders are not queued) and save the queue.
3. If `alertsOn`: flush the queue, oldest first; after each delivered request remove it and save; stop at the first failure or when the budget is used. Record the result with `recordDelivery`.
4. If the queue is now empty: send each reminder once, then the status report once (B12). None of these are queued, and a failure only goes to `recordDelivery`.
5. If the queue is not empty, schedule the flush job.

A flush also runs on every `finish`, because the queue is read in step 3 whether or not there are new events; that is how waiting alerts go out "at the next check".

`FlushJobService` (a `JobService`): schedule with `JobScheduler`, job id `7001`, `setRequiredNetworkType(NETWORK_TYPE_ANY)`, `setPersisted(true)`, default backoff. In `onStartJob` run steps 1 and 3 in a coroutine with a 30-second budget, call `jobFinished(params, needsReschedule = queue still not empty)`, and return true. It works with the app closed (B6). (verify) `setPersisted(true)` needs `RECEIVE_BOOT_COMPLETED`, which is declared.

### 5.7 `AlertNotifier.kt`

- Channels, created once: `guard_work` (importance low) and `guard_alerts` (importance high).
- `post(ctx, events)`: for each event, one notification on `guard_alerts` with the `AlertMessages.text` string, id derived from the alert kind so a reminder replaces the earlier one and `Recovered` replaces it with the recovery text. If `POST_NOTIFICATIONS` is not granted on Android 13 and above, do nothing and do not fail.
- Notifications are posted whether or not "Send alerts" is on (`BATTERY-GUARD.md` section 7 settings rules).

### 5.8 `BootReceiver.kt`

On `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` and `SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`: read the settings; if `enabled`, call `CheckScheduler.scheduleIn(ctx, 5_000L)`; if the waiting list is not empty, also schedule the flush job. The check then runs through the normal alarm path (B3). Do all store reads with `goAsync()` and a coroutine, as `CheckReceiver` does.

DataStore lives in credential-protected storage, so before the phone is first unlocked after a reboot this receiver is not called at all. That is the screen-lock limit in `BATTERY-GUARD.md` section 6; do not try to work around it with Direct Boot storage.

### 5.9 Settings UI (`guard/ui`)

`GuardViewModel` exposes `settings`, `status`, the current plug state from `PlugViewModel`, and these actions:

| Action | Does |
|---|---|
| `setEnabled(true)` | If no plug: refuse. Otherwise ask for the permissions below, then `store.update { enabled = true }` and `CheckScheduler.scheduleIn(1_000)` |
| `setEnabled(false)` | If the plug state is `Online(isOn = false)`, first show the dialog "The plug is off. Turn it on before disabling?" with Turn on / Leave off / Cancel (Turn on calls the existing toggle). Then `store.update { enabled = false }` and `CheckScheduler.cancel` |
| `setLow`, `setHigh`, `setChargingInterval`, `setBatteryInterval` | `store.update`; if enabled, `scheduleIn(1_000)` |
| `setAlertsEnabled(on)` | Refuse to turn on with an invalid address; `store.update` (clears the queue when turning off, 5.2) |
| `setStatusEnabled(on)` | Refuse to turn on with an invalid address; `store.update`. Takes effect at the next check |
| `setAlertAddress(text)` | Save the text always; if valid and the queue is not empty, schedule the flush job |
| `sendTestAlert()` | `HttpAlerter.postTest(...)`; shows "200 OK", the code, or the error text next to the button. Needs a valid address only |
| `checkNow()` | `CheckScheduler.scheduleIn(1_000)` |

Permissions at first enable (`GuardPermissions.kt`), each asked once and never blocking the switch:

- Android 13 and above: `POST_NOTIFICATIONS` runtime request.
- `PowerManager.isIgnoringBatteryOptimizations`; if false, start `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with the app's package.
- `CheckScheduler.canScheduleExact`; if false, do not ask, only show the warning.

`GuardSection` (Compose, Material 3) lists the controls in the order of `BATTERY-GUARD.md` section 10: switch, two sliders with `steps` for whole percent and ranges from `GuardSettings.lowRange/highRange`, two drop-downs fed from `GuardSettings.INTERVALS`, "Send alerts" switch, "Send status after every check" switch, address field, "Send test alert" and "Check now" buttons, then the status lines and notes. Build the status texts in a small pure function (`StatusText.kt`) so they can be unit-tested: "Last check 21:40: battery 64%, plugged in. Next check 21:55.", "Last switch: off at 87%, 21:10.", the alert state line (one line per active alert, including the reason for the third alert), the delivery line (including "N waiting" from the queue size), and the warning "Exact alarms are not allowed. Checks may be late." Use the device's 24- or 12-hour setting for times (`DateFormat.getTimeFormat`).

## 6. The receiver program (`tools/alert-receiver/`)

A single Go file (`main.go`) and a `go.mod`, standard library only (B8). It builds to one binary. It has been built for Linux and cross-compiled for Windows, and run on Linux against a status request, an alert, a malformed body, a GET, and the silence alarm and its recovery. It has not been run on Windows.

```
cd tools/alert-receiver
go build -o alert-receiver .                      # Linux
GOOS=windows GOARCH=amd64 go build -o alert-receiver.exe .
./alert-receiver -port 8080 -log alerts.log -notify-cmd "notify-send 'Matter Plug' {message}"
```

What it does:

- Accepts a POST with a JSON object on any path; prints and logs one line per request; answers 200. A body that is not a JSON object gets 400; any other method gets 405.
- Runs `-notify-cmd` for `started` and `reminder` requests, for its own `SILENT` alarm, and once when the phone is back. `{message}` is replaced by the text, quoted for the shell on Linux and macOS. The text is also in the environment variable `ALERT_MESSAGE`, which is the safe way to use it on Windows.
- **Silence alarm** (`BATTERY-GUARD.md` section 7): after a status request has set the next-check time, it expects any request within three intervals (an interval counts as at least 10 minutes) plus one minute. Otherwise it logs `SILENT`, notifies, and repeats every 60 minutes until a request arrives, then logs `BACK` and notifies once. `-silence-min N` sets a fixed limit in minutes instead.

Notification command examples:

- Linux desktop: `-notify-cmd "notify-send 'Matter Plug' {message}"` (needs a desktop session).
- Windows, with `msg` (Windows Pro editions): `-notify-cmd "msg * %ALERT_MESSAGE%"`.
- Windows, any edition: `-notify-cmd "powershell -NoProfile -File notify.ps1"` where `notify.ps1` contains `Add-Type -AssemblyName System.Windows.Forms; [System.Windows.Forms.MessageBox]::Show($env:ALERT_MESSAGE, 'Matter Plug') | Out-Null`.

The two Windows commands are untested. The alert address in the app is `http://<the computer's LAN address>:8080/battery`; allow the port in the computer's firewall. To keep the receiver running, use systemd (Linux) or Task Scheduler (Windows); that is not needed to test.

The complete `main.go`:

```go
// Matter Plug alert receiver.
//
// Usage: alert-receiver [-port 8080] [-bind 0.0.0.0] [-log alerts.log]
//
//	[-notify-cmd "notify-send 'Matter Plug' {message}"] [-silence-min 0]
//
// Accepts a POST with a JSON object on any path, prints and logs it, and answers 200.
// Runs -notify-cmd for "started" and "reminder" alerts, and for its own silence alarm.
// {message} in -notify-cmd is replaced by the text (quoted for the shell on Linux and macOS);
// the text is also in the environment variable ALERT_MESSAGE, which is the safe way on Windows.
//
// Silence alarm: after a "status" request tells it when the next check is due, it expects some request
// within 3 intervals (at least 3 x 10 minutes, plus 1 minute). If none arrives it logs SILENT, runs the
// notify command, and repeats every 60 minutes until a request arrives (then it logs BACK).
// -silence-min N overrides the limit with a fixed N minutes.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"time"
)

type alert struct {
	Alert          string `json:"alert"`
	State          string `json:"state"`
	Message        string `json:"message"`
	Battery        *int   `json:"battery"`
	Charging       *bool  `json:"charging"`
	Time           string `json:"time"`
	NextCheckInMin *int   `json:"next_check_in_min"`
}

var (
	notifyCmd  = flag.String("notify-cmd", "", "command to run for alerts; {message} is replaced")
	logPath    = flag.String("log", "", "append log lines to this file")
	silenceMin = flag.Int("silence-min", 0, "fixed silence limit in minutes (0 = from the status requests)")

	mu          sync.Mutex
	lastSeen    time.Time
	nextMin     int // from the latest status request; 0 until one arrives
	silent      bool
	lastSilence time.Time
)

func logLine(format string, a ...any) {
	line := time.Now().Format("2006-01-02 15:04:05") + "  " + fmt.Sprintf(format, a...)
	fmt.Println(line)
	if *logPath != "" {
		if f, err := os.OpenFile(*logPath, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644); err == nil {
			fmt.Fprintln(f, line)
			f.Close()
		}
	}
}

func shellQuote(s string) string { return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'" }

func notify(message string) {
	if *notifyCmd == "" {
		return
	}
	var cmd *exec.Cmd
	if runtime.GOOS == "windows" {
		text := strings.NewReplacer(`"`, `'`, "%", " percent").Replace(message)
		cmd = exec.Command("cmd", "/C", strings.ReplaceAll(*notifyCmd, "{message}", `"`+text+`"`))
	} else {
		cmd = exec.Command("sh", "-c", strings.ReplaceAll(*notifyCmd, "{message}", shellQuote(message)))
	}
	cmd.Env = append(os.Environ(), "ALERT_MESSAGE="+message)
	if err := cmd.Start(); err != nil {
		logLine("notify command failed: %v", err)
		return
	}
	go cmd.Wait()
}

func limit() time.Duration {
	if *silenceMin > 0 {
		return time.Duration(*silenceMin) * time.Minute
	}
	if nextMin == 0 {
		return 0 // no status seen yet: no silence alarm
	}
	iv := nextMin
	if iv < 10 {
		iv = 10 // an idle phone spaces alarms at least about 9 minutes apart
	}
	return time.Duration(3*iv+1) * time.Minute
}

func handle(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, `{"ok":false,"error":"POST only"}`, http.StatusMethodNotAllowed)
		return
	}
	body, err := io.ReadAll(io.LimitReader(r.Body, 64<<10))
	var a alert
	if err == nil {
		err = json.Unmarshal(body, &a)
	}
	if err != nil {
		logLine("BAD REQUEST from %s: %v", r.RemoteAddr, err)
		http.Error(w, `{"ok":false}`, http.StatusBadRequest)
		return
	}
	mu.Lock()
	lastSeen = time.Now()
	if a.Alert == "status" && a.NextCheckInMin != nil && *a.NextCheckInMin > 0 {
		nextMin = *a.NextCheckInMin
	}
	back := silent
	silent = false
	mu.Unlock()

	bat, chg := "?", "?"
	if a.Battery != nil {
		bat = fmt.Sprint(*a.Battery)
	}
	if a.Charging != nil {
		chg = fmt.Sprint(*a.Charging)
	}
	logLine("%s/%s  battery=%s plugged_in=%s event_time=%s from=%s  %q", a.Alert, a.State, bat, chg, a.Time, r.RemoteAddr, a.Message)
	if back {
		logLine("BACK: the phone is reporting again")
		notify("Matter Plug: the phone is reporting again.")
	}
	if a.State == "started" || a.State == "reminder" {
		notify(a.Message)
	}
	io.WriteString(w, `{"ok":true}`)
}

func watchSilence() {
	for range time.Tick(30 * time.Second) {
		mu.Lock()
		l := limit()
		quiet := time.Since(lastSeen)
		fire := l > 0 && !lastSeen.IsZero() && quiet >= l && (!silent || time.Since(lastSilence) >= time.Hour)
		if fire {
			silent = true
			lastSilence = time.Now()
		}
		seen := lastSeen
		mu.Unlock()
		if fire {
			msg := fmt.Sprintf("Matter Plug: no report from the phone for %d minutes (last seen %s).", int(quiet.Minutes()), seen.Format("15:04"))
			logLine("SILENT: %s", msg)
			notify(msg)
		}
	}
}

func main() {
	port := flag.Int("port", 8080, "port to listen on")
	bind := flag.String("bind", "0.0.0.0", "address to listen on")
	flag.Parse()
	addr := fmt.Sprintf("%s:%d", *bind, *port)
	logLine("listening on %s", addr)
	go watchSilence()
	http.HandleFunc("/", handle)
	log.Fatal(http.ListenAndServe(addr, nil))
}
```

And `go.mod`:

```
module matterplug/alert-receiver

go 1.21
```

## 7. Unit tests (G1)

Put them in `app/src/test/java/app/matterplug/guard/logic/`, JUnit 4, no Android. The task is `:app:testDebugUnitTest`.

| File | Cases |
|---|---|
| `GuardPolicyTest` | With low 20, high 85: (20, not plugged) On; (19, not plugged) On; (21, not plugged) Nothing; (20, plugged) Nothing; (0, not plugged) On; (85, plugged) Off; (86, plugged) Off; (84, plugged) Nothing; (85, not plugged) Nothing; (50, either) Nothing. With high 100: (100, plugged) Nothing and (99, plugged) Nothing (B7) |
| `BatteryMathTest` | `percent(57,100)=57`; `percent(1,2)=50`; `percent(5,0)=null`; `percent(-1,100)=null`; `percent(150,100)=100`; `isPluggedIn` false for 0 and true for 1, 2, 3, 4, 8 |
| `GuardSettingsTest` | Defaults (`statusEnabled` false); `lowRange(85)=5..50`; `lowRange(55)=5..45`; `highRange(20)=50..100`; `highRange(45)=55..100`; intervals are exactly 5, 10, 15, 30, 60; `intervalFor(true)` and `intervalFor(false)` pick the right field |
| `AlertAddressTest` | Valid: `http://192.168.1.20:8080/battery`, `https://example.com/x`, ` https://example.com ` (trimmed). Invalid: empty, `ftp://a.b`, `http://`, `http:///path`, `192.168.1.1:8080`, `http://bad host` |
| `AlertMessagesTest` | One test per row of the message table in `BATTERY-GUARD.md` section 7: not charging, unreachable low, unreachable high, Battery Guard not working (each of the three reasons), reminder (starts `Matter Plug (reminder): `), recovered plugged and not plugged, status plugged and on battery, test |
| `AlertJsonTest` | Exact output for the sample in `BATTERY-GUARD.md` section 7 (key order, `false` and numbers unquoted); a status event ends with `"next_check_in_min":15` and no other event has that key; a message containing `"`, `\`, a newline and a tab is escaped; `isoTime` for a fixed instant and zone gives `2026-11-11T09:47:12-05:00`, and whole minutes still print `:00` seconds |
| `CodecsTest` | `AlertStateCodec` round trip with zero, one and two active alerts, each `wantedOn` value, both counts, and a `detail` containing a comma, a quote and a newline; an old-format `F,2` line and an `A` line without a detail field decode; `decode(null)`, `decode("")` and `decode("garbage")` give the default state; a damaged line is skipped. `QueueCodec` round trip with bodies containing quotes and newlines; empty list; `decode(null)` |
| `AlertPolicyTest` | See the scenarios below |

`AlertPolicyTest` scenarios (times in minutes, battery 18 unless stated):

1. One `SwitchFailed(true)`: `failedChecks = 1`, no events. A second: `failedChecks = 2`, one `Started` `PlugUnreachable` with `wantedOn = true`. A third: no event.
2. A reminder: with the alert started at t=0, a failed check at t=59 gives no event; at t=60 gives one `Reminder` and moves `lastSentAtMs` to 60; at t=75 gives none; at t=120 gives another.
3. Recovery by confirmed switch: with the alert active, `SwitchConfirmed(true)` gives one `Recovered`, empty active list, `failedChecks = 0`.
4. Recovery by `NoSwitchNeeded` gives the same.
5. `SwitchFailed`, `NoSwitchNeeded`, `SwitchFailed`: `failedChecks` ends at 1 and no alert starts.
6. `SwitchConfirmed(true)` then `PowerWait(false)` with `pluggedIn = false`: one `Started` `NotCharging`. The same events on the next check: no second `Started`. At 60 minutes after the start: one `Reminder`.
7. `NotCharging` active, then `NoSwitchNeeded` with `pluggedIn = true`: one `Recovered` (`pluggedIn = true`).
8. `SwitchConfirmed(true)` then `PowerWait(true)` with `pluggedIn = true`: no events.
9. Both alerts active together: each reminds and recovers independently; `PlugUnreachable` recovering does not touch `NotCharging`.
10. `wantedOn = false` is kept on the active alert and appears on its events (the "to turn it off" message).
11. Restart: encode the resulting state with `AlertStateCodec`, decode it, apply the next event: the same events result as without the round trip (no duplicate `Started`, reminder timing kept).
12. Events from `AlertPolicy` are produced regardless of any settings; it has no settings parameter.
13. `Degraded("x")` once: `degradedChecks = 1`, no events. Twice in a row (each call also containing `NoSwitchNeeded`): one `Started` `GuardDegraded` with `detail = "x"`. A third: no event. At 60 minutes after the start: one `Reminder` carrying the detail.
14. With `GuardDegraded` active, a call with `NoSwitchNeeded` and no `Degraded`: one `Recovered`, `degradedChecks = 0`.
15. `Degraded` once, then a call without it, then `Degraded` once: `degradedChecks` ends at 1 and nothing starts.
16. A call with only `[Degraded("battery unreadable")]` leaves `failedChecks` unchanged.
17. `PlugUnreachable`, `NotCharging` and `GuardDegraded` active together: each reminds and recovers independently.

## 8. Checks on the phone

These are the tests of `BATTERY-GUARD.md` section 13 that G3 to G7 make possible. Tell the user which ones you could not run (for example T14, overnight, and T15, several days).

| Milestone | Tests |
|---|---|
| G3 | Log lines at the right interval in each state (part of T14) |
| G4 | T3, T4, T5, T6 (record the real overshoot for each while-charging interval) |
| G5 | T7, T8 and T20 with notifications only |
| G6 | T7, T8 and T20 with the receiver, T9 to T11, T17, T19 |
| G7 | T12, T13, T18 |
| After G7 | T14, T15, T16 on the real gateway phone |

## 9. Rules for whoever writes this

- Follow this file. Where it is wrong or a **(verify)** item turns out different, say so in the hand-over notes and fix the file that contains the claim; do not work around it silently.
- Do not change `PlugClient`, `Commissioner`, `ChipController` or the pairing code. If a change there seems necessary, stop and report why.
- Add no library. Everything needed is already a dependency or part of Android.
- Keep every decision in `guard.logic`. `CheckReceiver`, `ActionService` and the UI only gather inputs, call the logic and act on the result.
- Unit tests before the code that depends on them (G1 first), and run them before moving on.
- Log with tag `Guard` at each decision, one line, no personal data. The alert address is not logged in full: log the host only.
- At the end, hand over a short note: what was built, which milestone checks passed, which **(verify)** items were confirmed or found wrong, and what could not be tested without the phone.

## 10. Verify list

| # | Item | Where |
|---|---|---|
| V1 | Declaring both `USE_EXACT_ALARM` and `SCHEDULE_EXACT_ALARM` (max SDK 32) builds with lint at `targetSdk 34`, and the exact-alarm permission is granted by default on Android 12, 12L and 13 | 3.1, G3 |
| V2 | A `connectedDevice` foreground service started from an exact alarm's receiver is allowed on Android 13 and 14 with the app closed and the screen off | 5.5, G4 |
| V3 | A second `ChipController.connect` to the same node while the plug screen is open, and its release | 5.4, G4 |
| V4 | `TlvReader.getBool(AnonymousTag)` reads the On/Off attribute value as in `PlugClient.onReport` | 5.4 |
| V5 | `JobScheduler` persisted job survives a restart and runs with the app closed | 5.6, G6 |
| V6 | Whether the battery-optimisation exemption changes the spacing of alarms on an idle phone (the 9-minute limit) | T14 |
| V7 | What `EXTRA_PLUGGED` reports at the phone's own charge limit | T16 |
| V8 | Plain `http` to an IP address works with the base-config setting | G6 |
| V9 | A status request made from `CheckReceiver` inside `goAsync`, with the 5-second budget, completes and is not cut off on the S20 FE (Android 13) | 5.5, G6 |
| V10 | The receiver's notify commands on Windows (`msg`, PowerShell) | 6 |
