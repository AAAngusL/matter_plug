package app.matterplug.cellular.logic

/** Judges the passive layers (section 5): Registration needs SIM, Signal needs Registration; a failure skips what needs it. */
object CellularVerdict {
    private val passiveOrder = listOf(Layer.Sim, Layer.Registration, Layer.Signal)

    fun passive(r: PassiveReading): Map<Layer, LayerResult> {
        val sim = if (r.simReady) LayerResult.Pass(r.simDetail) else LayerResult.Fail(r.simDetail)
        val registration = when {
            sim is LayerResult.Fail -> LayerResult.Skipped(Layer.Sim)
            r.registered -> LayerResult.Pass(r.registrationDetail + if (r.roaming) ", roaming" else ", home")
            else -> LayerResult.Fail(r.registrationDetail)
        }
        val signal = when {
            registration is LayerResult.Skipped -> LayerResult.Skipped(registration.because)
            registration is LayerResult.Fail -> LayerResult.Skipped(Layer.Registration)
            r.signalLevel == null -> LayerResult.Fail("signal unreadable")
            r.signalLevel < 1 -> LayerResult.Fail("no signal bars")
            else -> LayerResult.Pass(r.signalDetail)
        }
        return mapOf(Layer.Sim to sim, Layer.Registration to registration, Layer.Signal to signal)
    }

    /** The first passive layer that failed, in order; null when they all pass. */
    fun firstFailed(results: Map<Layer, LayerResult>): Layer? = passiveOrder.firstOrNull { results[it] is LayerResult.Fail }
}

object SignalDetail {
    /** "LTE, RSRP -97 dBm, RSRQ -11 dB, SINR 8 dB", keeping only the values Android gave; "level 2" when there are none. */
    fun format(kind: String?, rsrp: Int?, rsrq: Int?, sinr: Int?, level: Int? = null): String {
        val values = listOfNotNull(rsrp?.let { "RSRP $it dBm" }, rsrq?.let { "RSRQ $it dB" }, sinr?.let { "SINR $it dB" })
        val parts = listOfNotNull(kind) + values
        return when {
            values.isNotEmpty() -> parts.joinToString(", ")
            level != null -> (listOfNotNull(kind) + "level $level").joinToString(", ")
            else -> kind ?: "unknown"
        }
    }
}

object CellularLog {
    /** One log line for a run, every layer it judged: "cellular: sim=pass (ready) registration=… signal=…". */
    fun line(results: Map<Layer, LayerResult>): String =
        "cellular: " + Layer.entries.mapNotNull { layer -> results[layer]?.let { "${layer.wire}=${text(it)}" } }.joinToString(" ")

    private fun text(r: LayerResult): String = when (r) {
        is LayerResult.Pass -> "pass (${r.detail})"
        is LayerResult.Fail -> "fail (${r.reason})"
        is LayerResult.Skipped -> "skipped (${r.because.wire} failed)"
        LayerResult.NotDue -> "not due"
    }
}
