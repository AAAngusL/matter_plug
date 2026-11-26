package app.matterplug.guard.logic

enum class GuardAction { TurnOn, TurnOff, Nothing }

object GuardPolicy {
    fun decide(level: Int, pluggedIn: Boolean, s: GuardSettings): GuardAction = when {
        level <= s.low && !pluggedIn -> GuardAction.TurnOn
        s.high < 100 && level >= s.high && pluggedIn -> GuardAction.TurnOff   // high 100 means never turn off
        else -> GuardAction.Nothing
    }
}
