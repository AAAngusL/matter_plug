package app.matterplug.cellular.logic

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * What Cellular Check remembers between runs about loopbacks. [down] is the alert: two failed loopbacks in a row.
 * [confirmedFault] is the passive layer whose fault is being (or was) confirmed; null when no passive fault is open.
 */
data class CellularState(
    val confirmedFault: Layer? = null,
    val failedInARow: Int = 0,
    val lastTrigger: LoopbackTrigger? = null,
    val lastLoopbackAt: Long? = null,
    val lastPassAt: Long? = null,
    val inProgress: Boolean = false,
) {
    val down: Boolean get() = failedInARow >= 2
}

/** When a loopback SMS is due (docs/CELLULAR-CHECK.md section 6). Pure: the caller stores the state between runs. */
object LoopbackPlan {
    /** After reading the passive layers: a passive fault that ended without an alert is closed (it was only a warning). */
    fun afterPassive(s: CellularState, failedLayer: Layer?): CellularState =
        if (failedLayer == null && !s.down) s.copy(confirmedFault = null) else s

    /** The loopback this run must send, or null. [failedLayer] is the first failed passive layer of this run. */
    fun next(s: CellularState, failedLayer: Layer?, now: Long, zone: ZoneId, testTime: LocalTime): LoopbackTrigger? {
        if (s.inProgress) return null
        if (failedLayer != null) {
            if (s.down) return null                                            // nothing can have recovered yet
            if (s.confirmedFault != failedLayer) return LoopbackTrigger.Confirmation   // a new fault: first of the pair
            return if (s.failedInARow == 1) LoopbackTrigger.Confirmation else null     // second of the pair, or SMS was fine
        }
        if (s.down && s.confirmedFault != null) return LoopbackTrigger.Recovery
        if (s.failedInARow == 1) return s.lastTrigger ?: LoopbackTrigger.Daily      // second of the pair
        return if (dailyDue(s, now, zone, testTime)) LoopbackTrigger.Daily else null
    }

    fun started(s: CellularState, trigger: LoopbackTrigger, failedLayer: Layer?): CellularState = s.copy(
        inProgress = true,
        confirmedFault = if (trigger == LoopbackTrigger.Confirmation) failedLayer else s.confirmedFault,
    )

    fun finished(s: CellularState, trigger: LoopbackTrigger, passed: Boolean, at: Long): CellularState = s.copy(
        inProgress = false,
        failedInARow = if (passed) 0 else s.failedInARow + 1,
        lastTrigger = trigger,
        lastLoopbackAt = at,
        lastPassAt = if (passed) at else s.lastPassAt,
        // A recovery check closes the passive fault either way: if it failed, the fault is now one only SMS shows.
        confirmedFault = if (trigger == LoopbackTrigger.Recovery) null else s.confirmedFault,
    )

    /**
     * After today's test time: while healthy, when no loopback has passed today; during a fault only SMS shows, when
     * no loopback has run since today's test time.
     */
    private fun dailyDue(s: CellularState, now: Long, zone: ZoneId, testTime: LocalTime): Boolean {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val testAt = today.atTime(testTime).atZone(zone).toInstant().toEpochMilli()
        if (now < testAt) return false
        return if (s.down) (s.lastLoopbackAt ?: Long.MIN_VALUE) < testAt
        else (s.lastPassAt ?: Long.MIN_VALUE) < today.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
