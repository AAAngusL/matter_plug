package app.matterplug.battery

object BatteryMath {
    /** Null when the values are unusable. */
    fun percent(level: Int, scale: Int): Int? =
        if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)

    /** EXTRA_PLUGGED is a bit mask (AC 1, USB 2, wireless 4, dock 8): any bit means plugged in. */
    fun isPluggedIn(extraPlugged: Int): Boolean = extraPlugged != 0
}
