package app.matterplug.alerts.logic

import app.matterplug.cellular.logic.CellularSummary
import app.matterplug.cellular.logic.Layer
import app.matterplug.cellular.logic.LoopbackTrigger
import app.matterplug.fixedip.logic.DriftReason
import app.matterplug.fixedip.logic.IpSummary
import app.matterplug.sms.SmsOutcome
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import app.matterplug.battery.BatteryStatus
import app.matterplug.battery.Power

/**
 * The bytes the alert server receives, and the texts of the SMS and notifications, for one event of every kind and phase. The golden file
 * was written from the code before the alert types were reorganised; a refactor must not change a byte of it (docs/ALERT-API.md).
 * To write it again on purpose: WRITE_GOLDEN=1 ./gradlew testDebugUnitTest --tests '*WireGoldenTest'.
 */
class WireGoldenTest {
    private val charging = Power(true, BatteryStatus.Charging)
    private val onBattery = Power(false, BatteryStatus.Discharging)
    private val cellular = CellularSummary(LoopbackTrigger.Manual, null, null, Layer.entries.associateWith { "pass" }, null, "LTE")
    private val ipFull = IpSummary("192.168.1.37", "192.168.1.52", "Home", DriftReason.AddressChanged, SmsOutcome.Sent)
    private val ipNoCurrent = IpSummary("192.168.1.37", null, "Home", DriftReason.NoWifi, SmsOutcome.Failed)

    private fun e(kind: AlertKind, phase: AlertPhase, power: Power = charging, wantedOn: Boolean? = null, detail: String? = null,
              next: Int? = null, cell: CellularSummary? = null, by: CancelledBy? = null, ip: IpSummary? = null) =
        AlertEvent(kind, phase, 42, power, wantedOn, 1_795_000_000_000L, detail, next, by, cell ?: ip)

    private fun matrix(): List<Pair<String, AlertEvent>> = buildList {
        for (p in listOf(AlertPhase.Started, AlertPhase.Reminder, AlertPhase.Recovered)) {
            add("plug_unreachable $p low" to e(AlertKind.PlugUnreachable, p, onBattery, true))
            add("plug_unreachable $p high" to e(AlertKind.PlugUnreachable, p, charging, false))
            add("plug_unreachable $p unknown" to e(AlertKind.PlugUnreachable, p, onBattery, null))
            add("not_charging $p" to e(AlertKind.NotCharging, p, Power(true, BatteryStatus.NotCharging)))
            add("guard_degraded $p" to e(AlertKind.GuardDegraded, p, detail = "service could not start"))
            add("cellular_down $p" to e(AlertKind.CellularDown, p, cell = cellular))
            for ((n, ip) in listOf("full" to ipFull, "nocurrent" to ipNoCurrent)) add("ip_drift $p $n" to e(AlertKind.IpDrift, p, ip = ip))
        }
        for (by in CancelledBy.entries) {
            add("cancelled ${by.wire} plug" to e(AlertKind.PlugUnreachable, AlertPhase.Cancelled, by = by))
            add("cancelled ${by.wire} cellular" to e(AlertKind.CellularDown, AlertPhase.Cancelled, by = by))
            add("cancelled ${by.wire} cellular summary" to e(AlertKind.CellularDown, AlertPhase.Cancelled, cell = cellular, by = by))
            add("cancelled ${by.wire} ip" to e(AlertKind.IpDrift, AlertPhase.Cancelled, by = by))
        }
        add("status" to e(AlertKind.Status, AlertPhase.Status, next = 5))
        add("status on battery" to e(AlertKind.Status, AlertPhase.Status, onBattery, next = 15))
        add("cellular_status" to e(AlertKind.CellularStatus, AlertPhase.Status, next = 5, cell = cellular))
        add("test" to e(AlertKind.Test, AlertPhase.Test))
    }

    private fun render(): String = matrix().joinToString("\n") { (name, ev) ->
        val json = AlertJson.encode(ev, AlertMessages.text(ev), AlertJson.isoTime(ev.atMs, java.time.ZoneOffset.UTC))
        "$name\n  json: $json\n  text: ${AlertMessages.text(ev)}"
    } + "\n"

    private val file = File("src/test/resources/wire-golden.txt")

    @Test fun writeGoldenOnPurpose() {
        assumeTrue(System.getenv("WRITE_GOLDEN") != null)
        file.writeText(render())
    }

    @Test fun everyRequestAndTextIsUnchanged() {
        assumeTrue(System.getenv("WRITE_GOLDEN") == null)
        assertEquals(file.readText(), render())
    }
}
