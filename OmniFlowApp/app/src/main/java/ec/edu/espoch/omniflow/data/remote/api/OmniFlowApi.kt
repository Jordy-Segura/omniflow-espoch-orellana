package ec.edu.espoch.omniflow.data.remote.api

import kotlinx.serialization.Serializable
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Contrato REST con el backend OmniFlow (FastAPI). La app funciona sin el backend (modo local);
 * cuando se configure OMNIFLOW_API_URL (o la URL en Ajustes), los eventos de ocupacion se sincronizan.
 */
interface OmniFlowApi {
    @GET("api/v1/health")
    suspend fun health(): HealthDto

    @POST("api/v1/occupancy-events/batch")
    suspend fun postEvents(@Body events: List<OccupancyEventDto>): BatchResultDto

    @GET("api/v1/labs")
    suspend fun labs(): List<LabDto>

    /** Horario de los laboratorios con docente e inscritos (PDF del periodo + OASIS). */
    @GET("api/v1/schedule")
    suspend fun schedule(): List<ScheduleEntryDto>

    /** Ultima version publicada de la app (actualizacion automatica). */
    @GET("api/v1/app/latest")
    suspend fun latestRelease(): AppReleaseDto

    // ------------------------------------------------------------- cuentas propias
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginDto): TokenDto

    @POST("api/v1/auth/change-password")
    suspend fun changePassword(@Header("Authorization") auth: String, @Body body: ChangePasswordDto): TokenDto

    // ------------------------------------------------------------- asistencia
    @GET("api/v1/attendance/classes")
    suspend fun attendanceClasses(
        @Header("Authorization") auth: String,
        @Query("date") date: String,
        @Query("mine") mine: Boolean,
    ): List<ClassSummaryDto>

    @GET("api/v1/attendance/slot/{id}")
    suspend fun attendanceDetail(@Header("Authorization") auth: String, @Path("id") slotId: Long, @Query("date") date: String): ClassAttendanceDto

    @PUT("api/v1/attendance/slot/{id}/students/{cedula}")
    suspend fun markAttendance(
        @Header("Authorization") auth: String,
        @Path("id") slotId: Long,
        @Path("cedula") cedula: String,
        @Query("date") date: String,
        @Body body: MarkDto,
    ): AttendanceStudentDto

    @GET("api/v1/attendance/me")
    suspend fun myAttendance(@Header("Authorization") auth: String, @Query("days") days: Int = 30): List<MyAttendanceDto>

    /** Fotograma JPEG de la camara del laboratorio -> reconocimiento facial y marca de asistencia. */
    @POST("api/v1/attendance/frame")
    suspend fun attendanceFrame(
        @Header("Authorization") auth: String?,
        @Header("X-Device-Key") deviceKey: String?,
        @Query("labCode") labCode: String,
        @Body jpeg: RequestBody,
    ): FrameResultDto

    @POST("api/v1/faces/enroll")
    suspend fun enrollFace(@Header("Authorization") auth: String, @Body body: EnrollDto): EnrollResultDto

    /** PDF de matricula: identifica al estudiante (cedula/nombre) y registra la foto como plantilla facial. */
    @POST("api/v1/faces/enroll-pdf")
    suspend fun enrollPdf(
        @Header("Authorization") auth: String,
        @Query("consent") consent: Boolean,
        @Query("cedula") cedula: String?,
        @Body pdf: RequestBody,
    ): PdfEnrollDto

    @GET("api/v1/faces/status")
    suspend fun faceStatus(@Header("Authorization") auth: String, @Query("cedula") cedula: String? = null): FaceStatusDto

    @DELETE("api/v1/faces")
    suspend fun deleteFace(@Header("Authorization") auth: String)

    // ------------------------------------------------------------- materia (bloque de horario)
    @GET("api/v1/schedule/{id}/detail")
    suspend fun subjectDetail(@Header("Authorization") auth: String, @Path("id") slotId: Long): SubjectDetailDto

    // ------------------------------------------------------------- administracion (administrador y coordinador)
    @GET("api/v1/admin/teachers")
    suspend fun teachers(@Header("Authorization") auth: String): List<TeacherDto>

    @POST("api/v1/admin/teachers")
    suspend fun createTeacher(@Header("Authorization") auth: String, @Body body: TeacherInDto): TeacherDto

    @PUT("api/v1/admin/teachers/{cedula}")
    suspend fun updateTeacher(@Header("Authorization") auth: String, @Path("cedula") cedula: String, @Body body: TeacherInDto): TeacherDto

    @DELETE("api/v1/admin/teachers/{cedula}")
    suspend fun deleteTeacher(@Header("Authorization") auth: String, @Path("cedula") cedula: String)

    @POST("api/v1/labs")
    suspend fun createLab(@Header("Authorization") auth: String, @Body body: LabDto): LabDto

    @PUT("api/v1/labs/{code}")
    suspend fun updateLab(@Header("Authorization") auth: String, @Path("code") code: String, @Body body: LabDto): LabDto

    @POST("api/v1/labs/{code}/schedule")
    suspend fun addSlot(@Header("Authorization") auth: String, @Path("code") code: String, @Body body: SlotInDto): ScheduleEntryDto

    @DELETE("api/v1/labs/{code}/schedule/{id}")
    suspend fun deleteSlot(@Header("Authorization") auth: String, @Path("code") code: String, @Path("id") slotId: Long)
}

@Serializable
data class LoginDto(val username: String, val password: String = "", val demoRole: String? = null)

@Serializable
data class ChangePasswordDto(val currentPassword: String, val newPassword: String)

@Serializable
data class UserDto(
    val username: String,
    val fullName: String,
    val email: String = "",
    val role: String,
    val cedula: String? = null,
    val careerCode: String? = null,
    val source: String = "",
    val mustChangePassword: Boolean = false,
)

@Serializable
data class TokenDto(val accessToken: String, val expiresIn: Long, val user: UserDto)

@Serializable
data class AttendanceStudentDto(
    val cedula: String,
    val nombres: String,
    val apellidos: String,
    val email: String = "",
    val status: String,
    val method: String = "",
    val firstSeen: Long? = null,
    val confidence: Double = 0.0,
    val faceEnrolled: Boolean = false,
    /** Persona agregada solo en este telefono (modo demostracion). */
    val localId: Long? = null,
)

@Serializable
data class ClassSummaryDto(
    val slotId: Long,
    val labCode: String,
    val subject: String,
    val teacher: String,
    val parallel: String,
    val careerCode: String = "",
    val level: Int = 0,
    val date: String,
    val startMinute: Int,
    val endMinute: Int,
    val state: String,
    val total: Int,
    val present: Int,
    val late: Int,
    val absent: Int,
    val pending: Int,
    val justified: Int,
    val facesEnrolled: Int = 0,
    val peopleDetected: Int? = null,
)

@Serializable
data class ClassAttendanceDto(
    val slotId: Long,
    val labCode: String,
    val subject: String,
    val teacher: String,
    val parallel: String,
    val date: String,
    val startMinute: Int,
    val endMinute: Int,
    val state: String,
    val total: Int,
    val present: Int,
    val late: Int,
    val absent: Int,
    val pending: Int,
    val justified: Int,
    val facesEnrolled: Int = 0,
    val peopleDetected: Int? = null,
    val students: List<AttendanceStudentDto>,
)

@Serializable
data class MarkDto(val status: String)

@Serializable
data class MyAttendanceDto(
    val slotId: Long,
    val labCode: String,
    val subject: String,
    val date: String,
    val startMinute: Int,
    val status: String,
    val method: String = "",
)

@Serializable
data class RecognizedDto(val cedula: String, val nombre: String, val similitud: Double, val estado: String, val caja: List<Int> = emptyList())

@Serializable
data class FrameResultDto(
    val labCode: String,
    val slotId: Long? = null,
    val subject: String? = null,
    val faces: Int,
    val recognized: List<RecognizedDto>,
    val unknown: Int,
    val policy: String = "",
)

@Serializable
data class EnrollDto(val cedula: String? = null, val consent: Boolean, val images: List<String>)

@Serializable
data class EnrollResultDto(val cedula: String, val added: Int, val templates: Int, val problems: List<String> = emptyList())

@Serializable
data class PdfPageResultDto(val pagina: Int, val cedula: String? = null, val nombre: String? = null, val estado: String, val detalle: String = "")

@Serializable
data class PdfEnrollDto(val registrados: Int, val total: Int, val resultados: List<PdfPageResultDto>)

@Serializable
data class FaceStatusDto(val cedula: String, val templates: Int, val consentAt: String? = null)

@Serializable
data class ScheduleEntryDto(
    /** Id del bloque en el servidor (para abrir el detalle de la materia). */
    val id: Long = 0,
    val labCode: String = "",
    val subject: String,
    val teacher: String = "",
    val parallel: String = "",
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val enrolled: Int = 0,
    val careerCode: String = "",
    val level: Int = 0,
)

@Serializable
data class AppReleaseDto(
    val versionCode: Int,
    val versionName: String,
    val notes: String = "",
    val sha256: String,
    val size: Long,
    val publishedAt: String = "",
    val downloadUrl: String,
)

@Serializable
data class HealthDto(val status: String = "ok")

@Serializable
data class OccupancyEventDto(
    val labCode: String,
    val timestamp: Long,
    val count: Int,
    val entries: Int,
    val exits: Int,
    val source: String,
    val deviceModel: String,
)

@Serializable
data class BatchResultDto(val accepted: Int = 0)

@Serializable
data class LabDto(
    val code: String,
    val name: String,
    val building: String = "",
    val floor: String = "",
    val capacity: Int = 0,
)

@Serializable
data class SlotInDto(
    val subject: String,
    val teacher: String = "",
    val parallel: String = "",
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val enrolled: Int = 0,
)

@Serializable
data class SubjectStudentDto(
    val cedula: String,
    val nombres: String,
    val apellidos: String,
    val email: String = "",
    val faceEnrolled: Boolean = false,
    val present: Int = 0,
    val late: Int = 0,
    val justified: Int = 0,
    val absent: Int = 0,
    val attendancePct: Int? = null,
)

@Serializable
data class SubjectDetailDto(
    val id: Long,
    val labCode: String,
    val labName: String = "",
    val subject: String,
    val subjectCode: String = "",
    val teacher: String = "",
    val teacherEmail: String = "",
    val parallel: String = "",
    val careerCode: String = "",
    val level: Int = 0,
    val group: String = "",
    val periodCode: String = "",
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val enrolled: Int = 0,
    val sessions: Int = 0,
    val facesEnrolled: Int = 0,
    val avgAttendancePct: Int? = null,
    val students: List<SubjectStudentDto> = emptyList(),
)

@Serializable
data class TeacherSlotDto(
    val id: Long,
    val labCode: String = "",
    val subject: String,
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val parallel: String = "",
)

@Serializable
data class TeacherDto(
    val cedula: String,
    val nombres: String,
    val apellidos: String,
    val email: String = "",
    val username: String? = null,
    val hasAccount: Boolean = false,
    val accountActive: Boolean = false,
    val mustChangePassword: Boolean = false,
    val source: String = "OASIS",
    val slots: List<TeacherSlotDto> = emptyList(),
) {
    val fullName: String get() = "$nombres $apellidos".trim()
}

@Serializable
data class TeacherInDto(
    val cedula: String,
    val nombres: String,
    val apellidos: String,
    val email: String = "",
    val username: String,
    val password: String? = null,
    val mustChangePassword: Boolean = true,
    val slotIds: List<Long> = emptyList(),
)
