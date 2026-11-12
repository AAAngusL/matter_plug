package app.matterplug.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.plugDataStore: DataStore<Preferences> by preferencesDataStore(name = "plugs")

/**
 * Stores the plug list (a list of one in version 1) and the node ID counter.
 * The last on/off state is deliberately not stored: the screen shows only what the plug reports.
 */
class PlugRepository(context: Context) {
    private val store = context.applicationContext.plugDataStore

    val plugs: Flow<List<Plug>> = store.data.map { decode(it[PLUGS_JSON]) }

    suspend fun add(plug: Plug) {
        store.edit { prefs ->
            val list = decode(prefs[PLUGS_JSON]) + plug
            prefs[PLUGS_JSON] = encode(list)
        }
    }

    suspend fun rename(nodeId: Long, name: String) {
        store.edit { prefs ->
            val list = decode(prefs[PLUGS_JSON]).map { if (it.nodeId == nodeId) it.copy(name = name) else it }
            prefs[PLUGS_JSON] = encode(list)
        }
    }

    suspend fun remove(nodeId: Long) {
        store.edit { prefs ->
            val list = decode(prefs[PLUGS_JSON]).filterNot { it.nodeId == nodeId }
            prefs[PLUGS_JSON] = encode(list)
        }
    }

    /** Returns the next free node ID (starting at 1) and saves the incremented counter before returning. */
    suspend fun nextNodeId(): Long {
        var issued = 1L
        store.edit { prefs ->
            issued = prefs[NEXT_NODE_ID] ?: 1L
            prefs[NEXT_NODE_ID] = issued + 1
        }
        return issued
    }

    private fun encode(list: List<Plug>): String {
        val array = JSONArray()
        for (p in list) {
            array.put(JSONObject().put("nodeId", p.nodeId).put("endpoint", p.endpoint).put("name", p.name))
        }
        return array.toString()
    }

    private fun decode(json: String?): List<Plug> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Plug(o.getLong("nodeId"), o.getInt("endpoint"), o.getString("name"))
            }
        } catch (e: Exception) {
            Log.e("PlugRepository", "could not read plug list", e)
            emptyList()
        }
    }

    private companion object {
        val PLUGS_JSON = stringPreferencesKey("plugs_json")
        val NEXT_NODE_ID = longPreferencesKey("next_node_id")
    }
}
