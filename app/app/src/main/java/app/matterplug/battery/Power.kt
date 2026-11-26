package app.matterplug.battery

/** Android's battery status (BatteryManager.EXTRA_STATUS), as `adb shell dumpsys battery` shows it. */
enum class BatteryStatus(val wire: String) {
    Charging("charging"), Discharging("discharging"), NotCharging("not_charging"), Full("full"), Unknown("unknown");

    companion object {
        /** BatteryManager.BATTERY_STATUS_*: 2 charging, 3 discharging, 4 not charging, 5 full; anything else is unknown. */
        fun fromAndroid(extraStatus: Int): BatteryStatus = when (extraStatus) {
            2 -> Charging
            3 -> Discharging
            4 -> NotCharging
            5 -> Full
            else -> Unknown
        }

        fun fromWire(s: String?): BatteryStatus = values().firstOrNull { it.wire == s } ?: Unknown
    }
}

/** The words a message and the status line use for the phone's power (ALERT-API.md section 5, the <power> table). */
enum class PowerWords(val wire: String) {
    OnBattery("on battery"), Charging("charging"), Full("plugged in, full"), NotCharging("plugged in, not charging"),
    Discharging("plugged in, discharging"), PluggedIn("plugged in"),
}

/**
 * The phone's power at one reading. [pluggedIn] is what Battery Guard switches by (any power source connected);
 * [status] is whether the battery takes charge, which a charge limit can stop while the phone stays plugged in.
 */
data class Power(val pluggedIn: Boolean, val status: BatteryStatus) {
    val charging: Boolean get() = status == BatteryStatus.Charging

    val words: PowerWords get() = if (!pluggedIn) PowerWords.OnBattery else when (status) {
        BatteryStatus.Charging -> PowerWords.Charging
        BatteryStatus.Full -> PowerWords.Full
        BatteryStatus.NotCharging -> PowerWords.NotCharging
        BatteryStatus.Discharging -> PowerWords.Discharging
        BatteryStatus.Unknown -> PowerWords.PluggedIn
    }
}
