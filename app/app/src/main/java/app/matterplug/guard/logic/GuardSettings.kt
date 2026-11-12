package app.matterplug.guard.logic

data class GuardSettings(
    val enabled: Boolean = false,
    val low: Int = 20,
    val high: Int = 85,
    val chargingIntervalMin: Int = 15,
    val batteryIntervalMin: Int = 15,
    val alertsEnabled: Boolean = false,
    val statusEnabled: Boolean = false,        // status report after every check
    val alertAddress: String = "",
    val alertToken: String = "",               // optional; sent as Authorization: Bearer <token> (B13). Never log it.
) {
    fun intervalFor(pluggedIn: Boolean): Int = if (pluggedIn) chargingIntervalMin else batteryIntervalMin

    companion object {
        val INTERVALS = listOf(5, 10, 15, 30, 60)
        const val MIN_GAP = 10
        fun lowRange(high: Int): IntRange = 5..minOf(50, high - MIN_GAP)
        fun highRange(low: Int): IntRange = maxOf(50, low + MIN_GAP)..100
    }
}
