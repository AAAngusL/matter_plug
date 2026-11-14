package app.matterplug.cellular.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CellularVerdictTest {
    private val healthy = PassiveReading(
        simReady = true, simDetail = "ready",
        registered = true, roaming = false, registrationDetail = "LTE",
        signalLevel = 3, signalDetail = "LTE, RSRP -97 dBm",
    )

    @Test fun healthyPhonePassesEveryPassiveLayer() {
        val r = CellularVerdict.passive(healthy)
        assertEquals(LayerResult.Pass("ready"), r[Layer.Sim])
        assertEquals(LayerResult.Pass("LTE, home"), r[Layer.Registration])
        assertEquals(LayerResult.Pass("LTE, RSRP -97 dBm"), r[Layer.Signal])
        assertNull(CellularVerdict.firstFailed(r))
    }

    @Test fun missingSimIsOneFailureAndTheRestAreSkipped() {
        val r = CellularVerdict.passive(healthy.copy(simReady = false, simDetail = "SIM absent", registered = false, signalLevel = null))
        assertEquals(LayerResult.Fail("SIM absent"), r[Layer.Sim])
        assertEquals(LayerResult.Skipped(Layer.Sim), r[Layer.Registration])
        assertEquals(LayerResult.Skipped(Layer.Sim), r[Layer.Signal])
        assertEquals(Layer.Sim, CellularVerdict.firstFailed(r))
    }

    @Test fun notRegisteredFailsRegistrationAndSkipsSignal() {
        val r = CellularVerdict.passive(healthy.copy(registered = false, registrationDetail = "out of service"))
        assertEquals(LayerResult.Pass("ready"), r[Layer.Sim])
        assertEquals(LayerResult.Fail("out of service"), r[Layer.Registration])
        assertEquals(LayerResult.Skipped(Layer.Registration), r[Layer.Signal])
        assertEquals(Layer.Registration, CellularVerdict.firstFailed(r))
    }

    @Test fun roamingIsAPassButSaysSo() =
        assertEquals(LayerResult.Pass("LTE, roaming"), CellularVerdict.passive(healthy.copy(roaming = true, registrationDetail = "LTE"))[Layer.Registration])

    @Test fun zeroBarsFailsSignal() =
        assertEquals(LayerResult.Fail("no signal bars"), CellularVerdict.passive(healthy.copy(signalLevel = 0))[Layer.Signal])

    @Test fun oneBarPassesSignal() =
        assertEquals(LayerResult.Pass("LTE, RSRP -97 dBm"), CellularVerdict.passive(healthy.copy(signalLevel = 1))[Layer.Signal])

    @Test fun unreadableSignalFails() =
        assertEquals(LayerResult.Fail("signal unreadable"), CellularVerdict.passive(healthy.copy(signalLevel = null))[Layer.Signal])

    @Test fun signalDetailShowsOnlyTheValuesAndroidGave() {
        assertEquals("LTE, RSRP -97 dBm, RSRQ -11 dB, SINR 8 dB", SignalDetail.format("LTE", rsrp = -97, rsrq = -11, sinr = 8))
        assertEquals("NR, RSRP -101 dBm", SignalDetail.format("NR", rsrp = -101, rsrq = null, sinr = null))
        assertEquals("level 2", SignalDetail.format(null, null, null, null, level = 2))
    }

    @Test fun logLineNamesEveryLayer() = assertEquals(
        "cellular: sim=pass (ready) registration=fail (out of service) signal=skipped (registration failed)",
        CellularLog.line(CellularVerdict.passive(healthy.copy(registered = false, registrationDetail = "out of service"))),
    )
}
