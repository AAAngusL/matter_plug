package app.matterplug.cellular.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalReadingTest {
    /** Android's CellInfo.UNAVAILABLE. */
    private val unavailable = Int.MAX_VALUE

    @Test fun validValuesAreKept() = assertEquals(
        SignalReading(level = 3, rsrpDbm = -97, rsrqDb = -11, sinrDb = 8, at = 5L),
        SignalReading.lte(level = 3, rsrp = -97, rsrq = -11, rssnr = 8, at = 5L),
    )

    @Test fun unavailableValuesAreLeftOut() = assertEquals(
        SignalReading(level = 2, rsrpDbm = -101, rsrqDb = null, sinrDb = null, at = 0L),
        SignalReading.lte(level = 2, rsrp = -101, rsrq = unavailable, rssnr = unavailable, at = 0L),
    )

    @Test fun valuesOutsideTheLteRangesAreLeftOut() {
        val r = SignalReading.lte(level = 5, rsrp = -150, rsrq = 5, rssnr = 35, at = 0L)
        assertEquals(SignalReading(null, null, null, null, 0L), r)
        val high = SignalReading.lte(level = -1, rsrp = -30, rsrq = -40, rssnr = -25, at = 0L)
        assertEquals(SignalReading(null, null, null, null, 0L), high)
    }

    @Test fun theRangeEdgesAreValid() {
        assertEquals(SignalReading(0, -140, -34, -20, 0L), SignalReading.lte(0, -140, -34, -20, 0L))
        assertEquals(SignalReading(4, -44, 3, 30, 0L), SignalReading.lte(4, -44, 3, 30, 0L))
    }

    @Test fun oneStepPastEachEdgeIsLeftOut() {
        assertEquals(SignalReading(null, null, null, null, 0L), SignalReading.lte(5, -141, -35, -21, 0L))
        assertEquals(SignalReading(null, null, null, null, 0L), SignalReading.lte(-1, -43, 4, 31, 0L))
    }

    @Test fun theScreenTextIsMadeFromTheReadingAndIsUnchanged() {
        assertEquals("LTE, RSRP -97 dBm, RSRQ -11 dB, SINR 8 dB", SignalDetail.format(SignalReading(3, -97, -11, 8, 0L)))
        assertEquals("LTE, RSRP -101 dBm", SignalDetail.format(SignalReading(2, -101, null, null, 0L)))
        assertEquals("LTE, level 2", SignalDetail.format(SignalReading(2, null, null, null, 0L)))
    }

    @Test fun aStoredReadingSurvivesARoundTrip() {
        val r = SignalReading(3, -97, null, 8, 1794709185491L)
        assertEquals(r, SignalReadingCodec.decode(SignalReadingCodec.encode(r)))
        assertNull(SignalReadingCodec.decode(SignalReadingCodec.encode(null)))
        assertNull(SignalReadingCodec.decode("garbage"))
        assertNull(SignalReadingCodec.decode(null))
    }
}
