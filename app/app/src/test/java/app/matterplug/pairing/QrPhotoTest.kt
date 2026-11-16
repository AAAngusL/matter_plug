package app.matterplug.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrPhotoTest {
    private val code = "MT:Y.K9042C00KA0648G00"

    /** A photo of [w] x [h] in [paper] with a QR code of [code] drawn in [ink], [size] px wide at ([x], [y]). */
    private fun photo(w: Int, h: Int, size: Int, x: Int, y: Int, ink: Int = BLACK, paper: Int = WHITE): IntArray {
        val pixels = IntArray(w * h) { paper }
        val matrix = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, size, size)
        for (r in 0 until size) for (c in 0 until size) {
            if (matrix[c, r]) pixels[(y + r) * w + (x + c)] = ink
        }
        return pixels
    }

    @Test fun readsACodePrintedInColour() {
        assertEquals(code, QrPhoto.decode(photo(400, 400, 300, 50, 50, ink = 0xFF006400.toInt(), paper = 0xFFFFF0B4.toInt()), 400, 400))
        assertEquals(code, QrPhoto.decode(photo(400, 400, 300, 50, 50, ink = 0xFF000096.toInt(), paper = 0xFFFFE696.toInt()), 400, 400))
    }

    @Test fun emptyPhotoGivesNull() {
        assertNull(QrPhoto.decode(IntArray(0), 0, 500))
        assertNull(QrPhoto.decode(IntArray(0), 500, 0))
    }

    @Test fun readsAScreenshotOfTheCode() {
        assertEquals(code, QrPhoto.decode(photo(400, 400, 300, 50, 50), 400, 400))
    }

    @Test fun readsASmallCodeInALargePhoto() {
        assertEquals(code, QrPhoto.decode(photo(3000, 4000, 400, 1900, 2800), 3000, 4000))
    }

    @Test fun photoWithoutACodeGivesNull() {
        assertNull(QrPhoto.decode(IntArray(500 * 500) { WHITE }, 500, 500))
    }

    @Test fun mismatchedSizeGivesNull() {
        assertNull(QrPhoto.decode(IntArray(10) { WHITE }, 500, 500))
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}
