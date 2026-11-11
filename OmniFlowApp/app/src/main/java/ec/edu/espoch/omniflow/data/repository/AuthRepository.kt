package ec.edu.espoch.omniflow.data.repository

import ec.edu.espoch.omniflow.data.remote.api.ChangePasswordDto
import ec.edu.espoch.omniflow.data.remote.api.LoginDto
import ec.edu.espoch.omniflow.data.remote.api.TokenDto
import ec.edu.espoch.omniflow.data.remote.oasis.OasisException
import ec.edu.espoch.omniflow.data.remote.oasis.OasisSoapClient
import ec.edu.espoch.omniflow.domain.model.AuthSource
import ec.edu.espoch.omniflow.domain.model.Role
import ec.edu.espoch.omniflow.domain.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

class AuthException(message: String) : Exception(message)

@Singleton
class AuthRepository @Inject constructor(
    private val oasis: OasisSoapClient,
    private val settings: SettingsRepository,
    private val sync: SyncRepository,
) {
    val currentUser: Flow<User?> = settings.user

    val oasisAvailable: Boolean get() = true

    /**
     * Inicio de sesion:
     *  1. Cuenta propia en el backend OmniFlow: correo institucional o cedula; clave inicial = cedula.
     *  2. Si el backend no responde: OASIS directo (Seguridad.asmx).
     *
     * OASIS directo necesita credenciales de servicio que la app aun no tiene, asi que si el servidor no responde
     * se informa eso (y no "contraseña incorrecta", que confundia al usuario).
     */
    suspend fun loginInstitutional(username: String, password: String): Result<User> = runCatching {
        val api = sync.api()
        val serverProblem: String = if (api == null) {
            "Configura la dirección del servidor OmniFlow (botón Servidor, abajo)."
        } else {
            try {
                return@runCatching saveToken(api.login(LoginDto(username.trim(), password)))
            } catch (e: HttpException) {
                throw AuthException(errorDetail(e) ?: "Usuario o contraseña incorrectos")
            } catch (_: IOException) {
                "No se pudo conectar con el servidor OmniFlow (${sync.baseUrl().orEmpty().trimEnd('/')}). " +
                    "Revisa que el teléfono esté en la misma red o cambia la dirección en el botón Servidor."
            }
        }
        try {
            loginOasis(username, password)
        } catch (_: Exception) {
            throw AuthException(serverProblem)
        }
    }

    suspend fun changePassword(current: String, new: String): Result<User> = runCatching {
        val user = settings.user.first() ?: throw AuthException("Inicia sesión de nuevo")
        val api = sync.api() ?: throw AuthException("Configura la URL del servidor OmniFlow")
        try {
            saveToken(api.changePassword(user.bearer ?: throw AuthException("Inicia sesión de nuevo"), ChangePasswordDto(current, new)))
        } catch (e: HttpException) {
            throw AuthException(errorDetail(e) ?: "No se pudo cambiar la contraseña")
        }
    }

    private suspend fun saveToken(t: TokenDto): User {
        val u = t.user
        val user = User(
            username = u.username,
            fullName = u.fullName,
            email = u.email,
            role = runCatching { Role.valueOf(u.role) }.getOrDefault(Role.ESTUDIANTE),
            cedula = u.cedula,
            careerCode = u.careerCode,
            source = if (u.source == "DEMO") AuthSource.DEMO else AuthSource.OMNIFLOW,
            token = t.accessToken,
            mustChangePassword = u.mustChangePassword,
        )
        settings.saveUser(user)
        return user
    }

    private suspend fun loginOasis(username: String, password: String): User {
        val login = username.trim().substringBefore("@")
        val roles = try {
            oasis.autenticarUsuarioCarrera(login, password)
        } catch (e: OasisException) {
            if (e.message.orEmpty().contains("referencia a objeto", ignoreCase = true)) {
                throw OasisException("Usuario o contraseña incorrectos")
            }
            throw e
        }
        if (roles.isEmpty()) throw OasisException("Usuario o contraseña incorrectos")
        val persona = runCatching { oasis.getUsuarioFacultad(login, password) }.getOrNull()
        val user = User(
            username = login,
            fullName = persona?.let { "${it.nombres} ${it.apellidos}".trim() }?.ifBlank { null } ?: login,
            email = persona?.email.orEmpty(),
            role = mapRole(roles.map { it.nombreRol }),
            cedula = persona?.cedula,
            careerCode = roles.firstOrNull()?.codigoCarrera,
            source = AuthSource.OASIS,
        )
        settings.saveUser(user)
        return user
    }

    /**
     * Modo demostracion: sesion de solo consulta en el servidor (horarios y estudiantes reales de OASIS).
     * Si el servidor no responde, entra igual con el horario incluido en la app.
     */
    suspend fun loginDemo(name: String, role: Role): User {
        val clean = name.trim().ifBlank { role.label }
        sync.api()?.let { api ->
            withTimeoutOrNull(6_000) { runCatching { api.login(LoginDto(clean, demoRole = role.name)) }.getOrNull() }
                ?.let { return saveToken(it) }
        }
        val user = User(username = clean.lowercase().replace(" ", "."), fullName = clean, role = role, source = AuthSource.DEMO)
        settings.saveUser(user)
        return user
    }

    suspend fun logout() = settings.saveUser(null)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Mensaje `detail` de FastAPI. */
        fun errorDetail(e: HttpException): String? = runCatching {
            json.parseToJsonElement(e.response()?.errorBody()?.string().orEmpty()).jsonObject["detail"]?.jsonPrimitive?.content
        }.getOrNull()

        fun mapRole(roleNames: List<String>): Role {
            val all = roleNames.joinToString(" ").uppercase()
            return when {
                "ADMIN" in all -> Role.ADMINISTRADOR
                "DIRECTOR" in all || "COORD" in all || "DECAN" in all || "SECRETAR" in all -> Role.COORDINADOR
                "DOCENTE" in all || "PROFESOR" in all -> Role.DOCENTE
                else -> Role.ESTUDIANTE
            }
        }
    }
}
