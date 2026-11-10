package ec.edu.espoch.omniflow.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import ec.edu.espoch.omniflow.BuildConfig
import ec.edu.espoch.omniflow.data.remote.api.AppReleaseDto
import ec.edu.espoch.omniflow.data.repository.SettingsRepository
import ec.edu.espoch.omniflow.data.repository.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppReleaseDto) : UpdateState
    data class Downloading(val release: AppReleaseDto, val progress: Float) : UpdateState
    /** Android pide permitir "instalar apps desconocidas" para OmniFlow (solo la primera vez). */
    data class NeedsPermission(val release: AppReleaseDto) : UpdateState
    data class Installing(val release: AppReleaseDto) : UpdateState
    /** Android muestra su dialogo "¿Actualizar esta app?" (primera actualizacion o Android < 12). */
    data class NeedsConfirmation(val release: AppReleaseDto?, val intent: Intent) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * Actualizacion automatica sin tienda: el backend publica la ultima APK (`/api/v1/app/latest`) y la app
 * la descarga, verifica su SHA-256 y la instala con PackageInstaller.
 *
 * En Android 12+ la instalacion es silenciosa cuando OmniFlow ya fue instalada/actualizada por si misma
 * (USER_ACTION_NOT_REQUIRED); la primera vez Android pide confirmacion y el permiso de instalar apps.
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
    private val sync: SyncRepository,
    private val settings: SettingsRepository,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    val currentVersionCode: Int = BuildConfig.VERSION_CODE
    val currentVersionName: String = BuildConfig.VERSION_NAME
    /** Compilaciones de desarrollo (Run desde Android Studio) no se auto-actualizan. */
    val isDevBuild: Boolean = BuildConfig.VERSION_CODE <= 1

    @Volatile private var busy = false
    private var pending: Pair<AppReleaseDto, File>? = null

    /** @param manual true si el usuario pulso "Buscar actualizaciones". */
    suspend fun check(manual: Boolean) {
        if (busy) return
        val base = sync.baseUrl()
        if (base == null) {
            if (manual) _state.value = UpdateState.Error("Configura la URL del backend en Ajustes para recibir actualizaciones")
            return
        }
        _state.value = UpdateState.Checking
        val release = try {
            sync.api()!!.latestRelease()
        } catch (e: HttpException) {
            _state.value = if (e.code() == 404) UpdateState.UpToDate else UpdateState.Error("El servidor respondió ${e.code()}")
            return
        } catch (e: Exception) {
            _state.value = if (manual) UpdateState.Error("Sin conexión con el servidor de actualizaciones") else UpdateState.Idle
            return
        }
        if (release.versionCode <= currentVersionCode) {
            _state.value = UpdateState.UpToDate
            return
        }
        _state.value = UpdateState.Available(release)
        val auto = settings.autoUpdate.first()
        if (manual || (auto && !isDevBuild)) downloadAndInstall(release)
    }

    suspend fun downloadAndInstall(release: AppReleaseDto) {
        if (busy) return
        busy = true
        try {
            val file = download(release) ?: return
            install(release, file)
        } finally {
            busy = false
        }
    }

    private suspend fun download(release: AppReleaseDto): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.filter { !it.name.endsWith("${release.versionCode}.apk") }?.forEach { it.delete() }
        val file = File(dir, "omniflow-${release.versionCode}.apk")
        if (file.exists() && sha256(file) == release.sha256) return@withContext file

        val base = sync.baseUrl() ?: return@withContext null
        val url = base.trimEnd('/') + "/" + release.downloadUrl.trimStart('/')
        _state.value = UpdateState.Downloading(release, 0f)
        try {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val total = resp.body.contentLength().takeIf { it > 0 } ?: release.size
                val tmp = File(dir, file.name + ".part")
                tmp.outputStream().use { out ->
                    resp.body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var lastEmit = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (read - lastEmit > 256 * 1024) {
                                lastEmit = read
                                _state.value = UpdateState.Downloading(release, (read.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                if (sha256(tmp) != release.sha256) {
                    tmp.delete()
                    error("la APK descargada está dañada (SHA-256 no coincide)")
                }
                tmp.renameTo(file)
            }
            file
        } catch (e: Exception) {
            Log.w(TAG, "Descarga fallida", e)
            _state.value = UpdateState.Error("No se pudo descargar la actualización: ${e.message}")
            null
        }
    }

    private suspend fun install(release: AppReleaseDto, file: File) = withContext(Dispatchers.IO) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            pending = release to file
            _state.value = UpdateState.NeedsPermission(release)
            return@withContext
        }
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("omniflow.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
                lastRelease = release
                _state.value = UpdateState.Installing(release)
                session.commit(pi.intentSender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Instalacion fallida", e)
            _state.value = UpdateState.Error("No se pudo instalar: ${e.message}")
        }
    }

    @Volatile private var lastRelease: AppReleaseDto? = null

    /** Lo llama InstallResultReceiver con el resultado de PackageInstaller. */
    fun onInstallStatus(status: Int, message: String?, confirmIntent: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> if (confirmIntent != null) {
                _state.value = UpdateState.NeedsConfirmation(lastRelease, confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value =
                lastRelease?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> _state.value =
                UpdateState.Error("La actualización está firmada con otra clave. Desinstala la app una vez e instala la nueva versión.")
            else -> _state.value = UpdateState.Error("Android rechazó la actualización: ${message ?: status}")
        }
    }

    /** El usuario confirmo (o descarto) el dialogo de Android. */
    fun confirmationShown() {
        val s = _state.value
        if (s is UpdateState.NeedsConfirmation) _state.value = s.release?.let { UpdateState.Installing(it) } ?: UpdateState.Idle
    }

    fun unknownSourcesSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Tras volver de Ajustes de Android con el permiso concedido. */
    suspend fun resumePendingInstall() {
        val (release, file) = pending ?: return
        if (context.packageManager.canRequestPackageInstalls()) {
            pending = null
            install(release, file)
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "AppUpdater"
        const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
