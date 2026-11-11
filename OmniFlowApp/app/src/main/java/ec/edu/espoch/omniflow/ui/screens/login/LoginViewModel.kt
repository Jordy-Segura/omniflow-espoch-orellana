package ec.edu.espoch.omniflow.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.edu.espoch.omniflow.data.repository.AuthRepository
import ec.edu.espoch.omniflow.data.repository.SettingsRepository
import ec.edu.espoch.omniflow.data.repository.SyncRepository
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
    /** Direccion del servidor OmniFlow (se puede cambiar desde el inicio de sesion). */
    val serverUrl: String = "",
    val serverMessage: String? = null,
    val serverOk: Boolean = false,
    val testingServer: Boolean = false,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val settings: SettingsRepository,
    private val sync: SyncRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState(oasisAvailable = auth.oasisAvailable, showDemo = !auth.oasisAvailable))
    val state: StateFlow<LoginUiState> = _state

    init {
        viewModelScope.launch { settings.monitor.collect { m -> _state.update { it.copy(serverUrl = m.apiUrl) } } }
    }

    /** Guarda la direccion del servidor y comprueba que responda. */
    fun saveServer(url: String) {
        val clean = url.trim().trimEnd('/')
        if (!(clean.startsWith("http://") || clean.startsWith("https://"))) {
            _state.update { it.copy(serverMessage = "La dirección debe empezar con http:// o https://", serverOk = false) }
            return
        }
        _state.update { it.copy(testingServer = true, serverMessage = "Probando conexión…", serverOk = false) }
        viewModelScope.launch {
            settings.updateMonitor { it.copy(apiUrl = clean) }
            sync.testConnection()
                .onSuccess { msg -> _state.update { it.copy(testingServer = false, serverOk = true, serverMessage = msg, error = null) } }
                .onFailure { e ->
                    _state.update {
                        it.copy(testingServer = false, serverOk = false, serverMessage = "Sin respuesta del servidor: ${e.message ?: "error de red"}")
                    }
                }
        }
    }

    fun clearServerMessage() = _state.update { it.copy(serverMessage = null, serverOk = false) }

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
