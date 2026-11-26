package app.matterplug.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryMathTest {
    @Test fun percentExact() = assertEquals(57, BatteryMath.percent(57, 100))
    @Test fun percentScaled() = assertEquals(50, BatteryMath.percent(1, 2))
    @Test fun zeroScaleIsNull() = assertNull(BatteryMath.percent(5, 0))
    @Test fun negativeLevelIsNull() = assertNull(BatteryMath.percent(-1, 100))
    @Test fun percentIsCappedAt100() = assertEquals(100, BatteryMath.percent(150, 100))

    @Test fun pluggedMask() {
        assertFalse(BatteryMath.isPluggedIn(0))
        for (v in listOf(1, 2, 3, 4, 8)) assertTrue("extra $v", BatteryMath.isPluggedIn(v))
    }
}
