package ec.edu.espoch.omniflow.data.repository

import android.graphics.Bitmap
import android.util.Base64
import ec.edu.espoch.omniflow.data.local.ClassSessionDao
import ec.edu.espoch.omniflow.data.local.LabDao
import ec.edu.espoch.omniflow.data.local.LocalAttendanceDao
import ec.edu.espoch.omniflow.data.local.LocalMarkEntity
import ec.edu.espoch.omniflow.data.local.LocalPersonEntity
import ec.edu.espoch.omniflow.data.local.ScheduleDao
import ec.edu.espoch.omniflow.data.local.ScheduleEntity
import ec.edu.espoch.omniflow.data.remote.api.AttendanceStudentDto
import ec.edu.espoch.omniflow.data.remote.api.ClassAttendanceDto
import ec.edu.espoch.omniflow.data.remote.api.ClassSummaryDto
import ec.edu.espoch.omniflow.data.remote.api.EnrollDto
import ec.edu.espoch.omniflow.data.remote.api.EnrollResultDto
import ec.edu.espoch.omniflow.data.remote.api.FaceStatusDto
import ec.edu.espoch.omniflow.data.remote.api.FrameResultDto
import ec.edu.espoch.omniflow.data.remote.api.MarkDto
import ec.edu.espoch.omniflow.data.remote.api.MyAttendanceDto
import ec.edu.espoch.omniflow.data.remote.api.OmniFlowApi
import ec.edu.espoch.omniflow.domain.model.AuthSource
import ec.edu.espoch.omniflow.domain.model.User
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asistencia por estudiante (la calcula el servidor OmniFlow con reconocimiento facial) y registro del rostro.
 * Con cuenta propia todo va al servidor. En el modo demostracion la asistencia se guarda solo en este
 * telefono: las clases salen del horario local y las personas se agregan a mano.
 */
@Singleton
class AttendanceRepository @Inject constructor(
    private val sync: SyncRepository,
    private val settings: SettingsRepository,
    private val local: LocalAttendanceDao,
    private val scheduleDao: ScheduleDao,
    private val labDao: LabDao,
    private val sessionDao: ClassSessionDao,
) {
    suspend fun isDemo(): Boolean = settings.user.first()?.source == AuthSource.DEMO

    /** Llamada al servidor con el token de la cuenta propia; traduce los errores a mensajes claros. */
    suspend fun <T> call(block: suspend (OmniFlowApi, String) -> T): Result<T> = runCatching {
        val api = sync.api() ?: throw AuthException("Configura la URL del servidor OmniFlow en Ajustes")
        val user: User = settings.user.first() ?: throw AuthException("Inicia sesión")
        val bearer = user.bearer ?: throw AuthException(
            if (user.source == AuthSource.DEMO) "Esta función necesita una cuenta OmniFlow: el modo demostración no envía datos al servidor."
            else "Inicia sesión con tu cuenta OmniFlow (correo o cédula)",
        )
        try {
            block(api, bearer)
        } catch (e: HttpException) {
            throw AuthException(
                AuthRepository.errorDetail(e) ?: when (e.code()) {
                    401 -> "Tu sesión expiró: vuelve a iniciar sesión"
                    403 -> "No tienes permiso para ver esta información"
                    else -> "El servidor respondió ${e.code()}"
                },
            )
        } catch (_: IOException) {
            throw AuthException("Sin conexión con el servidor OmniFlow")
        }
    }

    suspend fun classes(date: LocalDate, mine: Boolean): Result<List<ClassSummaryDto>> =
        if (isDemo()) runCatching { localClasses(date) } else call { api, b -> api.attendanceClasses(b, date.toString(), mine) }

    suspend fun detail(slotId: Long, date: LocalDate): Result<ClassAttendanceDto> =
        if (isDemo()) runCatching { localDetail(slotId, date) } else call { api, b -> api.attendanceDetail(b, slotId, date.toString()) }

    suspend fun mark(slotId: Long, student: AttendanceStudentDto, date: LocalDate, status: String): Result<Unit> =
        if (student.localId != null) runCatching {
            local.mark(LocalMarkEntity(student.localId, date.toEpochDay(), slotId, status))
        } else call { api, b -> api.markAttendance(b, slotId, student.cedula, date.toString(), MarkDto(status)); Unit }

    // ------------------------------------------------------------------ modo demostracion (solo en el telefono)
    suspend fun addLocalPerson(slotId: Long, nombres: String, apellidos: String, cedula: String): Result<Long> = runCatching {
        require(nombres.isNotBlank() && apellidos.isNotBlank()) { "Escribe nombres y apellidos" }
        local.addPerson(
            LocalPersonEntity(slotId = slotId, nombres = nombres.trim().uppercase(), apellidos = apellidos.trim().uppercase(),
                cedula = cedula.filter { it.isDigit() }),
        )
    }

    suspend fun removeLocalPerson(id: Long) {
        local.deleteMarksOf(id)
        local.deletePerson(id)
    }

    private fun stateOf(slot: ScheduleEntity, date: LocalDate): String {
        val today = LocalDate.now()
        val minute = LocalTime.now().let { it.hour * 60 + it.minute }
        return when {
            date.isBefore(today) -> "FINALIZADA"
            date.isAfter(today) -> "PROGRAMADA"
            minute < slot.startMinute -> "PROGRAMADA"
            minute < slot.endMinute -> "EN_CURSO"
            else -> "FINALIZADA"
        }
    }

    private suspend fun localStudents(slot: ScheduleEntity, date: LocalDate, state: String): List<AttendanceStudentDto> {
        val marks = local.marks(slot.id, date.toEpochDay()).associateBy { it.personId }
        return local.people(slot.id).map { p ->
            val m = marks[p.id]
            AttendanceStudentDto(
                cedula = p.cedula.ifBlank { "L${p.id}" }, nombres = p.nombres, apellidos = p.apellidos,
                status = m?.status ?: if (state == "FINALIZADA") "AUSENTE" else "PENDIENTE",
                method = if (m != null) "MANUAL" else "", firstSeen = m?.markedAt, localId = p.id,
            )
        }
    }

    private suspend fun summaryOf(slot: ScheduleEntity, date: LocalDate): ClassAttendanceDto {
        val lab = labDao.get(slot.labId)
        val state = stateOf(slot, date)
        val st = localStudents(slot, date, state)
        val peak = sessionDao.find(slot.id, date.toEpochDay())?.peakCount
        return ClassAttendanceDto(
            slotId = slot.id, labCode = lab?.code.orEmpty(), subject = CatalogRepository.prettySubject(slot.subject),
            teacher = slot.teacher, parallel = slot.parallel, date = date.toString(), startMinute = slot.startMinute,
            endMinute = slot.endMinute, state = state, total = st.size,
            present = st.count { it.status == "PRESENTE" }, late = st.count { it.status == "ATRASO" },
            absent = st.count { it.status == "AUSENTE" }, pending = st.count { it.status == "PENDIENTE" },
            justified = st.count { it.status == "JUSTIFICADO" }, peopleDetected = peak, students = st,
        )
    }

    private suspend fun localClasses(date: LocalDate): List<ClassSummaryDto> =
        scheduleDao.getAll().filter { it.dayOfWeek == date.dayOfWeek.value }.sortedBy { it.startMinute }.map { slot ->
            val d = summaryOf(slot, date)
            ClassSummaryDto(
                d.slotId, d.labCode, d.subject, d.teacher, d.parallel, slot.careerCode, slot.level, d.date, d.startMinute,
                d.endMinute, d.state, d.total, d.present, d.late, d.absent, d.pending, d.justified, 0, d.peopleDetected,
            )
        }

    private suspend fun localDetail(slotId: Long, date: LocalDate): ClassAttendanceDto =
        summaryOf(scheduleDao.get(slotId) ?: error("La clase ya no existe en el horario"), date)

    suspend fun mine(days: Int = 30): Result<List<MyAttendanceDto>> = call { api, b -> api.myAttendance(b, days) }

    suspend fun faceStatus(cedula: String? = null): Result<FaceStatusDto> = call { api, b -> api.faceStatus(b, cedula) }

    suspend fun enrollPdf(pdf: ByteArray, consent: Boolean, cedula: String? = null) =
        call { api, b -> api.enrollPdf(b, consent, cedula, pdf.toRequestBody("application/pdf".toMediaType())) }

    suspend fun deleteMyFace(): Result<Unit> = call { api, b -> api.deleteFace(b) }

    suspend fun enroll(photos: List<Bitmap>, cedula: String?, consent: Boolean): Result<EnrollResultDto> {
        val images = withContext(Dispatchers.Default) { photos.map { jpegBase64(it) } }
        return call { api, b -> api.enrollFace(b, EnrollDto(cedula = cedula, consent = consent, images = images)) }
    }

    /** Envia un fotograma de la camara del laboratorio. Usa la clave de dispositivo o el token del docente. */
    suspend fun sendFrame(labCode: String, frame: Bitmap, deviceKey: String?): Result<FrameResultDto> = runCatching {
        val api = sync.api() ?: throw AuthException("Configura la URL del servidor OmniFlow")
        val bearer = settings.user.first()?.bearer
        val jpeg = withContext(Dispatchers.Default) { jpeg(frame, 85) }
        try {
            api.attendanceFrame(bearer, deviceKey?.ifBlank { null }, labCode, jpeg.toRequestBody("image/jpeg".toMediaType()))
        } catch (e: HttpException) {
            throw AuthException(AuthRepository.errorDetail(e) ?: "El servidor respondió ${e.code()}")
        }
    }

    private fun jpeg(b: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { out -> b.compress(Bitmap.CompressFormat.JPEG, quality, out); out.toByteArray() }

    private fun jpegBase64(b: Bitmap): String = Base64.encodeToString(jpeg(b, 90), Base64.NO_WRAP)
}
