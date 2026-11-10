package ec.edu.espoch.omniflow.analytics

import kotlin.math.roundToInt

/**
 * Prediccion de ocupacion a corto plazo con dos modelos clasicos de ML / series de tiempo:
 *
 *  - Regresion lineal por minimos cuadrados (OLS):  y = b0 + b1 * t  -> tendencia y R^2.
 *  - Suavizado exponencial doble de Holt (nivel + tendencia), mas robusto al ruido del detector.
 *
 * El pronostico final es el promedio de ambos, acotado a [0, capacidad * 1.5].
 */
object OccupancyForecaster {

    data class LinearModel(val intercept: Double, val slope: Double, val r2: Double) {
        fun predict(x: Double) = intercept + slope * x
    }

    data class Forecast(
        val horizonMinutes: Int,
        val predicted: Int,
        /** Personas por hora (positivo = el laboratorio se esta llenando). */
        val trendPerHour: Double,
        val r2: Double,
        val samples: Int,
    )

    fun fitLinear(xs: DoubleArray, ys: DoubleArray): LinearModel? {
        val n = xs.size
        if (n < 2 || ys.size != n) return null
        val mx = xs.average()
        val my = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            sxy += (xs[i] - mx) * (ys[i] - my)
            sxx += (xs[i] - mx) * (xs[i] - mx)
        }
        if (sxx == 0.0) return LinearModel(my, 0.0, 0.0)
        val slope = sxy / sxx
        val intercept = my - slope * mx
        var ssRes = 0.0
        var ssTot = 0.0
        for (i in 0 until n) {
            val e = ys[i] - (intercept + slope * xs[i])
            ssRes += e * e
            ssTot += (ys[i] - my) * (ys[i] - my)
        }
        val r2 = if (ssTot == 0.0) 1.0 else 1.0 - ssRes / ssTot
        return LinearModel(intercept, slope, r2)
    }

    /** Holt: devuelve el pronostico a [steps] pasos. */
    fun holt(ys: DoubleArray, steps: Double, alpha: Double = 0.5, beta: Double = 0.3): Double {
        if (ys.isEmpty()) return 0.0
        if (ys.size == 1) return ys[0]
        var level = ys[0]
        var trend = ys[1] - ys[0]
        for (i in 1 until ys.size) {
            val prevLevel = level
            level = alpha * ys[i] + (1 - alpha) * (level + trend)
            trend = beta * (level - prevLevel) + (1 - beta) * trend
        }
        return level + steps * trend
    }

    /**
     * @param samples pares (timestampMillis, personas), ordenados por tiempo.
     * @param horizonMinutes minutos hacia adelante a predecir.
     */
    fun forecast(samples: List<Pair<Long, Int>>, horizonMinutes: Int, capacity: Int): Forecast? {
        if (samples.size < 4) return null
        val t0 = samples.first().first
        val xs = DoubleArray(samples.size) { (samples[it].first - t0) / 60000.0 } // minutos
        val ys = DoubleArray(samples.size) { samples[it].second.toDouble() }
        val lin = fitLinear(xs, ys) ?: return null
        val target = xs.last() + horizonMinutes
        val pLin = lin.predict(target)

        // Paso medio entre muestras para traducir minutos a pasos de Holt.
        val step = ((xs.last() - xs.first()) / (xs.size - 1)).coerceAtLeast(0.1)
        val pHolt = holt(ys, horizonMinutes / step)

        val predicted = ((pLin + pHolt) / 2).coerceIn(0.0, capacity * 1.5).roundToInt()
        return Forecast(horizonMinutes, predicted, lin.slope * 60, lin.r2.coerceIn(0.0, 1.0), samples.size)
    }
}
