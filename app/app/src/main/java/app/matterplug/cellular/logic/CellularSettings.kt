package app.matterplug.cellular.logic

import app.matterplug.guard.logic.CancelledBy
import app.matterplug.guard.logic.SwitchOff

import java.time.LocalTime

/**
 * The Cellular Check settings (docs/CELLULAR-CHECK.md section 10). The interval is not here: the passive layers use
 * Battery Guard's Check every value.
 */
data class CellularSettings(
    val enabled: Boolean = false,
    val ownNumber: String = "",
    val smsTestMinutes: Int = DEFAULT_TEST_MINUTES,        // minutes after midnight, local time
    /** Cellular Check's own switches (SIGNAL-STRENGTH.md draft 5): its alerts and its status report, to the shared address. */
    val alertsEnabled: Boolean = false,
    val statusEnabled: Boolean = false,
) {
    val smsTestTime: LocalTime get() = LocalTime.of(smsTestMinutes / 60, smsTestMinutes % 60)

    /** At least 7 digits: shorter is not a phone number, and every test SMS to it would fail and raise a false alert. */
    val ownNumberValid: Boolean get() = ownNumber.count { it.isDigit() } >= MIN_DIGITS

    /** Own number: digits and one leading plus only. Test time: inside the day, else 10:00. */
    fun coerced(): CellularSettings = copy(
        ownNumber = ownNumber.trim().let { n -> (if (n.startsWith("+")) "+" else "") + n.filter { it.isDigit() } },
        smsTestMinutes = if (smsTestMinutes in 0 until 24 * 60) smsTestMinutes else DEFAULT_TEST_MINUTES,
    )

    companion object {
        const val DEFAULT_TEST_MINUTES = 10 * 60
        const val MIN_DIGITS = 7
    }
}

/** What a change of Cellular Check's switches does to its open alert. */
fun CellularSettings.switchOff(new: CellularSettings): SwitchOff.Change =
    SwitchOff.change(enabled, new.enabled, alertsEnabled, new.alertsEnabled, CancelledBy.CellularOff, CancelledBy.CellularAlertsOff)
