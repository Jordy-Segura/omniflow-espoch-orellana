package ec.edu.espoch.omniflow.data.repository

import ec.edu.espoch.omniflow.data.local.ClassSessionDao
import ec.edu.espoch.omniflow.data.local.LabDao
import ec.edu.espoch.omniflow.data.local.OccupancyDao
import ec.edu.espoch.omniflow.data.local.ScheduleDao
import ec.edu.espoch.omniflow.domain.model.ClassSession
import ec.edu.espoch.omniflow.domain.model.Lab
import ec.edu.espoch.omniflow.domain.model.LabSnapshot
import ec.edu.espoch.omniflow.domain.model.ScheduleSlot
import ec.edu.espoch.omniflow.domain.usecase.LabRules
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LabRepository @Inject constructor(
    private val labDao: LabDao,
    private val scheduleDao: ScheduleDao,
    private val occupancyDao: OccupancyDao,
    private val sessionDao: ClassSessionDao,
) {
    val labs: Flow<List<Lab>> = labDao.observeAll().map { l -> l.map { it.toDomain() } }

    val schedule: Flow<List<ScheduleSlot>> = scheduleDao.observeAll().map { l -> l.map { it.toDomain() } }

    fun scheduleForLab(labId: Long): Flow<List<ScheduleSlot>> =
        scheduleDao.observeForLab(labId).map { l -> l.map { it.toDomain() } }

    suspend fun getLab(id: Long): Lab? = labDao.get(id)?.toDomain()
    suspend fun allLabs(): List<Lab> = labDao.getAll().map { it.toDomain() }
    suspend fun slotsForLab(labId: Long): List<ScheduleSlot> = scheduleDao.forLab(labId).map { it.toDomain() }

    /** Emite cada 30 s para que los estados "Proximo"/"Fuera de horario" avancen con el reloj. */
    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000)
        }
    }

    /** Estado en tiempo real de todos los laboratorios = horario (OASIS/local) + ultima lectura de la camara. */
    val snapshots: Flow<List<LabSnapshot>> = combine(labs, schedule, occupancyDao.observeLatest(), ticker) { labs, slots, latest, nowMs ->
        val now = LocalDateTime.now()
        val byLab = latest.associateBy { it.labId }
        labs.map { lab ->
            val ctx = LabRules.scheduleContext(slots.filter { it.labId == lab.id }, now)
            val reading = byLab[lab.id]
            // Una lectura de hace mas de 3 minutos ya no representa la ocupacion actual.
            val fresh = reading != null && nowMs - reading.timestamp < STALE_MS
            val occupancy = if (fresh) reading.count else null
            LabSnapshot(
                lab = lab,
                status = LabRules.status(lab, ctx, occupancy),
                occupancy = occupancy,
                currentClass = ctx.current,
                nextClass = ctx.next,
                minutesToNext = ctx.minutesToNext,
                lastUpdate = reading?.timestamp,
                hasCamera = fresh,
            )
        }
    }

    fun snapshot(labId: Long): Flow<LabSnapshot?> = snapshots.map { list -> list.firstOrNull { it.lab.id == labId } }

    /** Lecturas reales (no demo) de los ultimos [minutes] minutos como pares (timestamp, personas). */
    fun recentReadings(labId: Long, minutes: Int = 30): Flow<List<Pair<Long, Int>>> =
        occupancyDao.observeSince(labId, System.currentTimeMillis() - minutes * 60_000L)
            .map { l -> l.filter { it.source != "DEMO" }.map { it.timestamp to it.count } }

    fun currentSession(slot: ScheduleSlot?): Flow<ClassSession?> =
        if (slot == null) flow { emit(null) }
        else sessionDao.observe(slot.id, LocalDate.now().toEpochDay()).map { it?.toDomain() }

    companion object {
        const val STALE_MS = 3 * 60 * 1000L
    }
}
