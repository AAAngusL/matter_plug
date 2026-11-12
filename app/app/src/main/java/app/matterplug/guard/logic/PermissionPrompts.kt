package app.matterplug.guard.logic

/** The two things Battery Guard asks for the first time it is switched on (BATTERY-GUARD.md section 9, 5.9). */
enum class PermissionPrompt { Notifications, Exemption }

/** Pure decisions for the G7 UI flow. No Android imports. */
object PermissionPrompts {
    /** POST_NOTIFICATIONS is a runtime permission from Android 13 (API 33). */
    const val NOTIFICATIONS_RUNTIME_SDK = 33

    /**
     * The prompts to show now, in order. Each is asked once ("asked" is remembered whatever the answer was) and only
     * when it is still needed: notifications only on Android 13+ and while not granted, the exemption while not held.
     */
    fun due(
        sdkInt: Int,
        notificationsGranted: Boolean,
        askedNotifications: Boolean,
        exemptionHeld: Boolean,
        askedExemption: Boolean,
    ): List<PermissionPrompt> {
        val out = mutableListOf<PermissionPrompt>()
        if (sdkInt >= NOTIFICATIONS_RUNTIME_SDK && !notificationsGranted && !askedNotifications) out += PermissionPrompt.Notifications
        if (!exemptionHeld && !askedExemption) out += PermissionPrompt.Exemption
        return out
    }

    /**
     * Whether switching Battery Guard off first asks "The plug is off. Turn it on before disabling?".
     * Only a plug that was read and is off asks; an unreadable plug (null) or an on plug disables at once.
     */
    fun needsTurnOnDialog(plugOn: Boolean?): Boolean = plugOn == false
}
