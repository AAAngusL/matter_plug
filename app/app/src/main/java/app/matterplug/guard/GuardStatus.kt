package app.matterplug.guard

import app.matterplug.guard.logic.AlertState
import app.matterplug.guard.logic.PlugSighting

/** Everything the settings screen shows under the controls. Null means "never recorded". */
data class GuardStatus(
    val lastCheckAt: Long? = null,
    val lastLevel: Int? = null,
    val lastPlugged: Boolean? = null,
    val nextCheckAt: Long? = null,
    val lastSwitchAt: Long? = null,
    val lastSwitchOn: Boolean? = null,
    val lastSwitchLevel: Int? = null,
    val lastDeliveryOkAt: Long? = null,
    val lastDeliveryFailAt: Long? = null,
    val lastDeliveryError: String? = null,
    val lastRunError: String? = null,
    val queueSize: Int = 0,
    val alertState: AlertState = AlertState(),   // added to the document's list: the alert state line needs it
    val plug: PlugSighting = PlugSighting(),     // PLUG-WATCH.md section 7
)
