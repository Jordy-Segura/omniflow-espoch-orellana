package ec.edu.espoch.omniflow.data.repository

import android.graphics.Bitmap
import android.util.Base64
import ec.edu.espoch.omniflow.data.remote.api.ClassAttendanceDto
import ec.edu.espoch.omniflow.data.remote.api.ClassSummaryDto
import ec.edu.espoch.omniflow.data.remote.api.EnrollDto
import ec.edu.espoch.omniflow.data.remote.api.EnrollResultDto
import ec.edu.espoch.omniflow.data.remote.api.FaceStatusDto
import ec.edu.espoch.omniflow.data.remote.api.FrameResultDto
import ec.edu.espoch.omniflow.data.remote.api.MarkDto
import ec.edu.espoch.omniflow.data.remote.api.MyAttendanceDto
import ec.edu.espoch.omniflow.data.remote.api.OmniFlowApi
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asistencia por estudiante (la calcula el servidor OmniFlow con reconocimiento facial) y registro del rostro.
 * Todas las llamadas usan el token de la cuenta propia (login contra el backend).
 */
@Singleton
class AttendanceRepository @Inject constructor(
    private val sync: SyncRepository,
    private val settings: SettingsRepository,
) {
    private suspend fun <T> call(block: suspend (OmniFlowApi, String) -> T): Result<T> = runCatching {
        val api = sync.api() ?: throw AuthException("Configura la URL del servidor OmniFlow en Ajustes")
        val user: User = settings.user.first() ?: throw AuthException("Inicia sesión")
        val bearer = user.bearer ?: throw AuthException("Inicia sesión con tu cuenta OmniFlow (correo o cédula) para ver la asistencia")
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
        call { api, b -> api.attendanceClasses(b, date.toString(), mine) }

    suspend fun detail(slotId: Long, date: LocalDate): Result<ClassAttendanceDto> =
        call { api, b -> api.attendanceDetail(b, slotId, date.toString()) }

    suspend fun mark(slotId: Long, cedula: String, date: LocalDate, status: String) =
        call { api, b -> api.markAttendance(b, slotId, cedula, date.toString(), MarkDto(status)) }

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
