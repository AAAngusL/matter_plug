package app.matterplug.matter

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.matterplug.data.Plug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

sealed interface SwitchResult {
    data object Confirmed : SwitchResult
    data class NotReached(val reason: String) : SwitchResult      // connect or command failed or timed out
    data object Unconfirmed : SwitchResult                        // command accepted, read-back differs or failed
}

/**
 * One switch, then release everything (BATTERY-GUARD-IMPLEMENTATION.md 5.4). Connect, send On or Off (never Toggle),
 * read OnOff back, release the pointer. It does not subscribe and is not tied to a screen. The connection comes from [PlugSession].
 *
 * Timeouts use withTimeoutOrNull: a step that runs out of time gives NotReached("timeout ..."), while a cancellation of
 * the caller (for example the service's own 120 s limit) is never caught here and propagates.
 */
class PlugCommander(context: Context) {
    private val context: Context = context.applicationContext

    suspend fun switchTo(plug: Plug, on: Boolean): SwitchResult =
        PlugSession.use(context, plug.nodeId, CONNECT_TIMEOUT_MS, onNotConnected = { SwitchResult.NotReached(it) }) { pointer ->
            // 1. Send On or Off.
            val sentOrNull: Result<Unit>? = try {
                withTimeoutOrNull(INVOKE_TIMEOUT_MS) { sendOnOff(plug, pointer, on) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "command failed", e)
                return@use SwitchResult.NotReached("command failed: ${e.message ?: e.javaClass.simpleName}")
            }
            if (sentOrNull == null) return@use SwitchResult.NotReached("timeout sending command")
            if (sentOrNull.isFailure) {
                val e = sentOrNull.exceptionOrNull()
                Log.w(TAG, "command failed", e)
                return@use SwitchResult.NotReached("command failed: ${e?.message ?: e?.javaClass?.simpleName ?: "unknown"}")
            }

            // 2. Read OnOff back and compare. A failed or timed-out read is Unconfirmed, not NotReached: the command was accepted.
            val actual: Boolean? = try {
                withTimeoutOrNull(READ_TIMEOUT_MS) { readOnOffAt(plug, pointer) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "read-back failed", e)
                null
            }
            Log.i(TAG, "requested=${if (on) "on" else "off"} readBack=${actual ?: "unknown"}")
            if (actual == on) SwitchResult.Confirmed else SwitchResult.Unconfirmed
        }

    /**
     * Reads the plug's OnOff state alone (connect, read, release), for the "turn it on before disabling?" question.
     * One 8-second limit covers everything. A failed first try (for example a remembered session to an address the plug
     * no longer has after a power cycle) is released and tried once more with what is left of the 8 seconds; the first
     * try gets at most [FIRST_TRY_MS], so the second always has time. Null on any failure or timeout. Does not touch [switchTo].
     * A cancellation of the caller propagates.
     */
    suspend fun readOnOff(plug: Plug): Boolean? {
        val start = SystemClock.elapsedRealtime()
        for (attempt in 1..2) {
            val left = READ_ONLY_TIMEOUT_MS - (SystemClock.elapsedRealtime() - start)
            if (left <= 0) return null
            val limit = if (attempt == 1) minOf(left, FIRST_TRY_MS) else left
            val on = readOnOffOnce(plug, limit)
            if (on != null) {
                if (attempt == 2) Log.i(TAG, "readOnOff: second try worked after ${SystemClock.elapsedRealtime() - start} ms")
                return on
            }
        }
        return null
    }

    /** One connect, read and release within [limitMs]. A pointer that arrives after the timeout is lost, as in ChipController.connect (V3 note in G4). */
    private suspend fun readOnOffOnce(plug: Plug, limitMs: Long): Boolean? =
        try {
            withTimeoutOrNull(limitMs) {
                PlugSession.use(context, plug.nodeId, onNotConnected = { null }) { pointer -> readOnOffAt(plug, pointer) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "readOnOff try failed", e)
            null
        }

    private suspend fun sendOnOff(plug: Plug, pointer: Long, on: Boolean): Result<Unit> = OnOff.send(context, pointer, plug.endpoint, on)

    private suspend fun readOnOffAt(plug: Plug, pointer: Long): Boolean? = OnOff.read(context, pointer, plug.endpoint)

    private companion object {
        const val TAG = "PlugCommander"
        const val CONNECT_TIMEOUT_MS = 25_000L
        const val INVOKE_TIMEOUT_MS = 10_000L
        const val READ_TIMEOUT_MS = 10_000L
        const val READ_ONLY_TIMEOUT_MS = 8_000L
        const val FIRST_TRY_MS = 4_000L
    }
}
