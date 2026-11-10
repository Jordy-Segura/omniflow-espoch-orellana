package ec.edu.espoch.omniflow.vision.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

/**
 * Conecta la camara del propio telefono (CameraX) y entrega frames como Bitmap ya rotados.
 * No dibuja nada: la UI muestra el frame procesado con las cajas superpuestas.
 *
 * [onFrame] se invoca en un hilo de fondo. Mientras [isBusy] devuelva true (el modelo sigue
 * procesando el frame anterior) los frames se descartan sin convertirlos, para ahorrar CPU.
 */
@Composable
fun PhoneCameraFeed(
    front: Boolean,
    highRes: Boolean = false,
    isBusy: () -> Boolean,
    onError: (String) -> Unit,
    onFrame: (Bitmap) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnFrame = rememberUpdatedState(onFrame)
    val currentOnError = rememberUpdatedState(onError)
    val currentIsBusy = rememberUpdatedState(isBusy)

    DisposableEffect(front, highRes, lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        // 1280x720 para reconocimiento facial (rostros lejanos); 640x480 basta para contar personas.
                        ResolutionStrategy(if (highRes) Size(1280, 720) else Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        analysis.setAnalyzer(executor) { image ->
            try {
                if (currentIsBusy.value()) return@setAnalyzer
                val raw = image.toBitmap()
                val rotation = image.imageInfo.rotationDegrees
                val frame = if (rotation != 0 || front) {
                    val m = Matrix().apply {
                        postRotate(rotation.toFloat())
                        if (front) postScale(-1f, 1f) // efecto espejo para la camara frontal
                    }
                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                } else raw
                currentOnFrame.value(frame)
            } catch (e: Exception) {
                currentOnError.value("Error procesando frame: ${e.message}")
            } finally {
                image.close()
            }
        }

        providerFuture.addListener({
            try {
                val p = providerFuture.get()
                provider = p
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!p.hasCamera(selector)) {
                    currentOnError.value("Este teléfono no tiene cámara ${if (front) "frontal" else "trasera"}")
                    return@addListener
                }
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, selector, analysis)
            } catch (e: Exception) {
                currentOnError.value("No se pudo abrir la cámara: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            analysis.clearAnalyzer()
            provider?.unbind(analysis)
            executor.shutdown()
        }
    }
}
