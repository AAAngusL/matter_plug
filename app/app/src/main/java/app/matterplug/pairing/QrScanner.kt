package app.matterplug.pairing

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Camera preview that decodes QR codes with ZXing (no Google ML Kit, no Play services).
 * [onCode] is called on the main thread with the decoded text, at most once every 1.5 seconds.
 */
@Composable
fun QrScanner(onCode: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)

    val previewView = remember { PreviewView(context) }
    AndroidView(modifier = modifier, factory = { previewView })

    DisposableEffect(lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false

        providerFuture.addListener({
            if (disposed) return@addListener
            val cameraProvider = providerFuture.get()
            provider = cameraProvider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val reader = QRCodeReader()
            var lastEmit = 0L
            analysis.setAnalyzer(analysisExecutor) { image ->
                val text = decode(reader, image)
                image.close()
                if (text != null) {
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 1_500) {
                        lastEmit = now
                        mainExecutor.execute { currentOnCode(text) }
                    }
                }
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "could not start the camera", e)
            }
        }, mainExecutor)

        onDispose {
            disposed = true
            provider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }
}

private const val TAG = "QrScanner"

/** Longest side a picked photo is decoded at: enough for a QR code, and keeps a 12 MP photo small in memory. */
private const val PHOTO_SIDE = 2048

/**
 * Reads a QR code from a photo the user picked (a content [uri] from the photo picker).
 * Null when the photo cannot be opened or holds no readable QR code.
 */
suspend fun readQrFromPhoto(context: Context, uri: Uri): String? = withContext(Dispatchers.Default) {
    try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val sample = maxOf(info.size.width, info.size.height) / PHOTO_SIDE
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        QrPhoto.decode(pixels, bitmap.width, bitmap.height).also { bitmap.recycle() }
    } catch (e: Exception) {
        Log.w(TAG, "could not read the photo", e)
        null
    }
}

/** Decodes one camera frame from its luminance (Y) plane. QR detection works at any rotation. */
private fun decode(reader: QRCodeReader, image: ImageProxy): String? {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val rowStride = plane.rowStride
    val rows = bytes.size / rowStride
    val source = PlanarYUVLuminanceSource(
        bytes, rowStride, rows, 0, 0, minOf(image.width, rowStride), minOf(image.height, rows), false
    )
    return try {
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true
        )
        reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (e: Exception) {
        // NotFoundException, ChecksumException, FormatException: no readable code in this frame.
        null
    } finally {
        reader.reset()
    }
}
