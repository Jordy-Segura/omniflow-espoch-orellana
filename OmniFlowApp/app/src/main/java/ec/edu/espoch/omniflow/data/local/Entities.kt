package ec.edu.espoch.omniflow.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ec.edu.espoch.omniflow.domain.model.Alert
import ec.edu.espoch.omniflow.domain.model.AlertType
import ec.edu.espoch.omniflow.domain.model.ClassSession
import ec.edu.espoch.omniflow.domain.model.Lab
import ec.edu.espoch.omniflow.domain.model.ScheduleSlot

@Entity(tableName = "labs", indices = [Index(value = ["code"], unique = true)])
data class LabEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    val name: String,
    val building: String,
    val floor: String,
    val capacity: Int,
) {
    fun toDomain() = Lab(id, code, name, building, floor, capacity)
}

@Entity(tableName = "schedule", indices = [Index("labId"), Index("dayOfWeek")])
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val labId: Long,
    val subject: String,
    val teacher: String,
    val parallel: String,
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val enrolled: Int,
    /** LOCAL (creado en el telefono), PDF (horario oficial incluido en la app) o BACKEND (descargado del servidor) */
    val source: String = "LOCAL",
    /** Carrera (TI, IAMB) y nivel segun OASIS */
    val careerCode: String = "",
    val level: Int = 0,
    /** Id del bloque en el servidor (0 = aun no se conoce). */
    val remoteId: Long = 0,
) {
    fun toDomain() = ScheduleSlot(
        id, labId, subject, teacher, parallel, dayOfWeek, startMinute, endMinute, enrolled, source, careerCode, level, remoteId,
    )
}

/** Persona agregada a mano a una clase en el modo demostracion (solo vive en este telefono). */
@Entity(tableName = "local_people", indices = [Index("slotId")])
data class LocalPersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val slotId: Long,
    val nombres: String,
    val apellidos: String,
    val cedula: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** Marca de asistencia de una persona local en una fecha. */
@Entity(tableName = "local_marks", primaryKeys = ["personId", "epochDay"], indices = [Index(value = ["slotId", "epochDay"])])
data class LocalMarkEntity(
    val personId: Long,
    val epochDay: Long,
    val slotId: Long,
    val status: String,
    val markedAt: Long = System.currentTimeMillis(),
)

/** Muestra de ocupacion registrada por el modulo de vision (o generada para la demo). */
@Entity(tableName = "occupancy_events", indices = [Index(value = ["labId", "timestamp"])])
data class OccupancyEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val labId: Long,
    val timestamp: Long,
    val count: Int,
    val entries: Int = 0,
    val exits: Int = 0,
    /** Hora de la semana 0..167 (para el detector de anomalias). */
    val hourOfWeek: Int,
    /** CAMERA_PHONE, CAMERA_IP o DEMO */
    val source: String,
    val synced: Boolean = false,
)

@Entity(tableName = "class_sessions", indices = [Index(value = ["scheduleId", "epochDay"], unique = true), Index("labId")])
data class ClassSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val labId: Long,
    val scheduleId: Long,
    val epochDay: Long,
    val firstPresenceAt: Long? = null,
    val lastPresenceAt: Long? = null,
    val peakCount: Int = 0,
    val sumCount: Long = 0,
    val samples: Int = 0,
    val endedEarly: Boolean = false,
    /** Marca datos de prueba de versiones anteriores (ya no se generan); se pueden borrar desde Ajustes. */
    val demo: Boolean = false,
) {
    fun toDomain() = ClassSession(
        id, labId, scheduleId, epochDay, firstPresenceAt, lastPresenceAt, peakCount,
        if (samples == 0) 0f else sumCount.toFloat() / samples, samples, endedEarly,
    )
}

@Entity(tableName = "alerts", indices = [Index(value = ["labId", "type", "timestamp"])])
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val labId: Long,
    val labName: String,
    val type: String,
    val message: String,
    val timestamp: Long,
    val read: Boolean = false,
) {
    fun toDomain() = Alert(
        id, labId, labName,
        runCatching { AlertType.valueOf(type) }.getOrDefault(AlertType.ANOMALIA_ML),
        message, timestamp, read,
    )
}
