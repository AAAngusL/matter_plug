package app.matterplug.cellular.logic

/** The layers of Cellular Check, in the order they are judged (docs/CELLULAR-CHECK.md section 4). */
enum class Layer(val wire: String) { Sim("sim"), Registration("registration"), Signal("signal"), Sms("sms") }

sealed interface LayerResult {
    data class Pass(val detail: String) : LayerResult
    data class Fail(val reason: String) : LayerResult
    /** A passive layer it needs failed; [because] is the layer that failed first. */
    data class Skipped(val because: Layer) : LayerResult
    /** No loopback in this run; the last result stays shown. */
    data object NotDue : LayerResult
}

/** Why a loopback SMS was sent. */
enum class LoopbackTrigger(val wire: String) {
    Daily("daily"),                 // every passive layer passes: the once-a-day proof
    Confirmation("confirmation"),   // a passive layer failed: is SMS really down?
    Recovery("recovery"),           // the alert is on and the passive layers pass again: is SMS back?
    Manual("manual"),               // Run cellular check now
}

/**
 * What Android reported, parsed once where it comes in (CellularReader). [registrationDetail] is the radio type ("LTE")
 * when registered, or the reason when not. [signalLevel] is 0..4, or null when it could not be read.
 */
data class PassiveReading(
    val simReady: Boolean,
    val simDetail: String,
    val registered: Boolean,
    val roaming: Boolean,
    val registrationDetail: String,
    val signalLevel: Int?,
    val signalDetail: String,
)
