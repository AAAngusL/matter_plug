package app.matterplug.guard.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/** PLUG-WATCH.md section 7: what the Battery Guard tab shows about the plug. */
class PlugSightingTest {
    @Test fun reachedRecordsTimeAndState() =
        assertEquals(PlugSighting(reachedAt = 10, on = true), PlugSighting().reached(on = true, nowMs = 10))

    @Test fun firstMissKeepsTheLastReachAndStartsTheMiss() =
        assertEquals(PlugSighting(reachedAt = 10, on = true, missedSince = 20),
            PlugSighting(reachedAt = 10, on = true).missed(nowMs = 20))

    @Test fun laterMissesKeepTheFirstMissTime() =
        assertEquals(20L, PlugSighting().missed(20).missed(25).missed(30).missedSince)

    @Test fun reachedEndsTheMiss() =
        assertEquals(PlugSighting(reachedAt = 30, on = false), PlugSighting().missed(20).reached(on = false, nowMs = 30))
}
