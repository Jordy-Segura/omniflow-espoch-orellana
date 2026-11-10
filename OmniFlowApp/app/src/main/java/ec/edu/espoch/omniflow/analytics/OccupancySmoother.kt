package ec.edu.espoch.omniflow.analytics

/**
 * Filtro de mediana movil sobre el conteo por frame. Elimina parpadeos del detector
 * (una persona que "desaparece" un frame no cambia la ocupacion registrada).
 */
class OccupancySmoother(private val window: Int = 15) {
    private val values = ArrayDeque<Int>()

    fun add(count: Int): Int {
        values.addLast(count)
        while (values.size > window) values.removeFirst()
        return value()
    }

    fun value(): Int {
        if (values.isEmpty()) return 0
        val s = values.sorted()
        return s[s.size / 2]
    }

    fun clear() = values.clear()
}
