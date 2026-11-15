package app.matterplug.cellular.logic

import java.time.Instant
import java.time.ZoneId

/** The send result of the loopback SMS: its sent PendingIntent's result, or nothing within 60 s. */
sealed interface SentStatus {
    data object Ok : SentStatus
    /** An SmsManager result code, with the name of its SmsManager constant when Android has one ("RESULT_NO_DEFAULT_SMS_APP"). */
    data class Error(val code: Int, val name: String? = null) : SentStatus
    data object NoAnswer : SentStatus
}

enum class DeliveryStatus { Delivered, Failed, NoReport }

/** What one loopback saw: the three steps of docs/CELLULAR-CHECK.md section 7, recorded separately. */
data class LoopbackOutcome(val sent: SentStatus, val delivery: DeliveryStatus, val received: Boolean)

object LoopbackJudge {
    const val PREFIX = "MPCHK"

    fun body(token: String): String = "$PREFIX $token"

    fun isOurs(body: String, token: String): Boolean = body.trim() == body(token)

    /** Sent and received back. A missing or failed delivery report does not fail it: the message coming back is the proof. */
    fun passed(o: LoopbackOutcome): Boolean = o.sent == SentStatus.Ok && o.received

    fun result(trigger: LoopbackTrigger, o: LoopbackOutcome): LayerResult {
        val text = "${trigger.wire}: " + when (val s = o.sent) {
            SentStatus.Ok -> "sent, ${deliveryText(o.delivery)}, ${if (o.received) "received back" else "not received back"}"
            is SentStatus.Error -> "not sent: ${errorText(s.code, s.name)}"
            SentStatus.NoAnswer -> "not sent: no answer"
        }
        return if (passed(o)) LayerResult.Pass(text) else LayerResult.Fail(text)
    }

    /**
     * The TP-Status of a delivery report (3GPP TS 23.040): below 0x20 delivered, 0x20 to 0x3F still trying (null: keep
     * waiting), 0x40 and above failed.
     */
    fun deliveryFromStatus(status: Int): DeliveryStatus? = when {
        status < 0x20 -> DeliveryStatus.Delivered
        status < 0x40 -> null
        else -> DeliveryStatus.Failed
    }

    private fun deliveryText(d: DeliveryStatus) = when (d) {
        DeliveryStatus.Delivered -> "delivered"
        DeliveryStatus.Failed -> "delivery failed"
        DeliveryStatus.NoReport -> "no delivery report"
    }

    /** The common SmsManager codes in plain words; any other by Android's constant name, or by its number. */
    private fun errorText(code: Int, name: String?) = when (code) {
        1 -> "generic failure"
        2 -> "radio off"
        3 -> "no message data"
        4 -> "no service"
        5 -> "sending limit reached"
        else -> name?.let { it.removePrefix("RESULT_").removePrefix("ERROR_").lowercase().replace('_', ' ') + " (code $code)" }
            ?: "error code $code"
    }
}

/** Stores the loopback state as `key=value` pairs separated by `;`. Anything unreadable gives the default state. */
object CellularStateCodec {
    fun encode(s: CellularState): String = listOf(
        "fault=${s.confirmedFault?.wire ?: ""}",
        "failed=${s.failedInARow}",
        "trigger=${s.lastTrigger?.wire ?: ""}",
        "last=${s.lastLoopbackAt ?: ""}",
        "pass=${s.lastPassAt ?: ""}",
        "running=${if (s.inProgress) 1 else 0}",
        "started=${s.startedAt ?: ""}",
        "sfault=${s.startedFault?.wire ?: ""}",
    ).joinToString(";")

    fun decode(text: String?): CellularState {
        if (text.isNullOrEmpty()) return CellularState()
        return try {
            val m = text.split(';').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            if (!m.containsKey("failed")) return CellularState()
            CellularState(
                confirmedFault = Layer.entries.firstOrNull { it.wire == m["fault"] },
                failedInARow = m["failed"]!!.toInt().coerceAtLeast(0),
                lastTrigger = LoopbackTrigger.entries.firstOrNull { it.wire == m["trigger"] },
                lastLoopbackAt = m["last"]?.toLongOrNull(),
                lastPassAt = m["pass"]?.toLongOrNull(),
                inProgress = m["running"] == "1",
                startedAt = m["started"]?.toLongOrNull(),
                startedFault = Layer.entries.firstOrNull { it.wire == m["sfault"] },
            )
        } catch (e: NumberFormatException) {
            CellularState()
        }
    }
}

/** When test SMS were sent, kept for 7 days, so the screen can show how many went out (section 10). */
object SentLog {
    private const val WEEK_MS = 7 * 24 * 60 * 60_000L

    fun add(times: List<Long>, at: Long): List<Long> = (times + at).filter { at - it < WEEK_MS }

    /** (today, last 7 days). */
    fun counts(times: List<Long>, now: Long, zone: ZoneId): Pair<Int, Int> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        return times.count { it >= today } to times.count { now - it < WEEK_MS }
    }

    fun encode(times: List<Long>): String = times.joinToString(",")
    fun decode(text: String?): List<Long> = text?.split(',')?.mapNotNull { it.toLongOrNull() } ?: emptyList()
}
