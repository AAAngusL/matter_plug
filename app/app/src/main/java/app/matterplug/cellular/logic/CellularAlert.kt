package app.matterplug.cellular.logic

import app.matterplug.guard.logic.AlertJson
import app.matterplug.guard.logic.AlertPhase

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
)

object CellularReport {
    private val passive = listOf(Layer.Sim, Layer.Registration, Layer.Signal)
    private val triggerPrefix = Regex("^[a-z]+: ")

    fun summary(results: Map<Layer, LayerResult>, trigger: LoopbackTrigger?): CellularSummary {
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
        )
    }

    /** The `cellular` object; trigger, failed_layer, reason and sms_reason only when known. */
    fun json(s: CellularSummary): String {
        val parts = listOfNotNull(
            s.trigger?.let { "\"trigger\":${AlertJson.q(it.wire)}" },
            s.failedLayer?.let { "\"failed_layer\":${AlertJson.q(it.wire)}" },
            s.reason?.let { "\"reason\":${AlertJson.q(it)}" },
        ) + Layer.entries.map { "\"${it.wire}\":${AlertJson.q(s.layers[it] ?: "not_due")}" } + listOfNotNull(
            s.smsReason?.let { "\"sms_reason\":${AlertJson.q(it)}" },
            "\"signal_detail\":${AlertJson.q(s.signalDetail)}",
        )
        return parts.joinToString(",", "{", "}")
    }

    /** The started text; AlertMessages turns it into the reminder. A passive fault says which layer; an SMS-only fault says the signal is fine. */
    fun downText(s: CellularSummary, battery: Int): String {
        val sms = "the test SMS was ${s.smsReason ?: "not tested"}"
        return if (s.failedLayer in passive) "Matter Plug: SMS service unavailable: ${passivePhrase(s)}; $sms. Battery $battery%."
        else "Matter Plug: SMS is not working although the signal is fine; $sms. Battery $battery%."
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
