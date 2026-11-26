package app.matterplug.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * One optional value kept under one key of a DataStore, as [T] in memory and as [S] on disk. Writing null removes the key. A store that
 * must change the value together with its settings in one edit uses [readIn] and [removeIn] on the edit's preferences.
 */
class StoredValue<S : Any, T : Any>(
    private val store: DataStore<Preferences>,
    private val key: Preferences.Key<S>,
    private val decode: (S?) -> T?,
    private val encode: (T) -> S,
) {
    val flow: Flow<T?> = store.data.map { decode(it[key]) }

    suspend fun read(): T? = decode(store.data.first()[key])

    suspend fun write(value: T?) {
        store.edit { if (value == null) it.remove(key) else it[key] = encode(value) }
    }

    fun readIn(p: Preferences): T? = decode(p[key])

    fun removeIn(p: MutablePreferences) { p.remove(key) }

    companion object {
        /** A value stored as it is. */
        fun <S : Any> plain(store: DataStore<Preferences>, key: Preferences.Key<S>) = StoredValue(store, key, { it }, { it })
    }
}
