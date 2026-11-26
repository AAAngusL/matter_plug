package app.matterplug.alerts.logic

/**
 * Fixed wire text (docs/MESSAGES.md). Short and plain: what is wrong, then what it means or what to do, battery last. No text passes
 * [MAX] characters: a variable part (a fault detail, an Android error name) is cut with an ellipsis to make it fit.
 */
object AlertMessages {
    const val MAX = 100

    /** [head] + [variable] + [tail], the variable part cut with an ellipsis so the whole is at most [MAX] characters. */
    fun fit(head: String, variable: String, tail: String): String {
        val room = MAX - head.length - tail.length
        val v = if (variable.length <= room) variable else variable.take((room - 1).coerceAtLeast(0)) + "…"
        return head + v + tail
    }

    fun text(e: AlertEvent): String = when (e.phase) {
        AlertPhase.Recovered -> recovered(e)
        AlertPhase.Test -> "Matter Plug: test alert."
        AlertPhase.Cancelled -> "Matter Plug: ${e.cancelledBy?.cause ?: "An alert switch was turned off"}. " +
            "Open alert closed; problem may remain. Battery ${e.battery}%."
        AlertPhase.Status -> e.extra?.text(e) ?: "Matter Plug: status. Battery ${e.battery}%, ${e.power.words.wire}."
        AlertPhase.Started -> body(e)
        AlertPhase.Reminder -> body(e).replaceFirst("Matter Plug: ", "Reminder: ")
    }

    /** The text of a kind a feature owns. Its events always carry their extra; the rest is only the answer to one that does not. */
    private fun featureText(e: AlertEvent): String = e.extra?.text(e) ?: "Matter Plug: ${e.kind.wire}. Battery ${e.battery}%."

    private fun recovered(e: AlertEvent): String = when (e.kind) {
        AlertKind.IpDrift, AlertKind.CellularDown -> featureText(e)
        AlertKind.NotCharging -> "Matter Plug: charging again. Battery ${e.battery}%."
        AlertKind.PlugUnreachable -> "Matter Plug: charger plug reachable again. Battery ${e.battery}%."
        AlertKind.GuardDegraded -> "Matter Plug: Battery Guard working again. Battery ${e.battery}%."
        AlertKind.Status, AlertKind.Test, AlertKind.CellularStatus -> "Matter Plug: recovered. Battery ${e.battery}%."     // never recovered
    }

    private fun body(e: AlertEvent): String = when (e.kind) {
        AlertKind.NotCharging -> "Matter Plug: not charging though plug is on. Check charger and cable. Battery ${e.battery}%."
        AlertKind.PlugUnreachable ->
            when (e.wantedOn) {
                false -> "Matter Plug: cannot reach charger plug to turn it off. Battery ${e.battery}%."
                true -> "Matter Plug: cannot reach charger plug to turn it on. Battery ${e.battery}%."
                null -> "Matter Plug: cannot reach charger plug. Battery ${e.battery}%."     // from a plug read
            }
        AlertKind.GuardDegraded -> fit("Matter Plug: Battery Guard stopped: ", e.detail ?: "unknown fault", ". Battery ${e.battery}%.")
        AlertKind.IpDrift, AlertKind.CellularDown -> featureText(e)
        AlertKind.Status, AlertKind.Test, AlertKind.CellularStatus -> "Matter Plug: test alert."   // not reached: Status and Test use their phase's text above
    }
}
