package ec.edu.espoch.omniflow.ui.screens.labs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import ec.edu.espoch.omniflow.data.repository.LabRepository
import ec.edu.espoch.omniflow.domain.model.LabSnapshot
import ec.edu.espoch.omniflow.domain.model.LabStatus
import ec.edu.espoch.omniflow.ui.components.EmptyState
import ec.edu.espoch.omniflow.ui.components.LabCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.text.Normalizer
import javax.inject.Inject

data class LabsState(val query: String = "", val filter: LabStatus? = null, val labs: List<LabSnapshot> = emptyList())

@HiltViewModel
class LabsViewModel @Inject constructor(repo: LabRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow<LabStatus?>(null)

    val state: StateFlow<LabsState> = combine(repo.snapshots, query, filter) { labs, q, f ->
        val nq = q.normalized()
        LabsState(
            q, f,
            labs.filter { (f == null || it.status == f) && (nq.isBlank() || it.lab.name.normalized().contains(nq) || it.lab.code.normalized().contains(nq) || it.lab.building.normalized().contains(nq)) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LabsState())

    fun onQuery(q: String) { query.value = q }
    fun onFilter(f: LabStatus?) { filter.value = if (filter.value == f) null else f }

    private fun String.normalized() =
        Normalizer.normalize(lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
}

@Composable
fun LabsScreen(padding: PaddingValues, onOpenLab: (Long) -> Unit, vm: LabsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 12.dp, 16.dp, padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Laboratorios", style = MaterialTheme.typography.titleLarge) }
        item {
            OutlinedTextField(
                value = s.query,
                onValueChange = vm::onQuery,
                placeholder = { Text("Buscar laboratorio…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LabStatus.entries) { st ->
                    FilterChip(selected = s.filter == st, onClick = { vm.onFilter(st) }, label = { Text(st.label) })
                }
            }
        }
        if (s.labs.isEmpty()) {
            item { EmptyState(Icons.Default.SearchOff, "Sin resultados", "No hay laboratorios que coincidan con el filtro.") }
        }
        items(s.labs, key = { it.lab.id }) { Box(Modifier.animateItem()) { LabCard(it) { onOpenLab(it.lab.id) } } }
    }
}
