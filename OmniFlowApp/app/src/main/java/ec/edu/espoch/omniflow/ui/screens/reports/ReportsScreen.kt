package ec.edu.espoch.omniflow.ui.screens.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.edu.espoch.omniflow.data.repository.ReportRepository
import ec.edu.espoch.omniflow.data.repository.WeeklyReport
import ec.edu.espoch.omniflow.ui.components.AppCard
import ec.edu.espoch.omniflow.ui.components.BarChart
import ec.edu.espoch.omniflow.ui.components.InfoBanner
import ec.edu.espoch.omniflow.ui.components.PercentRow
import ec.edu.espoch.omniflow.ui.components.SectionTitle
import ec.edu.espoch.omniflow.ui.components.StatCard
import ec.edu.espoch.omniflow.ui.theme.InkSoft
import ec.edu.espoch.omniflow.ui.theme.StatusAmber
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class ReportsState(val days: Int = 7, val report: WeeklyReport? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ReportsViewModel @Inject constructor(repo: ReportRepository) : ViewModel() {
    private val days = MutableStateFlow(7)
    val state: StateFlow<ReportsState> = days.flatMapLatest { d -> repo.weekly(d).map { ReportsState(d, it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReportsState())

    fun setDays(d: Int) { days.value = d }
}

@Composable
fun ReportsScreen(padding: PaddingValues, vm: ReportsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val r = s.report
    val fmt = DateTimeFormatter.ofPattern("dd/MM")

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Reportes", style = MaterialTheme.typography.titleLarge) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "Hoy", 7 to "Esta semana", 30 to "30 días").forEach { (d, l) ->
                    FilterChip(selected = s.days == d, onClick = { vm.setDays(d) }, label = { Text(l) })
                }
            }
        }
        if (r == null) return@LazyColumn
        if (r.hasDemoData) {
            item {
                InfoBanner(
                    "Incluye datos de prueba de una versión anterior. Puedes borrarlos en Ajustes.",
                    color = StatusAmber,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(Icons.Default.BarChart, "${r.avgOccupancyPct}%", "Ocupación", "promedio", Modifier.weight(1f))
                StatCard(Icons.Default.Groups, "${r.totalUses}", "Usos", "con presencia", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(Icons.Default.Schedule, "${r.classesRegistered}", "Clases", "registradas", Modifier.weight(1f))
                StatCard(Icons.Default.NotificationsActive, "${r.alertsThisWeek}", "Alertas", "en el periodo", Modifier.weight(1f))
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Column {
                    SectionTitle("Ocupación de laboratorios")
                    BarChart(r.byWeekday, listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom"), Modifier.fillMaxWidth())
                }
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Column {
                    SectionTitle("Asistencia por laboratorio")
                    Text("Pico de personas detectadas / estudiantes inscritos", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    r.perLab.forEach { PercentRow(it.labName.removePrefix("Laboratorio de "), it.attendancePct) }
                }
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Column {
                    SectionTitle("Detección de atrasos (≥ ${ReportRepository.LATE_THRESHOLD_MIN} min)")
                    Text(
                        "${r.lateArrivals.size} clases iniciaron con retraso · ${r.earlyExits} salidas anticipadas",
                        style = MaterialTheme.typography.bodySmall, color = InkSoft,
                    )
                }
            }
        }
        items(r.lateArrivals.take(15)) { l ->
            AppCard(Modifier.fillMaxWidth()) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text(l.subject, style = MaterialTheme.typography.titleSmall)
                        Text("${l.teacher} · ${l.labName}", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    }
                    Column {
                        Text("+${l.minutesLate} min", style = MaterialTheme.typography.titleSmall, color = StatusAmber)
                        Text(l.date.format(fmt), style = MaterialTheme.typography.labelSmall, color = InkSoft)
                    }
                }
            }
        }
    }
}
