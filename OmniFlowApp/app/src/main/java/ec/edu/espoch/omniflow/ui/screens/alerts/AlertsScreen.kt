package ec.edu.espoch.omniflow.ui.screens.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import ec.edu.espoch.omniflow.data.repository.LabRepository
import ec.edu.espoch.omniflow.domain.model.Lab
import ec.edu.espoch.omniflow.domain.model.ScheduleSlot
import ec.edu.espoch.omniflow.ui.components.PulsingDot
import java.time.Instant
import java.util.Locale
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

/** Alerta abierta con el contexto que la explica. */
data class AlertDetail(val alert: Alert, val lab: Lab?, val slot: ScheduleSlot?)

@HiltViewModel
class AlertsViewModel @Inject constructor(private val repo: AlertRepository, private val labs: LabRepository) : ViewModel() {
    private val filter = MutableStateFlow(AlertFilter.TODAS)
    val state: StateFlow<AlertsState> = combine(filter, repo.alerts) { f, a -> AlertsState(f, a) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AlertsState())
    private val _detail = MutableStateFlow<AlertDetail?>(null)
    val detail: StateFlow<AlertDetail?> = _detail

    fun open(a: Alert) = viewModelScope.launch {
        repo.markRead(a.id)
        val at = Instant.ofEpochMilli(a.timestamp).atZone(ZoneId.systemDefault())
        val minute = at.hour * 60 + at.minute
        val slots = labs.slotsForLab(a.labId).filter { it.dayOfWeek == at.dayOfWeek.value }
        val slot = slots.firstOrNull { minute in it.startMinute until it.endMinute }
            ?: slots.filter { it.startMinute >= minute }.minByOrNull { it.startMinute }
        _detail.value = AlertDetail(a.copy(read = true), labs.getLab(a.labId), slot)
    }

    fun close() { _detail.value = null }

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

/** Que significa cada alerta y que conviene hacer. */
fun AlertType.meaning(): String = when (this) {
    AlertType.SOBREAFORO -> "La cámara contó más personas que la capacidad del laboratorio. Puede haber estudiantes de pie o sin equipo."
    AlertType.FUERA_DE_HORARIO -> "Se detectaron personas en el laboratorio cuando no había ninguna clase programada."
    AlertType.AUSENCIA_DOCENTE -> "La clase ya debía haber empezado y la cámara no registró presencia en el laboratorio."
    AlertType.SALIDA_ANTICIPADA -> "El laboratorio quedó vacío antes de la hora de fin de la clase."
    AlertType.ANOMALIA_ML -> "El modelo de aprendizaje automático detectó una ocupación muy distinta a la habitual para esta hora."
    AlertType.PROXIMA_CLASE -> "Una clase está por comenzar en este laboratorio."
}

fun AlertType.action(): String = when (this) {
    AlertType.SOBREAFORO -> "Verifica que todos tengan equipo y, si es necesario, redistribuye a los estudiantes en otro laboratorio libre."
    AlertType.FUERA_DE_HORARIO -> "Confirma si el uso está autorizado (tutoría, mantenimiento) o si el laboratorio quedó abierto."
    AlertType.AUSENCIA_DOCENTE -> "Comunícate con el docente o con la coordinación de la carrera para saber si la clase se dictará."
    AlertType.SALIDA_ANTICIPADA -> "Revisa con el docente si la clase terminó antes por algún motivo y regístralo si corresponde."
    AlertType.ANOMALIA_ML -> "Revisa la cámara del laboratorio: puede ser un evento especial o un problema con el conteo."
    AlertType.PROXIMA_CLASE -> "Prepara el laboratorio y verifica que la cámara esté activa para tomar la asistencia."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(padding: PaddingValues, onOpenLab: (Long) -> Unit, vm: AlertsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
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
            AppCard(Modifier.fillMaxWidth().animateItem(), onClick = { vm.open(a) }) {
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
                        if (!a.read) PulsingDot(EspochRed, 9.dp)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Ver detalle", tint = InkSoft)
                    }
                }
            }
        }
        item { Spacer(Modifier.fillMaxWidth()) }
    }

    detail?.let { d ->
        ModalBottomSheet(onDismissRequest = vm::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            AlertDetailSheet(d, onOpenLab = { vm.close(); onOpenLab(d.alert.labId) }, onClose = vm::close)
        }
    }
}

@Composable
private fun AlertDetailSheet(d: AlertDetail, onOpenLab: () -> Unit, onClose: () -> Unit) {
    val a = d.alert
    val full = remember { SimpleDateFormat("EEEE d 'de' MMMM, HH:mm", Locale.forLanguageTag("es-EC")) }
    val ago = remember(a.timestamp) {
        val m = (System.currentTimeMillis() - a.timestamp) / 60_000
        when {
            m < 1 -> "hace instantes"
            m < 60 -> "hace $m min"
            m < 24 * 60 -> "hace ${m / 60} h"
            else -> "hace ${m / (24 * 60)} día(s)"
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(a.type.tint().copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(a.type.icon(), null, tint = a.type.tint(), modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.type.title, style = MaterialTheme.typography.titleLarge, color = a.type.tint())
                Text(if (a.type.critical) "Prioridad alta" else "Informativa", style = MaterialTheme.typography.labelMedium,
                    color = if (a.type.critical) EspochRed else InkSoft)
            }
        }
        Text(a.message, style = MaterialTheme.typography.bodyLarge)
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailLine(Icons.Default.Science, "Laboratorio", listOfNotNull(a.labName, d.lab?.location).joinToString(" · "))
                DetailLine(Icons.Default.Schedule, "Cuándo", "${full.format(Date(a.timestamp)).replaceFirstChar { it.titlecase() }} ($ago)")
                val sl = d.slot
                DetailLine(
                    Icons.Default.School, "Clase en ese momento",
                    if (sl == null) "Ninguna programada"
                    else "${sl.subject} · ${sl.timeRange}" + if (sl.teacher.isNotBlank()) " · ${sl.teacher}" else "",
                )
                d.lab?.let { DetailLine(Icons.Default.Groups, "Capacidad", "${it.capacity} personas") }
            }
        }
        Text("¿Qué significa?", style = MaterialTheme.typography.titleSmall)
        Text(a.type.meaning(), style = MaterialTheme.typography.bodyMedium, color = InkSoft)
        Text("¿Qué hacer?", style = MaterialTheme.typography.titleSmall)
        Text(a.type.action(), style = MaterialTheme.typography.bodyMedium, color = InkSoft)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onOpenLab, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("Ver laboratorio") }
            OutlinedButton(onClick = onClose, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text("Cerrar") }
        }
    }
}

@Composable
private fun DetailLine(icon: ImageVector, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = EspochRed, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = InkSoft)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
