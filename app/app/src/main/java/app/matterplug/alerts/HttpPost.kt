package app.matterplug.alerts

import app.matterplug.alerts.logic.DeliveryError
import app.matterplug.alerts.logic.PostResult
import app.matterplug.alerts.logic.authorizationHeader
import app.matterplug.alerts.logic.resultForCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One JSON POST with a hard time limit. It knows nothing of alerts: what to send, when and how often are decided by the callers. */
object HttpPost {
    /**
     * POST [body] to [address], Content-Type application/json; charset=utf-8, no redirects (so a token is never forwarded).
     * If [token] is not blank, the header `Authorization: Bearer <token trimmed>` is set, otherwise there is no Authorization
     * header. A response of 401 or 403 gives error "rejected". The token is never logged and never put in an error.
     * [timeoutMs] is the connect timeout, the read timeout and, by a watchdog that closes the connection, the cap on the
     * whole request. The response body is read and discarded. Never throws except for coroutine cancellation.
     */
    suspend fun post(address: String, token: String, body: String, timeoutMs: Int = 10_000): PostResult = withContext(Dispatchers.IO) {
        val t = timeoutMs.coerceAtLeast(1)
        val connRef = AtomicReference<HttpURLConnection?>(null)
        val timedOut = AtomicBoolean(false)
        val watchdog = launch {
            delay(t.toLong())
            timedOut.set(true)
            connRef.get()?.disconnect()      // aborts a blocked connect, write or read
        }
        try {
            val conn = URI(address.trim()).toURL().openConnection() as HttpURLConnection
            connRef.set(conn)
            conn.requestMethod = "POST"
            conn.connectTimeout = t
            conn.readTimeout = t
            conn.instanceFollowRedirects = false
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            authorizationHeader(token)?.let { conn.setRequestProperty("Authorization", it) }
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            try {
                val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
                stream?.use { drain(it) }
            } catch (e: java.io.IOException) {
                // the status code is what counts; a broken body does not undo it
            }
            resultForCode(code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PostResult(null, if (timedOut.get()) DeliveryError.TIMEOUT else DeliveryError.of(e))
        } finally {
            watchdog.cancel()
            connRef.get()?.disconnect()
        }
    }

    private fun drain(s: InputStream) {
        val buf = ByteArray(2048)
        var total = 0
        while (total < 65_536) {
            val n = s.read(buf)
            if (n < 0) return
            total += n
        }
    }
}
