package ec.edu.espoch.omniflow.ui.screens.settings

import android.graphics.Bitmap
import ec.edu.espoch.omniflow.utils.NotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.edu.espoch.omniflow.data.remote.oasis.OasisSoapClient
import ec.edu.espoch.omniflow.data.repository.DemoDataSeeder
import ec.edu.espoch.omniflow.data.repository.MonitorSettings
import ec.edu.espoch.omniflow.data.repository.SettingsRepository
import ec.edu.espoch.omniflow.data.repository.SyncRepository
import ec.edu.espoch.omniflow.ui.components.AppCard
import ec.edu.espoch.omniflow.ui.components.InfoBanner
import ec.edu.espoch.omniflow.ui.components.SectionTitle
import ec.edu.espoch.omniflow.ui.theme.InkSoft
import ec.edu.espoch.omniflow.ui.theme.StatusAmber
import ec.edu.espoch.omniflow.ui.theme.StatusGreen
import ec.edu.espoch.omniflow.vision.camera.CameraSource
import ec.edu.espoch.omniflow.vision.camera.MjpegStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val sync: SyncRepository,
    private val seeder: DemoDataSeeder,
    oasis: OasisSoapClient,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val settings: StateFlow<MonitorSettings> = repo.monitor.stateIn(viewModelScope, SharingStarted.Eagerly, MonitorSettings())
    val alertSound: StateFlow<Boolean> = repo.alertSoundFlow.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAlertSound(v: Boolean) = viewModelScope.launch {
        repo.setAlertSound(v)
        if (v) NotificationHelper.playAlertSound(context)
    }

    fun testAlertSound() = NotificationHelper.playAlertSound(context)
    val oasisConfigured = oasis.hasServiceCredentials

    val message = MutableStateFlow<String?>(null)
    val preview = MutableStateFlow<Bitmap?>(null)

    fun update(t: (MonitorSettings) -> MonitorSettings) = viewModelScope.launch { repo.updateMonitor(t) }

    /** Descarga un solo frame del otro telefono / camara IP para verificar la URL. */
    fun testStream(url: String) = viewModelScope.launch {
        message.value = "Probando $url…"
        preview.value = null
        try {
            val bmp = withContext(Dispatchers.IO) {
                withTimeout(10_000) {
                    var got: Bitmap? = null
                    try {
                        MjpegStreamReader(url).stream { b -> got = b; throw FrameReceived() }
                    } catch (_: FrameReceived) {
                    }
                    got
                }
            }
            preview.value = bmp
            message.value = if (bmp != null) "Conexión correcta: ${bmp.width}×${bmp.height}" else "No se recibió ninguna imagen"
        } catch (e: Exception) {
            message.value = "No se pudo conectar: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun testBackend() = viewModelScope.launch {
        message.value = "Probando backend…"
        message.value = sync.testConnection().fold({ it }, { "Backend no disponible: ${it.message}" })
    }

    fun clearDemo() = viewModelScope.launch {
        seeder.clearDemoData()
        message.value = "Datos de demostración eliminados"
    }

    private class FrameReceived : RuntimeException()
}

@Composable
fun SettingsScreen(padding: PaddingValues, vm: SettingsViewModel = hiltViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val sound by vm.alertSound.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(s.streamUrl) }
    var apiUrl by remember { mutableStateOf(s.apiUrl) }
    LaunchedEffect(s.streamUrl) { url = s.streamUrl }
    LaunchedEffect(s.apiUrl) { apiUrl = s.apiUrl }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Ajustes de cámara e IA", style = MaterialTheme.typography.titleLarge)
        message?.let { InfoBanner(it) }

        SectionTitle("Fuente de video")
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                CameraSource.entries.forEach { src ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = s.source == src, onClick = { vm.update { it.copy(source = src) } })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = s.source == src, onClick = { vm.update { it.copy(source = src) } })
                        Column {
                            Text(src.label, style = MaterialTheme.typography.titleSmall)
                            Text(src.description, style = MaterialTheme.typography.bodySmall, color = InkSoft)
                        }
                    }
                }
            }
        }

        if (s.source == CameraSource.IP_STREAM) {
            AppCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Usar otro teléfono como cámara", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "1. En el otro teléfono instala «IP Webcam» (o DroidCam) y pulsa «Iniciar servidor».\n" +
                            "2. Conecta ambos teléfonos a la misma red Wi-Fi.\n" +
                            "3. Escribe la dirección que muestra la app, terminada en /video (stream) o /shot.jpg (fotos).\n" +
                            "Cuando llegue la cámara IP real, solo cambia esta URL por la MJPEG de la cámara.",
                        style = MaterialTheme.typography.bodySmall, color = InkSoft,
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL del stream") },
                        placeholder = { Text("http://192.168.1.50:8080/video") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { vm.update { it.copy(streamUrl = url) } }) { Text("Guardar") }
                        OutlinedButton(onClick = { vm.update { it.copy(streamUrl = url) }; vm.testStream(url) }) { Text("Probar conexión") }
                    }
                    preview?.let {
                        Image(
                            it.asImageBitmap(), "Vista previa",
                            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }

        SectionTitle("Modelo de detección")
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                Text("Confianza mínima: ${(s.minConfidence * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Detecciones por debajo de este valor no cuentan como persona (ByteTrack igual las usa para no perder a alguien ocluido).",
                    style = MaterialTheme.typography.bodySmall, color = InkSoft,
                )
                Slider(value = s.minConfidence, onValueChange = { v -> vm.update { it.copy(minConfidence = v) } }, valueRange = 0.3f..0.8f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Acelerar con GPU", style = MaterialTheme.typography.titleSmall)
                        Text("Más FPS en teléfonos compatibles; si falla se usa CPU.", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    }
                    Switch(checked = s.useGpu, onCheckedChange = { v -> vm.update { it.copy(useGpu = v) } })
                }
                Spacer(Modifier.height(8.dp))
                Text("Guardar una muestra cada ${s.sampleSeconds} s", style = MaterialTheme.typography.titleSmall)
                Slider(
                    value = s.sampleSeconds.toFloat(),
                    onValueChange = { v -> vm.update { it.copy(sampleSeconds = v.toInt()) } },
                    valueRange = 5f..60f, steps = 10,
                )
            }
        }

        SectionTitle("Línea virtual de conteo")
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Contar entradas y salidas", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(checked = s.countingLine, onCheckedChange = { v -> vm.update { it.copy(countingLine = v) } })
                }
                Text("Posición vertical: ${(s.linePosition * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                Slider(value = s.linePosition, onValueChange = { v -> vm.update { it.copy(linePosition = v) } }, valueRange = 0.1f..0.9f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bajar = entrar", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = s.downIsEntry, onCheckedChange = { v -> vm.update { it.copy(downIsEntry = v) } })
                }
            }
        }

        SectionTitle("Alertas")
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sonido de alertas", style = MaterialTheme.typography.titleSmall)
                        Text("Suena un tono y vibra cuando hay sobreaforo, ausencia del docente u otra alerta.",
                            style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    }
                    Switch(checked = sound, onCheckedChange = vm::setAlertSound)
                }
                OutlinedButton(onClick = vm::testAlertSound) { Text("Probar sonido") }
            }
        }

        SectionTitle("Integraciones")
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("OASIS: conectado (login institucional)", style = MaterialTheme.typography.titleSmall, color = StatusGreen)
                Text(
                    if (vm.oasisConfigured) "Con credenciales de servicio (local.properties)."
                    else "Sin credenciales de servicio: los horarios, docentes y estudiantes se consultan igual.",
                    style = MaterialTheme.typography.bodySmall, color = InkSoft,
                )
                OutlinedTextField(
                    value = apiUrl,
                    onValueChange = { apiUrl = it },
                    label = { Text("URL del backend OmniFlow (FastAPI)") },
                    placeholder = { Text("https://omniflow.tudominio.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.update { it.copy(apiUrl = apiUrl) } }) { Text("Guardar") }
                    OutlinedButton(onClick = { vm.update { it.copy(apiUrl = apiUrl) }; vm.testBackend() }) { Text("Probar") }
                }
            }
        }

        SectionTitle("Datos")
        AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Datos de prueba", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Los reportes muestran solo lo que registran las cámaras. Borra aquí los datos de prueba que hayan quedado de versiones anteriores.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = vm::clearDemo) { Text("Borrar") }
            }
        }
    }
}
