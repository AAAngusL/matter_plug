package app.matterplug.alerts.logic

import java.io.EOFException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URISyntaxException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Result of one HTTP request. [code] is null when no response arrived; then [error] is a [DeliveryError] token. */
data class PostResult(val code: Int?, val error: String?) {
    val delivered: Boolean get() = code != null && code in 200..299
}

/**
 * Error tokens: short machine-readable strings stored in DataStore (`last_delivery_error`) and mapped to text with
 * string resources by the UI, so no user-visible text is written in Kotlin.
 */
object DeliveryError {
    const val NO_CONNECTION = "no_connection"
    const val TIMEOUT = "timeout"
    const val BAD_ADDRESS = "bad_address"
    const val TLS = "tls"
    /** The receiver answered 401 or 403: the token is missing or wrong. */
    const val REJECTED = "rejected"
    private const val OTHER = "other:"
    private const val HTTP = "http:"

    sealed interface Parsed {
        data object NoConnection : Parsed
        data object Timeout : Parsed
        data object BadAddress : Parsed
        data object Tls : Parsed
        data object Rejected : Parsed
        data class Http(val code: Int) : Parsed
        data class Other(val name: String) : Parsed
    }

    /** The token for an exception thrown while making the request. */
    fun of(t: Throwable): String = when (t) {
        is SocketTimeoutException -> TIMEOUT
        is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketException -> NO_CONNECTION
        is MalformedURLException, is URISyntaxException, is IllegalArgumentException -> BAD_ADDRESS
        is SSLException -> TLS
        is EOFException -> OTHER + "EOFException"
        else -> OTHER + (t.javaClass.simpleName.ifBlank { "error" })
    }

    /** The token to store for a request that was not delivered (a non-2xx response or an error). */
    fun forFailure(r: PostResult): String = when {
        r.error == REJECTED -> REJECTED
        r.code != null -> HTTP + r.code
        else -> r.error ?: (OTHER + "unknown")
    }

    fun parse(token: String): Parsed = when {
        token == NO_CONNECTION -> Parsed.NoConnection
        token == TIMEOUT -> Parsed.Timeout
        token == BAD_ADDRESS -> Parsed.BadAddress
        token == TLS -> Parsed.Tls
        token == REJECTED -> Parsed.Rejected
        token.startsWith(HTTP) && token.removePrefix(HTTP).toIntOrNull() != null -> Parsed.Http(token.removePrefix(HTTP).toInt())
        token.startsWith(OTHER) -> Parsed.Other(token.removePrefix(OTHER))
        else -> Parsed.Other(token)
    }
}

/** What the test button shows. */
sealed interface TestOutcome {
    /** Status code 200: shown as "200 OK". */
    data object Ok : TestOutcome
    /** Any other response: shown as the status code. */
    data class Http(val code: Int) : TestOutcome
    data class Failed(val error: DeliveryError.Parsed) : TestOutcome
}

/** Pure decisions of HttpAlerter.deliver (BATTERY-GUARD-IMPLEMENTATION.md 5.6). HttpAlerter only does the I/O. */
object DeliveryPlan {
    const val MAX_TIMEOUT_MS = 10_000L
    /** Below this a request is not started: a timeout of 0 would mean "no timeout" to HttpURLConnection. */
    const val MIN_TIMEOUT_MS = 200L
    /** The waiting list never grows past this; the oldest entries are dropped first. */
    const val MAX_QUEUE = 100

    /** Connect and read timeout for the next request: min(10 s, what is left of the budget); null when too little is left. */
    fun timeoutFor(remainingMs: Long): Int? =
        if (remainingMs < MIN_TIMEOUT_MS) null else minOf(remainingMs, MAX_TIMEOUT_MS).toInt()

    /** Nothing at all can be sent: no valid address, or neither alerts nor a status report. */
    fun nothingToSend(addressValid: Boolean, alertsOn: Boolean, statusOn: Boolean): Boolean =
        !addressValid || (!alertsOn && !statusOn)

    /** The events that go on the waiting list: Started and Recovered, only when alerts are on. Reminders are not kept. */
    fun toEnqueue(events: List<AlertEvent>, alertsOn: Boolean): List<AlertEvent> =
        if (!alertsOn) emptyList() else events.filter { it.phase == AlertPhase.Started || it.phase == AlertPhase.Recovered }

    /**
     * The requests sent once and never kept, in order: each reminder (only when alerts are on), then the status report
     * (only when status reports are on). The caller sends them only if the waiting list is empty after the flush.
     */
    fun extras(events: List<AlertEvent>, status: AlertEvent?, alertsOn: Boolean, statusOn: Boolean): List<AlertEvent> {
        val out = mutableListOf<AlertEvent>()
        if (alertsOn) out += events.filter { it.phase == AlertPhase.Reminder }
        if (statusOn && status != null) out += status
        return out
    }

    /** [queue] plus [add] at the end, trimmed to [MAX_QUEUE] by dropping the oldest. */
    fun appendCapped(queue: List<String>, add: List<String>): List<String> = (queue + add).takeLast(MAX_QUEUE)

    /** [queue] without its first entry, but only if that entry is [sent] (another run may have changed the list). */
    fun withoutHead(queue: List<String>, sent: String): List<String> =
        if (queue.firstOrNull() == sent) queue.drop(1) else queue

    fun describeTest(r: PostResult): TestOutcome = when {
        r.error == DeliveryError.REJECTED || r.code == 401 || r.code == 403 -> TestOutcome.Failed(DeliveryError.Parsed.Rejected)
        r.code == 200 -> TestOutcome.Ok
        r.code != null -> TestOutcome.Http(r.code)
        else -> TestOutcome.Failed(DeliveryError.parse(r.error ?: DeliveryError.NO_CONNECTION))
    }
}
