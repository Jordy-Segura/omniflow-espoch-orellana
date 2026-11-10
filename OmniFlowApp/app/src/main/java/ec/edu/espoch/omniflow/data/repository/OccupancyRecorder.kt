package ec.edu.espoch.omniflow.data.repository

import ec.edu.espoch.omniflow.analytics.AnomalyDetector
import ec.edu.espoch.omniflow.analytics.OccupancyForecaster
import ec.edu.espoch.omniflow.data.local.ClassSessionDao
import ec.edu.espoch.omniflow.data.local.ClassSessionEntity
import ec.edu.espoch.omniflow.data.local.OccupancyDao
import ec.edu.espoch.omniflow.data.local.OccupancyEventEntity
import ec.edu.espoch.omniflow.domain.model.AlertType
import ec.edu.espoch.omniflow.domain.model.Lab
import ec.edu.espoch.omniflow.domain.usecase.LabRules
import ec.edu.espoch.omniflow.vision.camera.CameraSource
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pasos 4-7 de la "Logica operativa del sistema" (Documento Maestro, pag. 4):
 * comparacion con el horario, generacion de estado, registro en BD y alertas.
 */
@Singleton
class OccupancyRecorder @Inject constructor(
    private val occupancyDao: OccupancyDao,
    private val sessionDao: ClassSessionDao,
    private val labRepository: LabRepository,
    private val alerts: AlertRepository,
    private val sync: SyncRepository,
) {
    data class Insight(
        val savedAt: Long,
        val count: Int,
        val forecast: OccupancyForecaster.Forecast?,
        val anomaly: AnomalyDetector.Result?,
        val triggers: List<LabRules.Trigger>,
    )

    private var lastEntries = 0
    private var lastExits = 0
    private var emptySince: Long? = null
    private var lastSyncAt = 0L

    fun resetCounters() {
        lastEntries = 0
        lastExits = 0
        emptySince = null
    }

    suspend fun record(
        lab: Lab,
        count: Int,
        totalEntries: Int,
        totalExits: Int,
        source: CameraSource,
        now: Long = System.currentTimeMillis(),
    ): Insight {
        val dt = LocalDateTime.now()
        val hourOfWeek = (dt.dayOfWeek.value - 1) * 24 + dt.hour

        // --- Registro del evento
        occupancyDao.insert(
            OccupancyEventEntity(
                labId = lab.id,
                timestamp = now,
                count = count,
                entries = (totalEntries - lastEntries).coerceAtLeast(0),
                exits = (totalExits - lastExits).coerceAtLeast(0),
                hourOfWeek = hourOfWeek,
                source = if (source == CameraSource.IP_STREAM) "CAMERA_IP" else "CAMERA_PHONE",
            ),
        )
        lastEntries = totalEntries
        lastExits = totalExits

        if (count == 0) { if (emptySince == null) emptySince = now } else emptySince = null
        val minutesEmpty = emptySince?.let { ((now - it) / 60000).toInt() } ?: 0

        // --- Comparacion con el horario y sesion de clase
        val slots = labRepository.slotsForLab(lab.id)
        val ctx = LabRules.scheduleContext(slots, dt)
        val session = ctx.current?.let { slot -> updateSession(lab.id, slot.id, count, now) }

        // --- Reglas de negocio
        val triggers = LabRules.evaluate(lab, ctx, count, session?.toDomain(), minutesEmpty).toMutableList()

        // --- ML: anomalia respecto al historial de la misma franja horaria
        val history = occupancyDao.historyForSlot(lab.id, hourOfWeek, now - 60 * 60 * 1000L).map { it.toDouble() }
        val anomaly = AnomalyDetector.robustZ(history, count.toDouble())
        if (anomaly.isAnomaly && count > 0) {
            triggers += LabRules.Trigger(
                AlertType.ANOMALIA_ML,
                "Ocupación inusual: $count personas vs. mediana histórica de ${"%.0f".format(anomaly.median)} " +
                    "para esta franja (z = ${"%.1f".format(anomaly.zScore)}).",
            )
        }
        triggers.forEach { t ->
            if (t.type == AlertType.SALIDA_ANTICIPADA && session != null && !session.endedEarly) {
                sessionDao.update(session.copy(endedEarly = true))
            }
            alerts.raise(lab, t.type, t.message, now)
        }

        // --- ML: pronostico de los proximos 15 minutos
        val recent = occupancyDao.since(lab.id, now - 30 * 60 * 1000L).filter { it.source != "DEMO" }
        val forecast = OccupancyForecaster.forecast(recent.map { it.timestamp to it.count }, 15, lab.capacity)

        // --- Sincronizacion con el backend (si esta configurado), como maximo una vez por minuto
        if (now - lastSyncAt > 60_000) {
            lastSyncAt = now
            sync.pushPending()
        }
        return Insight(now, count, forecast, anomaly, triggers)
    }

    private suspend fun updateSession(labId: Long, scheduleId: Long, count: Int, now: Long): ClassSessionEntity {
        val day = LocalDate.now().toEpochDay()
        val existing = sessionDao.find(scheduleId, day)
            ?: ClassSessionEntity(labId = labId, scheduleId = scheduleId, epochDay = day).let { new ->
                val id = sessionDao.insert(new)
                if (id > 0) new.copy(id = id) else sessionDao.find(scheduleId, day)!!
            }
        val updated = existing.copy(
            firstPresenceAt = existing.firstPresenceAt ?: if (count > 0) now else null,
            lastPresenceAt = if (count > 0) now else existing.lastPresenceAt,
            peakCount = maxOf(existing.peakCount, count),
            sumCount = existing.sumCount + count,
            samples = existing.samples + 1,
        )
        sessionDao.update(updated)
        return updated
    }
}
