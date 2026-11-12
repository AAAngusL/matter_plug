package app.matterplug.guard.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {
    private fun min(m: Int) = m * 60_000L

    private fun step(
        st: AlertState, events: List<CheckEvent>, t: Int, battery: Int = 18, plugged: Boolean = false,
    ) = AlertPolicy.apply(st, events, battery, plugged, min(t))

    private fun List<AlertEvent>.phases() = map { it.kind to it.phase }
    private val fail = CheckEvent.SwitchFailed(true)

    // 1
    @Test fun scenario1_twoFailuresStartTheAlertThirdIsSilent() {
        val r1 = step(AlertState(), listOf(fail), 0)
        assertEquals(1, r1.state.failedChecks)
        assertTrue(r1.events.isEmpty())
        val r2 = step(r1.state, listOf(fail), 15)
        assertEquals(2, r2.state.failedChecks)
        assertEquals(1, r2.events.size)
        assertEquals(AlertKind.PlugUnreachable, r2.events[0].kind)
        assertEquals(AlertPhase.Started, r2.events[0].phase)
        assertEquals(true, r2.events[0].wantedOn)
        val r3 = step(r2.state, listOf(fail), 30)
        assertTrue(r3.events.isEmpty())
    }

    // 2
    @Test fun scenario2_reminderEveryHour() {
        var r = step(AlertState(), listOf(fail), -15)
        r = step(r.state, listOf(fail), 0)
        assertEquals(AlertPhase.Started, r.events.single().phase)
        r = step(r.state, listOf(fail), 59)
        assertTrue(r.events.isEmpty())
        r = step(r.state, listOf(fail), 60)
        assertEquals(listOf(AlertKind.PlugUnreachable to AlertPhase.Reminder), r.events.phases())
        assertEquals(min(60), r.state.active.single().lastSentAtMs)
        r = step(r.state, listOf(fail), 75)
        assertTrue(r.events.isEmpty())
        r = step(r.state, listOf(fail), 120)
        assertEquals(listOf(AlertKind.PlugUnreachable to AlertPhase.Reminder), r.events.phases())
    }

    private fun unreachableActive(): AlertState {
        var r = step(AlertState(), listOf(fail), 0)
        r = step(r.state, listOf(fail), 1)
        return r.state
    }

    // 3
    @Test fun scenario3_recoveryByConfirmedSwitch() {
        val r = step(unreachableActive(), listOf(CheckEvent.SwitchConfirmed(true)), 10, plugged = true)
        assertEquals(listOf(AlertKind.PlugUnreachable to AlertPhase.Recovered), r.events.phases())
        assertTrue(r.state.active.isEmpty())
        assertEquals(0, r.state.failedChecks)
    }

    // 4
    @Test fun scenario4_recoveryByNoSwitchNeeded() {
        val r = step(unreachableActive(), listOf(CheckEvent.NoSwitchNeeded), 10, battery = 40)
        assertEquals(listOf(AlertKind.PlugUnreachable to AlertPhase.Recovered), r.events.phases())
        assertTrue(r.state.active.isEmpty())
        assertEquals(0, r.state.failedChecks)
    }

    // 5
    @Test fun scenario5_failureNoneFailureDoesNotStart() {
        val r = step(AlertState(), listOf(fail, CheckEvent.NoSwitchNeeded, fail), 0)
        assertEquals(1, r.state.failedChecks)
        assertTrue(r.events.isEmpty())
        assertTrue(r.state.active.isEmpty())
    }

    private val notCharging = listOf(CheckEvent.SwitchConfirmed(true), CheckEvent.PowerWait(false))

    // 6
    @Test fun scenario6_notChargingStartsOnceThenReminds() {
        var r = step(AlertState(), notCharging, 0)
        assertEquals(listOf(AlertKind.NotCharging to AlertPhase.Started), r.events.phases())
        r = step(r.state, notCharging, 15)
        assertTrue(r.events.isEmpty())
        r = step(r.state, notCharging, 60)
        assertEquals(listOf(AlertKind.NotCharging to AlertPhase.Reminder), r.events.phases())
    }

    // 7
    @Test fun scenario7_notChargingRecoversWhenPluggedIn() {
        var r = step(AlertState(), notCharging, 0)
        r = step(r.state, listOf(CheckEvent.NoSwitchNeeded), 15, battery = 25, plugged = true)
        val e = r.events.single()
        assertEquals(AlertKind.NotCharging, e.kind)
        assertEquals(AlertPhase.Recovered, e.phase)
        assertEquals(true, e.pluggedIn)
        assertTrue(r.state.active.isEmpty())
    }

    // 8
    @Test fun scenario8_powerWaitPluggedGivesNoEvents() {
        val r = step(AlertState(), listOf(CheckEvent.SwitchConfirmed(true), CheckEvent.PowerWait(true)), 0, plugged = true)
        assertTrue(r.events.isEmpty())
        assertTrue(r.state.active.isEmpty())
    }

    // 9
    @Test fun scenario9_twoAlertsIndependent() {
        // Start both: NotCharging at t=0, PlugUnreachable via two failed checks.
        var r = step(AlertState(), notCharging, 0)
        r = step(r.state, listOf(fail), 1)
        r = step(r.state, listOf(fail), 2)
        assertEquals(setOf(AlertKind.NotCharging, AlertKind.PlugUnreachable), r.state.active.map { it.kind }.toSet())
        // Reminders: NotCharging due at 60, PlugUnreachable due at 62.
        r = step(r.state, listOf(fail), 60)
        assertEquals(listOf(AlertKind.NotCharging to AlertPhase.Reminder), r.events.phases())
        r = step(r.state, listOf(fail), 62)
        assertEquals(listOf(AlertKind.PlugUnreachable to AlertPhase.Reminder), r.events.phases())
        // PlugUnreachable recovers, NotCharging stays.
        r = step(r.state, listOf(CheckEvent.SwitchConfirmed(false)), 63)
        assertEquals(listOf(AlertKind.PlugUnreachable to AlertPhase.Recovered), r.events.phases())
        assertEquals(listOf(AlertKind.NotCharging), r.state.active.map { it.kind })
        // NotCharging recovers later.
        r = step(r.state, listOf(CheckEvent.NoSwitchNeeded), 70, plugged = true)
        assertEquals(listOf(AlertKind.NotCharging to AlertPhase.Recovered), r.events.phases())
        assertTrue(r.state.active.isEmpty())
    }

    // 10
    @Test fun scenario10_wantedOnFalseIsKept() {
        val failOff = CheckEvent.SwitchFailed(false)
        var r = step(AlertState(), listOf(failOff), 0, battery = 87, plugged = true)
        r = step(r.state, listOf(failOff), 15, battery = 87, plugged = true)
        val started = r.events.single()
        assertEquals(false, started.wantedOn)
        assertEquals(false, r.state.active.single().wantedOn)
        assertTrue(AlertMessages.text(started).endsWith("to turn it off."))
        r = step(r.state, listOf(failOff), 75, battery = 88, plugged = true)
        assertEquals(AlertPhase.Reminder, r.events.single().phase)
        assertEquals(false, r.events.single().wantedOn)
        r = step(r.state, listOf(CheckEvent.SwitchConfirmed(false)), 80, battery = 88, plugged = true)
        assertEquals(AlertPhase.Recovered, r.events.single().phase)
        assertEquals(false, r.events.single().wantedOn)
    }

    // 11
    @Test fun scenario11_restartKeepsBehaviour() {
        fun run(roundTrip: Boolean): List<List<AlertEvent>> {
            val results = mutableListOf<List<AlertEvent>>()
            var st = AlertState()
            val script = listOf(
                0 to notCharging, 1 to listOf(fail), 2 to listOf(fail),
                15 to notCharging, 61 to listOf(fail), 62 to notCharging, 130 to listOf(CheckEvent.NoSwitchNeeded),
            )
            for ((t, ev) in script) {
                if (roundTrip) st = AlertStateCodec.decode(AlertStateCodec.encode(st))
                val r = step(st, ev, t)
                results += r.events
                st = r.state
            }
            return results
        }
        val plain = run(false)
        assertEquals(plain, run(true))
        assertEquals(1, plain.flatten().count { it.kind == AlertKind.NotCharging && it.phase == AlertPhase.Started })
        assertEquals(1, plain.flatten().count { it.kind == AlertKind.PlugUnreachable && it.phase == AlertPhase.Started })
    }

    // 12
    @Test fun scenario12_noSettingsParameter() {
        val m = AlertPolicy::class.java.methods.single { it.name == "apply" }
        assertTrue(m.parameterTypes.none { it == GuardSettings::class.java })
        assertEquals(5, m.parameterCount)
    }

    private fun degraded(r: String) = listOf(CheckEvent.NoSwitchNeeded, CheckEvent.Degraded(r))

    // 13
    @Test fun scenario13_degradedAlert() {
        var r = step(AlertState(), degraded("x"), 0)
        assertEquals(1, r.state.degradedChecks)
        assertTrue(r.events.isEmpty())
        r = step(r.state, degraded("x"), 15)
        assertEquals(listOf(AlertKind.GuardDegraded to AlertPhase.Started), r.events.phases())
        assertEquals("x", r.events.single().detail)
        r = step(r.state, degraded("x"), 30)
        assertTrue(r.events.isEmpty())
        r = step(r.state, degraded("x"), 75)
        assertEquals(listOf(AlertKind.GuardDegraded to AlertPhase.Reminder), r.events.phases())
        assertEquals("x", r.events.single().detail)
    }

    // 14
    @Test fun scenario14_degradedRecovers() {
        var r = step(AlertState(), degraded("x"), 0)
        r = step(r.state, degraded("x"), 15)
        r = step(r.state, listOf(CheckEvent.NoSwitchNeeded), 30)
        assertEquals(listOf(AlertKind.GuardDegraded to AlertPhase.Recovered), r.events.phases())
        assertEquals("x", r.events.single().detail)
        assertEquals(0, r.state.degradedChecks)
        assertTrue(r.state.active.isEmpty())
    }

    // 15
    @Test fun scenario15_degradedNoneDegradedDoesNotStart() {
        var r = step(AlertState(), degraded("x"), 0)
        r = step(r.state, listOf(CheckEvent.NoSwitchNeeded), 15)
        r = step(r.state, degraded("x"), 30)
        assertEquals(1, r.state.degradedChecks)
        assertTrue(r.events.isEmpty())
        assertTrue(r.state.active.isEmpty())
    }

    // 16
    @Test fun scenario16_degradedOnlyLeavesFailedChecksUnchanged() {
        val st = AlertState(failedChecks = 1)
        val r = step(st, listOf(CheckEvent.Degraded("battery unreadable")), 0)
        assertEquals(1, r.state.failedChecks)
        assertEquals(1, r.state.degradedChecks)
    }

    // 17
    @Test fun scenario17_threeAlertsIndependent() {
        var r = step(AlertState(), notCharging, 0)                       // NotCharging at 0
        r = step(r.state, listOf(fail) + CheckEvent.Degraded("d"), 1)    // counts 1
        r = step(r.state, listOf(fail) + CheckEvent.Degraded("d"), 2)    // both start at 2
        assertEquals(
            setOf(AlertKind.NotCharging, AlertKind.PlugUnreachable, AlertKind.GuardDegraded),
            r.state.active.map { it.kind }.toSet())
        val keep = listOf(fail) + CheckEvent.Degraded("d")
        r = step(r.state, keep, 60)
        assertEquals(listOf(AlertKind.NotCharging to AlertPhase.Reminder), r.events.phases())
        r = step(r.state, keep, 62)
        assertEquals(
            setOf(AlertKind.PlugUnreachable to AlertPhase.Reminder, AlertKind.GuardDegraded to AlertPhase.Reminder),
            r.events.phases().toSet())
        // Degraded recovers alone.
        r = step(r.state, listOf(fail), 63)
        assertEquals(listOf(AlertKind.GuardDegraded to AlertPhase.Recovered), r.events.phases())
        // PlugUnreachable recovers alone.
        r = step(r.state, listOf(CheckEvent.NoSwitchNeeded), 64)
        assertEquals(listOf(AlertKind.PlugUnreachable to AlertPhase.Recovered), r.events.phases())
        assertEquals(listOf(AlertKind.NotCharging), r.state.active.map { it.kind })
        // NotCharging recovers alone.
        r = step(r.state, listOf(CheckEvent.NoSwitchNeeded), 65, plugged = true)
        assertEquals(listOf(AlertKind.NotCharging to AlertPhase.Recovered), r.events.phases())
        assertTrue(r.state.active.isEmpty())
    }
}
