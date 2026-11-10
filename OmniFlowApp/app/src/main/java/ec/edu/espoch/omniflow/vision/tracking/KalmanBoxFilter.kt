package ec.edu.espoch.omniflow.vision.tracking

import ec.edu.espoch.omniflow.vision.BBox

/**
 * Filtro de Kalman de velocidad constante para cajas, igual al usado por SORT/DeepSORT/ByteTrack.
 *
 * Estado (8): [cx, cy, a, h, vcx, vcy, va, vh]  (a = ancho/alto)
 * Medicion (4): [cx, cy, a, h]
 * El ruido del proceso y de la medicion se escala con el alto h de la caja.
 */
class KalmanBoxFilter(initial: BBox) {

    private var x: Matrix
    private var p: Matrix

    init {
        val h = initial.height.toDouble().coerceAtLeast(1e-3)
        x = Matrix.column(initial.cx.toDouble(), initial.cy.toDouble(), initial.width / h, h, 0.0, 0.0, 0.0, 0.0)
        val sp = STD_POS * h
        val sv = STD_VEL * h
        p = Matrix.diag(
            sq(2 * sp), sq(2 * sp), sq(1e-2), sq(2 * sp),
            sq(10 * sv), sq(10 * sv), sq(1e-5), sq(10 * sv),
        )
    }

    val box: BBox get() = BBox.fromCxCyAH(x[0, 0], x[1, 0], x[2, 0], x[3, 0].coerceAtLeast(1e-3))

    fun predict() {
        val h = x[3, 0].coerceAtLeast(1e-3)
        val sp = STD_POS * h
        val sv = STD_VEL * h
        val q = Matrix.diag(sq(sp), sq(sp), sq(1e-2), sq(sp), sq(sv), sq(sv), sq(1e-5), sq(sv))
        x = F * x
        p = F * p * F.t() + q
    }

    fun update(measured: BBox) {
        val h = x[3, 0].coerceAtLeast(1e-3)
        val sp = STD_POS * h
        val r = Matrix.diag(sq(sp), sq(sp), sq(1e-1), sq(sp))
        val mh = measured.height.toDouble().coerceAtLeast(1e-3)
        val z = Matrix.column(measured.cx.toDouble(), measured.cy.toDouble(), measured.width / mh, mh)

        val s = H * p * H.t() + r
        val k = p * H.t() * s.inverse()
        x = x + k * (z - H * x)
        p = (I8 - k * H) * p
    }

    companion object {
        private const val STD_POS = 1.0 / 20.0
        private const val STD_VEL = 1.0 / 160.0
        private fun sq(v: Double) = v * v

        private val F: Matrix = Matrix.identity(8).also { for (i in 0 until 4) it[i, i + 4] = 1.0 }
        private val H: Matrix = Matrix(4, 8).also { for (i in 0 until 4) it[i, i] = 1.0 }
        private val I8: Matrix = Matrix.identity(8)
    }
}
