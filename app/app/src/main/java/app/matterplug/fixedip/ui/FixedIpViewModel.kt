package app.matterplug.fixedip.ui

import app.matterplug.guard.logic.CancelledBy
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.matterplug.fixedip.IpWatch
import app.matterplug.fixedip.IpReader
import app.matterplug.fixedip.IpRunner
import app.matterplug.fixedip.IpStore
import app.matterplug.fixedip.logic.FixedIpSettings
import app.matterplug.fixedip.logic.IpFormat
import app.matterplug.fixedip.logic.IpState
import app.matterplug.fixedip.logic.IpVerdict
import app.matterplug.fixedip.logic.LinkReading
import app.matterplug.fixedip.logic.PinPlan
import app.matterplug.sms.SmsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the screen shows after one read: the reading, its state and the MAC warning. */
data class IpScreenState(val reading: LinkReading, val state: IpState, val macChanged: Boolean, val readAtMs: Long)

/** Why Lock this address pinned nothing (docs/FIXED-IP.md sections 5 and 9). */
enum class LockProblem { PermissionDenied, NoWifi, NoAddress, NoSsid }

/** The Fixed IP screen (docs/FIXED-IP.md section 10): it reads on open and on **Check now**, and pins, unpins and keeps the numbers. */
class FixedIpViewModel(application: Application) : AndroidViewModel(application) {
    private val store = IpStore(application)

    val settings: StateFlow<FixedIpSettings?> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _screen = MutableStateFlow<IpScreenState?>(null)
    val screen: StateFlow<IpScreenState?> = _screen

    private val _problem = MutableStateFlow<LockProblem?>(null)
    val problem: StateFlow<LockProblem?> = _problem

    /** The guide of section 5 is open. */
    private val _guide = MutableStateFlow(false)
    val guide: StateFlow<Boolean> = _guide

    /** What is in the number fields, kept here so typing is not interrupted by the round trip through DataStore. */
    private val _smsText = MutableStateFlow("")
    val smsText: StateFlow<String> = _smsText
    private val _backupText = MutableStateFlow("")
    val backupText: StateFlow<String> = _backupText

    val lastSms: StateFlow<SmsResult.Last?> = store.lastSms
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val s = store.readSettings()
            _smsText.value = s.smsNumber
            _backupText.value = s.backupNumber
            if (s.enabled) IpWatch.enable(getApplication())      // opening the app starts the watch again if Android ended it
        }
        checkNow()
    }

    fun checkNow() {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh(): LinkReading {
        val reading = withContext(Dispatchers.IO) { IpReader.read(getApplication()) }
        val pinned = store.readSettings().pinned
        val now = System.currentTimeMillis()
        val bootAt = now - SystemClock.elapsedRealtime()
        // A single read has no memory of when Wi-Fi was lost; the watch of F4 keeps that. Here the wait simply starts now.
        val state = IpVerdict.judge(pinned, reading, noWifiSinceMs = null, bootAtMs = bootAt, nowMs = now)
        _screen.value = IpScreenState(reading, state, IpVerdict.macChanged(pinned, reading), now)
        return reading
    }

    /** Lock this address. [hasPermission]: the location permission is held (section 9); without it nothing is pinned. */
    fun lock(hasPermission: Boolean) {
        viewModelScope.launch {
            _problem.value = null
            if (!hasPermission) { _problem.value = LockProblem.PermissionDenied; return@launch }
            when (val plan = PinPlan.fromReading(refresh())) {
                is PinPlan.Ready -> { store.update { it.copy(pinned = plan.network) }; _guide.value = true; refresh() }
                PinPlan.NoWifi -> _problem.value = LockProblem.NoWifi
                PinPlan.NoAddress -> _problem.value = LockProblem.NoAddress
                PinPlan.NoSsid -> _problem.value = LockProblem.NoSsid
            }
        }
    }

    fun permissionDenied() { _problem.value = LockProblem.PermissionDenied }

    /**
     * The switch (docs/FIXED-IP.md section 6): on only with a pinned network and a valid number; the caller has asked for the SMS permission.
     * Off ends an open alert with cancelled (R4) and stops the watch.
     */
    fun setEnabled(on: Boolean) {
        viewModelScope.launch {
            val closed = store.update { if (on && !it.canEnable) it else it.copy(enabled = on) }
            val enabled = store.readSettings().enabled
            if (enabled) IpWatch.enable(getApplication()) else { IpWatch.disable(getApplication()); IpRunner.closed(getApplication(), closed) }
        }
    }

    /** Forgets the pinned network and switches Fixed IP off: to move to another network, unpin and pin again (decision 17). */
    fun unpin() {
        viewModelScope.launch {
            val closed = store.update { it.copy(pinned = null, enabled = false) }
            IpWatch.disable(getApplication())
            IpRunner.closed(getApplication(), closed, CancelledBy.IpUnpinned)
            _guide.value = false
            _problem.value = null
            refresh()
        }
    }

    fun showGuide() { _guide.value = true }
    fun hideGuide() { _guide.value = false }

    /** The MAC typed in the guide, kept only when it is a MAC: the app cannot read it on this Android (section 4). */
    fun setMac(text: String) {
        val mac = IpFormat.mac(text) ?: return
        viewModelScope.launch { store.update { s -> s.copy(pinned = s.pinned?.copy(mac = mac)) }; refresh() }
    }

    fun setSmsNumber(text: String) {
        _smsText.value = text
        viewModelScope.launch { store.update { it.copy(smsNumber = text) } }
    }

    fun setBackupNumber(text: String) {
        _backupText.value = text
        viewModelScope.launch {
            store.update { it.copy(backupNumber = text) }
            // A backup equal to the main number is stored as empty (section 6); show what was kept once typing is done.
            if (store.readSettings().backupNumber.isEmpty() && text.isNotEmpty() && FixedIpSettings.cleanNumber(text) == store.settings.first().smsNumber) {
                _backupText.value = ""
            }
        }
    }
}
