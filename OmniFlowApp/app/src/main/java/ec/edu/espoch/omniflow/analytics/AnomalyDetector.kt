package ec.edu.espoch.omniflow.analytics

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Deteccion de anomalias de ocupacion (aprendizaje no supervisado sobre el historial):
 *
 * Se compara la ocupacion actual contra el historial del MISMO laboratorio en la MISMA franja
 * (dia de la semana + hora). Se usa el z-score robusto (mediana y MAD), que no se deja arrastrar
 * por valores extremos como el z-score clasico:
 *
 *      z = 0.6745 * (x - mediana) / MAD
 *
 * |z| > [threshold] (3.5 por defecto, Iglewicz & Hoaglin) => anomalia.
 */
object AnomalyDetector {

    data class Result(val isAnomaly: Boolean, val zScore: Double, val median: Double, val samples: Int)

    fun robustZ(history: List<Double>, value: Double, threshold: Double = 3.5, minSamples: Int = 12): Result {
        if (history.size < minSamples) return Result(false, 0.0, history.median(), history.size)
        val med = history.median()
        val mad = history.map { abs(it - med) }.median()
        val z = if (mad > 1e-9) {
            0.6745 * (value - med) / mad
        } else {
            // Historial casi constante: se recurre a la desviacion estandar clasica.
            val mean = history.average()
            val sd = sqrt(history.sumOf { (it - mean) * (it - mean) } / history.size)
            if (sd > 1e-9) (value - mean) / sd else if (abs(value - med) >= 3) 4.0 * (value - med).let { if (it > 0) 1 else -1 } else 0.0
        }
        return Result(abs(z) > threshold, z, med, history.size)
    }

    private fun List<Double>.median(): Double {
        if (isEmpty()) return 0.0
        val s = sorted()
        val m = s.size / 2
        return if (s.size % 2 == 0) (s[m - 1] + s[m]) / 2 else s[m]
    }
}
