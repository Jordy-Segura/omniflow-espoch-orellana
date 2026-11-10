package ec.edu.espoch.omniflow.vision.tracking

import ec.edu.espoch.omniflow.vision.BBox
import ec.edu.espoch.omniflow.vision.Detection
import ec.edu.espoch.omniflow.vision.TrackState
import ec.edu.espoch.omniflow.vision.TrackedPerson

/**
 * Implementacion en Kotlin del algoritmo ByteTrack (Zhang et al., ECCV 2022) para seguimiento
 * multi-objeto de personas:
 *
 *  1. Prediccion de cada track con un filtro de Kalman (velocidad constante).
 *  2. Primera asociacion: tracks (activos + perdidos) vs detecciones de ALTA confianza.
 *     Costo = 1 - IoU * score, resuelto con el algoritmo Hungaro.
 *  3. Segunda asociacion: tracks activos sin pareja vs detecciones de BAJA confianza (solo IoU).
 *     Asi se recuperan personas parcialmente ocluidas en lugar de descartarlas (la idea clave de "BYTE").
 *  4. Tracks nuevos sin confirmar vs detecciones altas restantes; los que no encuentran pareja se eliminan.
 *  5. Creacion de tracks nuevos y eliminacion de los perdidos por mas de [trackBuffer] frames.
 */
class ByteTracker(
    private val highThresh: Float = 0.5f,
    private val lowThresh: Float = 0.2f,
    private val newTrackThresh: Float = 0.55f,
    private val matchThresh: Float = 0.8f,
    private val trackBuffer: Int = 30,
) {
    private class Track(box: BBox, var score: Float, val id: Int, val startFrame: Int) {
        val kf = KalmanBoxFilter(box)
        var box: BBox = box
        var state = TrackState.NEW
        var activated = false
        var lastFrame = startFrame

        fun predict() {
            kf.predict()
            box = kf.box
        }

        fun update(det: Detection, frame: Int) {
            kf.update(det.box)
            box = kf.box
            score = det.score
            state = TrackState.TRACKED
            activated = true
            lastFrame = frame
        }
    }

    private var frameId = 0
    private var nextId = 1
    private val tracks = mutableListOf<Track>()

    fun reset() {
        frameId = 0
        nextId = 1
        tracks.clear()
    }

    fun update(detections: List<Detection>): List<TrackedPerson> {
        frameId++
        val high = detections.filter { it.score >= highThresh }
        val low = detections.filter { it.score >= lowThresh && it.score < highThresh }

        val unconfirmed = tracks.filter { !it.activated }
        val pool = tracks.filter { it.activated } // TRACKED + LOST
        tracks.forEach { it.predict() }

        // 1) Alta confianza
        val m1 = associate(pool, high, matchThresh, fuseScore = true)
        m1.matches.forEach { (t, d) -> pool[t].update(high[d], frameId) }

        // 2) Baja confianza, solo para los que estaban siendo seguidos en el frame anterior
        val remain = m1.unmatchedTracks.map { pool[it] }.filter { it.state == TrackState.TRACKED }
        val m2 = associate(remain, low, 0.5f, fuseScore = false)
        m2.matches.forEach { (t, d) -> remain[t].update(low[d], frameId) }
        m2.unmatchedTracks.forEach { remain[it].state = TrackState.LOST }

        // 3) Tracks sin confirmar vs detecciones altas restantes
        val remainHigh = m1.unmatchedDets.map { high[it] }
        val m3 = associate(unconfirmed, remainHigh, 0.7f, fuseScore = true)
        m3.matches.forEach { (t, d) -> unconfirmed[t].update(remainHigh[d], frameId) }
        m3.unmatchedTracks.forEach { unconfirmed[it].state = TrackState.REMOVED }

        // 4) Nuevos tracks
        val born = m3.unmatchedDets.map { remainHigh[it] }
            .filter { it.score >= newTrackThresh }
            .map { det ->
                Track(det.box, det.score, nextId++, frameId).apply {
                    if (frameId == 1) { activated = true; state = TrackState.TRACKED }
                }
            }

        // 5) Limpieza
        tracks.filter { it.state == TrackState.LOST && frameId - it.lastFrame > trackBuffer }
            .forEach { it.state = TrackState.REMOVED }
        tracks.removeAll { it.state == TrackState.REMOVED }
        tracks += born
        removeDuplicates()

        return tracks.map { TrackedPerson(it.id, it.box, it.score, it.state, frameId - it.startFrame + 1) }
    }

    /** Si un track activo y uno perdido se solapan mucho, se conserva el mas antiguo. */
    private fun removeDuplicates() {
        val active = tracks.filter { it.state == TrackState.TRACKED }
        val lost = tracks.filter { it.state == TrackState.LOST }
        val drop = mutableSetOf<Track>()
        for (a in active) for (b in lost) {
            if (a.box.iou(b.box) > 0.85f) drop += if (a.startFrame <= b.startFrame) b else a
        }
        tracks.removeAll(drop)
    }

    private data class Assoc(
        val matches: List<Pair<Int, Int>>,
        val unmatchedTracks: List<Int>,
        val unmatchedDets: List<Int>,
    )

    private fun associate(ts: List<Track>, dets: List<Detection>, thresh: Float, fuseScore: Boolean): Assoc {
        if (ts.isEmpty() || dets.isEmpty()) return Assoc(emptyList(), ts.indices.toList(), dets.indices.toList())
        val cost = Array(ts.size) { i ->
            DoubleArray(dets.size) { j ->
                val iou = ts[i].box.iou(dets[j].box).toDouble()
                1.0 - if (fuseScore) iou * dets[j].score else iou
            }
        }
        val assignment = Hungarian.assign(cost, thresh.toDouble())
        val matches = mutableListOf<Pair<Int, Int>>()
        val used = HashSet<Int>()
        assignment.forEachIndexed { t, d -> if (d >= 0) { matches += t to d; used += d } }
        return Assoc(matches, ts.indices.filter { assignment[it] < 0 }, dets.indices.filter { it !in used })
    }
}
