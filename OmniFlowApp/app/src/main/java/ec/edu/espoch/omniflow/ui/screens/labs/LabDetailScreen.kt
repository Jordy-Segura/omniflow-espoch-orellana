package ec.edu.espoch.omniflow.ui.screens.labs

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.edu.espoch.omniflow.analytics.OccupancyForecaster
import ec.edu.espoch.omniflow.data.repository.AlertRepository
import ec.edu.espoch.omniflow.data.repository.AuthRepository
import ec.edu.espoch.omniflow.data.repository.LabRepository
import ec.edu.espoch.omniflow.data.repository.SettingsRepository
import ec.edu.espoch.omniflow.domain.model.Alert
import ec.edu.espoch.omniflow.domain.model.ClassSession
import ec.edu.espoch.omniflow.domain.model.LabSnapshot
import ec.edu.espoch.omniflow.domain.model.ScheduleSlot
import ec.edu.espoch.omniflow.domain.model.User
import ec.edu.espoch.omniflow.ui.components.AppCard
import ec.edu.espoch.omniflow.ui.components.LabHeroImage
import ec.edu.espoch.omniflow.ui.components.SectionTitle
import ec.edu.espoch.omniflow.ui.components.StatusChip
import ec.edu.espoch.omniflow.ui.theme.EspochRed
import ec.edu.espoch.omniflow.ui.theme.InkSoft
import ec.edu.espoch.omniflow.ui.theme.StatusGreen
import ec.edu.espoch.omniflow.ui.theme.color
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class LabDetailState(
    val snapshot: LabSnapshot? = null,
    val schedule: List<ScheduleSlot> = emptyList(),
    val session: ClassSession? = null,
    val forecast: OccupancyForecaster.Forecast? = null,
    val alerts: List<Alert> = emptyList(),
    val user: User? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LabDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    repo: LabRepository,
    alerts: AlertRepository,
    auth: AuthRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val labId: Long = checkNotNull(handle.get<Long>("labId"))

    private val snapshot = repo.snapshot(labId)
    private val session = snapshot.map { it?.currentClass }.distinctUntilChanged().flatMapLatest { repo.currentSession(it) }
    private val forecast = combine(repo.recentReadings(labId), snapshot) { readings, snap ->
        snap?.let { OccupancyForecaster.forecast(readings, 15, it.lab.capacity) }
    }

    val state: StateFlow<LabDetailState> = combine(
        combine(snapshot, repo.scheduleForLab(labId), session) { a, b, c -> Triple(a, b, c) },
        forecast,
        alerts.alerts.map { l -> l.filter { it.labId == labId }.take(5) },
        auth.currentUser,
    ) { (snap, sched, sess), fc, al, user ->
        LabDetailState(snap, sched, sess, fc, al, user)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LabDetailState())

    fun useThisPhoneAsCamera(then: () -> Unit) {
        viewModelScope.launch {
            settings.updateMonitor { it.copy(labId = labId) }
            then()
        }
    }
}

private val DAYS = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")

@Composable
fun LabDetailScreen(padding: PaddingValues, onMonitor: () -> Unit, vm: LabDetailViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snap = s.snapshot ?: return
    val time = SimpleDateFormat("HH:mm", Locale.getDefault())

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { LabHeroImage(Modifier.fillMaxWidth(), height = 150.dp, icon = Icons.Default.Computer) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(snap.lab.name, style = MaterialTheme.typography.titleLarge)
                    Text("${snap.lab.location} · ${snap.lab.code}", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                }
                StatusChip(snap.status)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppCard(Modifier.weight(1f)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Groups, null, tint = StatusGreen)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (snap.occupancy != null) "${snap.occupancy} / ${snap.lab.capacity}" else "— / ${snap.lab.capacity}",
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        Text("Aforo actual", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { snap.occupancyRatio.coerceAtMost(1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = snap.status.color(),
                            strokeCap = StrokeCap.Round,
                        )
                        if (!snap.hasCamera) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                snap.lastUpdate?.let { "Última lectura ${time.format(Date(it))}" } ?: "Sin cámara asignada",
                                style = MaterialTheme.typography.labelSmall, color = InkSoft,
                            )
                        }
                    }
                }
                AppCard(Modifier.weight(1f)) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Schedule, null, tint = EspochRed, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Programación actual", style = MaterialTheme.typography.labelSmall, color = InkSoft)
                        }
                        val cur = snap.currentClass
                        Text(cur?.subject ?: "Sin clase", style = MaterialTheme.typography.titleSmall)
                        Text(
                            cur?.timeRange ?: snap.nextClass?.let { "Siguiente: ${it.subject} ${ScheduleSlot.fmt(it.startMinute)}" } ?: "Sin más clases hoy",
                            style = MaterialTheme.typography.bodySmall, color = InkSoft,
                        )
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppCard(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, null, tint = InkSoft)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Docente", style = MaterialTheme.typography.labelSmall, color = InkSoft)
                            Text(snap.currentClass?.teacher ?: "—", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                AppCard(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.HowToReg, null, tint = InkSoft)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Asistencia (pico)", style = MaterialTheme.typography.labelSmall, color = InkSoft)
                            Text(
                                if (snap.currentClass != null) "${s.session?.peakCount ?: 0} / ${snap.currentClass.enrolled}" else "—",
                                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
        s.forecast?.let { fc ->
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Insights, null, tint = EspochRed)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Predicción ML (15 min): ${fc.predicted} personas", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Regresión lineal + Holt · tendencia ${"%+.1f".format(fc.trendPerHour)} pers/h · R² ${"%.2f".format(fc.r2)} · ${fc.samples} muestras",
                                style = MaterialTheme.typography.bodySmall, color = InkSoft,
                            )
                        }
                    }
                }
            }
        }
        item {
            if (s.alerts.isEmpty()) {
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = StatusGreen)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("Sin alertas", style = MaterialTheme.typography.titleSmall, color = StatusGreen)
                            Text("El laboratorio funciona con normalidad.", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.alerts.forEach { a ->
                        AppCard(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, null, tint = EspochRed)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.type.title, style = MaterialTheme.typography.titleSmall, color = EspochRed)
                                    Text(a.message, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(time.format(Date(a.timestamp)), style = MaterialTheme.typography.labelSmall, color = InkSoft)
                            }
                        }
                    }
                }
            }
        }
        if (s.user?.canMonitor == true) {
            item {
                Button(
                    onClick = { vm.useThisPhoneAsCamera(onMonitor) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Videocam, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Monitorear este laboratorio con cámara")
                }
            }
        }
        item { SectionTitle("Horario semanal") }
        if (s.schedule.isEmpty()) {
            item { Text("No hay clases registradas para este laboratorio.", style = MaterialTheme.typography.bodySmall, color = InkSoft) }
        }
        items(s.schedule, key = { it.id }) { slot ->
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(86.dp)) {
                        Text(DAYS[slot.dayOfWeek - 1], style = MaterialTheme.typography.labelMedium)
                        Text(slot.timeRange, style = MaterialTheme.typography.labelSmall, color = InkSoft)
                    }
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(slot.subject, style = MaterialTheme.typography.titleSmall)
                        Text("${slot.teacher} · ${slot.parallel} · ${slot.enrolled} est.", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    }
                }
            }
        }
    }
}
