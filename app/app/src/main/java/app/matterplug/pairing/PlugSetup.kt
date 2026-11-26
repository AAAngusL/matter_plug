package app.matterplug.pairing

import android.content.Context
import android.util.Log
import app.matterplug.R
import app.matterplug.data.Plug
import app.matterplug.data.PlugRepository
import app.matterplug.matter.EndpointFinder
import app.matterplug.matter.PlugClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** What happens to a plug once it has joined: find its On/Off switch, and either keep it under a default name or leave it clean. */
class PlugSetup(context: Context) {
    private val context = context.applicationContext
    private val repository = PlugRepository(this.context)

    sealed interface Outcome {
        /** Saved under [name], which the naming step may change. */
        data class Ready(val endpoint: Int, val name: String) : Outcome
        /** The plug has no On/Off switch; the app's fabric was dropped again. */
        data object NoOnOff : Outcome
        /** The plug did not answer after pairing. */
        data object Unreachable : Outcome
    }

    suspend fun finish(nodeId: Long): Outcome {
        val endpoint = try {
            EndpointFinder.find(context, nodeId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "could not reach the plug after pairing", e)
            return Outcome.Unreachable
        }
        if (endpoint == null) {
            PlugClient(context, Plug(nodeId, 0, "")).leaveFabric()
            return Outcome.NoOnOff
        }
        // Saved now, under its default name: the plug already holds this app's credentials, so a kill before the
        // naming step must not lose it. The naming step only renames it.
        val name = nextDefaultName()
        repository.add(Plug(nodeId, endpoint, name))
        return Outcome.Ready(endpoint, name)
    }

    /** "Plug" for the first plug, "Plug 2", "Plug 3"... after that, so the switcher never lists two identical names. */
    private suspend fun nextDefaultName(): String {
        val base = context.getString(R.string.default_plug_name)
        val count = repository.plugs.first().size
        return if (count == 0) base else "$base ${count + 1}"
    }

    private companion object {
        const val TAG = "PlugSetup"
    }
}
