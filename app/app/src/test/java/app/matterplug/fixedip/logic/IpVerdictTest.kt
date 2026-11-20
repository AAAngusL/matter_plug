package app.matterplug.fixedip.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IpVerdictTest {
    private val pinned = PinnedNetwork("192.168.1.37", 24, "192.168.1.1", "Home", "a4:12:34:56:78:9a")
    private val good = LinkReading(wifi = true, address = "192.168.1.37", gateway = "192.168.1.1", ssid = "Home", mac = "a4:12:34:56:78:9a")
    private val boot = 0L
    private val settled = 10 * 60_000L     // well after the boot grace

    private fun judge(r: LinkReading, since: Long? = null, now: Long = settled, p: PinnedNetwork? = pinned, bootAt: Long = boot) =
        IpVerdict.judge(p, r, since, bootAt, now)

    @Test fun nothingPinned() = assertEquals(IpState.NotPinned, judge(good, p = null))
    @Test fun sameAddressIsMatch() = assertEquals(IpState.Match, judge(good))

    @Test fun differentAddressIsDrift() =
        assertEquals(IpState.Drift(DriftReason.AddressChanged), judge(good.copy(address = "192.168.1.52")))

    @Test fun differentGatewayIsDrift() =
        assertEquals(IpState.Drift(DriftReason.GatewayChanged), judge(good.copy(gateway = "192.168.0.1")))

    @Test fun addressWinsWhenBothChange() =
        assertEquals(IpState.Drift(DriftReason.AddressChanged), judge(good.copy(address = "10.0.0.5", gateway = "10.0.0.1")))

    @Test fun otherSsidIsOtherNetworkEvenWithAnotherAddress() =
        assertEquals(IpState.OtherNetwork, judge(good.copy(ssid = "Cafe", address = "10.0.0.5")))

    @Test fun unknownSsidComparesAddressAndGateway() {
        assertEquals(IpState.Match, judge(good.copy(ssid = null)))
        assertEquals(IpState.Drift(DriftReason.AddressChanged), judge(good.copy(ssid = null, address = "10.0.0.5")))
    }

    @Test fun macDifferenceDoesNotChangeTheState() = assertEquals(IpState.Match, judge(good.copy(mac = "aa:bb:cc:dd:ee:ff")))

    // ---- no Wi-Fi: the 1-minute wait ----

    @Test fun lostWifiWaitsOneMinute() {
        val since = settled
        val s = judge(LinkReading(wifi = false), since = since, now = since + 59_999) as IpState.NoWifi
        assertFalse(s.due); assertEquals(since + 60_000, s.dueAtMs)
        assertTrue((judge(LinkReading(wifi = false), since = since, now = since + 60_000) as IpState.NoWifi).due)
    }

    @Test fun firstSightOfNoWifiStartsTheWaitNow() {
        val s = judge(LinkReading(wifi = false), since = null, now = settled) as IpState.NoWifi
        assertFalse(s.due); assertEquals(settled + 60_000, s.dueAtMs)
    }

    @Test fun wifiWithoutAnAddressCountsAsNoWifi() =
        assertTrue(judge(LinkReading(wifi = true, address = null, ssid = "Home"), since = settled, now = settled) is IpState.NoWifi)

    @Test fun noWifiWinsOverTheSsidRule() =
        assertTrue(judge(LinkReading(wifi = false, ssid = "Cafe"), since = settled, now = settled) is IpState.NoWifi)

    // ---- boot grace ----

    @Test fun lossInsideTheBootGraceIsCountedFromTheEndOfTheGrace() {
        val s = judge(LinkReading(wifi = false), since = 1_000, now = 2 * 60_000, bootAt = 0) as IpState.NoWifi
        assertEquals(3 * 60_000L + 60_000L, s.dueAtMs)      // 4 minutes after boot
        assertFalse(s.due)
    }

    @Test fun stillNoWifiFourMinutesAfterBootIsDue() {
        val s = judge(LinkReading(wifi = false), since = 1_000, now = 4 * 60_000, bootAt = 0) as IpState.NoWifi
        assertTrue(s.due)
    }

    @Test fun lossAfterTheGraceIsCountedFromTheLoss() {
        val since = 5 * 60_000L
        assertEquals(since + 60_000, (judge(LinkReading(wifi = false), since = since, now = since, bootAt = 0) as IpState.NoWifi).dueAtMs)
    }

    @Test fun driftIsNotDelayedByTheBootGrace() =
        assertEquals(IpState.Drift(DriftReason.AddressChanged), judge(good.copy(address = "10.0.0.5"), now = 30_000, bootAt = 0))

    // ---- MAC warning ----

    @Test fun macChangedOnlyWhenBothKnownAndDifferent() {
        assertFalse(IpVerdict.macChanged(pinned, good))
        assertTrue(IpVerdict.macChanged(pinned, good.copy(mac = "aa:bb:cc:dd:ee:ff")))
        assertFalse(IpVerdict.macChanged(pinned, good.copy(mac = null)))
        assertFalse(IpVerdict.macChanged(pinned.copy(mac = ""), good))
        assertFalse(IpVerdict.macChanged(null, good))
    }

    @Test fun macCompareIgnoresCase() = assertFalse(IpVerdict.macChanged(pinned, good.copy(mac = "A4:12:34:56:78:9A")))
}
