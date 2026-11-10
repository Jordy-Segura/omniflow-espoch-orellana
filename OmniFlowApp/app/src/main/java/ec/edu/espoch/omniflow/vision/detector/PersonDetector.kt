package ec.edu.espoch.omniflow.vision.detector

import android.graphics.Bitmap
import ec.edu.espoch.omniflow.vision.Detection

/**
 * Detector de personas. La implementacion actual corre on-device (EfficientDet-Lite0 con MediaPipe);
 * cuando el backend FastAPI tenga YOLOv8 + ByteTrack se puede agregar una implementacion remota
 * sin tocar el resto del pipeline.
 */
interface PersonDetector : AutoCloseable {
    /** Devuelve detecciones con cajas normalizadas [0,1]. Debe llamarse desde un hilo de fondo. */
    fun detect(frame: Bitmap): List<Detection>
    val name: String
}
