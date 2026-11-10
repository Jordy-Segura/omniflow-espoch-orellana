package ec.edu.espoch.omniflow.ui.screens.attendance

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.edu.espoch.omniflow.data.repository.AttendanceRepository
import ec.edu.espoch.omniflow.data.repository.AuthRepository
import ec.edu.espoch.omniflow.ui.components.AppCard
import ec.edu.espoch.omniflow.ui.components.InfoBanner
import ec.edu.espoch.omniflow.ui.theme.InkSoft
import ec.edu.espoch.omniflow.ui.theme.StatusGreen
import ec.edu.espoch.omniflow.vision.camera.PhoneCameraFeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val FACE_CONSENT_TEXT =
    "Autorizo el tratamiento de mi dato biométrico facial con la única finalidad de registrar mi asistencia a los " +
        "laboratorios de la ESPOCH mediante el sistema OmniFlow. Se guarda solo una plantilla numérica (no la foto), " +
        "no se comparte con terceros y puedo eliminarla en cualquier momento desde la app (Ley Orgánica de Protección " +
        "de Datos Personales del Ecuador)."

data class EnrollState(
    val photos: List<Bitmap> = emptyList(),
    val consent: Boolean = false,
    val sending: Boolean = false,
    val result: String? = null,
    val error: String? = null,
    val done: Boolean = false,
)

@HiltViewModel
class FaceEnrollViewModel @Inject constructor(handle: SavedStateHandle, private val repo: AttendanceRepository) : ViewModel() {
    /** null = el usuario registra su propio rostro; si no, un docente registra a un estudiante. */
    val cedula: String? = handle.get<String>("cedula")?.ifBlank { null }
    val name: String = handle.get<String>("name").orEmpty()
    private val _state = MutableStateFlow(EnrollState())
    val state: StateFlow<EnrollState> = _state
    @Volatile var latest: Bitmap? = null
    @Volatile private var lastFrameAt = 0L

    /** Solo se conserva un fotograma cada 150 ms (vista previa). */
    val isBusy: () -> Boolean = { System.currentTimeMillis() - lastFrameAt < 150 }
    fun onFrame(b: Bitmap) { latest = b; lastFrameAt = System.currentTimeMillis() }

    fun capture() {
        val b = latest ?: return
        _state.update { if (it.photos.size >= 5) it else it.copy(photos = it.photos + b, error = null) }
    }

    fun clear() = _state.update { it.copy(photos = emptyList()) }
    fun setConsent(v: Boolean) = _state.update { it.copy(consent = v) }

    fun send() = viewModelScope.launch {
        val s = _state.value
        _state.update { it.copy(sending = true, error = null) }
        repo.enroll(s.photos, cedula, s.consent)
            .onSuccess { r ->
                _state.update {
                    it.copy(sending = false, done = true, photos = emptyList(),
                        result = "Rostro registrado: ${r.added} foto(s) válidas (${r.templates} en total)." +
                            if (r.problems.isNotEmpty()) "\n" + r.problems.joinToString("\n") else "")
                }
            }
            .onFailure { e -> _state.update { it.copy(sending = false, error = e.message) } }
    }
}

@Composable
fun FaceEnrollScreen(padding: PaddingValues, onDone: () -> Unit, vm: FaceEnrollViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var front by remember { mutableStateOf(vm.cedula == null) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var preview by remember { mutableStateOf<Bitmap?>(null) }

    if (granted && !s.done) {
        PhoneCameraFeed(front = front, isBusy = vm.isBusy, onError = {}, onFrame = { b -> vm.onFrame(b); preview = b })
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (vm.cedula == null) "Registrar mi rostro" else "Registrar rostro de ${vm.name}", style = MaterialTheme.typography.titleLarge)
        Text("Toma 3 fotos de frente, con buena luz y sin gafas oscuras; cambia un poco el ángulo entre cada una.",
            style = MaterialTheme.typography.bodySmall, color = InkSoft)

        if (!granted) {
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Permitir cámara") }
        } else if (!s.done) {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF111114)), contentAlignment = Alignment.Center) {
                preview?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: CircularProgressIndicator(color = Color.White)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.capture() }, enabled = s.photos.size < 5, shape = RoundedCornerShape(12.dp)) { Text("Capturar (${s.photos.size}/3)") }
                OutlinedButton(onClick = { front = !front }, shape = RoundedCornerShape(12.dp)) { Text(if (front) "Cámara trasera" else "Cámara frontal") }
                if (s.photos.isNotEmpty()) OutlinedButton(onClick = { vm.clear() }, shape = RoundedCornerShape(12.dp)) { Text("Borrar") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.photos.forEach { Image(it.asImageBitmap(), null, Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop) }
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(checked = s.consent, onCheckedChange = vm::setConsent)
                Text(
                    if (vm.cedula == null) FACE_CONSENT_TEXT
                    else "El estudiante ${vm.name} leyó y aceptó: \"$FACE_CONSENT_TEXT\"",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        s.error?.let { InfoBanner(it) }
        s.result?.let { InfoBanner(it, color = StatusGreen) }
        if (s.done) {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Listo") }
        } else {
            Button(
                onClick = { vm.send() },
                enabled = s.consent && s.photos.size >= 3 && !s.sending,
                modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp),
            ) {
                if (s.sending) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp) else Text("Enviar y registrar")
            }
        }
    }
}

// ============================================================================ cambio de clave obligatorio
data class PasswordState(val current: String = "", val new: String = "", val repeat: String = "", val loading: Boolean = false, val error: String? = null)

@HiltViewModel
class ChangePasswordViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(PasswordState())
    val state: StateFlow<PasswordState> = _state
    fun update(f: (PasswordState) -> PasswordState) = _state.update(f)

    fun save() = viewModelScope.launch {
        val s = _state.value
        if (s.new != s.repeat) { _state.update { it.copy(error = "Las contraseñas nuevas no coinciden") }; return@launch }
        _state.update { it.copy(loading = true, error = null) }
        auth.changePassword(s.current, s.new).onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
    }

    fun logout() = viewModelScope.launch { auth.logout() }
}

@Composable
fun ChangePasswordScreen(vm: ChangePasswordViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().padding(24.dp).padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Crea tu contraseña", style = MaterialTheme.typography.headlineSmall)
        Text("Es tu primer ingreso: la contraseña inicial es tu cédula. Elige una nueva de al menos 8 caracteres con letras y números.",
            style = MaterialTheme.typography.bodySmall, color = InkSoft)
        OutlinedTextField(s.current, { v -> vm.update { it.copy(current = v) } }, label = { Text("Contraseña actual (cédula)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(s.new, { v -> vm.update { it.copy(new = v) } }, label = { Text("Nueva contraseña") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(s.repeat, { v -> vm.update { it.copy(repeat = v) } }, label = { Text("Repetir nueva contraseña") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        s.error?.let { InfoBanner(it) }
        Button(onClick = { vm.save() }, enabled = !s.loading, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
            Text("Guardar")
        }
        OutlinedButton(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Cerrar sesión") }
    }
}
