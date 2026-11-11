package ec.edu.espoch.omniflow.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class Role(val label: String) {
    ADMINISTRADOR("Administrador"),
    COORDINADOR("Coordinador"),
    DOCENTE("Docente"),
    ESTUDIANTE("Estudiante"),
}

@Serializable
enum class AuthSource { OASIS, DEMO, OMNIFLOW }

@Serializable
data class User(
    val username: String,
    val fullName: String,
    val email: String = "",
    val role: Role,
    val cedula: String? = null,
    val careerCode: String? = null,
    val source: AuthSource,
    /** JWT del backend OmniFlow (cuentas propias). */
    val token: String? = null,
    val mustChangePassword: Boolean = false,
) {
    val bearer: String? get() = token?.let { "Bearer $it" }
    val isStudent: Boolean get() = role == Role.ESTUDIANTE
    val firstName: String get() = fullName.split(" ").firstOrNull().orEmpty()
    val canMonitor: Boolean get() = role == Role.ADMINISTRADOR || role == Role.COORDINADOR || role == Role.DOCENTE
}

enum class LabStatus(val label: String) {
    LIBRE("Libre"),
    OCUPADO("Ocupado"),
    PROXIMO("Próximo"),
    FUERA_DE_HORARIO("Fuera de horario"),
    ANOMALO("Anómalo"),
}

data class Lab(
    val id: Long,
    val code: String,
    val name: String,
    val building: String,
    val floor: String,
    val capacity: Int,
) {
    val location: String get() = listOf(building, floor).filter { it.isNotBlank() }.joinToString(" - ").ifBlank { "ESPOCH Sede Orellana" }
}

/** Bloque de horario semanal. dayOfWeek: 1 = lunes ... 7 = domingo. Minutos desde 00:00. */
data class ScheduleSlot(
    val id: Long,
    val labId: Long,
    val subject: String,
    val teacher: String,
    val parallel: String,
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val enrolled: Int,
    val source: String = "LOCAL",
    val careerCode: String = "",
    val level: Int = 0,
    val remoteId: Long = 0,
) {
    val dayName: String get() = DAY_NAMES.getOrElse(dayOfWeek - 1) { "" }

    val timeRange: String get() = "${fmt(startMinute)} - ${fmt(endMinute)}"

    companion object {
        fun fmt(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
        val DAY_NAMES = listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo")
        val DAY_SHORT = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")
    }
}

/** Estado consolidado de un laboratorio en un instante (horario + vision computacional). */
data class LabSnapshot(
    val lab: Lab,
    val status: LabStatus,
    /** null = el laboratorio aun no tiene camara/fuente de video asignada. */
    val occupancy: Int?,
    val currentClass: ScheduleSlot?,
    val nextClass: ScheduleSlot?,
    val minutesToNext: Int?,
    val lastUpdate: Long?,
    val hasCamera: Boolean,
) {
    val occupancyRatio: Float get() = ((occupancy ?: 0).toFloat() / lab.capacity.coerceAtLeast(1)).coerceIn(0f, 1.5f)
}

enum class AlertType(val title: String, val critical: Boolean) {
    SOBREAFORO("Sobreaforo detectado", true),
    FUERA_DE_HORARIO("Uso fuera de horario", true),
    AUSENCIA_DOCENTE("Ausencia de docente", true),
    SALIDA_ANTICIPADA("Salida anticipada", false),
    ANOMALIA_ML("Anomalía de ocupación", true),
    PROXIMA_CLASE("Próxima clase", false),
}

data class Alert(
    val id: Long,
    val labId: Long,
    val labName: String,
    val type: AlertType,
    val message: String,
    val timestamp: Long,
    val read: Boolean,
)

/** Registro de una clase concreta (fecha + bloque de horario) observada por la camara. */
data class ClassSession(
    val id: Long,
    val labId: Long,
    val scheduleId: Long,
    val epochDay: Long,
    val firstPresenceAt: Long?,
    val lastPresenceAt: Long?,
    val peakCount: Int,
    val avgCount: Float,
    val samples: Int,
    val endedEarly: Boolean,
)
