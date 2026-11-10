package ec.edu.espoch.omniflow.vision.detector

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import ec.edu.espoch.omniflow.vision.BBox
import ec.edu.espoch.omniflow.vision.Detection

/**
 * Deteccion de personas con una red neuronal convolucional (EfficientDet-Lite0, entrenada en COCO)
 * ejecutada en el telefono con MediaPipe Tasks / TensorFlow Lite. Solo se conserva la clase "person".
 *
 * El umbral de score es bajo a proposito: ByteTrack aprovecha las detecciones de baja confianza
 * en su segunda asociacion.
 */
class MediaPipePersonDetector(
    context: Context,
    useGpu: Boolean = false,
    minScore: Float = 0.2f,
    maxResults: Int = 40,
) : PersonDetector {

    override val name: String = "EfficientDet-Lite0 (${if (useGpu) "GPU" else "CPU"})"

    private val detector: ObjectDetector = run {
        val base = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .setDelegate(if (useGpu) Delegate.GPU else Delegate.CPU)
            .build()
        val options = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setScoreThreshold(minScore)
            .setMaxResults(maxResults)
            .setCategoryAllowlist(listOf("person"))
            .build()
        ObjectDetector.createFromOptions(context, options)
    }

    override fun detect(frame: Bitmap): List<Detection> {
        val w = frame.width.toFloat()
        val h = frame.height.toFloat()
        val result = try {
            detector.detect(BitmapImageBuilder(frame).build())
        } catch (e: Exception) {
            Log.w(TAG, "Fallo la inferencia", e)
            return emptyList()
        }
        return result.detections().mapNotNull { d ->
            val cat = d.categories().firstOrNull() ?: return@mapNotNull null
            val r = d.boundingBox()
            Detection(
                box = BBox(
                    (r.left / w).coerceIn(0f, 1f),
                    (r.top / h).coerceIn(0f, 1f),
                    (r.right / w).coerceIn(0f, 1f),
                    (r.bottom / h).coerceIn(0f, 1f),
                ),
                score = cat.score(),
                label = cat.categoryName(),
            )
        }
    }

    override fun close() = detector.close()

    companion object {
        const val MODEL_ASSET = "efficientdet_lite0.tflite"
        private const val TAG = "PersonDetector"
    }
}
