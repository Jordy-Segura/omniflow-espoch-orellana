package ec.edu.espoch.omniflow

import ec.edu.espoch.omniflow.analytics.AnomalyDetector
import ec.edu.espoch.omniflow.analytics.OccupancyForecaster
import ec.edu.espoch.omniflow.analytics.OccupancySmoother
import ec.edu.espoch.omniflow.data.repository.AuthRepository
import ec.edu.espoch.omniflow.domain.model.AlertType
import ec.edu.espoch.omniflow.domain.model.ClassSession
import ec.edu.espoch.omniflow.domain.model.Lab
import ec.edu.espoch.omniflow.domain.model.LabStatus
import ec.edu.espoch.omniflow.domain.model.Role
import ec.edu.espoch.omniflow.domain.model.ScheduleSlot
import ec.edu.espoch.omniflow.domain.usecase.LabRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AnalyticsAndRulesTest {

    @Test
    fun linearRegression_recoversSlopeAndIntercept() {
        val xs = DoubleArray(10) { it.toDouble() }
        val ys = DoubleArray(10) { 3.0 + 2.0 * it }
        val m = OccupancyForecaster.fitLinear(xs, ys)!!
        assertEquals(2.0, m.slope, 1e-9)
        assertEquals(3.0, m.intercept, 1e-9)
        assertEquals(1.0, m.r2, 1e-9)
    }

    @Test
    fun forecast_followsIncreasingTrend() {
        val t0 = 1_000_000L
        val samples = (0 until 10).map { t0 + it * 60_000L to (5 + it) } // +1 persona por minuto
        val f = OccupancyForecaster.forecast(samples, 5, capacity = 40)
        assertNotNull(f)
        assertTrue("prediccion ${f!!.predicted}", f.predicted in 18..21)
        assertEquals(60.0, f.trendPerHour, 1e-6)
    }

    @Test
    fun anomaly_detectsSpikeButNotNormalValue() {
        val history = listOf(20, 22, 19, 21, 23, 20, 18, 22, 21, 20, 19, 21, 22, 20).map { it.toDouble() }
        assertFalse(AnomalyDetector.robustZ(history, 21.0).isAnomaly)
        assertTrue(AnomalyDetector.robustZ(history, 45.0).isAnomaly)
        assertFalse("pocas muestras", AnomalyDetector.robustZ(history.take(5), 45.0).isAnomaly)
    }

    @Test
    fun smoother_removesFlicker() {
        val s = OccupancySmoother(window = 5)
        listOf(10, 10, 0, 10, 10).forEach { s.add(it) }
        assertEquals(10, s.value())
    }

    private val lab = Lab(1, "LAB-C1", "Laboratorio de Cómputo 1", "Edificio A", "Planta 1", 30)
    // 2026-11-09 es lunes
    private val slot = ScheduleSlot(7, 1, "Programación Avanzada", "Ing. Carlos García", "4°A", 1, 10 * 60, 12 * 60, 28)

    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 11, 9, h, m)

    @Test
    fun status_followsScheduleAndOccupancy() {
        val during = LabRules.scheduleContext(listOf(slot), at(10, 30))
        assertEquals(LabStatus.OCUPADO, LabRules.status(lab, during, 12))
        assertEquals(LabStatus.ANOMALO, LabRules.status(lab, during, 35)) // sobreaforo

        val before = LabRules.scheduleContext(listOf(slot), at(9, 40))
        assertEquals(LabStatus.PROXIMO, LabRules.status(lab, before, 0))

        val free = LabRules.scheduleContext(listOf(slot), at(8, 0))
        assertEquals(LabStatus.LIBRE, LabRules.status(lab, free, 0))

        val night = LabRules.scheduleContext(listOf(slot), at(22, 0))
        assertEquals(LabStatus.FUERA_DE_HORARIO, LabRules.status(lab, night, 0))
        assertEquals(LabStatus.ANOMALO, LabRules.status(lab, night, 3))
    }

    @Test
    fun rules_generateExpectedAlerts() {
        val noClass = LabRules.scheduleContext(listOf(slot), at(15, 0))
        assertEquals(listOf(AlertType.FUERA_DE_HORARIO), LabRules.evaluate(lab, noClass, 4, null, 0).map { it.type })

        val during = LabRules.scheduleContext(listOf(slot), at(10, 20))
        assertTrue(LabRules.evaluate(lab, during, 31, null, 0).any { it.type == AlertType.SOBREAFORO })
        assertEquals(listOf(AlertType.AUSENCIA_DOCENTE), LabRules.evaluate(lab, during, 0, null, 0).map { it.type })

        val session = ClassSession(1, 1, 7, 0, firstPresenceAt = 1L, lastPresenceAt = 2L, peakCount = 20, avgCount = 15f, samples = 10, endedEarly = false)
        val leftEarly = LabRules.scheduleContext(listOf(slot), at(11, 0))
        assertEquals(listOf(AlertType.SALIDA_ANTICIPADA), LabRules.evaluate(lab, leftEarly, 0, session, 6).map { it.type })
        assertTrue(LabRules.evaluate(lab, leftEarly, 18, session, 0).isEmpty())
    }

    @Test
    fun oasisRoles_mapToAppRoles() {
        assertEquals(Role.DOCENTE, AuthRepository.mapRole(listOf("DOCENTE")))
        assertEquals(Role.ESTUDIANTE, AuthRepository.mapRole(listOf("ESTUDIANTE")))
        assertEquals(Role.COORDINADOR, AuthRepository.mapRole(listOf("DIRECTOR DE CARRERA", "DOCENTE")))
    }
}
