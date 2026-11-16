package app.matterplug.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.Binarizer
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Reads a QR code from a saved photo or screenshot, given as ARGB pixels.
 * A phone photo is tried at a few reduced sizes: ZXing finds a code faster and more reliably when it
 * is not thousands of pixels wide. Pure Kotlin, so it is tested on the JVM.
 */
object QrPhoto {
    /** Longest side of each attempt, largest first. A photo smaller than a step is tried at its own size. */
    private val SIDES = listOf(2048, 1024, 512)

    private val HINTS = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true
    )

    /** The QR code's text, or null when the photo holds no readable QR code. */
    fun decode(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || pixels.size != width * height) return null
        val longest = maxOf(width, height)
        for (side in SIDES.map { minOf(it, longest) }.distinct()) {
            val source = luminance(pixels, width, height, side)
            for (binarizer in listOf<(LuminanceSource) -> Binarizer>(::HybridBinarizer, ::GlobalHistogramBinarizer)) {
                read(BinaryBitmap(binarizer(source)))?.let { return it }
            }
        }
        return null
    }

    private fun read(bitmap: BinaryBitmap): String? = try {
        QRCodeReader().decode(bitmap, HINTS).text
    } catch (e: Exception) {
        // NotFoundException, ChecksumException, FormatException: no readable code at this size.
        null
    }

    /** Grey levels of the photo, reduced by sampling so that its longest side is at most [side]. */
    private fun luminance(pixels: IntArray, width: Int, height: Int, side: Int): LuminanceSource {
        val step = maxOf(width, height).toDouble() / side  // >= 1: side is never above the longest side
        val w = (width / step).toInt().coerceAtLeast(1)
        val h = (height / step).toInt().coerceAtLeast(1)
        val grey = ByteArray(w * h)
        for (y in 0 until h) {
            val row = (y * step).toInt() * width
            for (x in 0 until w) {
                val p = pixels[row + (x * step).toInt()]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                grey[y * w + x] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
            }
        }
        return PlanarYUVLuminanceSource(grey, w, h, 0, 0, w, h, false)
    }
}
