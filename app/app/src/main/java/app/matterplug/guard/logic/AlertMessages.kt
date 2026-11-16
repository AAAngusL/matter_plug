package app.matterplug.guard.logic

import app.matterplug.cellular.logic.CellularReport

/** Fixed wire text. */
object AlertMessages {
    fun text(e: AlertEvent): String = when (e.phase) {
        AlertPhase.Recovered -> if (e.kind == AlertKind.CellularDown) CellularReport.recoveredText(e.battery) else "Matter Plug: recovered. Battery ${e.battery}%, ${e.power.words.wire}."
        AlertPhase.Test -> "Matter Plug: test alert."
        AlertPhase.Cancelled -> "Matter Plug: ${e.cancelledBy?.phrase ?: "An alert switch was turned off"}. " +
            "The ${alertName(e.kind)} alert is closed without a recovery. Battery ${e.battery}%."
        AlertPhase.Status -> if (e.kind == AlertKind.CellularStatus) CellularReport.statusText(e.cellular, e.battery) else "Matter Plug: status. Battery ${e.battery}%, ${e.power.words.wire}."
        AlertPhase.Started -> body(e)
        AlertPhase.Reminder -> body(e).replaceFirst("Matter Plug: ", "Matter Plug (reminder): ")
    }

    private fun alertName(k: AlertKind): String = when (k) {
        AlertKind.NotCharging -> "not charging"
        AlertKind.PlugUnreachable -> "plug unreachable"
        AlertKind.GuardDegraded -> "Battery Guard not working"
        AlertKind.CellularDown -> "SMS"
        AlertKind.Status, AlertKind.Test, AlertKind.CellularStatus -> k.wire       // never cancelled
    }

    private fun body(e: AlertEvent): String = when (e.kind) {
        AlertKind.NotCharging -> "Matter Plug: battery ${e.battery}%, ${e.power.words.wire}. The plug is on. Check the charger and cable."
        AlertKind.PlugUnreachable ->
            when (e.wantedOn) {
                false -> "Matter Plug: battery ${e.battery}%. The charger plug cannot be reached to turn it off."
                true -> "Matter Plug: battery ${e.battery}%, not charging. The charger plug cannot be reached."
                null -> "Matter Plug: battery ${e.battery}%. The charger plug cannot be reached."     // from a plug read
            }
        AlertKind.GuardDegraded -> "Matter Plug: Battery Guard is not working: ${e.detail ?: "unknown fault"}. Battery ${e.battery}%."
        AlertKind.CellularDown -> e.cellular?.let { CellularReport.downText(it, e.battery) } ?: "Matter Plug: SMS is not working. Battery ${e.battery}%."
        AlertKind.Status, AlertKind.Test, AlertKind.CellularStatus -> "Matter Plug: test alert."   // not reached: Status and Test use their phase's text above
    }
}
