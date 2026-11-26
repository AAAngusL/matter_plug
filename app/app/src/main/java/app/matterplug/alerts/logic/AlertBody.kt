package app.matterplug.alerts.logic

import java.time.ZoneId

/** The finished JSON body of an event, as it goes on the wire and into the waiting list. */
object AlertBody {
    fun of(e: AlertEvent): String = AlertJson.encode(e, AlertMessages.text(e), AlertJson.isoTime(e.atMs, ZoneId.systemDefault()))
}
