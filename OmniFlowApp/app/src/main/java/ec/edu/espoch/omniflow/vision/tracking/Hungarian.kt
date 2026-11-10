package ec.edu.espoch.omniflow.vision.tracking

/**
 * Algoritmo Hungaro (Kuhn-Munkres con potenciales, O(n^2 m)) para asignacion lineal de costo minimo.
 * Es el mismo problema que resuelve `lap.lapjv` en ByteTrack: emparejar tracks con detecciones.
 */
object Hungarian {

    /**
     * @param cost matriz [filas][columnas] (puede ser rectangular).
     * @param maxCost pares con costo > maxCost se descartan tras la asignacion.
     * @return para cada fila, la columna asignada o -1.
     */
    fun assign(cost: Array<DoubleArray>, maxCost: Double = Double.MAX_VALUE): IntArray {
        val n = cost.size
        if (n == 0) return IntArray(0)
        val m = cost[0].size
        if (m == 0) return IntArray(n) { -1 }

        val result = if (n <= m) {
            solve(cost, n, m)
        } else {
            // Transponer para cumplir n <= m y luego invertir el resultado.
            val t = Array(m) { j -> DoubleArray(n) { i -> cost[i][j] } }
            val colToRow = solve(t, m, n)
            IntArray(n) { -1 }.also { r -> colToRow.forEachIndexed { col, row -> if (row >= 0) r[row] = col } }
        }
        for (i in 0 until n) if (result[i] >= 0 && cost[i][result[i]] > maxCost) result[i] = -1
        return result
    }

    /** Requiere n <= m. Indices 1-based internamente (formulacion clasica). */
    private fun solve(a: Array<DoubleArray>, n: Int, m: Int): IntArray {
        val inf = Double.MAX_VALUE / 4
        val u = DoubleArray(n + 1)
        val v = DoubleArray(m + 1)
        val p = IntArray(m + 1)
        val way = IntArray(m + 1)
        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(m + 1) { inf }
            val used = BooleanArray(m + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = inf
                var j1 = 0
                for (j in 1..m) if (!used[j]) {
                    val cur = a[i0 - 1][j - 1] - u[i0] - v[j]
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0 }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j }
                }
                for (j in 0..m) {
                    if (used[j]) { u[p[j]] += delta; v[j] -= delta } else minv[j] -= delta
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        val ans = IntArray(n) { -1 }
        for (j in 1..m) if (p[j] != 0) ans[p[j] - 1] = j - 1
        return ans
    }
}
