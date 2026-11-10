package ec.edu.espoch.omniflow.data.repository

import android.os.Build
import android.util.Log
import ec.edu.espoch.omniflow.data.local.LabDao
import ec.edu.espoch.omniflow.data.local.OccupancyDao
import ec.edu.espoch.omniflow.data.remote.api.OccupancyEventDto
import ec.edu.espoch.omniflow.data.remote.api.OmniFlowApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Envia al backend OmniFlow (FastAPI) los eventos de ocupacion pendientes.
 * Si no hay URL configurada o el backend no responde, los datos quedan en Room y se reintenta luego.
 */
@Singleton
class SyncRepository @Inject constructor(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val occupancyDao: OccupancyDao,
    private val labDao: LabDao,
    private val catalog: CatalogRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cachedUrl: String? = null
    private var cachedApi: OmniFlowApi? = null

    /** URL base del backend con "/" final, o null si no esta configurado. */
    suspend fun baseUrl(): String? {
        val url = settings.currentMonitor().apiUrl.trim()
        if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) return null
        return if (url.endsWith("/")) url else "$url/"
    }

    suspend fun api(): OmniFlowApi? {
        val url = settings.currentMonitor().apiUrl.trim()
        if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) return null
        val base = if (url.endsWith("/")) url else "$url/"
        if (base != cachedUrl) {
            cachedApi = Retrofit.Builder()
                .baseUrl(base)
                .client(http)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(OmniFlowApi::class.java)
            cachedUrl = base
        }
        return cachedApi
    }

    suspend fun testConnection(): Result<String> = runCatching {
        val api = api() ?: error("Configura la URL del backend")
        api.health()
        val n = pullCatalog().getOrNull()
        if (n != null) "Backend OK · horario actualizado ($n bloques)" else "Backend OK"
    }

    /** Descarga laboratorios y horario (con docentes e inscritos de OASIS) desde el backend. */
    suspend fun pullCatalog(): Result<Int> = runCatching {
        val api = api() ?: error("Backend no configurado")
        val labs = api.labs()
        val schedule = api.schedule()
        catalog.applyRemote(labs.map { CatalogLab(it.code, it.name, it.building, it.floor, it.capacity) }, schedule)
        schedule.size
    }.onFailure { Log.i("SyncRepository", "No se pudo descargar el horario: ${it.message}") }

    suspend fun pushPending(): Int {
        val api = api() ?: return 0
        return try {
            val pending = occupancyDao.unsynced()
            if (pending.isEmpty()) return 0
            val codes = labDao.getAll().associate { it.id to it.code }
            api.postEvents(
                pending.map {
                    OccupancyEventDto(
                        labCode = codes[it.labId] ?: it.labId.toString(),
                        timestamp = it.timestamp,
                        count = it.count,
                        entries = it.entries,
                        exits = it.exits,
                        source = it.source,
                        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                    )
                },
            )
            occupancyDao.markSynced(pending.map { it.id })
            pending.size
        } catch (e: Exception) {
            Log.i("SyncRepository", "Backend no disponible, se reintentara: ${e.message}")
            0
        }
    }
}
