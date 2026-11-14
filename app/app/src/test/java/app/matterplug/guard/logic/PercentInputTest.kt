package app.matterplug.guard.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PercentInputTest {
    private val range = 5..50

    @Test fun newValueInRangeIsCommitted() = assertEquals(30, PercentInput.toCommit("30", range, current = 20))

    @Test fun unchangedValueIsNotCommitted() = assertNull(PercentInput.toCommit("20", range, current = 20))   // no write, no re-armed check

    @Test fun outOfRangeIsNotCommitted() {
        assertNull(PercentInput.toCommit("4", range, current = 20))
        assertNull(PercentInput.toCommit("51", range, current = 20))
    }

    @Test fun emptyOrNonNumberIsNotCommitted() {
        assertNull(PercentInput.toCommit("", range, current = 20))
        assertNull(PercentInput.toCommit("2a", range, current = 20))
    }

    @Test fun validityIgnoresWhetherItChanged() {
        assertEquals(true, PercentInput.isValid("20", range))
        assertEquals(false, PercentInput.isValid("", range))
        assertEquals(false, PercentInput.isValid("99", range))
    }
}
