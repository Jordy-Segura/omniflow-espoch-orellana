package ec.edu.espoch.omniflow.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import ec.edu.espoch.omniflow.BuildConfig
import ec.edu.espoch.omniflow.domain.model.User
import ec.edu.espoch.omniflow.vision.camera.CameraSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "omniflow_settings")

data class MonitorSettings(
    val source: CameraSource = CameraSource.PHONE_BACK,
    val streamUrl: String = "http://192.168.1.50:8080/video",
    /** Laboratorio que esta vigilando ESTE telefono. */
    val labId: Long? = null,
    val minConfidence: Float = 0.5f,
    val useGpu: Boolean = false,
    val countingLine: Boolean = false,
    val linePosition: Float = 0.5f,
    val downIsEntry: Boolean = true,
    /** Cada cuantos segundos se guarda una muestra de ocupacion. */
    val sampleSeconds: Int = 10,
    val apiUrl: String = BuildConfig.OMNIFLOW_API_URL,
    /** Envia fotogramas al servidor para reconocer rostros y tomar asistencia por estudiante. */
    val attendanceMode: Boolean = false,
    /** Clave del telefono-camara (X-Device-Key) si el servidor la exige. */
    val deviceKey: String = "",
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private object K {
        val USER = stringPreferencesKey("session_user")
        val SOURCE = stringPreferencesKey("camera_source")
        val STREAM_URL = stringPreferencesKey("stream_url")
        val LAB_ID = longPreferencesKey("monitor_lab_id")
        val MIN_CONF = floatPreferencesKey("min_confidence")
        val GPU = booleanPreferencesKey("use_gpu")
        val LINE = booleanPreferencesKey("counting_line")
        val LINE_POS = floatPreferencesKey("line_position")
        val DOWN_IN = booleanPreferencesKey("down_is_entry")
        val SAMPLE = floatPreferencesKey("sample_seconds")
        val API_URL = stringPreferencesKey("api_url")
        val ATTENDANCE = booleanPreferencesKey("attendance_mode")
        val DEVICE_KEY = stringPreferencesKey("device_key")
        val SEED_VERSION = intPreferencesKey("seed_version")
        val AUTO_UPDATE = booleanPreferencesKey("auto_update")
    }

    val user: Flow<User?> = context.dataStore.data.map { p ->
        p[K.USER]?.let { runCatching { json.decodeFromString(User.serializer(), it) }.getOrNull() }
    }

    suspend fun saveUser(user: User?) {
        context.dataStore.edit { p ->
            if (user == null) p.remove(K.USER) else p[K.USER] = json.encodeToString(User.serializer(), user)
        }
    }

    val monitor: Flow<MonitorSettings> = context.dataStore.data.map { it.toMonitorSettings() }

    suspend fun currentMonitor(): MonitorSettings = monitor.first()

    private fun Preferences.toMonitorSettings(): MonitorSettings {
        val d = MonitorSettings()
        return MonitorSettings(
            source = this[K.SOURCE]?.let { runCatching { CameraSource.valueOf(it) }.getOrNull() } ?: d.source,
            streamUrl = this[K.STREAM_URL] ?: d.streamUrl,
            labId = this[K.LAB_ID],
            minConfidence = this[K.MIN_CONF] ?: d.minConfidence,
            useGpu = this[K.GPU] ?: d.useGpu,
            countingLine = this[K.LINE] ?: d.countingLine,
            linePosition = this[K.LINE_POS] ?: d.linePosition,
            downIsEntry = this[K.DOWN_IN] ?: d.downIsEntry,
            sampleSeconds = this[K.SAMPLE]?.toInt() ?: d.sampleSeconds,
            // Vacio o ausente = usar la URL configurada al compilar (local.properties: OMNIFLOW_API_URL).
            apiUrl = this[K.API_URL]?.ifBlank { null } ?: d.apiUrl,
            attendanceMode = this[K.ATTENDANCE] ?: d.attendanceMode,
            deviceKey = this[K.DEVICE_KEY] ?: d.deviceKey,
        )
    }

    suspend fun updateMonitor(transform: (MonitorSettings) -> MonitorSettings) {
        context.dataStore.edit { p ->
            val s = transform(p.toMonitorSettings())
            p[K.SOURCE] = s.source.name
            p[K.STREAM_URL] = s.streamUrl.trim()
            if (s.labId != null) p[K.LAB_ID] = s.labId else p.remove(K.LAB_ID)
            p[K.MIN_CONF] = s.minConfidence
            p[K.GPU] = s.useGpu
            p[K.LINE] = s.countingLine
            p[K.LINE_POS] = s.linePosition
            p[K.DOWN_IN] = s.downIsEntry
            p[K.SAMPLE] = s.sampleSeconds.toFloat()
            p[K.ATTENDANCE] = s.attendanceMode
            p[K.DEVICE_KEY] = s.deviceKey.trim()
            val api = s.apiUrl.trim()
            if (api.isBlank() || api == BuildConfig.OMNIFLOW_API_URL) p.remove(K.API_URL) else p[K.API_URL] = api
        }
    }

    suspend fun seedVersion(): Int = context.dataStore.data.first()[K.SEED_VERSION] ?: 0
    suspend fun setSeedVersion(v: Int) = context.dataStore.edit { it[K.SEED_VERSION] = v }

    /** Descargar e instalar automaticamente las nuevas versiones de la app. */
    val autoUpdate: Flow<Boolean> = context.dataStore.data.map { it[K.AUTO_UPDATE] ?: true }
    suspend fun setAutoUpdate(v: Boolean) = context.dataStore.edit { it[K.AUTO_UPDATE] = v }
}
