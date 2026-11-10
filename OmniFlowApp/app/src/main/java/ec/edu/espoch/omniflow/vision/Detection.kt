package ec.edu.espoch.omniflow.vision

import kotlin.math.max
import kotlin.math.min

/** Caja delimitadora en coordenadas normalizadas [0,1] respecto al frame. */
data class BBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f

    fun iou(other: BBox): Float {
        val ix = (min(right, other.right) - max(left, other.left)).coerceAtLeast(0f)
        val iy = (min(bottom, other.bottom) - max(top, other.top)).coerceAtLeast(0f)
        val inter = ix * iy
        val union = area + other.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    companion object {
        /** Construye desde centro (cx, cy), relacion de aspecto a = w/h y alto h (formato del filtro de Kalman). */
        fun fromCxCyAH(cx: Double, cy: Double, a: Double, h: Double): BBox {
            val w = a * h
            return BBox((cx - w / 2).toFloat(), (cy - h / 2).toFloat(), (cx + w / 2).toFloat(), (cy + h / 2).toFloat())
        }
    }
}

data class Detection(val box: BBox, val score: Float, val label: String = "person")

enum class TrackState { NEW, TRACKED, LOST, REMOVED }

/** Persona seguida entre frames (salida del tracker). */
data class TrackedPerson(
    val id: Int,
    val box: BBox,
    val score: Float,
    val state: TrackState,
    val ageFrames: Int,
)

/** Resultado de procesar un frame completo con el pipeline de vision. */
data class FrameResult(
    val frameWidth: Int,
    val frameHeight: Int,
    val detections: List<Detection>,
    val tracks: List<TrackedPerson>,
    /** Personas visibles y confirmadas en este frame. */
    val peopleCount: Int,
    val entries: Int,
    val exits: Int,
    val inferenceMs: Long,
    val fps: Float,
    val timestamp: Long,
)
