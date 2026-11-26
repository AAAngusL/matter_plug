package app.matterplug.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerTest {
    // BatteryManager.BATTERY_STATUS_*: 1 unknown, 2 charging, 3 discharging, 4 not charging, 5 full.
    @Test fun androidStatusNumbers() {
        assertEquals(BatteryStatus.Unknown, BatteryStatus.fromAndroid(1))
        assertEquals(BatteryStatus.Charging, BatteryStatus.fromAndroid(2))
        assertEquals(BatteryStatus.Discharging, BatteryStatus.fromAndroid(3))
        assertEquals(BatteryStatus.NotCharging, BatteryStatus.fromAndroid(4))
        assertEquals(BatteryStatus.Full, BatteryStatus.fromAndroid(5))
    }

    @Test fun anyOtherNumberIsUnknown() {
        for (v in listOf(-1, 0, 6, 99)) assertEquals("status $v", BatteryStatus.Unknown, BatteryStatus.fromAndroid(v))
    }

    @Test fun wireNamesMatchTheApi() = assertEquals(
        listOf("charging", "discharging", "not_charging", "full", "unknown"),
        listOf(BatteryStatus.Charging, BatteryStatus.Discharging, BatteryStatus.NotCharging, BatteryStatus.Full, BatteryStatus.Unknown)
            .map { it.wire })

    @Test fun wireRoundTrip() {
        for (s in BatteryStatus.values()) assertEquals(s, BatteryStatus.fromWire(s.wire))
    }

    @Test fun unknownOrMissingWireIsUnknown() {
        assertEquals(BatteryStatus.Unknown, BatteryStatus.fromWire(null))
        assertEquals(BatteryStatus.Unknown, BatteryStatus.fromWire("boiling"))
    }

    @Test fun chargingOnlyWhenAndroidSaysCharging() {
        assertTrue(Power(true, BatteryStatus.Charging).charging)
        for (s in BatteryStatus.values().filter { it != BatteryStatus.Charging }) {
            assertFalse("status $s", Power(true, s).charging)
        }
    }

    // ALERT-API.md section 5, the <power> table.
    @Test fun wordsWhenPluggedIn() {
        assertEquals("charging", Power(true, BatteryStatus.Charging).words.wire)
        assertEquals("plugged in, full", Power(true, BatteryStatus.Full).words.wire)
        assertEquals("plugged in, not charging", Power(true, BatteryStatus.NotCharging).words.wire)
        assertEquals("plugged in, discharging", Power(true, BatteryStatus.Discharging).words.wire)
        assertEquals("plugged in", Power(true, BatteryStatus.Unknown).words.wire)
    }

    @Test fun notPluggedInIsOnBatteryWhateverTheStatus() {
        for (s in BatteryStatus.values()) assertEquals("status $s", PowerWords.OnBattery, Power(false, s).words)
        assertEquals("on battery", PowerWords.OnBattery.wire)
    }
}
