package ec.edu.espoch.omniflow.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.edu.espoch.omniflow.data.repository.AuthRepository
import ec.edu.espoch.omniflow.domain.model.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val demoName: String = "",
    val demoRole: Role = Role.COORDINADOR,
    val loading: Boolean = false,
    val error: String? = null,
    val oasisAvailable: Boolean = false,
    val showDemo: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(private val auth: AuthRepository) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState(oasisAvailable = auth.oasisAvailable, showDemo = !auth.oasisAvailable))
    val state: StateFlow<LoginUiState> = _state

    fun onUsername(v: String) = _state.update { it.copy(username = v, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onDemoName(v: String) = _state.update { it.copy(demoName = v) }
    fun onDemoRole(r: Role) = _state.update { it.copy(demoRole = r) }
    fun toggleDemo() = _state.update { it.copy(showDemo = !it.showDemo, error = null) }

    fun login() {
        val s = _state.value
        if (s.username.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(error = "Ingresa tu usuario institucional y contraseña") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            auth.loginInstitutional(s.username, s.password)
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: "Error de autenticación") } }
                .onSuccess { _state.update { it.copy(loading = false, password = "") } }
        }
    }

    fun loginDemo() {
        val s = _state.value
        viewModelScope.launch { auth.loginDemo(s.demoName, s.demoRole) }
    }
}
