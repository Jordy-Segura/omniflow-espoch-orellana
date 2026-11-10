package ec.edu.espoch.omniflow.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import ec.edu.espoch.omniflow.vision.detector.MediaPipePersonDetector
import ec.edu.espoch.omniflow.vision.detector.PersonDetector
import ec.edu.espoch.omniflow.vision.tracking.ByteTracker
import ec.edu.espoch.omniflow.vision.tracking.LineCrossingCounter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pipeline de vision computacional: frame -> deteccion (CNN) -> seguimiento (ByteTrack) -> conteo.
 * No es thread-safe: el llamador debe procesar un frame a la vez (lo hace MonitorViewModel).
 */
@Singleton
class VisionEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var detector: PersonDetector? = null
    private var tracker = ByteTracker()
    val counter = LineCrossingCounter()
    private var lastTs = 0L
    private var fpsEma = 0f

    val detectorName: String get() = detector?.name ?: "Sin cargar"

    @Synchronized
    fun ensureDetector(useGpu: Boolean): PersonDetector {
        val current = detector
        if (current != null && current.name.contains(if (useGpu) "GPU" else "CPU")) return current
        current?.close()
        return try {
            MediaPipePersonDetector(context, useGpu = useGpu)
        } catch (e: Throwable) {
            // Algunos telefonos no soportan el delegado GPU: se vuelve a CPU.
            if (useGpu) MediaPipePersonDetector(context, useGpu = false) else throw e
        }.also { detector = it }
    }

    fun reset() {
        tracker.reset()
        counter.reset()
        lastTs = 0L
        fpsEma = 0f
    }

    // Sincronizado con ensureDetector/release: nunca se cierra el detector en medio de una inferencia (crash nativo).
    @Synchronized
    fun process(frame: Bitmap, useGpu: Boolean, minConfidence: Float): FrameResult {
        val det = ensureDetector(useGpu)
        val t0 = SystemClock.elapsedRealtime()
        val detections = det.detect(frame).filter { it.score >= minConfidence.coerceAtMost(0.2f) }
        val inference = SystemClock.elapsedRealtime() - t0

        val tracks = tracker.update(detections)
        counter.update(tracks)

        val now = SystemClock.elapsedRealtime()
        if (lastTs > 0) {
            val inst = 1000f / (now - lastTs).coerceAtLeast(1)
            fpsEma = if (fpsEma == 0f) inst else fpsEma * 0.85f + inst * 0.15f
        }
        lastTs = now

        // Persona contada = track confirmado y visible en este frame con confianza suficiente.
        val people = tracks.count { it.state == TrackState.TRACKED && it.score >= minConfidence * 0.5f }
        return FrameResult(
            frameWidth = frame.width,
            frameHeight = frame.height,
            detections = detections,
            tracks = tracks,
            peopleCount = people,
            entries = counter.entries,
            exits = counter.exits,
            inferenceMs = inference,
            fps = fpsEma,
            timestamp = System.currentTimeMillis(),
        )
    }

    @Synchronized
    fun release() {
        detector?.close()
        detector = null
    }
}
