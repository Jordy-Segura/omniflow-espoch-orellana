package ec.edu.espoch.omniflow.ui.screens.subject

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.FaceRetouchingOff
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.edu.espoch.omniflow.data.remote.api.SubjectDetailDto
import ec.edu.espoch.omniflow.data.repository.AttendanceRepository
import ec.edu.espoch.omniflow.data.repository.AuthRepository
import ec.edu.espoch.omniflow.data.repository.CatalogRepository
import ec.edu.espoch.omniflow.data.repository.LocalPersonStats
import ec.edu.espoch.omniflow.data.repository.SubjectRepository
import ec.edu.espoch.omniflow.domain.model.AuthSource
import ec.edu.espoch.omniflow.domain.model.Lab
import ec.edu.espoch.omniflow.domain.model.ScheduleSlot
import ec.edu.espoch.omniflow.domain.model.User
import ec.edu.espoch.omniflow.ui.components.AppCard
import ec.edu.espoch.omniflow.ui.components.InfoBanner
import ec.edu.espoch.omniflow.ui.components.InitialsAvatar
import ec.edu.espoch.omniflow.ui.components.PulsingDot
import ec.edu.espoch.omniflow.ui.components.RingProgress
import ec.edu.espoch.omniflow.ui.components.SectionTitle
import ec.edu.espoch.omniflow.ui.theme.EspochRed
import ec.edu.espoch.omniflow.ui.theme.EspochRedDark
import ec.edu.espoch.omniflow.ui.theme.InkSoft
import ec.edu.espoch.omniflow.ui.theme.RedSoft
import ec.edu.espoch.omniflow.ui.theme.StatusAmber
import ec.edu.espoch.omniflow.ui.theme.StatusGreen
import ec.edu.espoch.omniflow.ui.theme.StatusGrey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject

/** Fila comun para estudiantes del servidor y personas locales del modo demostracion. */
data class StudentRow(
    val key: String,
    val name: String,
    val id: String,
    val email: String = "",
    val faceEnrolled: Boolean? = null,
    val present: Int = 0,
    val late: Int = 0,
    val justified: Int = 0,
    val absent: Int = 0,
    val pct: Int? = null,
    val localId: Long? = null,
)

data class SubjectState(
    val slot: ScheduleSlot? = null,
    val lab: Lab? = null,
    val user: User? = null,
    val remote: SubjectDetailDto? = null,
    val rows: List<StudentRow> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    val demo get() = user?.source == AuthSource.DEMO
    val isNow: Boolean
        get() {
            val s = slot ?: return false
            val now = LocalTime.now().let { it.hour * 60 + it.minute }
            return LocalDate.now().dayOfWeek.value == s.dayOfWeek && now in s.startMinute until s.endMinute
        }
}

@HiltViewModel
class SubjectViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: SubjectRepository,
    private val attendance: AttendanceRepository,
    auth: AuthRepository,
) : ViewModel() {
    private val slotId: Long = checkNotNull(handle.get<Long>("slotId"))
    private val userFlow = auth.currentUser
    private val _state = MutableStateFlow(SubjectState())
    val state: StateFlow<SubjectState> = _state

    init {
        viewModelScope.launch {
            repo.slot(slotId).collect { slot ->
                val lab = slot?.let { repo.lab(it.labId) }
                _state.update { it.copy(slot = slot, lab = lab) }
                if (slot != null) load()
            }
        }
    }

    fun load() = viewModelScope.launch {
        val slot = _state.value.slot ?: return@launch
        val user = userFlow.first()
        _state.update { it.copy(user = user, loading = true, error = null) }
        if (user?.source == AuthSource.DEMO) {
            val rows = repo.localStats(slot.id).map { it.toRow() }
            _state.update { it.copy(rows = rows, loading = false) }
            return@launch
        }
        repo.remoteDetail(slot)
            .onSuccess { d ->
                val rows = d.students.map {
                    StudentRow(it.cedula, "${it.apellidos} ${it.nombres}".trim(), it.cedula, it.email, it.faceEnrolled,
                        it.present, it.late, it.justified, it.absent, it.attendancePct)
                }
                _state.update { it.copy(remote = d, rows = rows, loading = false) }
            }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
    }

    fun addPerson(nombres: String, apellidos: String, cedula: String, done: (String?) -> Unit) = viewModelScope.launch {
        attendance.addLocalPerson(slotId, nombres, apellidos, cedula)
            .onSuccess { done(null); load() }
            .onFailure { done(it.message) }
    }

    fun removePerson(id: Long) = viewModelScope.launch { attendance.removeLocalPerson(id); load() }

    /** Id con el que se abre la lista de asistencia (local en demo, del servidor con cuenta). */
    fun attendanceSlotId(): Long? = if (_state.value.demo) slotId else _state.value.remote?.id

    private fun LocalPersonStats.toRow() = StudentRow(
        "L${person.id}", "${person.apellidos} ${person.nombres}".trim(), person.cedula.ifBlank { "Sin cédula" },
        present = present, late = late, justified = justified, absent = absent, pct = pct, localId = person.id,
    )
}

@Composable
fun SubjectScreen(padding: PaddingValues, onTakeAttendance: (Long, String) -> Unit, vm: SubjectViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val slot = s.slot ?: return
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    val shown = s.rows.filter { query.isBlank() || it.name.contains(query, true) || it.id.contains(query) }
    val today = LocalDate.now()
    val isToday = today.dayOfWeek.value == slot.dayOfWeek

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Header(slot, s) }
        item { InfoGrid(slot, s) }
        if (s.attendanceAvailable(isToday)) {
            item {
                Button(
                    onClick = { vm.attendanceSlotId()?.let { onTakeAttendance(it, today.toString()) } },
                    modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.FactCheck, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Tomar asistencia de hoy")
                }
            }
        }
        item {
            SectionTitle(if (s.demo) "Personas de la clase (${s.rows.size})" else "Estudiantes inscritos (${s.rows.size})") {
                if (s.demo) TextButton(onClick = { adding = true }) {
                    Icon(Icons.Default.PersonAdd, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Agregar")
                }
            }
        }
        if (s.demo) {
            item {
                InfoBanner("Modo demostración: las personas que agregues quedan solo en este teléfono y sirven para practicar la " +
                    "toma de asistencia sin una cuenta.", color = StatusAmber)
            }
        }
        s.error?.let { item { InfoBanner(it) } }
        if (s.loading && s.rows.isEmpty()) {
            item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        }
        if (s.rows.size > 6) {
            item {
                OutlinedTextField(
                    query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
                    leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Buscar por nombre o cédula") },
                )
            }
        }
        if (!s.loading && s.error == null && s.rows.isEmpty()) {
            item {
                Text(
                    if (s.demo) "Aún no hay personas. Toca «Agregar» para sumar estudiantes a esta clase."
                    else "El servidor no tiene estudiantes inscritos para esta materia.",
                    style = MaterialTheme.typography.bodySmall, color = InkSoft,
                )
            }
        }
        items(shown, key = { it.key }) { row ->
            StudentCard(
                row, expanded = expanded == row.key,
                onClick = { expanded = if (expanded == row.key) null else row.key },
                onDelete = row.localId?.let { id -> { vm.removePerson(id) } },
                modifier = Modifier.animateItem(),
            )
        }
    }

    if (adding) AddPersonDialog(onDismiss = { adding = false }, onSave = vm::addPerson)
}

private fun SubjectState.attendanceAvailable(isToday: Boolean): Boolean =
    isToday && (demo || (remote != null && user?.canMonitor == true))

@Composable
private fun Header(slot: ScheduleSlot, s: SubjectState) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(EspochRedDark, EspochRed, Color(0xFFE85A6B))))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${slot.dayName} · ${slot.timeRange}", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f))
            if (s.isNow) {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PulsingDot(StatusGreen, 8.dp)
                    Spacer(Modifier.width(5.dp))
                    Text("En clase ahora", color = StatusGreen, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(CatalogRepository.prettySubject(slot.subject), color = Color.White, style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(listOfNotNull(s.lab?.name, s.lab?.location).joinToString(" · "), color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InfoGrid(slot: ScheduleSlot, s: SubjectState) {
    val r = s.remote
    val teacher = (r?.teacher ?: slot.teacher).ifBlank { "Sin docente asignado" }
    val enrolled = if (s.demo) s.rows.size else r?.enrolled ?: slot.enrolled
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialsAvatar(teacher, size = 46.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Docente", style = MaterialTheme.typography.labelSmall, color = InkSoft)
                    Text(teacher, style = MaterialTheme.typography.titleSmall)
                    r?.teacherEmail?.takeIf { it.isNotBlank() }?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Email, null, Modifier.size(14.dp), tint = InkSoft)
                            Spacer(Modifier.width(4.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = InkSoft)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Fact(Icons.Default.Groups, "Inscritos", "$enrolled", Modifier.weight(1f))
            Fact(Icons.Default.School, "Paralelo", (r?.parallel ?: slot.parallel).ifBlank { "—" }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val career = (r?.careerCode ?: slot.careerCode).ifBlank { "—" }
            val level = (r?.level ?: slot.level).takeIf { it > 0 }?.let { " · nivel $it" }.orEmpty()
            Fact(Icons.Default.Badge, "Carrera", career + level, Modifier.weight(1f))
            Fact(Icons.Default.CalendarMonth, "Periodo", r?.periodCode?.ifBlank { null } ?: "Actual", Modifier.weight(1f))
        }
        if (r != null || s.demo) {
            AppCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val avg = if (s.demo) s.rows.mapNotNull { it.pct }.takeIf { it.isNotEmpty() }?.average()?.toInt() else r?.avgAttendancePct
                    RingProgress((avg ?: 0) / 100f, size = 70.dp, color = pctColor(avg), center = avg?.let { "$it%" } ?: "—")
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Asistencia promedio", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (s.demo) "Calculada con las marcas guardadas en este teléfono."
                            else "${r?.sessions ?: 0} clase(s) registrada(s) · ${r?.facesEnrolled ?: 0} con rostro registrado",
                            style = MaterialTheme.typography.bodySmall, color = InkSoft,
                        )
                        r?.subjectCode?.takeIf { it.isNotBlank() }?.let {
                            Text("Código de materia: $it", style = MaterialTheme.typography.labelSmall, color = InkSoft)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(icon: ImageVector, label: String, value: String, modifier: Modifier) {
    AppCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = EspochRed, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = InkSoft)
                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

fun pctColor(p: Int?): Color = when {
    p == null -> StatusGrey
    p >= 80 -> StatusGreen
    p >= 60 -> StatusAmber
    else -> EspochRed
}

@Composable
private fun StudentCard(row: StudentRow, expanded: Boolean, onClick: () -> Unit, onDelete: (() -> Unit)?, modifier: Modifier) {
    AppCard(modifier.fillMaxWidth().animateContentSize(), onClick = onClick) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InitialsAvatar(row.name, size = 38.dp, tint = pctColor(row.pct))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        row.faceEnrolled?.let { f ->
                            Icon(if (f) Icons.Default.Face else Icons.Default.FaceRetouchingOff, null,
                                tint = if (f) StatusGreen else StatusGrey, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(row.id, style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    }
                }
                Text(row.pct?.let { "$it%" } ?: "—", style = MaterialTheme.typography.titleMedium, color = pctColor(row.pct),
                    fontWeight = FontWeight.Bold)
            }
            if (row.pct != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { row.pct / 100f }, color = pctColor(row.pct), trackColor = RedSoft, strokeCap = StrokeCap.Round,
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                )
            }
            AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Mini("Presente", row.present, StatusGreen)
                        Mini("Atraso", row.late, StatusAmber)
                        Mini("Justif.", row.justified, Color(0xFF1E88E5))
                        Mini("Ausente", row.absent, EspochRed)
                    }
                    if (row.email.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Email, null, Modifier.size(14.dp), tint = InkSoft)
                            Spacer(Modifier.width(4.dp))
                            Text(row.email, style = MaterialTheme.typography.bodySmall, color = InkSoft)
                        }
                    }
                    if (row.faceEnrolled == false) {
                        Text("Sin rostro registrado: la cámara no puede reconocerlo.", style = MaterialTheme.typography.bodySmall,
                            color = StatusAmber)
                    }
                    if (onDelete != null) {
                        OutlinedButton(onClick = onDelete, shape = RoundedCornerShape(10.dp)) {
                            Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = EspochRed)
                            Spacer(Modifier.width(6.dp))
                            Text("Quitar de la clase", color = EspochRed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Mini(label: String, n: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$n", style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = InkSoft)
    }
}

/** Formulario para agregar una persona a la clase (modo demostracion). */
@Composable
fun AddPersonDialog(onDismiss: () -> Unit, onSave: (String, String, String, (String?) -> Unit) -> Unit) {
    var nombres by remember { mutableStateOf("") }
    var apellidos by remember { mutableStateOf("") }
    var cedula by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(0) }
    LaunchedEffect(saved) { if (saved > 0) { nombres = ""; apellidos = ""; cedula = "" } }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Person, null, tint = EspochRed) },
        title = { Text("Agregar persona") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(nombres, { nombres = it; error = null }, label = { Text("Nombres") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(apellidos, { apellidos = it; error = null }, label = { Text("Apellidos") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cedula, { cedula = it.filter(Char::isDigit).take(10) }, label = { Text("Cédula (opcional)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                if (saved > 0) Text("$saved persona(s) agregada(s). Puedes seguir agregando.", color = StatusGreen,
                    style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = EspochRed, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(nombres, apellidos, cedula) { e -> if (e == null) saved++ else error = e } }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
    )
}
