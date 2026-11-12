package app.matterplug.guard.logic

enum class GuardAction { TurnOn, TurnOff, Nothing }

object GuardPolicy {
    fun decide(level: Int, pluggedIn: Boolean, s: GuardSettings): GuardAction = when {
        level <= s.low && !pluggedIn -> GuardAction.TurnOn
        s.high < 100 && level >= s.high && pluggedIn -> GuardAction.TurnOff   // B7: high 100 means never turn off
        else -> GuardAction.Nothing
    }
}

object BatteryMath {
    /** Null when the values are unusable. */
    fun percent(level: Int, scale: Int): Int? =
        if (level < 0 || scale <= 0) null else (level * 100 / scale).coerceIn(0, 100)

    /** EXTRA_PLUGGED is a bit mask (AC 1, USB 2, wireless 4, dock 8): any bit means plugged in. */
    fun isPluggedIn(extraPlugged: Int): Boolean = extraPlugged != 0
}
