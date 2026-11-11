package ec.edu.espoch.omniflow.ui.screens.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.edu.espoch.omniflow.data.repository.AlertRepository
import ec.edu.espoch.omniflow.domain.model.Alert
import ec.edu.espoch.omniflow.domain.model.AlertType
import ec.edu.espoch.omniflow.ui.components.AppCard
import ec.edu.espoch.omniflow.ui.components.EmptyState
import ec.edu.espoch.omniflow.ui.theme.EspochRed
import ec.edu.espoch.omniflow.ui.theme.InkSoft
import ec.edu.espoch.omniflow.ui.theme.StatusAmber
import ec.edu.espoch.omniflow.ui.theme.StatusPurple
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import javax.inject.Inject

enum class AlertFilter { TODAS, SIN_LEER, HOY }

data class AlertsState(val filter: AlertFilter = AlertFilter.TODAS, val all: List<Alert> = emptyList()) {
    private val startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val unread get() = all.count { !it.read }
    val today get() = all.count { it.timestamp >= startOfDay }
    val visible: List<Alert>
        get() = when (filter) {
            AlertFilter.TODAS -> all
            AlertFilter.SIN_LEER -> all.filter { !it.read }
            AlertFilter.HOY -> all.filter { it.timestamp >= startOfDay }
        }
}

@HiltViewModel
class AlertsViewModel @Inject constructor(private val repo: AlertRepository) : ViewModel() {
    private val filter = MutableStateFlow(AlertFilter.TODAS)
    val state: StateFlow<AlertsState> = combine(filter, repo.alerts) { f, a -> AlertsState(f, a) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlertsState())

    fun setFilter(f: AlertFilter) { filter.value = f }
    fun markRead(id: Long) = viewModelScope.launch { repo.markRead(id) }
    fun markAllRead() = viewModelScope.launch { repo.markAllRead() }
}

fun AlertType.icon() = when (this) {
    AlertType.SOBREAFORO -> Icons.Default.Groups
    AlertType.FUERA_DE_HORARIO -> Icons.Default.Schedule
    AlertType.AUSENCIA_DOCENTE -> Icons.Default.PersonOff
    AlertType.SALIDA_ANTICIPADA -> Icons.AutoMirrored.Filled.DirectionsWalk
    AlertType.ANOMALIA_ML -> Icons.Default.QueryStats
    AlertType.PROXIMA_CLASE -> Icons.Default.Info
}

fun AlertType.tint(): Color = when (this) {
    AlertType.ANOMALIA_ML -> StatusPurple
    AlertType.SALIDA_ANTICIPADA -> StatusAmber
    AlertType.PROXIMA_CLASE -> Color(0xFF1E88E5)
    else -> EspochRed
}

@Composable
fun AlertsScreen(padding: PaddingValues, vm: AlertsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val time = remember(locale) { SimpleDateFormat("dd/MM HH:mm", locale) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Alertas y notificaciones", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (s.unread > 0) TextButton(onClick = { vm.markAllRead() }) { Text("Marcar leídas") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    AlertFilter.TODAS to "Todas (${s.all.size})",
                    AlertFilter.SIN_LEER to "Sin leer (${s.unread})",
                    AlertFilter.HOY to "Hoy (${s.today})",
                ).forEach { (f, label) ->
                    FilterChip(
                        selected = s.filter == f,
                        onClick = { vm.setFilter(f) },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = EspochRed, selectedLabelColor = Color.White),
                    )
                }
            }
        }
        if (s.visible.isEmpty()) {
            item {
                EmptyState(
                    Icons.Default.NotificationsNone, "Sin alertas",
                    "Las alertas aparecen cuando la cámara detecta sobreaforo, uso fuera de horario, ausencias o anomalías.",
                )
            }
        }
        items(s.visible, key = { it.id }) { a ->
            AppCard(Modifier.fillMaxWidth(), onClick = { vm.markRead(a.id) }) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(a.type.icon(), null, tint = a.type.tint(), modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.type.title, style = MaterialTheme.typography.titleSmall, color = a.type.tint())
                        Text(a.labName, style = MaterialTheme.typography.bodySmall)
                        Text(a.message, style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(time.format(Date(a.timestamp)), style = MaterialTheme.typography.labelSmall, color = InkSoft)
                        if (!a.read) Text("●", color = EspochRed)
                    }
                }
            }
        }
        item { Spacer(Modifier.fillMaxWidth()) }
    }
}
