package app.matterplug.cellular.logic

import java.util.Base64

/**
 * Stores the results of a run as text: one line per layer, `L,<layer>,<kind>,<text>`, where kind is p (pass), f (fail),
 * s (skipped: text is the layer that failed) or n (not due), and text is URL-safe Base64 so commas and newlines are safe.
 * A damaged line is dropped; the others are kept.
 */
object CellularCodec {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    fun encode(results: Map<Layer, LayerResult>): String = results.entries.joinToString("\n") { (layer, r) ->
        val (kind, text) = when (r) {
            is LayerResult.Pass -> "p" to r.detail
            is LayerResult.Fail -> "f" to r.reason
            is LayerResult.Skipped -> "s" to r.because.wire
            LayerResult.NotDue -> "n" to ""
        }
        "L,${layer.wire},$kind,${enc.encodeToString(text.toByteArray(Charsets.UTF_8))}"
    }

    fun decode(text: String?): Map<Layer, LayerResult> {
        if (text.isNullOrEmpty()) return emptyMap()
        val out = linkedMapOf<Layer, LayerResult>()
        for (line in text.split('\n')) {
            try {
                val f = line.split(',')
                if (f.size != 4 || f[0] != "L") continue
                val layer = byWire(f[1]) ?: continue
                val value = String(dec.decode(f[3]), Charsets.UTF_8)
                out[layer] = when (f[2]) {
                    "p" -> LayerResult.Pass(value)
                    "f" -> LayerResult.Fail(value)
                    "s" -> LayerResult.Skipped(byWire(value) ?: continue)
                    "n" -> LayerResult.NotDue
                    else -> continue
                }
            } catch (e: IllegalArgumentException) {      // bad Base64
                continue
            }
        }
        return out
    }

    private fun byWire(w: String): Layer? = Layer.entries.firstOrNull { it.wire == w }
}
