package ec.edu.espoch.omniflow.domain.usecase

import ec.edu.espoch.omniflow.domain.model.AlertType
import ec.edu.espoch.omniflow.domain.model.ClassSession
import ec.edu.espoch.omniflow.domain.model.Lab
import ec.edu.espoch.omniflow.domain.model.LabStatus
import ec.edu.espoch.omniflow.domain.model.ScheduleSlot
import java.time.LocalDateTime

/**
 * Logica de negocio pura (sin Android) del Documento Maestro, pagina 4:
 *  - Calculo del estado del laboratorio (Libre / Ocupado / Proximo / Fuera de horario / Anomalo).
 *  - Reglas basicas que generan alertas.
 */
object LabRules {

    const val PROXIMO_MINUTES = 30
    const val TEACHER_GRACE_MINUTES = 15
    const val EARLY_EXIT_MINUTES = 15
    const val ACADEMIC_START = 7 * 60
    const val ACADEMIC_END = 21 * 60

    data class ScheduleContext(
        val current: ScheduleSlot?,
        val next: ScheduleSlot?,
        val minutesToNext: Int?,
        val minuteOfDay: Int,
        val dayOfWeek: Int,
    )

    fun scheduleContext(slots: List<ScheduleSlot>, now: LocalDateTime): ScheduleContext {
        val day = now.dayOfWeek.value
        val minute = now.hour * 60 + now.minute
        val today = slots.filter { it.dayOfWeek == day }.sortedBy { it.startMinute }
        val current = today.firstOrNull { minute >= it.startMinute && minute < it.endMinute }
        val next = today.firstOrNull { it.startMinute > minute }
        return ScheduleContext(current, next, next?.let { it.startMinute - minute }, minute, day)
    }

    fun status(lab: Lab, ctx: ScheduleContext, occupancy: Int?, anomaly: Boolean = false): LabStatus {
        val occ = occupancy ?: 0
        return when {
            anomaly -> LabStatus.ANOMALO
            occ > lab.capacity -> LabStatus.ANOMALO
            ctx.current != null -> LabStatus.OCUPADO
            occ > 0 && isOutsideAcademicHours(ctx) -> LabStatus.ANOMALO
            occ > 0 -> LabStatus.OCUPADO
            ctx.minutesToNext != null && ctx.minutesToNext <= PROXIMO_MINUTES -> LabStatus.PROXIMO
            isOutsideAcademicHours(ctx) -> LabStatus.FUERA_DE_HORARIO
            else -> LabStatus.LIBRE
        }
    }

    fun isOutsideAcademicHours(ctx: ScheduleContext) =
        ctx.dayOfWeek == 7 || ctx.minuteOfDay < ACADEMIC_START || ctx.minuteOfDay >= ACADEMIC_END

    data class Trigger(val type: AlertType, val message: String)

    /**
     * Reglas basicas de negocio:
     *  1. Laboratorio ocupado sin clase programada -> alerta.
     *  2. Ocupacion > capacidad (sobreaforo) -> alerta.
     *  3. Nadie presente en los primeros minutos de la clase -> observacion por posible ausencia del docente.
     *  4. Laboratorio vacio antes de terminar la clase -> salida anticipada (historial).
     */
    fun evaluate(
        lab: Lab,
        ctx: ScheduleContext,
        occupancy: Int,
        session: ClassSession?,
        minutesEmpty: Int,
    ): List<Trigger> {
        val out = mutableListOf<Trigger>()
        val cur = ctx.current

        if (occupancy > lab.capacity) {
            out += Trigger(
                AlertType.SOBREAFORO,
                "Se ha superado el aforo máximo ($occupancy/${lab.capacity} personas).",
            )
        }

        val startsSoon = ctx.minutesToNext != null && ctx.minutesToNext <= 10
        if (cur == null && occupancy > 0 && !startsSoon) {
            out += Trigger(
                AlertType.FUERA_DE_HORARIO,
                "Se detectaron $occupancy persona(s) sin clase programada en este horario.",
            )
        }

        if (cur != null) {
            val sinceStart = ctx.minuteOfDay - cur.startMinute
            val toEnd = cur.endMinute - ctx.minuteOfDay
            val nobodyYet = session?.firstPresenceAt == null && occupancy == 0
            if (sinceStart >= TEACHER_GRACE_MINUTES && nobodyYet) {
                out += Trigger(
                    AlertType.AUSENCIA_DOCENTE,
                    "No se ha registrado presencia en la clase ${cur.subject} (${cur.teacher}) " +
                        "a $sinceStart min de iniciada.",
                )
            }
            if (session?.firstPresenceAt != null && occupancy == 0 && minutesEmpty >= 5 && toEnd > EARLY_EXIT_MINUTES) {
                out += Trigger(
                    AlertType.SALIDA_ANTICIPADA,
                    "El laboratorio quedó vacío $toEnd min antes de terminar ${cur.subject}.",
                )
            }
        }
        return out
    }
}
