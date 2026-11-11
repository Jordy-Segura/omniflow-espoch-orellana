package ec.edu.espoch.omniflow.data.repository

import ec.edu.espoch.omniflow.data.local.LabDao
import ec.edu.espoch.omniflow.data.local.ScheduleDao
import ec.edu.espoch.omniflow.data.remote.api.SubjectDetailDto
import ec.edu.espoch.omniflow.domain.model.Lab
import ec.edu.espoch.omniflow.domain.model.ScheduleSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Detalle de una materia (bloque del horario): datos del horario local y estudiantes de OASIS desde el servidor. */
@Singleton
class SubjectRepository @Inject constructor(
    private val scheduleDao: ScheduleDao,
    private val labDao: LabDao,
    private val sync: SyncRepository,
    private val attendance: AttendanceRepository,
) {
    fun slot(id: Long): Flow<ScheduleSlot?> = scheduleDao.observe(id).map { it?.toDomain() }

    suspend fun lab(id: Long): Lab? = labDao.get(id)?.toDomain()

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
            ?: return Result.failure(AuthException("Sin conexión con el servidor OmniFlow: no se pudo cargar la lista de estudiantes"))
        return attendance.call { api, b -> api.subjectDetail(b, id) }
    }
}
