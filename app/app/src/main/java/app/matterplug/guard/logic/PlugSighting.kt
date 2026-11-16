package app.matterplug.guard.logic

/**
 * What Battery Guard last learnt about the charger plug (PLUG-WATCH.md section 7): when it was last reached and its
 * On/Off state then, and since when it has not been reached. [missedSince] is null while the plug answers.
 */
data class PlugSighting(val reachedAt: Long? = null, val on: Boolean? = null, val missedSince: Long? = null) {
    fun reached(on: Boolean?, nowMs: Long) = PlugSighting(reachedAt = nowMs, on = on, missedSince = null)
    fun missed(nowMs: Long) = copy(missedSince = missedSince ?: nowMs)
}
