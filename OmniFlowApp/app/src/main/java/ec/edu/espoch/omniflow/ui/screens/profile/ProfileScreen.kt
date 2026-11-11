package ec.edu.espoch.omniflow.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.edu.espoch.omniflow.BuildConfig
import ec.edu.espoch.omniflow.data.repository.AuthRepository
import ec.edu.espoch.omniflow.domain.model.AuthSource
import ec.edu.espoch.omniflow.domain.model.User
import ec.edu.espoch.omniflow.ui.components.AppCard
import ec.edu.espoch.omniflow.ui.components.IconBadge
import ec.edu.espoch.omniflow.ui.components.UpdateCard
import ec.edu.espoch.omniflow.update.UpdateViewModel
import androidx.compose.ui.platform.LocalContext
import ec.edu.espoch.omniflow.ui.theme.EspochRed
import ec.edu.espoch.omniflow.ui.theme.InkSoft
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {
    val user: StateFlow<User?> = auth.currentUser.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    fun logout() = viewModelScope.launch { auth.logout() }
}

@Composable
fun ProfileScreen(
    padding: PaddingValues,
    onOpenSettings: () -> Unit,
    vm: ProfileViewModel = hiltViewModel(),
    updates: UpdateViewModel = hiltViewModel(),
) {
    val user by vm.user.collectAsStateWithLifecycle()
    val updateState by updates.state.collectAsStateWithLifecycle()
    val autoUpdate by updates.autoUpdate.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val u = user ?: return

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Mi perfil", style = MaterialTheme.typography.titleLarge)
        AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Default.Person, size = 56.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(u.fullName, style = MaterialTheme.typography.titleMedium)
                    Text(u.role.label, style = MaterialTheme.typography.bodyMedium, color = EspochRed)
                    if (u.email.isNotBlank()) Text(u.email, style = MaterialTheme.typography.bodySmall, color = InkSoft)
                }
            }
        }
        // El usuario suele ser un correo largo: va en su propia fila para que no se corte en varias lineas.
        InfoTile(Icons.Default.Badge, "Usuario", u.username, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val origin = when (u.source) {
                AuthSource.OMNIFLOW -> "Cuenta OmniFlow"
                AuthSource.OASIS -> "OASIS"
                AuthSource.DEMO -> "Demostración"
            }
            InfoTile(Icons.Default.Info, "Origen", origin, Modifier.weight(1f))
            if (u.careerCode != null) InfoTile(Icons.Default.Info, "Carrera", u.careerCode, Modifier.weight(1f))
        }

        if (u.canMonitor) {
            AppCard(Modifier.fillMaxWidth(), onClick = onOpenSettings) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Default.Tune)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Ajustes de cámara e IA", style = MaterialTheme.typography.titleSmall)
                        Text("Fuente de video, modelo, línea de conteo, backend", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = InkSoft)
                }
            }
        }

        UpdateCard(
            state = updateState,
            versionName = updates.versionName,
            versionCode = updates.versionCode,
            autoUpdate = autoUpdate,
            onAutoUpdate = updates::setAutoUpdate,
            onCheck = { updates.check() },
            onInstall = { updates.install() },
            onOpenPermission = { updates.openPermission(context) },
            onConfirm = { updates.confirm(context) },
        )

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.logout() }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.AutoMirrored.Filled.Logout, null)
            Spacer(Modifier.width(8.dp))
            Text("Cerrar sesión")
        }
        Text(
            "OmniFlow App v${BuildConfig.VERSION_NAME} · ESPOCH",
            style = MaterialTheme.typography.labelSmall, color = InkSoft,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun InfoTile(icon: ImageVector, label: String, value: String, modifier: Modifier) {
    AppCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = InkSoft)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = InkSoft)
                Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
