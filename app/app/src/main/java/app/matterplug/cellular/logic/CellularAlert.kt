package app.matterplug.cellular.logic

import app.matterplug.guard.logic.AlertMessages
import app.matterplug.guard.logic.AlertJson
import app.matterplug.guard.logic.AlertPhase
import java.time.ZoneId

/** The cellular_down alert while it is on: when it started and when a request about it was last sent. */
data class CellularAlert(val startedAt: Long, val lastSentAt: Long)

/**
 * The cellular_down alert (docs/CELLULAR-CHECK.md section 8). It follows the plan's "down" (two failed loopbacks in a
 * row): started when it turns on, a reminder every 60 minutes while it stays on, recovered when a loopback passes.
 * Kept apart from Battery Guard's alert state, because Cellular Check runs with Battery Guard off.
 */
object CellularAlertPolicy {
    const val REMINDER_MS = 60L * 60 * 1000

    fun step(active: CellularAlert?, down: Boolean, now: Long): Pair<CellularAlert?, AlertPhase?> = when {
        active == null && down -> CellularAlert(now, now) to AlertPhase.Started
        active == null -> null to null
        !down -> null to AlertPhase.Recovered
        now - active.lastSentAt >= REMINDER_MS -> active.copy(lastSentAt = now) to AlertPhase.Reminder
        else -> active to null
    }
}

/** `<startedAt>,<lastSentAt>`, or empty for no alert. */
object CellularAlertCodec {
    fun encode(a: CellularAlert?): String = a?.let { "${it.startedAt},${it.lastSentAt}" } ?: ""

    fun decode(text: String?): CellularAlert? {
        val f = text?.split(',') ?: return null
        if (f.size != 2) return null
        val start = f[0].toLongOrNull() ?: return null
        val last = f[1].toLongOrNull() ?: return null
        return CellularAlert(start, last)
    }
}

/** What an alert or a status report says about the phone's cellular service: the `cellular` object of section 8. */
data class CellularSummary(
    val trigger: LoopbackTrigger?,
    val failedLayer: Layer?,
    val reason: String?,
    val layers: Map<Layer, String>,          // pass, fail, skipped or not_due
    val smsReason: String?,                  // the SMS layer's failure, without its trigger: "not sent: radio off"
    val signalDetail: String,
    val reading: SignalReading? = null,      // the LTE signal as numbers (docs/SIGNAL-STRENGTH.md); null with no LTE cell
)

object CellularReport {
    private val passive = listOf(Layer.Sim, Layer.Registration, Layer.Signal)
    private val triggerPrefix = Regex("^[a-z]+: ")

    fun summary(results: Map<Layer, LayerResult>, trigger: LoopbackTrigger?, reading: SignalReading? = null): CellularSummary {
        val sms = (results[Layer.Sms] as? LayerResult.Fail)?.reason?.replace(triggerPrefix, "")
        val failedPassive = CellularVerdict.firstFailed(results)
        val failed = failedPassive ?: if (sms != null) Layer.Sms else null
        val reason = when (failed) {
            null -> null
            Layer.Sms -> sms
            else -> (results[failed] as LayerResult.Fail).reason
        }
        return CellularSummary(
            trigger = trigger,
            failedLayer = failed,
            reason = reason,
            layers = Layer.entries.associateWith { status(results[it]) },
            smsReason = sms,
            signalDetail = (results[Layer.Signal] as? LayerResult.Pass)?.detail ?: "none",
            reading = reading,
        )
    }

    /**
     * The `cellular` object; trigger, failed_layer, reason, sms_reason and signal_reading only when known. [zone] gives
     * signal_reading's read_at its UTC offset.
     */
    fun json(s: CellularSummary, zone: ZoneId = ZoneId.systemDefault()): String {
        val parts = listOfNotNull(
            s.trigger?.let { "\"trigger\":${AlertJson.q(it.wire)}" },
            s.failedLayer?.let { "\"failed_layer\":${AlertJson.q(it.wire)}" },
            s.reason?.let { "\"reason\":${AlertJson.q(it)}" },
        ) + Layer.entries.map { "\"${it.wire}\":${AlertJson.q(s.layers[it] ?: "not_due")}" } + listOfNotNull(
            s.smsReason?.let { "\"sms_reason\":${AlertJson.q(it)}" },
            "\"signal_detail\":${AlertJson.q(s.signalDetail)}",
            s.reading?.let { "\"signal_reading\":${readingJson(it, zone)}" },
        )
        return parts.joinToString(",", "{", "}")
    }

    /** The numbers Android gave, each left out when it gave none; rat is always "lte" in this version. */
    private fun readingJson(r: SignalReading, zone: ZoneId): String = listOfNotNull(
        "\"rat\":\"lte\"",
        r.level?.let { "\"level\":$it" },
        r.rsrpDbm?.let { "\"rsrp_dbm\":$it" },
        r.rsrqDb?.let { "\"rsrq_db\":$it" },
        r.sinrDb?.let { "\"sinr_db\":$it" },
        "\"read_at\":${AlertJson.q(AlertJson.isoTime(r.at, zone))}",
    ).joinToString(",", "{", "}")

    /** The started text; AlertMessages turns it into the reminder. A passive fault says which layer; an SMS-only fault says the signal is fine. */
    fun downText(s: CellularSummary, battery: Int): String {
        val tail = ". Battery $battery%."
        return if (s.failedLayer in passive) AlertMessages.fit("Matter Plug: SMS unavailable: ", passivePhrase(s), tail)
        else AlertMessages.fit("Matter Plug: SMS not working, signal fine: ", smsWords(s.smsReason), tail)
    }

    /** "not sent: radio off" reads "not sent, radio off"; a sent test that did not come back reads "sent, not received back". */
    private fun smsWords(reason: String?): String = when {
        reason == null -> "not tested"
        reason.startsWith("not sent: ") -> "not sent, " + reason.removePrefix("not sent: ")
        reason.startsWith("sent") -> "sent, not received back"
        else -> reason
    }

    /**
     * The cellular_status message: every layer's result in words, "not tested" for an SMS test not yet run. With no reading at all it
     * says so in one phrase: four "not tested" would pass 100 characters.
     */
    fun statusText(s: CellularSummary?, battery: Int): String {
        if (s == null) return "Matter Plug: cellular: not tested yet. Battery $battery%."
        val word = { l: Layer -> (s.layers[l] ?: "not_due").let { if (it == "not_due") "not tested" else it } }
        return "Matter Plug: cellular: SIM ${word(Layer.Sim)}, registered ${word(Layer.Registration)}, " +
            "signal ${word(Layer.Signal)}, SMS ${word(Layer.Sms)}. Battery $battery%."
    }

    fun recoveredText(battery: Int): String = "Matter Plug: SMS works again. Battery $battery%."

    private fun passivePhrase(s: CellularSummary): String =
        if (s.failedLayer == Layer.Registration) "not registered (${s.reason})" else s.reason ?: "unknown"

    private fun status(r: LayerResult?): String = when (r) {
        is LayerResult.Pass -> "pass"
        is LayerResult.Fail -> "fail"
        is LayerResult.Skipped -> "skipped"
        LayerResult.NotDue, null -> "not_due"
    }
}
