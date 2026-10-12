package io.github.ammarqaisar11a55.relaysms.feature.pairing

import android.Manifest
import io.github.ammarqaisar11a55.relaysms.MainActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.github.ammarqaisar11a55.relaysms.feature.home.hasPermission
import io.github.ammarqaisar11a55.relaysms.feature.home.openAppSettings
import io.github.ammarqaisar11a55.relaysms.ui.components.FullWidthButton
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Renders [text] as a QR code. Always black on white, whatever the theme, so cameras can read it. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 3),
        )
    }
    Canvas(
        modifier.aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color.White)
            .semantics { contentDescription = "Pairing QR code" },
    ) {
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/** Blocks screenshots and screen recording while a screen with pairing secrets is visible, whatever the setting. */
@Composable
fun SecureWindow() {
    val activity = LocalContext.current as? MainActivity
    DisposableEffect(activity) {
        activity?.windowSecurity?.acquire()
        onDispose { activity?.windowSecurity?.release() }
    }
}

/**
 * Camera preview that reports the first QR code it reads. Asks for the camera permission itself
 * and explains what to do if it is refused. Frames are analysed on-device and never stored.
 */
@Composable
fun QrScanner(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPermission(context, Manifest.permission.CAMERA)) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    if (!granted) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "RelaySMS needs the camera only to read the pairing code shown on your companion phone.",
                style = MaterialTheme.typography.bodyMedium,
            )
            FullWidthButton("Allow camera", onClick = { launcher.launch(Manifest.permission.CAMERA) })
            if (asked) {
                Text(
                    "Camera access was not granted. You can allow it in App info, or paste the pairing text instead.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                FullWidthButton("Open App info", onClick = { openAppSettings(context) }, outlined = true)
            }
        }
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult by rememberUpdatedState(onResult)
    val executor = remember { Executors.newSingleThreadExecutor() }
    val delivered = remember { AtomicBoolean(false) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }
    AndroidView(
        modifier = modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = "Camera preview for scanning the pairing code" },
        factory = { ctx ->
            PreviewView(ctx).also { view ->
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(executor) { image ->
                        val text = decodeQr(image)
                        if (text != null && delivered.compareAndSet(false, true)) {
                            ContextCompat.getMainExecutor(ctx).execute { currentOnResult(text) }
                        }
                    }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }
                }, ContextCompat.getMainExecutor(ctx))
            }
        },
    )
}

private val qrReader = QRCodeReader()
private val decodeHints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

private fun decodeQr(image: ImageProxy): String? = image.use {
    try {
        val plane = it.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val width = it.width
        val height = it.height
        val data = ByteArray(width * height)
        for (row in 0 until height) {
            buffer.position(row * rowStride)
            buffer.get(data, row * width, width)
        }
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        qrReader.decode(BinaryBitmap(HybridBinarizer(source)), decodeHints).text
    } catch (e: ReaderException) {
        null
    } catch (e: RuntimeException) {
        null
    } finally {
        qrReader.reset()
    }
}
