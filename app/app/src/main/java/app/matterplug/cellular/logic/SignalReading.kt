package app.matterplug.cellular.logic

/**
 * One sample of the serving LTE cell's signal (docs/SIGNAL-STRENGTH.md section 4). Each value is null when Android
 * gave none, or one outside the LTE ranges of 3GPP TS 36.133: nothing after the reader ever sees a sentinel.
 */
data class SignalReading(
    val level: Int?,        // 0..4, Android's bars for the LTE cell
    val rsrpDbm: Int?,      // CellSignalStrengthLte.rsrp
    val rsrqDb: Int?,       // CellSignalStrengthLte.rsrq
    val sinrDb: Int?,       // CellSignalStrengthLte.rssnr
    val at: Long,           // when it was read
) {
    companion object {
        private val LEVEL = 0..4
        private val RSRP = -140..-44
        private val RSRQ = -34..3
        private val SINR = -20..30

        /** From the raw CellSignalStrengthLte values; CellInfo.UNAVAILABLE (Int.MAX_VALUE) is outside every range. */
        fun lte(level: Int?, rsrp: Int?, rsrq: Int?, rssnr: Int?, at: Long): SignalReading = SignalReading(
            level = level?.takeIf { it in LEVEL },
            rsrpDbm = rsrp?.takeIf { it in RSRP },
            rsrqDb = rsrq?.takeIf { it in RSRQ },
            sinrDb = rssnr?.takeIf { it in SINR },
            at = at,
        )
    }
}

/** Stores a reading as `l=;p=;q=;s=;at=` (empty for a missing value). No `at`, or anything unreadable, gives no reading. */
object SignalReadingCodec {
    fun encode(r: SignalReading?): String =
        r?.let { "l=${it.level ?: ""};p=${it.rsrpDbm ?: ""};q=${it.rsrqDb ?: ""};s=${it.sinrDb ?: ""};at=${it.at}" } ?: ""

    fun decode(text: String?): SignalReading? {
        if (text.isNullOrEmpty()) return null
        val m = text.split(';').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val at = m["at"]?.toLongOrNull() ?: return null
        return SignalReading(m["l"]?.toIntOrNull(), m["p"]?.toIntOrNull(), m["q"]?.toIntOrNull(), m["s"]?.toIntOrNull(), at)
    }
}
