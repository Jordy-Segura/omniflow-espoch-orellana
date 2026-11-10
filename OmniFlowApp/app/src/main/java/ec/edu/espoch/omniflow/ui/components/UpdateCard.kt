package ec.edu.espoch.omniflow.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ec.edu.espoch.omniflow.update.UpdateState
import ec.edu.espoch.omniflow.ui.theme.EspochRed
import ec.edu.espoch.omniflow.ui.theme.InkSoft
import ec.edu.espoch.omniflow.ui.theme.StatusGreen

/** Tarjeta completa de actualizaciones (pantalla Perfil). */
@Composable
fun UpdateCard(
    state: UpdateState,
    versionName: String,
    versionCode: Int,
    autoUpdate: Boolean,
    onAutoUpdate: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onOpenPermission: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Default.SystemUpdate)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Actualizaciones", style = MaterialTheme.typography.titleSmall)
                    Text("Versión instalada $versionName ($versionCode)", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                }
            }
            UpdateStatus(state, onInstall, onOpenPermission, onConfirm)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Actualizar automáticamente", style = MaterialTheme.typography.bodyMedium)
                    Text("Descarga e instala las nuevas versiones publicadas en el servidor.",
                        style = MaterialTheme.typography.bodySmall, color = InkSoft)
                }
                Switch(checked = autoUpdate, onCheckedChange = onAutoUpdate)
            }
            OutlinedButton(onClick = onCheck, enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Text("Buscar actualizaciones")
            }
        }
    }
}

/** Aviso compacto (Inicio) solo cuando hay algo que hacer o en progreso. */
@Composable
fun UpdateBanner(state: UpdateState, onInstall: () -> Unit, onOpenPermission: () -> Unit, onConfirm: () -> Unit) {
    val visible = state is UpdateState.Available || state is UpdateState.Downloading || state is UpdateState.Installing ||
        state is UpdateState.NeedsPermission || state is UpdateState.NeedsConfirmation
    if (!visible) return
    AppCard(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.SystemUpdate, null, tint = EspochRed)
                Spacer(Modifier.width(8.dp))
                Text("Nueva versión de OmniFlow", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(6.dp))
            UpdateStatus(state, onInstall, onOpenPermission, onConfirm)
        }
    }
}

@Composable
private fun UpdateStatus(state: UpdateState, onInstall: () -> Unit, onOpenPermission: () -> Unit, onConfirm: () -> Unit) {
    when (state) {
        UpdateState.Idle -> Unit
        UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Buscando actualizaciones…", style = MaterialTheme.typography.bodySmall)
        }
        UpdateState.UpToDate -> Text("La app está al día.", style = MaterialTheme.typography.bodySmall, color = StatusGreen)
        is UpdateState.Available -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Disponible: ${state.release.versionName}", style = MaterialTheme.typography.bodyMedium)
            if (state.release.notes.isNotBlank()) Text(state.release.notes, style = MaterialTheme.typography.bodySmall, color = InkSoft)
            Button(onClick = onInstall, shape = RoundedCornerShape(12.dp)) { Text("Actualizar ahora") }
        }
        is UpdateState.Downloading -> Column {
            Text("Descargando ${state.release.versionName}… ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
        }
        is UpdateState.Installing -> Text("Instalando ${state.release.versionName}… la app se reiniciará.", style = MaterialTheme.typography.bodySmall)
        is UpdateState.NeedsPermission -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Para actualizarse sola, permite que OmniFlow instale apps (solo se pide una vez).",
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = onOpenPermission, shape = RoundedCornerShape(12.dp)) { Text("Dar permiso") }
        }
        is UpdateState.NeedsConfirmation -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Android pide confirmar la actualización.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = onConfirm, shape = RoundedCornerShape(12.dp)) { Text("Confirmar") }
        }
        is UpdateState.Error -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = EspochRed)
    }
}
