package app.matterplug.cellular.logic

import java.time.LocalTime

/**
 * The Cellular Check settings (docs/CELLULAR-CHECK.md section 10). The interval is not here: the passive layers use
 * Battery Guard's Check every value.
 */
data class CellularSettings(
    val enabled: Boolean = false,
    val ownNumber: String = "",
    val smsTestMinutes: Int = DEFAULT_TEST_MINUTES,        // minutes after midnight, local time
) {
    val smsTestTime: LocalTime get() = LocalTime.of(smsTestMinutes / 60, smsTestMinutes % 60)

    /** Own number: digits and one leading plus only. Test time: inside the day, else 10:00. */
    fun coerced(): CellularSettings = copy(
        ownNumber = ownNumber.trim().let { n -> (if (n.startsWith("+")) "+" else "") + n.filter { it.isDigit() } },
        smsTestMinutes = if (smsTestMinutes in 0 until 24 * 60) smsTestMinutes else DEFAULT_TEST_MINUTES,
    )

    companion object {
        const val DEFAULT_TEST_MINUTES = 10 * 60
    }
}
