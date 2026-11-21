package app.matterplug.guard.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertPolicyTest {
    private fun min(m: Int) = m * 60_000L

    private fun step(
        st: AlertState, events: List<CheckEvent>, t: Int, battery: Int = 18, plugged: Boolean = false,
        status: BatteryStatus = BatteryStatus.Unknown,
    ) = AlertPolicy.apply(st, events, battery, Power(plugged, status), min(t))

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

    // 4 (PLUG-WATCH.md section 4): a check with no switch needed did not reach the plug, so it is not a recovery
    @Test fun scenario4_noSwitchNeededDoesNotRecover() {
        val r = step(unreachableActive(), listOf(CheckEvent.NoSwitchNeeded), 10, battery = 40)
        assertTrue(r.events.isEmpty())
        assertEquals(listOf(AlertKind.PlugUnreachable), r.state.active.map { it.kind })
        assertEquals(2, r.state.failedChecks)
    }

    // 5
    @Test fun scenario5_failureReachedFailureDoesNotStart() {
        val r = step(AlertState(), listOf(fail, reached, fail), 0)
        assertEquals(1, r.state.failedChecks)
        assertTrue(r.events.isEmpty())
        assertTrue(r.state.active.isEmpty())
    }

    private val reached = CheckEvent.PlugRead(true)
    private val missed = CheckEvent.PlugRead(false)

    // P1 (PLUG-WATCH.md section 4): two missed reads start the alert, with no wanted switch
    @Test fun plugRead_twoMissesStartTheAlert() {
        val r1 = step(AlertState(), listOf(CheckEvent.NoSwitchNeeded, missed), 0, battery = 72)
        assertTrue(r1.events.isEmpty())
        assertEquals(1, r1.state.failedChecks)
        val r2 = step(r1.state, listOf(CheckEvent.NoSwitchNeeded, missed), 5, battery = 72)
        val e = r2.events.single()
        assertEquals(AlertKind.PlugUnreachable to AlertPhase.Started, e.kind to e.phase)
        assertEquals(null, e.wantedOn)
        assertEquals(null, r2.state.active.single().wantedOn)
        assertEquals("Matter Plug: cannot reach charger plug. Battery 72%.", AlertMessages.text(e))
    }

    // No charger plug (unpaired or removed): nothing to reach, so an active alert ends and the count resets
    @Test fun noPlug_recoversTheAlert() {
        val r = step(unreachableActive(), listOf(CheckEvent.NoSwitchNeeded, CheckEvent.NoPlug), 10, battery = 40)
        assertEquals(listOf(AlertKind.PlugUnreachable to AlertPhase.Recovered), r.events.phases())
        assertTrue(r.state.active.isEmpty())
        assertEquals(0, r.state.failedChecks)
    }

    @Test fun plugRead_reachedRecovers() {
        var r = step(AlertState(), listOf(missed), 0)
        r = step(r.state, listOf(missed), 5)
        r = step(r.state, listOf(CheckEvent.NoSwitchNeeded, reached), 10, battery = 72)
        assertEquals(listOf(AlertKind.PlugUnreachable to AlertPhase.Recovered), r.events.phases())
        assertTrue(r.state.active.isEmpty())
        assertEquals(0, r.state.failedChecks)
    }

    @Test fun plugRead_aMissAndASwitchFailureShareTheCount() {
        var r = step(AlertState(), listOf(missed), 0, battery = 21)
        r = step(r.state, listOf(fail), 5, battery = 20)
        assertEquals(listOf(AlertKind.PlugUnreachable to AlertPhase.Started), r.events.phases())
        assertEquals(true, r.events.single().wantedOn)
    }

    @Test fun plugRead_oneAlertWhenAReadAlertMeetsASwitchFailure() {
        var r = step(AlertState(), listOf(missed), 0, battery = 72)
        r = step(r.state, listOf(missed), 5, battery = 72)
        r = step(r.state, listOf(fail), 10, battery = 20)
        assertTrue(r.events.isEmpty())
        assertEquals(1, r.state.active.size)
        assertEquals(3, r.state.failedChecks)
    }

    @Test fun plugRead_reachedResetsAFirstMiss() {
        var r = step(AlertState(), listOf(missed), 0)
        r = step(r.state, listOf(reached), 5)
        assertEquals(0, r.state.failedChecks)
        r = step(r.state, listOf(missed), 10)
        assertTrue(r.events.isEmpty())
        assertEquals(1, r.state.failedChecks)
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
        r = step(r.state, listOf(CheckEvent.NoSwitchNeeded), 15, battery = 25, plugged = true, status = BatteryStatus.Charging)
        val e = r.events.single()
        assertEquals(AlertKind.NotCharging, e.kind)
        assertEquals(AlertPhase.Recovered, e.phase)
        assertEquals(Power(true, BatteryStatus.Charging), e.power)
        assertTrue(r.state.active.isEmpty())
    }

    // RECOVERY-SIGNALS.md 2.1: plugged in is not enough; the battery must take charge.
    @Test fun notCharging_pluggedInButNotTakingChargeStaysAndReminds() {
        var r = step(AlertState(), notCharging, 0)
        for (s in listOf(BatteryStatus.NotCharging, BatteryStatus.Discharging, BatteryStatus.Unknown)) {
            r = step(r.state, listOf(CheckEvent.NoSwitchNeeded), 15, battery = 19, plugged = true, status = s)
            assertTrue("status $s", r.events.isEmpty())
            assertEquals(listOf(AlertKind.NotCharging), r.state.active.map { it.kind })
        }
        r = step(r.state, listOf(CheckEvent.NoSwitchNeeded), 60, battery = 18, plugged = true, status = BatteryStatus.NotCharging)
        assertEquals(listOf(AlertKind.NotCharging to AlertPhase.Reminder), r.events.phases())
    }

    @Test fun notCharging_fullEndsIt() {
        var r = step(AlertState(), notCharging, 0)
        r = step(r.state, listOf(CheckEvent.NoSwitchNeeded), 15, plugged = true, status = BatteryStatus.Full)
        assertEquals(listOf(AlertKind.NotCharging to AlertPhase.Recovered), r.events.phases())
    }

    @Test fun notCharging_chargingWhileUnpluggedDoesNotEndIt() {
        var r = step(AlertState(), notCharging, 0)
        r = step(r.state, listOf(CheckEvent.NoSwitchNeeded), 15, plugged = false, status = BatteryStatus.Charging)
        assertTrue(r.events.isEmpty())
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
        r = step(r.state, listOf(CheckEvent.NoSwitchNeeded), 70, plugged = true, status = BatteryStatus.Charging)
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
        assertTrue(AlertMessages.text(started).contains("to turn it off."))
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

    // RECOVERY-SIGNALS.md 2.2: only a service that starts ends "service could not start".
    private val svc = CheckDecision.FAULT_SERVICE

    @Test fun degraded_serviceAlertNotEndedByACheckThatDidNotTry() {
        var r = step(AlertState(), degraded(svc), 0)
        r = step(r.state, degraded(svc), 5)
        r = step(r.state, listOf(CheckEvent.NoSwitchNeeded), 10)
        assertTrue(r.events.isEmpty())
        assertEquals(listOf(AlertKind.GuardDegraded), r.state.active.map { it.kind })
        assertEquals(0, r.state.degradedChecks)
        r = step(r.state, listOf(CheckEvent.NoSwitchNeeded), 65)
        assertEquals(listOf(AlertKind.GuardDegraded to AlertPhase.Reminder), r.events.phases())
    }

    @Test fun degraded_serviceAlertEndsWhenTheServiceStarts() {
        var r = step(AlertState(), degraded(svc), 0)
        r = step(r.state, degraded(svc), 5)
        r = step(r.state, listOf(CheckEvent.ServiceStarted, CheckEvent.SwitchConfirmed(true), CheckEvent.PowerWait(true)), 10, plugged = true)
        assertEquals(listOf(AlertKind.GuardDegraded to AlertPhase.Recovered), r.events.phases())
        assertEquals(svc, r.events.single().detail)
    }

    @Test fun degraded_serviceStartedWithAnotherFaultKeepsTheAlertWithTheNewReason() {
        var r = step(AlertState(), degraded(svc), 0)
        r = step(r.state, degraded(svc), 5)
        r = step(r.state, listOf(CheckEvent.ServiceStarted, CheckEvent.Degraded("battery unreadable")), 10)
        assertTrue(r.events.isEmpty())
        assertEquals("battery unreadable", r.state.active.single().detail)
        r = step(r.state, listOf(CheckEvent.Degraded("battery unreadable")), 65)     // started at 5: reminder due at 65
        assertEquals("battery unreadable", r.events.single().detail)
        assertEquals(AlertPhase.Reminder, r.events.single().phase)
        // The battery is read again: that reason is proved gone, so the alert ends.
        r = step(r.state, listOf(CheckEvent.NoSwitchNeeded), 70)
        assertEquals(listOf(AlertKind.GuardDegraded to AlertPhase.Recovered), r.events.phases())
    }

    @Test fun degraded_otherReasonsEndAtTheFirstCheckWithoutThem() {
        for (reason in listOf("battery unreadable", "exact alarms not allowed")) {
            var r = step(AlertState(), degraded(reason), 0)
            r = step(r.state, degraded(reason), 5)
            r = step(r.state, listOf(CheckEvent.NoSwitchNeeded), 10)
            assertEquals(reason, listOf(AlertKind.GuardDegraded to AlertPhase.Recovered), r.events.phases())
        }
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
        // PlugUnreachable recovers alone, when the plug is reached.
        r = step(r.state, listOf(CheckEvent.NoSwitchNeeded, reached), 64)
        assertEquals(listOf(AlertKind.PlugUnreachable to AlertPhase.Recovered), r.events.phases())
        assertEquals(listOf(AlertKind.NotCharging), r.state.active.map { it.kind })
        // NotCharging recovers alone.
        r = step(r.state, listOf(CheckEvent.NoSwitchNeeded), 65, plugged = true, status = BatteryStatus.Charging)
        assertEquals(listOf(AlertKind.NotCharging to AlertPhase.Recovered), r.events.phases())
        assertTrue(r.state.active.isEmpty())
    }
}
