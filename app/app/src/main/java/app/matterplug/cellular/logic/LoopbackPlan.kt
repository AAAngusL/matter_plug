package app.matterplug.cellular.logic

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * What Cellular Check remembers between runs about loopbacks. [down] is the alert: two failed loopbacks in a row.
 * [confirmedFault] is the passive layer whose fault a finished loopback has confirmed (or shown SMS survives); null when
 * no passive fault is open. [startedFault] is the fault a running confirmation is about: it becomes [confirmedFault]
 * only when that loopback finishes, so one that never finishes does not mute the fault.
 */
data class CellularState(
    val confirmedFault: Layer? = null,
    val failedInARow: Int = 0,
    val lastTrigger: LoopbackTrigger? = null,
    val lastLoopbackAt: Long? = null,
    val lastPassAt: Long? = null,
    val inProgress: Boolean = false,
    val startedAt: Long? = null,
    val startedFault: Layer? = null,
) {
    val down: Boolean get() = failedInARow >= 2
}

/** When a loopback SMS is due (docs/CELLULAR-CHECK.md section 6). Pure: the caller stores the state between runs. */
object LoopbackPlan {
    /** After reading the passive layers: a passive fault that ended without an alert is closed (it was only a warning). */
    fun afterPassive(s: CellularState, failedLayer: Layer?): CellularState =
        if (failedLayer == null && !s.down) s.copy(confirmedFault = null) else s

    /**
     * The loopback this run must send, or null. [failedLayer] is the first failed passive layer of this run.
     * [manualRequested] (Run cellular check now) sends one whatever the state, but never while one is still waiting:
     * the caller keeps the request until this returns Manual.
     */
    fun next(
        s: CellularState, failedLayer: Layer?, now: Long, zone: ZoneId, testTime: LocalTime, manualRequested: Boolean = false,
    ): LoopbackTrigger? {
        if (s.inProgress) return null
        if (manualRequested) return LoopbackTrigger.Manual
        if (failedLayer != null) {
            if (s.down) return null                                            // nothing can have recovered yet
            if (s.confirmedFault != failedLayer) return LoopbackTrigger.Confirmation   // a new fault: first of the pair
            return if (s.failedInARow == 1) LoopbackTrigger.Confirmation else null     // second of the pair, or SMS was fine
        }
        if (s.down && s.confirmedFault != null) return LoopbackTrigger.Recovery
        if (s.failedInARow == 1) return s.lastTrigger ?: LoopbackTrigger.Daily      // second of the pair
        return if (dailyDue(s, now, zone, testTime)) LoopbackTrigger.Daily else null
    }

    fun started(s: CellularState, trigger: LoopbackTrigger, failedLayer: Layer?, at: Long): CellularState = s.copy(
        inProgress = true,
        startedAt = at,
        startedFault = if (trigger == LoopbackTrigger.Confirmation) failedLayer else null,
    )

    /** A loopback that could not run (the service was refused, the app failed, or it was cut off): undone, not counted. */
    fun released(s: CellularState): CellularState = s.copy(inProgress = false, startedAt = null, startedFault = null)

    fun finished(s: CellularState, trigger: LoopbackTrigger, passed: Boolean, at: Long): CellularState = s.copy(
        inProgress = false,
        startedAt = null,
        startedFault = null,
        failedInARow = if (passed) 0 else s.failedInARow + 1,
        lastTrigger = trigger,
        lastLoopbackAt = at,
        lastPassAt = if (passed) at else s.lastPassAt,
        // A recovery check closes the passive fault either way: if it failed, the fault is now one only SMS shows.
        confirmedFault = when (trigger) {
            LoopbackTrigger.Recovery -> null
            LoopbackTrigger.Confirmation -> s.startedFault ?: s.confirmedFault
            else -> s.confirmedFault
        },
    )

    /**
     * A loopback takes at most 5 minutes. One still marked as running after [STALE_MS] was cut off (the process was
     * killed): it is released without counting, so a killed process can neither block the plan nor raise the alert.
     */
    fun clearStale(s: CellularState, now: Long): CellularState =
        if (s.inProgress && now - (s.startedAt ?: Long.MIN_VALUE / 2) > STALE_MS) released(s) else s

    private const val STALE_MS = 10 * 60_000L

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
