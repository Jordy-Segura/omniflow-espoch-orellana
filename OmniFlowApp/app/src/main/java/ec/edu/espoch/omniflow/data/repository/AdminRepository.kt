package ec.edu.espoch.omniflow.data.repository

import ec.edu.espoch.omniflow.data.local.LabDao
import ec.edu.espoch.omniflow.data.local.LabEntity
import ec.edu.espoch.omniflow.data.local.ScheduleDao
import ec.edu.espoch.omniflow.data.local.ScheduleEntity
import ec.edu.espoch.omniflow.data.remote.api.LabDto
import ec.edu.espoch.omniflow.data.remote.api.ScheduleEntryDto
import ec.edu.espoch.omniflow.data.remote.api.SlotInDto
import ec.edu.espoch.omniflow.data.remote.api.TeacherDto
import ec.edu.espoch.omniflow.data.remote.api.TeacherInDto
import ec.edu.espoch.omniflow.domain.model.Lab
import ec.edu.espoch.omniflow.domain.model.ScheduleSlot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Administracion (administrador y coordinador).
 *  - Docentes: siempre en el servidor (crean cuentas con usuario y contrasena).
 *  - Laboratorios y bloques de horario: en el servidor con cuenta; en el modo demostracion solo en este telefono.
 */
@Singleton
class AdminRepository @Inject constructor(
    private val attendance: AttendanceRepository,
    private val sync: SyncRepository,
    private val labDao: LabDao,
    private val scheduleDao: ScheduleDao,
) {
    suspend fun isDemo() = attendance.isDemo()

    // ------------------------------------------------------------------ docentes
    suspend fun teachers(): Result<List<TeacherDto>> = attendance.call { api, b -> api.teachers(b) }

    suspend fun saveTeacher(body: TeacherInDto, editing: Boolean): Result<TeacherDto> = attendance.call { api, b ->
        if (editing) api.updateTeacher(b, body.cedula, body) else api.createTeacher(b, body)
    }.onSuccess { sync.pullCatalog() }

    suspend fun deleteTeacher(cedula: String): Result<Unit> =
        attendance.call { api, b -> api.deleteTeacher(b, cedula) }.onSuccess { sync.pullCatalog() }

    /** Todos los bloques del servidor (con su id) para asignarlos a un docente. */
    suspend fun serverSchedule(): Result<List<ScheduleEntryDto>> = runCatching {
        (sync.api() ?: throw AuthException("Configura la dirección del servidor OmniFlow")).schedule()
    }

    // ------------------------------------------------------------------ laboratorios
    suspend fun saveLab(lab: LabDto, existing: Lab?): Result<Unit> = runCatching {
        require(lab.code.matches(Regex("^[A-Za-z0-9_-]{2,32}$"))) { "El código solo admite letras, números, - y _ (2 a 32)" }
        require(lab.name.trim().length >= 2) { "Escribe el nombre del laboratorio" }
        require(lab.capacity in 1..1000) { "La capacidad debe estar entre 1 y 1000" }
        if (!isDemo()) {
            attendance.call { api, b -> if (existing == null) api.createLab(b, lab) else api.updateLab(b, existing.code, lab) }.getOrThrow()
        }
        val current = labDao.byCode(existing?.code ?: lab.code)
        if (current == null) {
            labDao.insert(LabEntity(code = lab.code, name = lab.name.trim(), building = lab.building.trim(), floor = lab.floor.trim(), capacity = lab.capacity))
        } else {
            labDao.update(current.copy(name = lab.name.trim(), building = lab.building.trim(), floor = lab.floor.trim(), capacity = lab.capacity))
        }
    }

    suspend fun addSlot(lab: Lab, slot: SlotInDto): Result<Unit> = runCatching {
        require(slot.subject.isNotBlank()) { "Escribe la materia" }
        require(slot.endMinute > slot.startMinute) { "La hora de fin debe ser posterior a la de inicio" }
        val clash = scheduleDao.forLab(lab.id).firstOrNull {
            it.dayOfWeek == slot.dayOfWeek && it.startMinute < slot.endMinute && slot.startMinute < it.endMinute
        }
        require(clash == null) { "Se cruza con ${clash?.subject} (${clash?.let { ScheduleSlot.fmt(it.startMinute) }})" }
        if (isDemo()) {
            scheduleDao.insert(
                ScheduleEntity(labId = lab.id, subject = slot.subject.trim(), teacher = slot.teacher.trim(), parallel = slot.parallel.trim(),
                    dayOfWeek = slot.dayOfWeek, startMinute = slot.startMinute, endMinute = slot.endMinute, enrolled = 0, source = "LOCAL"),
            )
        } else {
            attendance.call { api, b -> api.addSlot(b, lab.code, slot) }.getOrThrow()
            sync.pullCatalog().getOrThrow()
        }
    }

    suspend fun deleteSlot(lab: Lab, slot: ScheduleSlot): Result<Unit> = runCatching {
        if (isDemo() || slot.source == "LOCAL") {
            scheduleDao.delete(slot.id)
            return@runCatching
        }
        val remote = slot.remoteId.takeIf { it > 0 } ?: serverSchedule().getOrThrow().firstOrNull {
            it.labCode == lab.code && it.dayOfWeek == slot.dayOfWeek && it.startMinute == slot.startMinute
        }?.id ?: throw AuthException("No se encontró el bloque en el servidor")
        attendance.call { api, b -> api.deleteSlot(b, lab.code, remote) }.getOrThrow()
        scheduleDao.delete(slot.id)
    }
}
