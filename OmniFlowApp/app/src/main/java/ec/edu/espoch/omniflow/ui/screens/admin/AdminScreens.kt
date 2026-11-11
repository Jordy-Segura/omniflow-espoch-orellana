package ec.edu.espoch.omniflow.ui.screens.admin

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.edu.espoch.omniflow.data.remote.api.LabDto
import ec.edu.espoch.omniflow.data.remote.api.ScheduleEntryDto
import ec.edu.espoch.omniflow.data.remote.api.SlotInDto
import ec.edu.espoch.omniflow.data.remote.api.TeacherDto
import ec.edu.espoch.omniflow.data.remote.api.TeacherInDto
import ec.edu.espoch.omniflow.data.repository.AdminRepository
import ec.edu.espoch.omniflow.data.repository.CatalogRepository
import ec.edu.espoch.omniflow.data.repository.LabRepository
import ec.edu.espoch.omniflow.domain.model.Lab
import ec.edu.espoch.omniflow.domain.model.ScheduleSlot
import ec.edu.espoch.omniflow.ui.components.AppCard
import ec.edu.espoch.omniflow.ui.components.EmptyState
import ec.edu.espoch.omniflow.ui.components.InfoBanner
import ec.edu.espoch.omniflow.ui.components.InitialsAvatar
import ec.edu.espoch.omniflow.ui.components.LabHeroImage
import ec.edu.espoch.omniflow.ui.components.SectionTitle
import ec.edu.espoch.omniflow.ui.theme.EspochRed
import ec.edu.espoch.omniflow.ui.theme.InkSoft
import ec.edu.espoch.omniflow.ui.theme.StatusAmber
import ec.edu.espoch.omniflow.ui.theme.StatusGreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ============================================================================ inicio de administracion
data class AdminState(
    val demo: Boolean = false,
    val loading: Boolean = false,
    val teachers: List<TeacherDto> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class AdminViewModel @Inject constructor(private val repo: AdminRepository, labs: LabRepository) : ViewModel() {
    private val _state = MutableStateFlow(AdminState())
    val state: StateFlow<AdminState> = _state
    val labs: StateFlow<List<Lab>> = labs.labs.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init { load() }

    fun load() = viewModelScope.launch {
        val demo = repo.isDemo()
        _state.update { it.copy(demo = demo, loading = !demo, error = null) }
        if (demo) return@launch
        repo.teachers()
            .onSuccess { t -> _state.update { it.copy(loading = false, teachers = t) } }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.message) } }
    }
}

@Composable
fun AdminScreen(
    padding: PaddingValues,
    onEditTeacher: (String?) -> Unit,
    onEditLab: (Long?) -> Unit,
    vm: AdminViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val labs by vm.labs.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.load() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            SecondaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Docentes") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Laboratorios") })
            }
            AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, padding.calculateBottomPadding() + 88.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (t == 0) teachersTab(s, query, { query = it }, onEditTeacher) else labsTab(s.demo, labs, onEditLab)
                }
            }
        }
        if (!(tab == 0 && s.demo)) {
            ExtendedFloatingActionButton(
                onClick = { if (tab == 0) onEditTeacher(null) else onEditLab(null) },
                icon = { Icon(if (tab == 0) Icons.Default.PersonAdd else Icons.Default.Add, null) },
                text = { Text(if (tab == 0) "Agregar docente" else "Agregar laboratorio") },
                containerColor = EspochRed, contentColor = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = padding.calculateBottomPadding() + 16.dp),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.teachersTab(
    s: AdminState, query: String, onQuery: (String) -> Unit, onEdit: (String?) -> Unit,
) {
    if (s.demo) {
        item {
            EmptyState(
                Icons.Default.Lock, "Necesitas una cuenta de administrador",
                "Los docentes reciben un usuario y una contraseña reales en el servidor, por eso esta sección no funciona en el " +
                    "modo demostración. Inicia sesión con una cuenta de administrador o coordinador.",
            )
        }
        item {
            InfoBanner("Para crear la primera cuenta de administrador, en la PC del servidor ejecuta:\n" +
                ".venv\\Scripts\\python -m app.crear_admin  (dentro de la carpeta omniflow-backend)", color = InkSoft)
        }
        return
    }
    item {
        OutlinedTextField(
            query, onQuery, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
            leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Buscar docente o materia") },
        )
    }
    s.error?.let { item { InfoBanner(it) } }
    if (s.loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
    val shown = s.teachers.filter { t ->
        query.isBlank() || t.fullName.contains(query, true) || t.cedula.contains(query) ||
            t.slots.any { it.subject.contains(query, true) }
    }
    if (!s.loading && s.error == null && shown.isEmpty()) {
        item { Text("No hay docentes que coincidan.", style = MaterialTheme.typography.bodySmall, color = InkSoft) }
    }
    items(shown, key = { it.cedula }) { t -> TeacherCard(t, Modifier.animateItem()) { onEdit(t.cedula) } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TeacherCard(t: TeacherDto, modifier: Modifier, onClick: () -> Unit) {
    AppCard(modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InitialsAvatar(t.fullName, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(CatalogRepository.prettySubject(t.fullName), style = MaterialTheme.typography.titleSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        !t.hasAccount -> "Sin cuenta"
                        !t.accountActive -> "Cuenta desactivada"
                        else -> "Usuario: ${t.username}"
                    },
                    style = MaterialTheme.typography.bodySmall, color = if (t.hasAccount && t.accountActive) InkSoft else StatusAmber,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag(if (t.source == "MANUAL") "Creado aquí" else "OASIS", if (t.source == "MANUAL") EspochRed else InkSoft)
                    Tag(if (t.slots.size == 1) "1 materia" else "${t.slots.size} materias", StatusGreen)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = InkSoft)
        }
    }
}

@Composable
private fun Tag(text: String, color: Color) {
    Text(
        text,
        Modifier.padding(top = 2.dp).then(Modifier),
        color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
    )
}

private fun androidx.compose.foundation.lazy.LazyListScope.labsTab(demo: Boolean, labs: List<Lab>, onEdit: (Long?) -> Unit) {
    if (demo) item { InfoBanner("Modo demostración: los cambios en laboratorios quedan solo en este teléfono.", color = StatusAmber) }
    items(labs, key = { it.id }) { lab ->
        AppCard(Modifier.fillMaxWidth().animateItem(), onClick = { onEdit(lab.id) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabHeroImage(Modifier.width(64.dp), height = 52.dp, icon = Icons.Default.Computer)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(lab.name, style = MaterialTheme.typography.titleSmall)
                    Text("${lab.code} · ${lab.capacity} puestos", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    Text(lab.location, style = MaterialTheme.typography.bodySmall, color = InkSoft)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = InkSoft)
            }
        }
    }
}

// ============================================================================ crear / editar docente
data class TeacherForm(
    val editing: Boolean = false,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val cedula: String = "",
    val nombres: String = "",
    val apellidos: String = "",
    val email: String = "",
    val username: String = "",
    val password: String = "",
    val mustChange: Boolean = true,
    val selected: Set<Long> = emptySet(),
    val schedule: List<ScheduleEntryDto> = emptyList(),
    val owners: Map<Long, String> = emptyMap(),
    val error: String? = null,
    val done: Boolean = false,
)

@HiltViewModel
class TeacherFormViewModel @Inject constructor(handle: SavedStateHandle, private val repo: AdminRepository) : ViewModel() {
    private val cedula: String = handle.get<String>("cedula").orEmpty()
    private val _state = MutableStateFlow(TeacherForm(editing = cedula.isNotBlank()))
    val state: StateFlow<TeacherForm> = _state

    init {
        viewModelScope.launch {
            val schedule = repo.serverSchedule().getOrElse { e ->
                _state.update { it.copy(loading = false, error = e.message) }
                return@launch
            }
            val teachers = repo.teachers().getOrDefault(emptyList())
            val owners = teachers.flatMap { t -> t.slots.map { it.id to "${t.nombres} ${t.apellidos}" } }.toMap()
            val t = teachers.firstOrNull { it.cedula == cedula }
            _state.update {
                it.copy(
                    loading = false, schedule = schedule.sortedWith(compareBy({ s -> s.labCode }, { s -> s.dayOfWeek }, { s -> s.startMinute })),
                    owners = owners,
                    cedula = t?.cedula?.filter(Char::isDigit) ?: it.cedula,
                    nombres = t?.nombres?.let(CatalogRepository::prettySubject) ?: it.nombres,
                    apellidos = t?.apellidos?.let(CatalogRepository::prettySubject) ?: it.apellidos,
                    email = t?.email ?: it.email,
                    username = t?.username ?: t?.email ?: it.username,
                    selected = t?.slots?.map { s -> s.id }?.toSet() ?: it.selected,
                    mustChange = t?.mustChangePassword ?: true,
                )
            }
        }
    }

    fun update(f: (TeacherForm) -> TeacherForm) = _state.update { f(it).copy(error = null) }

    fun toggle(id: Long) = _state.update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }

    fun save() = viewModelScope.launch {
        val f = _state.value
        val problem = when {
            f.cedula.length != 10 -> "La cédula debe tener 10 dígitos"
            f.nombres.isBlank() || f.apellidos.isBlank() -> "Escribe nombres y apellidos"
            f.username.isBlank() || f.username.contains(' ') -> "Escribe un usuario sin espacios (por ejemplo el correo)"
            !f.editing && f.password.length < 8 -> "La contraseña debe tener al menos 8 caracteres"
            f.password.isNotEmpty() && (f.password.length < 8 || f.password.none(Char::isDigit) || f.password.none(Char::isLetter)) ->
                "La contraseña debe tener al menos 8 caracteres y combinar letras y números"
            else -> null
        }
        if (problem != null) { _state.update { it.copy(error = problem) }; return@launch }
        _state.update { it.copy(saving = true, error = null) }
        val body = TeacherInDto(
            cedula = f.cedula, nombres = f.nombres, apellidos = f.apellidos, email = f.email.trim(), username = f.username.trim(),
            password = f.password.ifEmpty { null }, mustChangePassword = f.mustChange, slotIds = f.selected.toList(),
        )
        repo.saveTeacher(body, f.editing)
            .onSuccess { _state.update { it.copy(saving = false, done = true) } }
            .onFailure { e -> _state.update { it.copy(saving = false, error = e.message) } }
    }

    fun delete() = viewModelScope.launch {
        _state.update { it.copy(saving = true) }
        repo.deleteTeacher(_state.value.cedula)
            .onSuccess { _state.update { it.copy(saving = false, done = true) } }
            .onFailure { e -> _state.update { it.copy(saving = false, error = e.message) } }
    }
}

@Composable
fun TeacherFormScreen(padding: PaddingValues, onDone: () -> Unit, vm: TeacherFormViewModel = hiltViewModel()) {
    val f by vm.state.collectAsStateWithLifecycle()
    var showPw by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(f.done) { if (f.done) onDone() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text(if (f.editing) "Editar docente" else "Nuevo docente", style = MaterialTheme.typography.titleLarge) }
        item { SectionTitle("Datos personales") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(f.nombres, { v -> vm.update { it.copy(nombres = v) } }, Modifier.fillMaxWidth(), label = { Text("Nombres") }, singleLine = true)
                OutlinedTextField(f.apellidos, { v -> vm.update { it.copy(apellidos = v) } }, Modifier.fillMaxWidth(), label = { Text("Apellidos") }, singleLine = true)
                OutlinedTextField(
                    f.cedula, { v -> vm.update { it.copy(cedula = v.filter(Char::isDigit).take(10)) } }, Modifier.fillMaxWidth(),
                    label = { Text("Cédula") }, singleLine = true, enabled = !f.editing,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    f.email, { v -> vm.update { it.copy(email = v.trim(), username = if (it.username.isBlank() || it.username == it.email) v.trim() else it.username) } },
                    Modifier.fillMaxWidth(), label = { Text("Correo institucional") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
            }
        }
        item { SectionTitle("Cuenta de acceso") }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    f.username, { v -> vm.update { it.copy(username = v.trim()) } }, Modifier.fillMaxWidth(),
                    label = { Text("Usuario") }, singleLine = true, supportingText = { Text("Con este usuario inicia sesión en la app") },
                )
                OutlinedTextField(
                    f.password, { v -> vm.update { it.copy(password = v) } }, Modifier.fillMaxWidth(),
                    label = { Text(if (f.editing) "Nueva contraseña (opcional)" else "Contraseña") }, singleLine = true,
                    visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { showPw = !showPw }) {
                            Icon(if (showPw) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Mostrar contraseña")
                        }
                    },
                    supportingText = { Text(if (f.editing) "Déjala vacía para mantener la actual" else "Mínimo 8 caracteres, con letras y números") },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pedir cambio en el primer ingreso", style = MaterialTheme.typography.bodyMedium)
                        Text("El docente elegirá su propia contraseña al entrar.", style = MaterialTheme.typography.bodySmall, color = InkSoft)
                    }
                    Switch(f.mustChange, { v -> vm.update { it.copy(mustChange = v) } })
                }
            }
        }
        item {
            SectionTitle("Materias que dicta (${f.selected.size})")
        }
        if (f.loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        if (!f.loading && f.schedule.isNotEmpty()) {
            item {
                OutlinedTextField(
                    filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(12.dp),
                    leadingIcon = { Icon(Icons.Default.Search, null) }, placeholder = { Text("Filtrar materias") },
                )
            }
        }
        val shown = f.schedule.filter { filter.isBlank() || it.subject.contains(filter, true) || it.labCode.contains(filter, true) }
        items(shown, key = { it.id }) { e ->
            val checked = e.id in f.selected
            val owner = f.owners[e.id]?.takeIf { !checked }
            AppCard(Modifier.fillMaxWidth(), onClick = { vm.toggle(e.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked, { vm.toggle(e.id) })
                    Column(Modifier.weight(1f)) {
                        Text(CatalogRepository.prettySubject(e.subject), style = MaterialTheme.typography.titleSmall, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text("${e.labCode} · ${ScheduleSlot.DAY_NAMES[e.dayOfWeek - 1]} ${ScheduleSlot.fmt(e.startMinute)}–${ScheduleSlot.fmt(e.endMinute)}" +
                            if (e.parallel.isNotBlank()) " · ${e.parallel}" else "",
                            style = MaterialTheme.typography.bodySmall, color = InkSoft)
                        val current = owner ?: e.teacher.takeIf { it.isNotBlank() && !checked }
                        current?.let {
                            Text("Actualmente: ${CatalogRepository.prettySubject(it)}", style = MaterialTheme.typography.labelSmall, color = StatusAmber)
                        }
                    }
                }
            }
        }
        f.error?.let { item { InfoBanner(it) } }
        item {
            Button(onClick = vm::save, enabled = !f.saving && !f.loading, modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp)) {
                if (f.saving) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(if (f.editing) "Guardar cambios" else "Crear docente")
            }
        }
        if (f.editing) {
            item {
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Default.Delete, null, tint = EspochRed)
                    Spacer(Modifier.width(6.dp))
                    Text("Quitar materias y desactivar cuenta", color = EspochRed)
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Quitar al docente?") },
            text = { Text("Se le quitarán todas sus materias y ya no podrá iniciar sesión. Si lo creaste aquí, se elimina por completo.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text("Quitar", color = EspochRed) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

// ============================================================================ crear / editar laboratorio
data class LabForm(
    val existing: Lab? = null,
    val code: String = "",
    val name: String = "",
    val building: String = "ESPOCH Sede Orellana",
    val floor: String = "",
    val capacity: String = "30",
    val demo: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class LabFormViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: AdminRepository,
    private val labs: LabRepository,
) : ViewModel() {
    private val labId: Long = handle.get<Long>("labId") ?: 0L
    private val _state = MutableStateFlow(LabForm())
    val state: StateFlow<LabForm> = _state
    val slots: StateFlow<List<ScheduleSlot>> =
        (if (labId > 0) labs.scheduleForLab(labId) else emptyFlow()).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            val demo = repo.isDemo()
            val lab = if (labId > 0) labs.getLab(labId) else null
            _state.update {
                if (lab == null) it.copy(demo = demo)
                else it.copy(existing = lab, code = lab.code, name = lab.name, building = lab.building, floor = lab.floor,
                    capacity = lab.capacity.toString(), demo = demo)
            }
        }
    }

    fun update(f: (LabForm) -> LabForm) = _state.update { f(it).copy(error = null, message = null) }

    fun save() = viewModelScope.launch {
        val f = _state.value
        _state.update { it.copy(saving = true, error = null) }
        repo.saveLab(LabDto(f.code.trim().uppercase(), f.name.trim(), f.building.trim(), f.floor.trim(), f.capacity.toIntOrNull() ?: 0), f.existing)
            .onSuccess {
                val saved = labs.allLabs().firstOrNull { it.code == f.code.trim().uppercase() }
                _state.update { it.copy(saving = false, existing = saved ?: it.existing, message = "Laboratorio guardado") }
            }
            .onFailure { e -> _state.update { it.copy(saving = false, error = e.message) } }
    }

    fun addSlot(slot: SlotInDto, done: (String?) -> Unit) = viewModelScope.launch {
        val lab = _state.value.existing ?: return@launch done("Guarda primero el laboratorio")
        repo.addSlot(lab, slot).onSuccess { done(null) }.onFailure { done(it.message) }
    }

    fun deleteSlot(slot: ScheduleSlot) = viewModelScope.launch {
        val lab = _state.value.existing ?: return@launch
        repo.deleteSlot(lab, slot).onFailure { e -> _state.update { it.copy(error = e.message) } }
    }
}

@Composable
fun LabFormScreen(padding: PaddingValues, vm: LabFormViewModel = hiltViewModel()) {
    val f by vm.state.collectAsStateWithLifecycle()
    val slots by vm.slots.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<ScheduleSlot?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text(if (f.existing != null) "Editar laboratorio" else "Nuevo laboratorio", style = MaterialTheme.typography.titleLarge) }
        if (f.demo) item { InfoBanner("Modo demostración: los cambios quedan solo en este teléfono.", color = StatusAmber) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(f.code, { v -> vm.update { it.copy(code = v.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' }) } },
                    Modifier.fillMaxWidth(), label = { Text("Código (p. ej. LAB-C5)") }, singleLine = true, enabled = f.existing == null)
                OutlinedTextField(f.name, { v -> vm.update { it.copy(name = v) } }, Modifier.fillMaxWidth(), label = { Text("Nombre") }, singleLine = true)
                OutlinedTextField(f.building, { v -> vm.update { it.copy(building = v) } }, Modifier.fillMaxWidth(), label = { Text("Edificio / sede") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(f.floor, { v -> vm.update { it.copy(floor = v) } }, Modifier.weight(1f), label = { Text("Piso") }, singleLine = true)
                    OutlinedTextField(f.capacity, { v -> vm.update { it.copy(capacity = v.filter(Char::isDigit).take(4)) } }, Modifier.weight(1f),
                        label = { Text("Capacidad") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            }
        }
        f.error?.let { item { InfoBanner(it) } }
        f.message?.let { item { InfoBanner(it, color = StatusGreen) } }
        item {
            Button(onClick = vm::save, enabled = !f.saving, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(12.dp)) {
                if (f.saving) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text(if (f.existing != null) "Guardar cambios" else "Crear laboratorio")
            }
        }
        if (f.existing != null) {
            item {
                SectionTitle("Horario (${slots.size} bloques)") {
                    TextButton(onClick = { adding = true }) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Agregar bloque")
                    }
                }
            }
            val byDay = slots.sortedWith(compareBy({ it.dayOfWeek }, { it.startMinute })).groupBy { it.dayOfWeek }
            byDay.forEach { (day, list) ->
                item(key = "d$day") { Text(ScheduleSlot.DAY_NAMES[day - 1], style = MaterialTheme.typography.titleSmall, color = InkSoft) }
                items(list, key = { it.id }) { slot ->
                    AppCard(Modifier.fillMaxWidth().animateItem()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(slot.subject, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${slot.timeRange} · ${slot.teacher.ifBlank { "Sin docente" }}", style = MaterialTheme.typography.bodySmall,
                                    color = InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { toDelete = slot }) { Icon(Icons.Default.Delete, "Eliminar bloque", tint = EspochRed) }
                        }
                    }
                }
            }
        }
    }
    if (adding) AddSlotDialog(onDismiss = { adding = false }, onSave = vm::addSlot)
    toDelete?.let { slot ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("¿Eliminar bloque?") },
            text = { Text("${slot.subject} · ${slot.dayName} ${slot.timeRange}. También se pierde su lista de asistencia.") },
            confirmButton = { TextButton(onClick = { toDelete = null; vm.deleteSlot(slot) }) { Text("Eliminar", color = EspochRed) } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }
}

private fun parseTime(t: String): Int? {
    val p = t.trim().split(":")
    val h = p.getOrNull(0)?.toIntOrNull() ?: return null
    val m = p.getOrNull(1)?.toIntOrNull() ?: 0
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddSlotDialog(onDismiss: () -> Unit, onSave: (SlotInDto, (String?) -> Unit) -> Unit) {
    var subject by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var parallel by remember { mutableStateOf("") }
    var day by remember { mutableIntStateOf(1) }
    var start by remember { mutableStateOf("07:00") }
    var end by remember { mutableStateOf("09:00") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo bloque de horario") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(subject, { subject = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Materia") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..6).forEach { d ->
                        FilterChip(day == d, { day = d }, label = { Text(ScheduleSlot.DAY_SHORT[d - 1]) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = EspochRed, selectedLabelColor = Color.White))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it; error = null }, Modifier.weight(1f), label = { Text("Inicio") }, singleLine = true)
                    OutlinedTextField(end, { end = it; error = null }, Modifier.weight(1f), label = { Text("Fin") }, singleLine = true)
                }
                OutlinedTextField(teacher, { teacher = it }, Modifier.fillMaxWidth(), label = { Text("Docente (opcional)") }, singleLine = true)
                OutlinedTextField(parallel, { parallel = it }, Modifier.fillMaxWidth(), label = { Text("Paralelo (opcional)") }, singleLine = true)
                error?.let { Text(it, color = EspochRed, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = parseTime(start)
                val e = parseTime(end)
                if (s == null || e == null) { error = "Escribe las horas como 07:00"; return@TextButton }
                onSave(SlotInDto(subject.trim(), teacher.trim(), parallel.trim(), day, s, e)) { err -> if (err == null) onDismiss() else error = err }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
