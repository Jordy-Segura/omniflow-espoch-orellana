package ec.edu.espoch.omniflow.ui.screens.dashboard

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.edu.espoch.omniflow.data.repository.AlertRepository
import ec.edu.espoch.omniflow.data.repository.AuthRepository
import ec.edu.espoch.omniflow.data.repository.LabRepository
import ec.edu.espoch.omniflow.domain.model.LabSnapshot
import ec.edu.espoch.omniflow.domain.model.LabStatus
import ec.edu.espoch.omniflow.domain.model.User
import ec.edu.espoch.omniflow.ui.components.AppCard
import ec.edu.espoch.omniflow.ui.components.IconBadge
import ec.edu.espoch.omniflow.ui.components.InfoBanner
import ec.edu.espoch.omniflow.ui.components.LabCard
import ec.edu.espoch.omniflow.ui.components.SectionTitle
import ec.edu.espoch.omniflow.ui.components.StatCard
import ec.edu.espoch.omniflow.ui.components.UpdateBanner
import ec.edu.espoch.omniflow.update.UpdateViewModel
import androidx.compose.ui.platform.LocalContext
import ec.edu.espoch.omniflow.ui.theme.InkSoft
import ec.edu.espoch.omniflow.ui.theme.StatusGreen
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class DashboardState(
    val user: User? = null,
    val labs: List<LabSnapshot> = emptyList(),
    val unread: Int = 0,
) {
    private val monitored get() = labs.filter { it.occupancy != null }
    val currentPeople: Int get() = monitored.sumOf { it.occupancy ?: 0 }
    val monitoredCapacity: Int get() = monitored.sumOf { it.lab.capacity }
    val activeLabs: Int get() = labs.count { it.status == LabStatus.OCUPADO || it.status == LabStatus.ANOMALO }
    val upcoming: Int get() = labs.count { it.nextClass != null && (it.minutesToNext ?: Int.MAX_VALUE) <= 180 }
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    auth: AuthRepository,
    labs: LabRepository,
    alerts: AlertRepository,
) : ViewModel() {
    val state: StateFlow<DashboardState> = combine(auth.currentUser, labs.snapshots, alerts.unreadCount) { u, l, n ->
        DashboardState(u, l, n)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())
}

@Composable
fun DashboardScreen(
    padding: PaddingValues,
    onOpenLab: (Long) -> Unit,
    onNavigate: (String) -> Unit,
    vm: DashboardViewModel = hiltViewModel(),
    updates: UpdateViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val updateState by updates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val canMonitor = s.user?.canMonitor == true

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 12.dp, bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("¡Hola, ${s.user?.firstName ?: ""}!", style = MaterialTheme.typography.headlineSmall)
                    Text("Resumen en tiempo real · ${s.user?.role?.label.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                }
                Icon(Icons.Default.AccountCircle, null, Modifier.size(44.dp), tint = InkSoft)
            }
        }
        item {
            UpdateBanner(
                updateState,
                onInstall = { updates.install() },
                onOpenPermission = { updates.openPermission(context) },
                onConfirm = { updates.confirm(context) },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    Icons.Default.Groups, "${s.currentPeople}", "Ocupación actual",
                    if (s.monitoredCapacity > 0) "de ${s.monitoredCapacity} plazas con cámara" else "Sin cámaras activas",
                    Modifier.weight(1f),
                    progress = if (s.monitoredCapacity > 0) s.currentPeople.toFloat() / s.monitoredCapacity else 0f,
                )
                StatCard(
                    Icons.Default.Science, "${s.activeLabs}", "Laboratorios activos", "de ${s.labs.size}",
                    Modifier.weight(1f),
                    progress = if (s.labs.isEmpty()) 0f else s.activeLabs.toFloat() / s.labs.size,
                    progressColor = StatusGreen,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    Icons.Default.NotificationsActive, "${s.unread}", "Alertas activas", "Requieren atención",
                    Modifier.weight(1f), onClick = { onNavigate("alerts") },
                )
                StatCard(
                    Icons.Default.Schedule, "${s.upcoming}", "Próximas clases", "En las siguientes 3 horas",
                    Modifier.weight(1f),
                )
            }
        }
        if (s.labs.none { it.hasCamera } && canMonitor) {
            item {
                InfoBanner(
                    "Ningún laboratorio tiene cámara activa. Usa la pestaña Cámara para convertir este teléfono " +
                        "(u otro teléfono por Wi-Fi) en la cámara de un laboratorio.",
                )
            }
        }
        item { SectionTitle("Accesos rápidos") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAccess(Icons.Default.Science, "Laboratorios", Modifier.weight(1f)) { onNavigate("labs") }
                if (canMonitor) QuickAccess(Icons.Default.Videocam, "Cámara", Modifier.weight(1f)) { onNavigate("monitor") }
                if (canMonitor) QuickAccess(Icons.Default.Assessment, "Reportes", Modifier.weight(1f)) { onNavigate("reports") }
                QuickAccess(Icons.Default.NotificationsActive, "Alertas", Modifier.weight(1f)) { onNavigate("alerts") }
            }
        }
        item {
            SectionTitle("Estado de laboratorios") {
                TextButton(onClick = { onNavigate("labs") }) { Text("Ver todos") }
            }
        }
        items(s.labs.sortedBy { it.status.ordinal }.take(4), key = { it.lab.id }) { snap ->
            Box(Modifier.animateItem()) { LabCard(snap) { onOpenLab(snap.lab.id) } }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun QuickAccess(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    AppCard(modifier, onClick) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            IconBadge(icon)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}
