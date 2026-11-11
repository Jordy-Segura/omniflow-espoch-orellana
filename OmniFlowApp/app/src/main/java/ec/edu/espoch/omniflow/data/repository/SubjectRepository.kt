package ec.edu.espoch.omniflow.data.repository

import ec.edu.espoch.omniflow.data.local.LabDao
import ec.edu.espoch.omniflow.data.local.LocalAttendanceDao
import ec.edu.espoch.omniflow.data.local.LocalPersonEntity
import ec.edu.espoch.omniflow.data.local.ScheduleDao
import ec.edu.espoch.omniflow.data.remote.api.SubjectDetailDto
import ec.edu.espoch.omniflow.domain.model.Lab
import ec.edu.espoch.omniflow.domain.model.ScheduleSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Persona agregada en el modo demostracion con su asistencia acumulada en este telefono. */
data class LocalPersonStats(val person: LocalPersonEntity, val present: Int, val late: Int, val justified: Int, val absent: Int) {
    val attended: Int get() = present + late + justified
    val pct: Int? get() = (attended + absent).takeIf { it > 0 }?.let { attended * 100 / it }
}

/** Detalle de una materia (bloque del horario): datos del horario local y, con cuenta, estudiantes del servidor. */
@Singleton
class SubjectRepository @Inject constructor(
    private val scheduleDao: ScheduleDao,
    private val labDao: LabDao,
    private val local: LocalAttendanceDao,
    private val sync: SyncRepository,
    private val attendance: AttendanceRepository,
) {
    fun slot(id: Long): Flow<ScheduleSlot?> = scheduleDao.observe(id).map { it?.toDomain() }

    suspend fun lab(id: Long): Lab? = labDao.get(id)?.toDomain()

    fun localPeople(slotId: Long): Flow<List<LocalPersonEntity>> = local.observePeople(slotId)

    suspend fun localStats(slotId: Long): List<LocalPersonStats> {
        val marks = local.allMarks(slotId).groupBy { it.personId }
        return local.people(slotId).map { p ->
            val m = marks[p.id].orEmpty()
            LocalPersonStats(p, m.count { it.status == "PRESENTE" }, m.count { it.status == "ATRASO" },
                m.count { it.status == "JUSTIFICADO" }, m.count { it.status == "AUSENTE" })
        }
    }

    /** Busca el id del bloque en el servidor (el horario incluido en la app no lo trae). */
    private suspend fun remoteId(slot: ScheduleSlot, lab: Lab): Long? {
        if (slot.remoteId > 0) return slot.remoteId
        val api = sync.api() ?: return null
        val match = api.schedule().firstOrNull {
            it.labCode == lab.code && it.dayOfWeek == slot.dayOfWeek && it.startMinute == slot.startMinute
        } ?: return null
        scheduleDao.setRemoteId(slot.id, match.id)
        return match.id
    }

    suspend fun remoteDetail(slot: ScheduleSlot): Result<SubjectDetailDto> {
        val lab = lab(slot.labId) ?: return Result.failure(AuthException("Laboratorio no encontrado"))
        val id = runCatching { remoteId(slot, lab) }.getOrNull()
            ?: return Result.failure(AuthException("No se pudo ubicar esta materia en el servidor OmniFlow"))
        return attendance.call { api, b -> api.subjectDetail(b, id) }
    }
}
