package app.matterplug.guard.logic

import java.util.Base64

private val B64_ENC = Base64.getUrlEncoder().withoutPadding()
private val B64_DEC = Base64.getUrlDecoder()

private fun b64(s: String): String = B64_ENC.encodeToString(s.toByteArray(Charsets.UTF_8))
private fun unb64(s: String): String = String(B64_DEC.decode(s), Charsets.UTF_8)

/**
 * Text format. First line `F,<failed>,<degraded>`; then one line per active alert
 * `A,<kindWire>,<startedAtMs>,<lastSentAtMs>,<1|0|->,<detail>` where detail is URL-safe Base64 without padding, or `-` for null.
 * (A one-character Base64 string cannot occur, so `-` never clashes with an encoded detail.)
 */
object AlertStateCodec {
    fun encode(s: AlertState): String {
        val sb = StringBuilder("F,${s.failedChecks},${s.degradedChecks}")
        for (a in s.active) {
            sb.append('\n').append("A,").append(a.kind.wire).append(',')
                .append(a.startedAtMs).append(',').append(a.lastSentAtMs).append(',')
                .append(when (a.wantedOn) { true -> "1"; false -> "0"; null -> "-" }).append(',')
                .append(a.detail?.let { b64(it) } ?: "-")
        }
        return sb.toString()
    }

    fun decode(text: String?): AlertState {
        if (text.isNullOrEmpty()) return AlertState()
        var failed = 0
        var degraded = 0
        val active = mutableListOf<ActiveAlert>()
        for (line in text.split('\n')) {
            try {
                val f = line.split(',')
                when (f[0]) {
                    "F" -> {
                        val a = f[1].toInt()
                        val b = if (f.size >= 3) f[2].toInt() else 0
                        if (a < 0 || b < 0) continue
                        failed = a
                        degraded = b
                    }
                    "A" -> {
                        if (f.size < 5) continue
                        val kind = AlertKind.values().firstOrNull { it.wire == f[1] } ?: continue
                        val wantedOn = when (f[4]) { "1" -> true; "0" -> false; "-" -> null; else -> continue }
                        val detail = if (f.size >= 6 && f[5] != "-") unb64(f[5]) else null
                        active += ActiveAlert(kind, f[2].toLong(), f[3].toLong(), wantedOn, detail)
                    }
                }
            } catch (e: Exception) {
                // malformed line: skip it
            }
        }
        return AlertState(failed, degraded, active)
    }
}

/** Each body is URL-safe Base64 without padding, entries joined by a newline, oldest first. */
object QueueCodec {
    fun encode(bodies: List<String>): String = bodies.joinToString("\n") { b64(it) }

    fun decode(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val out = mutableListOf<String>()
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            try { out += unb64(line) } catch (e: Exception) { /* skip a damaged entry */ }
        }
        return out
    }
}
