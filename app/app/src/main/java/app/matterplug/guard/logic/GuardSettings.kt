package app.matterplug.guard.logic

data class GuardSettings(
    val enabled: Boolean = false,
    val low: Int = 20,
    val high: Int = 85,
    val intervalMin: Int = 15,                 // one interval for every check, plugged in or not
    val alertsEnabled: Boolean = false,
    val statusEnabled: Boolean = false,        // status report after every check
) {
    companion object {
        val INTERVALS = listOf(5, 10, 15, 30, 60)
        const val MIN_GAP = 10
        fun lowRange(high: Int): IntRange = 5..minOf(50, high - MIN_GAP)
        fun highRange(low: Int): IntRange = maxOf(50, low + MIN_GAP)..100
    }
}
