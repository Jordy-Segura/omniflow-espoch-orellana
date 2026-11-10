package ec.edu.espoch.omniflow

import ec.edu.espoch.omniflow.data.remote.api.LoginDto
import ec.edu.espoch.omniflow.data.remote.api.OccupancyEventDto
import ec.edu.espoch.omniflow.data.remote.api.OmniFlowApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Prueba de integracion con el backend real (omniflow-backend). Usa EXACTAMENTE el mismo cliente
 * que SyncRepository (OmniFlowApi + kotlinx.serialization). Se omite si no hay backend:
 *
 *   OMNIFLOW_TEST_API=http://127.0.0.1:8000/ ./gradlew testDebugUnitTest
 */
class BackendSyncIntegrationTest {

    private val baseUrl: String? = System.getenv("OMNIFLOW_TEST_API")?.takeIf { it.isNotBlank() }

    private fun api(): OmniFlowApi {
        val json = Json { ignoreUnknownKeys = true }
        return Retrofit.Builder()
            .baseUrl(if (baseUrl!!.endsWith("/")) baseUrl else "$baseUrl/")
            .client(OkHttpClient())
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OmniFlowApi::class.java)
    }

    @Test
    fun appClientSyncsWithBackend(): Unit = runBlocking {
        assumeTrue("OMNIFLOW_TEST_API no definido: se omite", baseUrl != null)
        val api = api()

        assertEquals("ok", api.health().status)

        val labs = api.labs()
        assertTrue(labs.any { it.code == "LAB-C1" && it.capacity > 0 })

        val now = System.currentTimeMillis()
        val batch = listOf(
            OccupancyEventDto("LAB-C1", now - 20_000, 7, 1, 0, "CAMERA_PHONE", "JVM test"),
            OccupancyEventDto("LAB-C1", now - 10_000, 8, 1, 0, "CAMERA_PHONE", "JVM test"),
            OccupancyEventDto("LAB-C3", now, 3, 0, 0, "CAMERA_IP", "JVM test"),
        )
        assertEquals(3, api.postEvents(batch).accepted)
        // Reintento del mismo lote (la app reenvia si no recibio respuesta): no se duplica.
        assertEquals(0, api.postEvents(batch).accepted)

        // Horario oficial (PDF + OASIS) que la app descarga al iniciar.
        val schedule = api.schedule()
        assertEquals(setOf("LAB-C1", "LAB-C2", "LAB-C3", "LAB-C4"), schedule.map { it.labCode }.toSet())
        val ml = schedule.first { it.labCode == "LAB-C2" && it.subject.equals("MACHINE LEARNING", ignoreCase = true) }
        assertEquals(2, ml.dayOfWeek)
        assertEquals(600, ml.startMinute)
        assertTrue(ml.teacher.isNotBlank())

        // Asistencia por asignatura (lista real de OASIS) leida con los DTO de la app.
        val token = api.login(LoginDto("Prueba integracion", demoRole = "COORDINADOR")).accessToken
        val monday = java.time.LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val classes = api.attendanceClasses("Bearer $token", monday.toString(), mine = false)
        assertTrue("Debe haber clases de laboratorio el lunes", classes.isNotEmpty())
        val first = classes.first()
        val detail = api.attendanceDetail("Bearer $token", first.slotId, first.date)
        assertEquals(first.total, detail.students.size)
        assertTrue(detail.students.isNotEmpty() && detail.students.all { it.status.isNotBlank() && it.cedula.isNotBlank() })

        // Canal de actualizaciones: si hay una version publicada, el JSON debe poder leerse.
        runCatching { api.latestRelease() }.getOrNull()?.let {
            assertTrue(it.versionCode > 1 && it.downloadUrl.startsWith("/api/v1/app/download/"))
        }
    }
}
