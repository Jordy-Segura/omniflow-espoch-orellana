package ec.edu.espoch.omniflow.ui.screens.attendance

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import ec.edu.espoch.omniflow.ui.components.AnimatedValueText
import ec.edu.espoch.omniflow.ui.components.InitialsAvatar
import ec.edu.espoch.omniflow.ui.components.PulsingDot
import ec.edu.espoch.omniflow.ui.components.RingProgress
import ec.edu.espoch.omniflow.ui.screens.subject.AddPersonDialog
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
    val demo: Boolean = false,
)

@HiltViewModel
class AttendanceClassesViewModel @Inject constructor(
    private val repo: AttendanceRepository,
    auth: AuthRepository,
) : ViewModel() {
    val user: StateFlow<User?> = auth.currentUser.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _state = MutableStateFlow(ClassesState())
    val state: StateFlow<ClassesState> = _state

    init {
        viewModelScope.launch {
            while (true) {
                load().join()
                // La clase en curso cambia con el tiempo: se refresca sola cada minuto.
                delay(60_000)
            }
        }
    }

    fun load() = viewModelScope.launch {
        val s = _state.value
        _state.update { it.copy(loading = true, error = null, demo = repo.isDemo()) }
        repo.classes(s.date, s.mine)
            .onSuccess { list -> _state.update { it.copy(loading = false, classes = list) } }
            .onFailure { e -> _state.update { it.copy(loading = false, classes = emptyList(), error = e.message) } }
    }

    fun shiftDay(d: Long) { _state.update { it.copy(date = it.date.plusDays(d)) }; load() }
    fun today() { _state.update { it.copy(date = LocalDate.now()) }; load() }
    fun setMine(v: Boolean) { _state.update { it.copy(mine = v) }; load() }
}

private fun ClassSummaryDto.attended() = present + late + justified

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttendanceClassesScreen(padding: PaddingValues, onOpen: (Long, String) -> Unit, vm: AttendanceClassesViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val user by vm.user.collectAsStateWithLifecycle()
    var howOpen by rememberSaveable { mutableStateOf(false) }
    val live = s.classes.filter { it.state == "EN_CURSO" }
    val next = s.classes.filter { it.state == "PROGRAMADA" }
    val done = s.classes.filter { it.state == "FINALIZADA" }

    PullToRefreshBox(isRefreshing = s.loading, onRefresh = { vm.load() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Asistencia", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (s.date != LocalDate.now()) TextButton(onClick = vm::today) { Text("Hoy") }
                    IconButton(onClick = { vm.load() }) { Icon(Icons.Default.Refresh, "Actualizar") }
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.shiftDay(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Día anterior") }
                        AnimatedContent(s.date, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "date", modifier = Modifier.weight(1f)) { d ->
                            Text(d.format(DAY_FMT).replaceFirstChar { it.titlecase() }, Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                        }
                        IconButton(onClick = { vm.shiftDay(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Día siguiente") }
                    }
                }
            }
            if (s.classes.isNotEmpty()) item { DaySummary(s.classes) }
            item { HowItWorks(s.demo, howOpen) { howOpen = !howOpen } }
            if (s.demo) {
                item {
                    InfoBanner("Modo demostración: toca una clase y agrega personas para practicar. La asistencia se guarda solo en este teléfono.",
                        color = StatusAmber)
                }
            }
            if (!s.demo && user?.role?.name in listOf("ADMINISTRADOR", "COORDINADOR")) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = s.mine, onClick = { vm.setMine(true) }, label = { Text("Mis clases") })
                        FilterChip(selected = !s.mine, onClick = { vm.setMine(false) }, label = { Text("Todos los laboratorios") })
                    }
                }
            }
            s.error?.let { item { InfoBanner(it) } }
            if (!s.loading && s.error == null && s.classes.isEmpty()) {
                item { EmptyState(Icons.Default.EventBusy, "Sin clases este día", "No hay clases de laboratorio programadas para esta fecha.") }
            }
            classSection("En curso", live, onOpen)
            classSection("Próximas", next, onOpen)
            classSection("Finalizadas", done, onOpen)
            if (!s.demo) item { PdfEnrollCard(staff = true, onDone = { vm.load() }) }
        }
    }
}

private fun LazyListScope.classSection(title: String, list: List<ClassSummaryDto>, onOpen: (Long, String) -> Unit) {
    if (list.isEmpty()) return
    item(key = "h-$title") { Text("$title (${list.size})", style = MaterialTheme.typography.titleSmall, color = InkSoft) }
    items(list, key = { "${it.slotId}-${it.date}" }) { c -> ClassCard(c, Modifier.animateItem()) { onOpen(c.slotId, c.date) } }
}

@Composable
private fun DaySummary(classes: List<ClassSummaryDto>) {
    val total = classes.sumOf { it.total }
    val attended = classes.sumOf { it.attended() }
    AppCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RingProgress(if (total == 0) 0f else attended.toFloat() / total, size = 68.dp,
                center = if (total == 0) "—" else "${attended * 100 / total}%")
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Resumen del día", style = MaterialTheme.typography.titleSmall)
                Text("${classes.size} clase(s) · $attended de $total estudiantes con asistencia",
                    style = MaterialTheme.typography.bodySmall, color = InkSoft)
                val live = classes.count { it.state == "EN_CURSO" }
                if (live > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot(StatusGreen, 8.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("$live en curso ahora", style = MaterialTheme.typography.labelMedium, color = StatusGreen)
                    }
                }
            }
        }
    }
}

@Composable
private fun HowItWorks(demo: Boolean, open: Boolean, onToggle: () -> Unit) {
    AppCard(Modifier.fillMaxWidth().animateContentSize(), onClick = onToggle) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, null, tint = EspochRed)
                Spacer(Modifier.width(8.dp))
                Text("¿Cómo se toma la asistencia?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = InkSoft)
            }
            if (open) {
                val steps = if (demo) listOf(
                    "Abre una clase y agrega a las personas que asisten.",
                    "Marca a cada una como presente, atraso, ausente o justificado.",
                    "Al terminar la clase, quien no tenga marca queda como ausente.",
                ) else listOf(
                    "Cada estudiante registra su rostro una vez (con fotos o con su PDF de matrícula).",
                    "Durante la clase, la cámara del laboratorio lo reconoce y lo marca como presente o atrasado (10 min).",
                    "El docente revisa la lista y puede corregir cualquier registro a mano.",
                    "Al terminar la clase, los inscritos sin registro quedan como ausentes.",
                )
                steps.forEachIndexed { i, t ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(RedSoft), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = EspochRed, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(t, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ClassCard(c: ClassSummaryDto, modifier: Modifier, onClick: () -> Unit) {
    val live = c.state == "EN_CURSO"
    AppCard(modifier.fillMaxWidth(), onClick) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.subject, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${ScheduleSlot.fmt(c.startMinute)}–${ScheduleSlot.fmt(c.endMinute)} · ${c.labCode}" +
                        if (c.parallel.isNotBlank()) " · ${c.parallel}" else "",
                        style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    Text(c.teacher.ifBlank { "Sin docente" }, style = MaterialTheme.typography.bodySmall, color = InkSoft, maxLines = 1)
                }
                if (live) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot(StatusGreen, 8.dp)
                        Spacer(Modifier.width(4.dp))
                        Text("En vivo", color = StatusGreen, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Text(if (c.state == "FINALIZADA") "Finalizada" else "Programada", color = InkSoft, style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
            val progress by animateFloatAsState(if (c.total == 0) 0f else c.attended().toFloat() / c.total, tween(700), label = "att")
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = StatusGreen, trackColor = RedSoft, strokeCap = StrokeCap.Round,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Count("Presentes", c.present + c.late, StatusGreen)
                Count(if (c.state == "FINALIZADA") "Ausentes" else "Faltan", if (c.state == "FINALIZADA") c.absent else c.pending + c.absent, EspochRed)
                Count("Inscritos", c.total, InkSoft)
                if (c.facesEnrolled > 0 || c.state != "PROGRAMADA") Count("Con rostro", c.facesEnrolled, InkSoft)
            }
        }
    }
}

@Composable
private fun Count(label: String, n: Int, color: Color) {
    Column {
        AnimatedValueText("$n", style = MaterialTheme.typography.titleMedium, color = color)
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
    val demo: Boolean = false,
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
        val demo = repo.isDemo()
        repo.detail(slotId, date)
            .onSuccess { d -> _state.update { it.copy(loading = false, data = d, error = null, demo = demo) } }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.message, demo = demo) } }
    }

    fun refresh() = viewModelScope.launch { _state.update { it.copy(loading = true) }; load() }
    fun setFilter(f: StudentFilter) = _state.update { it.copy(filter = f) }
    fun edit(s: AttendanceStudentDto?) = _state.update { it.copy(editing = s) }

    fun mark(student: AttendanceStudentDto, status: String) = viewModelScope.launch {
        _state.update { it.copy(editing = null) }
        repo.mark(slotId, student, date, status).onFailure { e -> _state.update { it.copy(error = e.message) } }
        load()
    }

    fun addPerson(nombres: String, apellidos: String, cedula: String, done: (String?) -> Unit) = viewModelScope.launch {
        repo.addLocalPerson(slotId, nombres, apellidos, cedula).onSuccess { done(null); load() }.onFailure { done(it.message) }
    }

    fun removePerson(id: Long) = viewModelScope.launch {
        _state.update { it.copy(editing = null) }
        repo.removeLocalPerson(id)
        load()
    }
}

@Composable
fun AttendanceDetailScreen(padding: PaddingValues, onEnrollFace: (String, String) -> Unit, vm: AttendanceDetailViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val d = s.data
    val haptic = LocalHapticFeedback.current
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var query by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val shown = d?.students.orEmpty().filter {
        when (s.filter) {
            StudentFilter.TODOS -> true
            StudentFilter.PRESENTES -> it.status in listOf("PRESENTE", "ATRASO", "JUSTIFICADO")
            StudentFilter.FALTAN -> it.status in listOf("AUSENTE", "PENDIENTE")
        }
    }.filter { query.isBlank() || "${it.apellidos} ${it.nombres}".contains(query, true) || it.cedula.contains(query) }

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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val attended = d.present + d.late + d.justified
                        RingProgress(if (d.total == 0) 0f else attended.toFloat() / d.total, size = 64.dp,
                            center = if (d.total == 0) "—" else "${attended * 100 / d.total}%")
                        Spacer(Modifier.width(12.dp))
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.weight(1f)) {
                            Count("Presentes", d.present, StatusGreen)
                            Count("Atrasos", d.late, StatusAmber)
                            Count(if (d.state == "FINALIZADA") "Ausentes" else "Faltan", if (d.state == "FINALIZADA") d.absent else d.pending + d.absent, EspochRed)
                            Count("Justif.", d.justified, Color(0xFF1E88E5))
                        }
                    }
                }
            }
            if (d.state == "EN_CURSO") {
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(StatusGreen.copy(alpha = 0.08f)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        PulsingDot(StatusGreen, 9.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (s.demo) "Clase en curso: marca a cada persona a medida que llega."
                            else "Clase en curso: la lista se actualiza sola cada 10 s con los rostros que reconoce la cámara" +
                                (d.peopleDetected?.let { " (ahora ve $it persona(s))" } ?: "") + ".",
                            style = MaterialTheme.typography.bodySmall, color = StatusGreen,
                        )
                    }
                }
            }
            if (!s.demo && d.facesEnrolled < d.total) {
                item {
                    InfoBanner("${d.total - d.facesEnrolled} estudiante(s) aún no registran su rostro: la cámara no puede reconocerlos. " +
                        "Toca un estudiante para registrarlo o corregir su asistencia.", color = StatusAmber)
                }
            }
            if (s.demo) {
                item {
                    OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Default.PersonAdd, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Agregar persona a la clase")
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudentFilter.entries.forEach { f -> FilterChip(selected = s.filter == f, onClick = { vm.setFilter(f) }, label = { Text(f.label) }) }
                }
            }
            if (d.students.size > 8) {
                item {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
                        leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Buscar estudiante") })
                }
            }
            if (d.students.isEmpty()) {
                item {
                    Text(if (s.demo) "Todavía no hay personas en esta clase." else "Esta clase no tiene estudiantes inscritos.",
                        style = MaterialTheme.typography.bodySmall, color = InkSoft)
                }
            }
        }
        s.error?.let { item { InfoBanner(it) } }
        if (s.loading && d == null) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
        items(shown, key = { it.localId?.let { id -> "L$id" } ?: it.cedula }) { st ->
            AppCard(Modifier.fillMaxWidth().animateItem(), onClick = { vm.edit(st) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (st.localId != null) InitialsAvatar("${st.nombres} ${st.apellidos}", size = 32.dp, tint = statusColor(st.status))
                    else Icon(if (st.faceEnrolled) Icons.Default.Face else Icons.Default.FaceRetouchingOff, null,
                        tint = if (st.faceEnrolled) StatusGreen else StatusGrey, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${st.apellidos} ${st.nombres}".trim(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val how = when (st.method) {
                            "ROSTRO" -> "Reconocido ${st.firstSeen?.let { time.format(Date(it)) } ?: ""} · ${(st.confidence * 100).toInt()}%"
                            "MANUAL" -> "Registro manual" + (st.firstSeen?.let { " · ${time.format(Date(it))}" } ?: "")
                            else -> if (st.localId != null && st.cedula.startsWith("L")) "Sin cédula" else st.cedula
                        }
                        Text(how, style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    }
                    AnimatedContent(st.status, transitionSpec = { (scaleIn() + fadeIn()) togetherWith fadeOut() }, label = "status") { status ->
                        StatusPill(status)
                    }
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
                    Text("Marcar asistencia:", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    listOf("PRESENTE", "ATRASO", "AUSENTE", "JUSTIFICADO").forEach { status ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .background(if (st.status == status) statusColor(status).copy(alpha = 0.12f) else Color.Transparent)
                                .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.mark(st, status) }
                                .padding(10.dp),
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
                if (st.localId != null) {
                    TextButton(onClick = { vm.removePerson(st.localId) }) { Text("Quitar de la clase", color = EspochRed) }
                } else {
                    TextButton(onClick = { vm.edit(null); onEnrollFace(st.cedula, "${st.nombres} ${st.apellidos}".trim()) }) {
                        Text(if (st.faceEnrolled) "Volver a registrar rostro" else "Registrar rostro")
                    }
                }
            },
            dismissButton = { TextButton(onClick = { vm.edit(null) }) { Text("Cerrar") } },
        )
    }
    if (adding) AddPersonDialog(onDismiss = { adding = false }, onSave = vm::addPerson)
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
