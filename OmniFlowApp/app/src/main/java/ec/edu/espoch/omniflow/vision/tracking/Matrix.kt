package ec.edu.espoch.omniflow.vision.tracking

import kotlin.math.abs

/** Matriz densa minima (row-major) para el filtro de Kalman. Sin dependencias de Android. */
class Matrix(val rows: Int, val cols: Int, val data: DoubleArray = DoubleArray(rows * cols)) {

    operator fun get(r: Int, c: Int): Double = data[r * cols + c]
    operator fun set(r: Int, c: Int, v: Double) { data[r * cols + c] = v }

    operator fun times(o: Matrix): Matrix {
        require(cols == o.rows) { "Dimensiones incompatibles ${rows}x$cols * ${o.rows}x${o.cols}" }
        val out = Matrix(rows, o.cols)
        for (i in 0 until rows) for (k in 0 until cols) {
            val a = this[i, k]
            if (a == 0.0) continue
            for (j in 0 until o.cols) out.data[i * o.cols + j] += a * o[k, j]
        }
        return out
    }

    operator fun plus(o: Matrix) = Matrix(rows, cols, DoubleArray(data.size) { data[it] + o.data[it] })
    operator fun minus(o: Matrix) = Matrix(rows, cols, DoubleArray(data.size) { data[it] - o.data[it] })

    fun t(): Matrix {
        val out = Matrix(cols, rows)
        for (i in 0 until rows) for (j in 0 until cols) out[j, i] = this[i, j]
        return out
    }

    /** Inversa por Gauss-Jordan con pivoteo parcial (matrices pequenas, 4x4 en la practica). */
    fun inverse(): Matrix {
        require(rows == cols)
        val n = rows
        val a = Matrix(n, n, data.copyOf())
        val inv = identity(n)
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) if (abs(a[r, col]) > abs(a[pivot, col])) pivot = r
            require(abs(a[pivot, col]) > 1e-12) { "Matriz singular" }
            if (pivot != col) { a.swapRows(pivot, col); inv.swapRows(pivot, col) }
            val p = a[col, col]
            for (j in 0 until n) { a[col, j] /= p; inv[col, j] /= p }
            for (r in 0 until n) if (r != col) {
                val f = a[r, col]
                if (f != 0.0) for (j in 0 until n) { a[r, j] -= f * a[col, j]; inv[r, j] -= f * inv[col, j] }
            }
        }
        return inv
    }

    private fun swapRows(r1: Int, r2: Int) {
        for (j in 0 until cols) { val t = this[r1, j]; this[r1, j] = this[r2, j]; this[r2, j] = t }
    }

    companion object {
        fun identity(n: Int) = Matrix(n, n).also { for (i in 0 until n) it[i, i] = 1.0 }
        fun diag(vararg v: Double) = Matrix(v.size, v.size).also { for (i in v.indices) it[i, i] = v[i] }
        fun column(vararg v: Double) = Matrix(v.size, 1, v.copyOf())
    }
}
