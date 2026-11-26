package app.matterplug.guard

import android.content.Context
import app.matterplug.data.PlugRepository
import app.matterplug.matter.PlugCommander
import app.matterplug.matter.SwitchResult

/** The plug Battery Guard acts on (the Charger plug), switched and read without the caller looking it up or handling "no plug". */
class ChargerPlug(private val ctx: Context) {
    /** [SwitchResult.NotReached] with "no plug" when there is no charger plug. */
    suspend fun switchTo(on: Boolean): SwitchResult {
        val plug = PlugRepository(ctx).charger() ?: return SwitchResult.NotReached("no plug")
        return PlugCommander(ctx).switchTo(plug, on)
    }

    /** The plug's On/Off state; null when there is no charger plug or it did not answer. */
    suspend fun readOnOff(): Boolean? = PlugRepository(ctx).charger()?.let { PlugCommander(ctx).readOnOff(it) }
}
