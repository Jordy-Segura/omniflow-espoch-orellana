package ec.edu.espoch.omniflow.vision.tracking

import ec.edu.espoch.omniflow.vision.TrackState
import ec.edu.espoch.omniflow.vision.TrackedPerson

/**
 * Cuenta entradas y salidas cuando el centro de un track cruza una linea virtual horizontal
 * (por ejemplo, la puerta del laboratorio vista desde arriba).
 *
 * - Cruce de arriba hacia abajo = ENTRADA (configurable con [downIsEntry]).
 * - Se usa una banda de histeresis [margin] para no contar dos veces a quien se queda sobre la linea.
 */
class LineCrossingCounter(
    var linePosition: Float = 0.5f,
    var downIsEntry: Boolean = true,
    private val margin: Float = 0.03f,
) {
    var entries = 0
        private set
    var exits = 0
        private set

    /** Ultimo lado conocido de cada track: -1 arriba, +1 abajo. */
    private val side = HashMap<Int, Int>()

    fun reset() {
        entries = 0
        exits = 0
        side.clear()
    }

    fun update(tracks: List<TrackedPerson>) {
        val alive = HashSet<Int>()
        for (t in tracks) {
            if (t.state != TrackState.TRACKED) continue
            alive += t.id
            val y = t.box.cy
            val current = when {
                y < linePosition - margin -> -1
                y > linePosition + margin -> 1
                else -> 0 // dentro de la banda: no cambia de lado
            }
            if (current == 0) continue
            val previous = side[t.id]
            if (previous != null && previous != current) {
                val movedDown = current > previous
                if (movedDown == downIsEntry) entries++ else exits++
            }
            side[t.id] = current
        }
        // Olvidar tracks eliminados por el tracker para no crecer sin limite.
        if (side.size > 256) side.keys.retainAll(alive)
    }
}
