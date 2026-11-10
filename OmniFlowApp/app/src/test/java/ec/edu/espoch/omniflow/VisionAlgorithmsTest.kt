package ec.edu.espoch.omniflow

import ec.edu.espoch.omniflow.vision.BBox
import ec.edu.espoch.omniflow.vision.Detection
import ec.edu.espoch.omniflow.vision.TrackState
import ec.edu.espoch.omniflow.vision.tracking.ByteTracker
import ec.edu.espoch.omniflow.vision.tracking.Hungarian
import ec.edu.espoch.omniflow.vision.tracking.LineCrossingCounter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionAlgorithmsTest {

    @Test
    fun hungarian_findsMinimumCostAssignment() {
        val cost = arrayOf(
            doubleArrayOf(4.0, 1.0, 3.0),
            doubleArrayOf(2.0, 0.0, 5.0),
            doubleArrayOf(3.0, 2.0, 2.0),
        )
        // Optimo: (0->1)=1, (1->0)=2, (2->2)=2 => 5
        assertArrayEquals(intArrayOf(1, 0, 2), Hungarian.assign(cost))
    }

    @Test
    fun hungarian_rectangularAndThreshold() {
        val cost = arrayOf(doubleArrayOf(0.1, 0.9), doubleArrayOf(0.95, 0.99), doubleArrayOf(0.8, 0.2))
        val a = Hungarian.assign(cost, maxCost = 0.5)
        assertEquals(0, a[0])
        assertEquals(-1, a[1])
        assertEquals(1, a[2])
    }

    private fun box(cx: Float, cy: Float, w: Float = 0.1f, h: Float = 0.3f) =
        BBox(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)

    @Test
    fun byteTracker_keepsIdsForMovingPeople() {
        val tracker = ByteTracker()
        var ids = emptySet<Int>()
        for (f in 0 until 20) {
            val dets = listOf(
                Detection(box(0.2f + f * 0.01f, 0.5f), 0.9f),
                Detection(box(0.7f - f * 0.01f, 0.5f), 0.85f),
            )
            val tracks = tracker.update(dets).filter { it.state == TrackState.TRACKED }
            assertEquals("frame $f", 2, tracks.size)
            if (f == 0) ids = tracks.map { it.id }.toSet() else assertEquals(ids, tracks.map { it.id }.toSet())
        }
    }

    @Test
    fun byteTracker_recoversOccludedPersonWithLowScoreDetection() {
        val tracker = ByteTracker()
        repeat(5) { tracker.update(listOf(Detection(box(0.5f, 0.5f), 0.9f))) }
        val id = tracker.update(listOf(Detection(box(0.5f, 0.5f), 0.9f))).single().id
        // Oclusion parcial: el detector solo da score 0.3 (baja confianza), ByteTrack la asocia igual.
        val occluded = tracker.update(listOf(Detection(box(0.505f, 0.5f), 0.3f)))
        val t = occluded.single { it.state == TrackState.TRACKED }
        assertEquals(id, t.id)
    }

    @Test
    fun byteTracker_lostTrackIsRecoveredWithSameId() {
        val tracker = ByteTracker()
        repeat(5) { tracker.update(listOf(Detection(box(0.5f, 0.5f), 0.9f))) }
        val id = tracker.update(listOf(Detection(box(0.5f, 0.5f), 0.9f))).single().id
        repeat(5) { tracker.update(emptyList()) } // desaparece 5 frames
        val back = tracker.update(listOf(Detection(box(0.5f, 0.5f), 0.9f))).single { it.state == TrackState.TRACKED }
        assertEquals(id, back.id)
    }

    @Test
    fun byteTracker_ignoresSingleFrameNoise() {
        val tracker = ByteTracker()
        tracker.update(emptyList())
        val r = tracker.update(listOf(Detection(box(0.3f, 0.3f), 0.9f)))
        assertTrue(r.none { it.state == TrackState.TRACKED })
        val r2 = tracker.update(emptyList())
        assertTrue(r2.isEmpty())
    }

    @Test
    fun lineCounter_countsEntriesAndExits() {
        val tracker = ByteTracker()
        val counter = LineCrossingCounter(linePosition = 0.5f, downIsEntry = true)
        // Persona baja de y=0.2 a y=0.8 => entrada
        for (f in 0..30) {
            val y = 0.2f + f * 0.02f
            counter.update(tracker.update(listOf(Detection(box(0.5f, y, h = 0.2f), 0.9f))))
        }
        assertEquals(1, counter.entries)
        assertEquals(0, counter.exits)
        // Y vuelve a subir => salida
        for (f in 0..30) {
            val y = 0.8f - f * 0.02f
            counter.update(tracker.update(listOf(Detection(box(0.5f, y, h = 0.2f), 0.9f))))
        }
        assertEquals(1, counter.exits)
    }
}
