package app.matterplug.guard.logic

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pure helpers for the check (BATTERY-GUARD-IMPLEMENTATION.md 5.5): the fault text and the one log line per check. */
object CheckLog {
    const val FAULT_BATTERY_UNREADABLE = "battery unreadable"
    const val FAULT_EXACT_ALARMS = "exact alarms not allowed"

    /** The first of: battery unreadable, exact alarms not allowed, or null. */
    fun degradationReason(batteryReadable: Boolean, exactAlarmsAllowed: Boolean): String? = when {
        !batteryReadable -> FAULT_BATTERY_UNREADABLE
        !exactAlarmsAllowed -> FAULT_EXACT_ALARMS
        else -> null
    }

    /** For example: `level=57 plugged=false action=Nothing fault=none interval=5min next=14:05:03`. No personal data. */
    fun line(
        level: Int?, pluggedIn: Boolean?, action: GuardAction, fault: String?, intervalMin: Int, nextAtMs: Long?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val next = if (nextAtMs == null) "none" else TIME.withZone(zone).format(Instant.ofEpochMilli(nextAtMs))
        return "level=${level ?: "unknown"} plugged=${pluggedIn ?: "unknown"} action=$action " +
            "fault=${fault ?: "none"} interval=${intervalMin}min next=$next"
    }

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
}
