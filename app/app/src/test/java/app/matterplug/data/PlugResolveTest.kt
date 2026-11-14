package app.matterplug.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlugResolveTest {
    private val a = Plug(1, 1, "A")
    private val b = Plug(2, 1, "B")

    @Test fun findsThePlugWithThatId() = assertEquals(b, listOf(a, b).resolve(2))

    @Test fun nullIdFallsBackToFirst() = assertEquals(a, listOf(a, b).resolve(null))

    @Test fun removedIdFallsBackToFirst() = assertEquals(a, listOf(a, b).resolve(99))

    @Test fun emptyListHasNoPlug() = assertNull(emptyList<Plug>().resolve(1))
}
