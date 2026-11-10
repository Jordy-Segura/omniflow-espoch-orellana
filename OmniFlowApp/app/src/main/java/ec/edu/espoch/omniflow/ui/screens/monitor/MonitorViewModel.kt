package ec.edu.espoch.omniflow.ui.screens.monitor

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.edu.espoch.omniflow.analytics.OccupancySmoother
import ec.edu.espoch.omniflow.data.remote.api.FrameResultDto
import ec.edu.espoch.omniflow.data.repository.AttendanceRepository
import ec.edu.espoch.omniflow.data.repository.LabRepository
import ec.edu.espoch.omniflow.data.repository.MonitorSettings
import ec.edu.espoch.omniflow.data.repository.OccupancyRecorder
import ec.edu.espoch.omniflow.data.repository.SettingsRepository
import ec.edu.espoch.omniflow.domain.model.Lab
import ec.edu.espoch.omniflow.vision.FrameResult
import ec.edu.espoch.omniflow.vision.VisionEngine
import ec.edu.espoch.omniflow.vision.camera.CameraSource
import ec.edu.espoch.omniflow.vision.camera.MjpegStreamReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

data class MonitorUiState(
    val running: Boolean = false,
    val loadingModel: Boolean = false,
    val frame: Bitmap? = null,
    val result: FrameResult? = null,
    val occupancy: Int = 0,
    val insight: OccupancyRecorder.Insight? = null,
    val error: String? = null,
    val streamStatus: String? = null,
    val detectorName: String = "",
    val savedSamples: Int = 0,
    /** Ultimo resultado del reconocimiento facial en el servidor (asistencia por estudiante). */
    val attendance: FrameResultDto? = null,
    val attendanceError: String? = null,
    val attendanceRecognized: Map<String, String> = emptyMap(),
)

/**
 * Orquesta el modulo de vision en el telefono:
 *   fuente (camara propia u otro telefono) -> VisionEngine (CNN + ByteTrack) -> mediana movil
 *   -> OccupancyRecorder (BD, reglas, anomalias, prediccion) -> UI.
 */
@HiltViewModel
class MonitorViewModel @Inject constructor(
    private val engine: VisionEngine,
    private val recorder: OccupancyRecorder,
    private val settingsRepo: SettingsRepository,
    private val attendanceRepo: AttendanceRepository,
    labRepo: LabRepository,
) : ViewModel() {

    val settings: StateFlow<MonitorSettings> =
        settingsRepo.monitor.stateIn(viewModelScope, SharingStarted.Eagerly, MonitorSettings())
    val labs: StateFlow<List<Lab>> = labRepo.labs.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _state = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = _state

    private val frames = Channel<Bitmap>(Channel.CONFLATED)
    private val busy = AtomicBoolean(false)
    /** Hay un fotograma esperando en la cola: no convertir mas hasta que el modelo lo tome (evita lag y OOM). */
    private val pending = AtomicBoolean(false)
    private val smoother = OccupancySmoother()
    private var processJob: Job? = null
    private var streamJob: Job? = null
    private var lastSaveAt = 0L
    private var lastAttendanceAt = 0L
    @Volatile private var attendanceUploading = false
    /** Configuracion con la que se inicio el monitoreo (si cambia en Ajustes se reinicia). */
    private var activeConfig: List<Any>? = null

    private fun MonitorSettings.key(): List<Any> =
        listOf(source, if (source == CameraSource.IP_STREAM) streamUrl else "", useGpu, attendanceMode)

    fun restartIfConfigChanged(s: MonitorSettings) {
        val active = activeConfig ?: return
        if (_state.value.running && active != s.key()) {
            stop()
            start()
        }
    }

    val isBusy: () -> Boolean = { busy.get() || pending.get() || !_state.value.running }

    init {
        // Precarga del modelo al abrir la pantalla: "Iniciar" responde de inmediato.
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { engine.ensureDetector(settingsRepo.currentMonitor().useGpu) }
                .onFailure { Log.w(TAG, "No se pudo precargar el modelo", it) }
        }
    }

    fun selectLab(id: Long) = viewModelScope.launch { settingsRepo.updateMonitor { it.copy(labId = id) } }

    fun toggleAttendance(enabled: Boolean) = viewModelScope.launch { settingsRepo.updateMonitor { it.copy(attendanceMode = enabled) } }

    fun toggleCountingLine(enabled: Boolean) = viewModelScope.launch { settingsRepo.updateMonitor { it.copy(countingLine = enabled) } }

    fun start() {
        if (_state.value.running) return
        val s = settings.value
        engine.reset()
        recorder.resetCounters()
        smoother.clear()
        lastSaveAt = 0L
        activeConfig = s.key()
        _state.update { MonitorUiState(running = true, loadingModel = true) }

        processJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                engine.ensureDetector(s.useGpu)
                _state.update { it.copy(loadingModel = false, detectorName = engine.detectorName) }
            } catch (e: Throwable) {
                Log.e(TAG, "No se pudo cargar el modelo", e)
                _state.update { it.copy(running = false, loadingModel = false, error = "No se pudo cargar el modelo: ${e.message}") }
                return@launch
            }
            for (bmp in frames) {
                busy.set(true)
                pending.set(false)
                try {
                    process(bmp)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "Error en el pipeline", e)
                    _state.update { it.copy(error = "Error de procesamiento: ${e.message}") }
                } finally {
                    busy.set(false)
        pending.set(false)
                }
            }
        }

        if (s.source == CameraSource.IP_STREAM) startStream(s.streamUrl, if (s.attendanceMode) 1280 else 640)
    }

    private fun startStream(url: String, maxSide: Int) {
        streamJob = viewModelScope.launch(Dispatchers.IO) {
            val reader = MjpegStreamReader(url, maxSide)
            while (true) {
                try {
                    _state.update { it.copy(streamStatus = "Conectando a $url…") }
                    var first = true
                    reader.stream { bmp ->
                        if (first) {
                            first = false
                            _state.update { it.copy(streamStatus = null, error = null) }
                        }
                        if (!busy.get() && pending.compareAndSet(false, true)) frames.trySend(bmp)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update {
                        it.copy(streamStatus = "Sin conexión con la cámara remota (${e.message}). Reintentando en 3 s…")
                    }
                    delay(3000)
                }
            }
        }
    }

    /** Frames de la camara propia del telefono (CameraX, hilo de fondo). */
    fun onPhoneFrame(bmp: Bitmap) {
        if (_state.value.running && !busy.get() && pending.compareAndSet(false, true)) frames.trySend(bmp)
    }

    fun onCameraError(msg: String) = _state.update { it.copy(error = msg) }

    private suspend fun process(bmp: Bitmap) {
        val s = settings.value
        engine.counter.linePosition = s.linePosition
        engine.counter.downIsEntry = s.downIsEntry

        // En modo asistencia el fotograma llega a 1280 px; el detector de personas trabaja con una copia de 640 px.
        val detectFrame = if (maxOf(bmp.width, bmp.height) > 800) {
            val k = 640f / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        } else bmp
        val result = engine.process(detectFrame, s.useGpu, s.minConfidence)
        val occupancy = smoother.add(result.peopleCount)
        _state.update { it.copy(frame = bmp, result = result, occupancy = occupancy) }

        val labId = s.labId ?: return
        maybeSendAttendanceFrame(s, labId, bmp, result.peopleCount)
        val now = System.currentTimeMillis()
        if (now - lastSaveAt >= s.sampleSeconds * 1000L) {
            lastSaveAt = now
            val lab = labs.value.firstOrNull { it.id == labId } ?: return
            val insight = recorder.record(
                lab = lab,
                count = occupancy,
                totalEntries = if (s.countingLine) result.entries else 0,
                totalExits = if (s.countingLine) result.exits else 0,
                source = s.source,
                now = now,
            )
            _state.update { it.copy(insight = insight, savedSamples = it.savedSamples + 1) }
        }
    }

    /** Cada 8 s (si hay personas) envia el fotograma al servidor para reconocer rostros y marcar asistencia. */
    private fun maybeSendAttendanceFrame(s: MonitorSettings, labId: Long, bmp: Bitmap, people: Int) {
        if (!s.attendanceMode || attendanceUploading || people == 0) return
        val now = System.currentTimeMillis()
        if (now - lastAttendanceAt < ATTENDANCE_INTERVAL_MS) return
        val lab = labs.value.firstOrNull { it.id == labId } ?: return
        lastAttendanceAt = now
        attendanceUploading = true
        viewModelScope.launch(Dispatchers.IO) {
            attendanceRepo.sendFrame(lab.code, bmp, s.deviceKey)
                .onSuccess { r ->
                    _state.update {
                        it.copy(
                            attendance = r, attendanceError = null,
                            attendanceRecognized = it.attendanceRecognized + r.recognized.associate { x -> x.cedula to x.nombre },
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(attendanceError = e.message) } }
            attendanceUploading = false
        }
    }

    fun stop() {
        processJob?.cancel()
        streamJob?.cancel()
        processJob = null
        streamJob = null
        busy.set(false)
        pending.set(false)
        activeConfig = null
        // Vaciar un frame pendiente
        frames.tryReceive()
        _state.update { it.copy(running = false, loadingModel = false, streamStatus = null) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    companion object {
        private const val TAG = "MonitorViewModel"
        const val ATTENDANCE_INTERVAL_MS = 8_000L
    }
}
