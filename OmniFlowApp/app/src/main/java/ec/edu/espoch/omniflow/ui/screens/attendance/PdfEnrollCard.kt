package ec.edu.espoch.omniflow.ui.screens.attendance

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.edu.espoch.omniflow.data.repository.AttendanceRepository
import ec.edu.espoch.omniflow.ui.components.AppCard
import ec.edu.espoch.omniflow.ui.components.InfoBanner
import ec.edu.espoch.omniflow.ui.theme.EspochRed
import ec.edu.espoch.omniflow.ui.theme.InkSoft
import ec.edu.espoch.omniflow.ui.theme.StatusAmber
import ec.edu.espoch.omniflow.ui.theme.StatusGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PdfState(
    val pending: List<Uri> = emptyList(),
    val consent: Boolean = false,
    val working: Boolean = false,
    val progress: String? = null,
    val lines: List<Pair<String, Boolean>> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class PdfEnrollViewModel @Inject constructor(private val repo: AttendanceRepository) : ViewModel() {
    private val _state = MutableStateFlow(PdfState())
    val state: StateFlow<PdfState> = _state

    fun choose(uris: List<Uri>) = _state.update { it.copy(pending = uris, consent = false, error = null) }
    fun cancel() = _state.update { it.copy(pending = emptyList()) }
    fun setConsent(v: Boolean) = _state.update { it.copy(consent = v) }

    fun upload(context: Context) = viewModelScope.launch {
        val uris = _state.value.pending
        _state.update { it.copy(pending = emptyList(), working = true, lines = emptyList(), error = null) }
        val lines = mutableListOf<Pair<String, Boolean>>()
        uris.forEachIndexed { i, uri ->
            val name = displayName(context, uri)
            _state.update { it.copy(progress = "Procesando ${i + 1} de ${uris.size}: $name") }
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                lines += "$name: no se pudo leer el archivo" to false
                return@forEachIndexed
            }
            repo.enrollPdf(bytes, consent = true)
                .onSuccess { r ->
                    r.resultados.forEach { p ->
                        val who = p.nombre?.lowercase()?.split(" ")?.joinToString(" ") { w -> w.replaceFirstChar(Char::titlecase) } ?: "Página ${p.pagina}"
                        lines += when (p.estado) {
                            "REGISTRADO" -> "$who: rostro registrado desde la matrícula" to true
                            "SIN_ROSTRO" -> "$who: la matrícula no tiene una foto con un rostro claro" to false
                            "NO_AUTORIZADO" -> "$who: esa matrícula es de otra persona" to false
                            else -> "$name (pág. ${p.pagina}): no se identificó al estudiante" to false
                        }
                    }
                }
                .onFailure { e -> lines += "$name: ${e.message}" to false }
        }
        _state.update { it.copy(working = false, progress = null, lines = lines) }
    }

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "archivo.pdf"
}

/**
 * Registro facial a partir del PDF de matricula: el servidor identifica al estudiante (cedula/nombre)
 * y usa la foto de la matricula. Estudiante: su propia matricula. Docente/coordinacion: varias a la vez.
 */
@Composable
fun PdfEnrollCard(staff: Boolean, onDone: () -> Unit = {}, vm: PdfEnrollViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> vm.choose(listOf(u)) } }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.choose(it) }
    var notified by remember { mutableStateOf(false) }

    AppCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PictureAsPdf, null, tint = EspochRed)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (staff) "Importar matrículas (PDF)" else "Registrar rostro con mi matrícula (PDF)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (staff) "Se identifica a cada estudiante por su cédula o nombre y se usa la foto de su matrícula."
                        else "Sube el PDF de tu matrícula: se usa su foto, sin tomar fotos nuevas.",
                        style = MaterialTheme.typography.bodySmall, color = InkSoft,
                    )
                }
            }
            OutlinedButton(
                onClick = { notified = false; if (staff) pickMany.launch(arrayOf("application/pdf")) else pickOne.launch(arrayOf("application/pdf")) },
                enabled = !s.working, shape = RoundedCornerShape(12.dp),
            ) { Text(if (staff) "Elegir PDFs de matrícula" else "Subir PDF de matrícula") }
            if (s.working) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                s.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            s.error?.let { InfoBanner(it) }
            if (s.lines.isNotEmpty()) {
                val ok = s.lines.count { it.second }
                InfoBanner("$ok de ${s.lines.size} registrados", color = if (ok > 0) StatusGreen else StatusAmber)
                s.lines.take(30).forEach { (t, good) ->
                    Text("• $t", style = MaterialTheme.typography.bodySmall, color = if (good) StatusGreen else StatusAmber)
                }
                if (!notified && ok > 0) { notified = true; onDone() }
            }
        }
    }

    if (s.pending.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { vm.cancel() },
            title = { Text("Consentimiento") },
            text = {
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(checked = s.consent, onCheckedChange = vm::setConsent)
                    Text(
                        if (staff) "Confirmo que los estudiantes de ${if (s.pending.size == 1) "este PDF" else "estos ${s.pending.size} PDF"} de matrícula aceptaron: \"$FACE_CONSENT_TEXT\""
                        else FACE_CONSENT_TEXT,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.upload(context) }, enabled = s.consent) { Text("Subir") } },
            dismissButton = { TextButton(onClick = { vm.cancel() }) { Text("Cancelar") } },
        )
    }
}
