package app.matterplug.guard.ui

import android.app.Application
import android.os.Build
import android.util.Log
import app.matterplug.guard.GuardPermissions
import app.matterplug.guard.PermissionAsks
import app.matterplug.guard.logic.PermissionPrompt
import app.matterplug.guard.logic.PermissionPrompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The permission prompts after the first enable (BATTERY-GUARD.md 5.9): works out which are due (each is asked once) and hands them to the
 * screen one at a time through [prompt]. They never block the switch; they come after it has been turned on.
 */
class PermissionPromptQueue(private val app: Application, private val asks: PermissionAsks, private val scope: CoroutineScope) {
    /** The prompt the screen should launch next; the screen calls [consumed] as it launches it. */
    private val _prompt = MutableStateFlow<PermissionPrompt?>(null)
    val prompt: StateFlow<PermissionPrompt?> = _prompt
    private var pending: List<PermissionPrompt> = emptyList()

    /** Works out which prompts are due and starts with the first. */
    suspend fun queue() {
        try {
            pending = PermissionPrompts.due(
                sdkInt = Build.VERSION.SDK_INT,
                notificationsGranted = GuardPermissions.notificationsGranted(app),
                askedNotifications = asks.askedNotifications(),
                exemptionHeld = GuardPermissions.ignoringBatteryOptimizations(app),
                askedExemption = asks.askedExemption(),
            )
            showNext()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e("Guard", "permission prompts failed", e)
        }
    }

    private suspend fun showNext() {
        val next = pending.firstOrNull()
        pending = pending.drop(1)
        when (next) {
            PermissionPrompt.Notifications -> asks.markNotifications()
            PermissionPrompt.Exemption -> asks.markExemption()
            null -> {}
        }
        _prompt.value = next
    }

    /** The screen has taken the prompt and is launching it; clearing it here means a rotation does not launch it twice. */
    fun consumed() { _prompt.value = null }

    /** The screen's launcher has returned (granted or not) or the intent was started: go on to the next prompt, if any. */
    fun done() {
        scope.launch {
            try { showNext() } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("Guard", "next prompt failed", e)
            }
        }
    }
}
