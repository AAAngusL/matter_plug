package app.matterplug.alerts.ui

import android.app.Application
import android.util.Log
import app.matterplug.alerts.AlertChannel
import app.matterplug.alerts.AlertOutbox
import app.matterplug.alerts.logic.AlertAddress
import app.matterplug.alerts.logic.DeliveryPlan
import app.matterplug.alerts.logic.TestOutcome
import app.matterplug.battery.BatteryReader
import app.matterplug.battery.Power
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What is shown next to the "Send test alert" button. */
sealed interface TestState {
    data object Idle : TestState
    data object Sending : TestState
    data class Done(val outcome: TestOutcome) : TestState
}

/**
 * The alert server's address and token as a screen edits them, and the test button. The text is kept here so typing is not interrupted by the
 * round trip through DataStore; it is saved whatever it is, and the switches that need a valid address ask [requireValidAddress].
 * [needsAddress] says whether something already depends on the address (a "send" switch that is on).
 */
class AlertEndpointEditor(private val app: Application, private val scope: CoroutineScope, needsAddress: Flow<Boolean>) {
    private val outbox = AlertOutbox(app)

    private val _addressText = MutableStateFlow("")
    val addressText: StateFlow<String> = _addressText

    /** The token field's text, trimmed. Never logged. */
    private val _tokenText = MutableStateFlow("")
    val tokenText: StateFlow<String> = _tokenText

    /** The result of the last press of "Send test alert". Cleared when the address or token is edited. */
    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState

    private var flushJob: Job? = null
    private var addressTouched = false
    private var tokenTouched = false
    private val refusedAddress = MutableStateFlow(false)

    /** True when the address is invalid and something needs it (a refused switch, or a "send" switch already on). */
    val addressError: StateFlow<Boolean> = combine(needsAddress, _addressText, refusedAddress) { needed, text, refused ->
        !AlertAddress.isValid(text) && (refused || needed)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        scope.launch {
            val saved = outbox.readEndpoint()
            if (!addressTouched) _addressText.value = saved.address
            if (!tokenTouched) _tokenText.value = saved.token
        }
    }

    /**
     * The text is saved whatever it is. When it is valid and requests are waiting, the flush job is started after the typing has paused for
     * 1.5 s, so a half-typed address ("http://192.168.1.2") is not tried.
     */
    fun setAddress(text: String) {
        addressTouched = true
        _addressText.value = text
        refusedAddress.value = false
        _testState.value = TestState.Idle
        scope.launch { outbox.updateEndpoint { it.copy(address = text) } }
        flushJob?.cancel()
        if (AlertAddress.isValid(text)) {
            flushJob = scope.launch {
                delay(ADDRESS_SETTLE_MS)
                if (outbox.readEndpoint().address == text) AlertChannel.flushIfWaiting(app)
            }
        }
    }

    /** The token is trimmed (whitespace and newlines) and saved; the next request reads it. It does not start a flush by itself. */
    fun setToken(text: String) {
        val t = text.trim()
        tokenTouched = true
        _tokenText.value = t
        _testState.value = TestState.Idle
        scope.launch { outbox.updateEndpoint { it.copy(token = t) } }
    }

    /** For a switch that needs the address: true when it is valid; otherwise the field is marked as the reason. */
    fun requireValidAddress(): Boolean {
        val ok = AlertAddress.isValid(_addressText.value)
        refusedAddress.value = !ok
        return ok
    }

    /**
     * Posts a test request to the address in the field; it needs a valid address only, so it works with "Send alerts" off. The request carries
     * the battery as it reads now, else what [lastKnown] gives.
     */
    fun sendTest(lastKnown: () -> Pair<Int, Power>) {
        if (_testState.value == TestState.Sending) return
        if (!AlertAddress.isValid(_addressText.value)) { refusedAddress.value = true; return }
        _testState.value = TestState.Sending
        scope.launch {
            try {
                // What was typed has been saved by setAddress and setToken; make sure of it before the request reads it.
                outbox.updateEndpoint { it.copy(address = _addressText.value, token = _tokenText.value) }
                val reading = BatteryReader.readOrNull(app)
                val (level, power) = if (reading != null) reading.level to reading.power else lastKnown()
                _testState.value = TestState.Done(DeliveryPlan.describeTest(AlertChannel.sendTest(app, level, power)))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e("Alerts", "test alert failed", e)
                _testState.value = TestState.Idle
            }
        }
    }

    private companion object {
        const val ADDRESS_SETTLE_MS = 1_500L
    }
}
