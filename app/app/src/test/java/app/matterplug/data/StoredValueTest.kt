package app.matterplug.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.matterplug.sms.LastSms
import app.matterplug.sms.SmsOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StoredValueTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = PreferenceDataStoreFactory.create { File(tmp.root, "t.preferences_pb") }

    @Test fun writeReadAndRemove() = runBlocking {
        val v = StoredValue.plain(store(), longPreferencesKey("n"))
        assertNull(v.read())
        v.write(5L)
        assertEquals(5L, v.read())
        assertEquals(5L, v.flow.first())
        v.write(null)
        assertNull(v.read())
    }

    @Test fun usesTheCodecAndTheGivenKey() = runBlocking {
        val s = store()
        val key = stringPreferencesKey("k")
        val v = StoredValue(s, key, { t: String? -> t?.removePrefix("<") }, { "<$it" })
        v.write("x")
        assertEquals("<x", s.data.first()[key])
        assertEquals("x", v.read())
    }

    @Test fun readInAndRemoveInInsideOneEdit() = runBlocking {
        val s = store()
        val v = StoredValue.plain(s, longPreferencesKey("n"))
        v.write(7L)
        var seen: Long? = null
        s.edit { p -> seen = v.readIn(p); v.removeIn(p) }
        assertEquals(7L, seen)
        assertNull(v.read())
    }

    @Test fun lastSmsKeepsItsStoredFormat() = runBlocking {
        val s = store()
        val last = LastSms(s)
        assertNull(last.read())
        last.record(1234L, SmsOutcome.Failed, "no answer")
        assertEquals("1234|failed|no answer", s.data.first()[stringPreferencesKey("last_sms")])
        assertEquals(SmsOutcome.Failed, last.read()?.outcome)
        assertEquals("no answer", last.flow.first()?.detail)
    }
}
