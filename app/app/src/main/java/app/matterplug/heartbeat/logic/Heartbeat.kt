package app.matterplug.heartbeat.logic

/** What one check learned about the autodialer console (docs/CONSOLE-HEARTBEAT.md section 3). */
enum class Observation {
    /** The first push of the check was delivered (2xx). */
    Delivered,
    /** The first push failed and the phone was not the cause. */
    Failed,
    /** No push was made, the test button was used, or the phone itself was offline: nothing was proved. */
    None,
}

/** The heartbeat's memory between checks: missed checks in a row, and whether an alert is open (the SMS was sent). */
data class HeartbeatState(val missed: Int = 0, val down: Boolean = false)

enum class HeartbeatSms { Start, Recovery }

data class HeartbeatSettings(val enabled: Boolean = false, val limit: Int = DEFAULT_LIMIT) {
    companion object {
        val LIMITS = listOf(2, 3, 5)
        const val DEFAULT_LIMIT = 2
        fun validLimit(n: Int?): Int = if (n != null && n in LIMITS) n else DEFAULT_LIMIT
    }
}

object Heartbeat {
    /**
     * What a failed first push means: it counts only when the phone is not the cause. [phoneOffline] is true when Fixed IP is on
     * and reads no Wi-Fi, or another network than the pinned one (section 3); with Fixed IP off the caller passes false.
     */
    fun observe(firstPushDelivered: Boolean?, phoneOffline: Boolean): Observation = when {
        firstPushDelivered == null -> Observation.None
        firstPushDelivered -> Observation.Delivered
        phoneOffline -> Observation.None
        else -> Observation.Failed
    }

    /**
     * One check. A delivered push clears the count and ends an open alert (one recovery SMS); a failed one adds a missed check and,
     * on reaching [limit] with no alert open, opens one (one start SMS); anything else changes nothing. No SMS repeats.
     */
    fun step(state: HeartbeatState, obs: Observation, limit: Int): Pair<HeartbeatState, HeartbeatSms?> = when (obs) {
        Observation.None -> state to null
        Observation.Delivered -> HeartbeatState() to (if (state.down) HeartbeatSms.Recovery else null)
        Observation.Failed -> {
            val missed = minOf(state.missed + 1, MAX_COUNT)
            if (!state.down && missed >= limit) HeartbeatState(missed, true) to HeartbeatSms.Start
            else HeartbeatState(missed, state.down) to null
        }
    }

    /** A cap, so a console that stays down for months cannot overflow the stored count. */
    const val MAX_COUNT = 1_000

    /** The two SMS texts (section 4), at most 100 characters. */
    fun text(sms: HeartbeatSms, limit: Int): String = when (sms) {
        HeartbeatSms.Start -> "Matter Plug: the autodialer has not answered $limit checks. Check it."
        HeartbeatSms.Recovery -> "Matter Plug: the autodialer answers again."
    }

    fun encode(s: HeartbeatState): String = "${s.missed},${if (s.down) 1 else 0}"

    /** A damaged value reads as a clean state: no alert open, nothing missed. */
    fun decode(text: String?): HeartbeatState {
        val p = text?.split(',') ?: return HeartbeatState()
        if (p.size != 2) return HeartbeatState()
        val missed = p[0].toIntOrNull()?.takeIf { it in 0..MAX_COUNT } ?: return HeartbeatState()
        val down = when (p[1]) { "1" -> true; "0" -> false; else -> return HeartbeatState() }
        return HeartbeatState(missed, down)
    }
}
