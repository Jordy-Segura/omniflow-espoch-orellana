package ec.edu.espoch.omniflow.ui.screens.attendance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FaceRetouchingOff
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.edu.espoch.omniflow.data.remote.api.AttendanceStudentDto
import ec.edu.espoch.omniflow.data.remote.api.ClassAttendanceDto
import ec.edu.espoch.omniflow.data.remote.api.ClassSummaryDto
import ec.edu.espoch.omniflow.data.remote.api.MyAttendanceDto
import ec.edu.espoch.omniflow.data.repository.AttendanceRepository
import ec.edu.espoch.omniflow.data.repository.AuthRepository
import ec.edu.espoch.omniflow.domain.model.ScheduleSlot
import ec.edu.espoch.omniflow.domain.model.User
import ec.edu.espoch.omniflow.ui.components.AppCard
import ec.edu.espoch.omniflow.ui.components.EmptyState
import ec.edu.espoch.omniflow.ui.components.InfoBanner
import ec.edu.espoch.omniflow.ui.theme.EspochRed
import ec.edu.espoch.omniflow.ui.theme.InkSoft
import ec.edu.espoch.omniflow.ui.theme.RedSoft
import ec.edu.espoch.omniflow.ui.theme.StatusAmber
import ec.edu.espoch.omniflow.ui.theme.StatusGreen
import ec.edu.espoch.omniflow.ui.theme.StatusGrey
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private val DAY_FMT = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.forLanguageTag("es-EC"))

fun statusLabel(s: String) = when (s) {
    "PRESENTE" -> "Presente"
    "ATRASO" -> "Atraso"
    "AUSENTE" -> "Ausente"
    "JUSTIFICADO" -> "Justificado"
    else -> "Sin registro"
}

fun statusColor(s: String): Color = when (s) {
    "PRESENTE" -> StatusGreen
    "ATRASO" -> StatusAmber
    "AUSENTE" -> EspochRed
    "JUSTIFICADO" -> Color(0xFF1E88E5)
    else -> StatusGrey
}

@Composable
fun StatusPill(status: String) {
    val c = statusColor(status)
    Text(
        statusLabel(status),
        Modifier.clip(RoundedCornerShape(8.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp),
        color = c, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
    )
}

// ============================================================================ lista de clases (docentes)
data class ClassesState(
    val date: LocalDate = LocalDate.now(),
    val mine: Boolean = true,
    val loading: Boolean = false,
    val classes: List<ClassSummaryDto> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class AttendanceClassesViewModel @Inject constructor(
    private val repo: AttendanceRepository,
    auth: AuthRepository,
) : ViewModel() {
    val user: StateFlow<User?> = auth.currentUser.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _state = MutableStateFlow(ClassesState())
    val state: StateFlow<ClassesState> = _state

    init { load() }

    fun load() = viewModelScope.launch {
        val s = _state.value
        _state.update { it.copy(loading = true, error = null) }
        repo.classes(s.date, s.mine)
            .onSuccess { list -> _state.update { it.copy(loading = false, classes = list) } }
            .onFailure { e -> _state.update { it.copy(loading = false, classes = emptyList(), error = e.message) } }
    }

    fun shiftDay(d: Long) { _state.update { it.copy(date = it.date.plusDays(d)) }; load() }
    fun setMine(v: Boolean) { _state.update { it.copy(mine = v) }; load() }
}

@Composable
fun AttendanceClassesScreen(padding: PaddingValues, onOpen: (Long, String) -> Unit, vm: AttendanceClassesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Asistencia", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.load() }) { Icon(Icons.Default.Refresh, "Actualizar") }
            }
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.shiftDay(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Día anterior") }
                    Text(s.date.format(DAY_FMT).replaceFirstChar { it.titlecase() }, Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(onClick = { vm.shiftDay(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Día siguiente") }
                }
            }
        }
        if (user?.role?.name in listOf("ADMINISTRADOR", "COORDINADOR")) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = s.mine, onClick = { vm.setMine(true) }, label = { Text("Mis clases") })
                    FilterChip(selected = !s.mine, onClick = { vm.setMine(false) }, label = { Text("Todos los laboratorios") })
                }
            }
        }
        item { PdfEnrollCard(staff = true, onDone = { vm.load() }) }
        s.error?.let { item { InfoBanner(it) } }
        if (s.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (!s.loading && s.error == null && s.classes.isEmpty()) {
            item { EmptyState(Icons.Default.EventBusy, "Sin clases este día", "No hay clases de laboratorio programadas para esta fecha.") }
        }
        items(s.classes, key = { it.slotId }) { c -> ClassCard(c) { onOpen(c.slotId, c.date) } }
    }
}

@Composable
private fun ClassCard(c: ClassSummaryDto, onClick: () -> Unit) {
    AppCard(Modifier.fillMaxWidth(), onClick) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.subject, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${ScheduleSlot.fmt(c.startMinute)}–${ScheduleSlot.fmt(c.endMinute)} · ${c.labCode} · ${c.parallel}",
                        style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    Text(c.teacher, style = MaterialTheme.typography.bodySmall, color = InkSoft, maxLines = 1)
                }
                Text(
                    when (c.state) { "EN_CURSO" -> "En curso"; "FINALIZADA" -> "Finalizada"; else -> "Programada" },
                    color = if (c.state == "EN_CURSO") StatusGreen else InkSoft, style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.height(8.dp))
            val attended = c.present + c.late + c.justified
            LinearProgressIndicator(
                progress = { if (c.total == 0) 0f else attended.toFloat() / c.total },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = StatusGreen, trackColor = RedSoft, strokeCap = StrokeCap.Round,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Count("Presentes", c.present + c.late, StatusGreen)
                Count(if (c.state == "FINALIZADA") "Ausentes" else "Faltan", if (c.state == "FINALIZADA") c.absent else c.pending + c.absent, EspochRed)
                Count("Inscritos", c.total, InkSoft)
                Count("Con rostro", c.facesEnrolled, InkSoft)
            }
        }
    }
}

@Composable
private fun Count(label: String, n: Int, color: Color) {
    Column {
        Text("$n", style = MaterialTheme.typography.titleMedium, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = InkSoft)
    }
}

// ============================================================================ detalle: quienes estan y quienes faltan
enum class StudentFilter(val label: String) { TODOS("Todos"), PRESENTES("Presentes"), FALTAN("Faltan") }

data class DetailState(
    val loading: Boolean = true,
    val data: ClassAttendanceDto? = null,
    val filter: StudentFilter = StudentFilter.TODOS,
    val error: String? = null,
    val editing: AttendanceStudentDto? = null,
)

@HiltViewModel
class AttendanceDetailViewModel @Inject constructor(handle: SavedStateHandle, private val repo: AttendanceRepository) : ViewModel() {
    val slotId: Long = checkNotNull(handle.get<Long>("slotId"))
    val date: LocalDate = LocalDate.parse(checkNotNull(handle.get<String>("date")))
    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state

    init {
        viewModelScope.launch {
            while (true) {
                load()
                // Mientras la clase esta en curso la lista se actualiza sola (la camara marca a los que llegan).
                delay(if (_state.value.data?.state == "EN_CURSO") 10_000 else 60_000)
            }
        }
    }

    suspend fun load() {
        repo.detail(slotId, date)
            .onSuccess { d -> _state.update { it.copy(loading = false, data = d, error = null) } }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
    }

    fun refresh() = viewModelScope.launch { _state.update { it.copy(loading = true) }; load() }
    fun setFilter(f: StudentFilter) = _state.update { it.copy(filter = f) }
    fun edit(s: AttendanceStudentDto?) = _state.update { it.copy(editing = s) }

    fun mark(cedula: String, status: String) = viewModelScope.launch {
        _state.update { it.copy(editing = null) }
        repo.mark(slotId, cedula, date, status).onFailure { e -> _state.update { it.copy(error = e.message) } }
        load()
    }
}

@Composable
fun AttendanceDetailScreen(padding: PaddingValues, onEnrollFace: (String, String) -> Unit, vm: AttendanceDetailViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val d = s.data
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val shown = d?.students.orEmpty().filter {
        when (s.filter) {
            StudentFilter.TODOS -> true
            StudentFilter.PRESENTES -> it.status in listOf("PRESENTE", "ATRASO", "JUSTIFICADO")
            StudentFilter.FALTAN -> it.status in listOf("AUSENTE", "PENDIENTE")
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (d != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.subject, style = MaterialTheme.typography.titleLarge)
                        Text("${d.labCode} · ${d.parallel} · ${ScheduleSlot.fmt(d.startMinute)}–${ScheduleSlot.fmt(d.endMinute)} · ${d.date}",
                            style = MaterialTheme.typography.bodySmall, color = InkSoft)
                        Text(d.teacher, style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    }
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "Actualizar") }
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Count("Presentes", d.present, StatusGreen)
                        Count("Atrasos", d.late, StatusAmber)
                        Count(if (d.state == "FINALIZADA") "Ausentes" else "Faltan", if (d.state == "FINALIZADA") d.absent else d.pending + d.absent, EspochRed)
                        Count("Justif.", d.justified, Color(0xFF1E88E5))
                        Count("Personas cámara", d.peopleDetected ?: 0, InkSoft)
                    }
                }
            }
            if (d.state == "EN_CURSO") item { InfoBanner("Clase en curso: la lista se actualiza sola cada 10 s con los rostros que reconoce la cámara.", color = StatusGreen) }
            if (d.facesEnrolled < d.total) {
                item {
                    InfoBanner("${d.total - d.facesEnrolled} estudiante(s) aún no registran su rostro: la cámara no puede reconocerlos. " +
                        "Toca un estudiante para registrarlo o corregir su asistencia.", color = StatusAmber)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudentFilter.entries.forEach { f -> FilterChip(selected = s.filter == f, onClick = { vm.setFilter(f) }, label = { Text(f.label) }) }
                }
            }
        }
        s.error?.let { item { InfoBanner(it) } }
        if (s.loading && d == null) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
        items(shown, key = { it.cedula }) { st ->
            AppCard(Modifier.fillMaxWidth(), onClick = { vm.edit(st) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (st.faceEnrolled) Icons.Default.Face else Icons.Default.FaceRetouchingOff, null,
                        tint = if (st.faceEnrolled) StatusGreen else StatusGrey, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${st.apellidos} ${st.nombres}".trim(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val how = when (st.method) {
                            "ROSTRO" -> "Reconocido ${st.firstSeen?.let { time.format(Date(it)) } ?: ""} · ${(st.confidence * 100).toInt()}%"
                            "MANUAL" -> "Registro manual"
                            else -> st.cedula
                        }
                        Text(how, style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    }
                    StatusPill(st.status)
                }
            }
        }
    }

    s.editing?.let { st ->
        AlertDialog(
            onDismissRequest = { vm.edit(null) },
            title = { Text("${st.nombres} ${st.apellidos}".trim()) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Corregir asistencia:", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    listOf("PRESENTE", "ATRASO", "AUSENTE", "JUSTIFICADO").forEach { status ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.mark(st.cedula, status) }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.EditNote, null, tint = statusColor(status))
                            Spacer(Modifier.width(10.dp))
                            Text(statusLabel(status), color = statusColor(status), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.edit(null); onEnrollFace(st.cedula, "${st.nombres} ${st.apellidos}".trim()) }) {
                    Text(if (st.faceEnrolled) "Volver a registrar rostro" else "Registrar rostro")
                }
            },
            dismissButton = { TextButton(onClick = { vm.edit(null) }) { Text("Cerrar") } },
        )
    }
}

// ============================================================================ estudiante: mi asistencia
data class MyState(val loading: Boolean = true, val items: List<MyAttendanceDto> = emptyList(), val faceTemplates: Int? = null,
                   val error: String? = null)

@HiltViewModel
class MyAttendanceViewModel @Inject constructor(private val repo: AttendanceRepository) : ViewModel() {
    private val _state = MutableStateFlow(MyState())
    val state: StateFlow<MyState> = _state

    init { load() }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true) }
        val face = repo.faceStatus().getOrNull()?.templates
        repo.mine().onSuccess { l -> _state.update { MyState(false, l, face) } }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.message, faceTemplates = face) } }
    }

    fun deleteFace() = viewModelScope.launch { repo.deleteMyFace(); load() }
}

@Composable
fun MyAttendanceScreen(padding: PaddingValues, onEnroll: () -> Unit, vm: MyAttendanceViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val attended = s.items.count { it.status in listOf("PRESENTE", "ATRASO", "JUSTIFICADO") }
    val finished = s.items.count { it.status != "PENDIENTE" }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Mi asistencia", style = MaterialTheme.typography.titleLarge) }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.HowToReg, null, tint = if ((s.faceTemplates ?: 0) > 0) StatusGreen else StatusAmber)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if ((s.faceTemplates ?: 0) > 0) "Rostro registrado (${s.faceTemplates} fotos): la cámara te marca presente."
                            else "Registra tu rostro para que la cámara del laboratorio tome tu asistencia automáticamente.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onEnroll, shape = RoundedCornerShape(12.dp)) {
                            Text(if ((s.faceTemplates ?: 0) > 0) "Actualizar rostro" else "Registrar mi rostro")
                        }
                        if ((s.faceTemplates ?: 0) > 0) OutlinedButton(onClick = { confirmDelete = true }, shape = RoundedCornerShape(12.dp)) { Text("Eliminar") }
                    }
                }
            }
        }
        item { PdfEnrollCard(staff = false, onDone = { vm.load() }) }
        if (finished > 0) {
            item { InfoBanner("Asististe a $attended de $finished clases de laboratorio (${attended * 100 / finished}%).", color = StatusGreen) }
        }
        s.error?.let { item { InfoBanner(it) } }
        if (s.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        items(s.items, key = { "${it.slotId}-${it.date}" }) { a ->
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.subject, style = MaterialTheme.typography.titleSmall)
                        Text("${a.date} · ${ScheduleSlot.fmt(a.startMinute)} · ${a.labCode}", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    }
                    StatusPill(a.status)
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eliminar registro facial") },
            text = { Text("Se borrarán tus plantillas faciales del servidor. La cámara ya no podrá marcar tu asistencia; el docente lo hará manualmente.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteFace() }) { Text("Eliminar", color = EspochRed) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}
