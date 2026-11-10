package ec.edu.espoch.omniflow.data.repository

import ec.edu.espoch.omniflow.data.local.ClassSessionDao
import ec.edu.espoch.omniflow.data.local.OccupancyDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

data class LabAttendance(val labId: Long, val labName: String, val attendancePct: Int, val avgOccupancyPct: Int, val peak: Int)

data class LateArrival(val labName: String, val subject: String, val teacher: String, val date: LocalDate, val minutesLate: Int)

data class WeeklyReport(
    val avgOccupancyPct: Int,
    val totalUses: Int,
    val classesRegistered: Int,
    val alertsThisWeek: Int,
    /** Lunes..Domingo -> % de ocupacion promedio (0..100). */
    val byWeekday: List<Int>,
    val perLab: List<LabAttendance>,
    val lateArrivals: List<LateArrival>,
    val earlyExits: Int,
    val hasDemoData: Boolean,
)

@Singleton
class ReportRepository @Inject constructor(
    private val occupancyDao: OccupancyDao,
    private val sessionDao: ClassSessionDao,
    private val labRepository: LabRepository,
    private val alertRepository: AlertRepository,
) {
    fun weekly(days: Int = 7): Flow<WeeklyReport> {
        val since = System.currentTimeMillis() - days * 24 * 3600 * 1000L
        val fromDay = LocalDate.now().minusDays(days.toLong()).toEpochDay()
        val base = combine(
            labRepository.labs,
            labRepository.schedule,
            sessionDao.observeFrom(fromDay),
            occupancyDao.observeLabAverages(since),
        ) { labs, slots, sessions, averages -> Base(labs, slots, sessions, averages) }

        return combine(
            base, occupancyDao.observeByWeekday(since), alertRepository.countSince(since), occupancyDao.observeHasDemo(),
        ) { b, weekday, alerts, hasDemo ->
            val labsById = b.labs.associateBy { it.id }
            val slotsById = b.slots.associateBy { it.id }
            val totalCapacity = b.labs.sumOf { it.capacity }.coerceAtLeast(1)
            val avgCapacity = totalCapacity.toDouble() / b.labs.size.coerceAtLeast(1)

            // strftime('%w'): 0 = domingo. Se reordena a lunes..domingo.
            val byDay = (1..7).map { iso ->
                val sqlDay = iso % 7
                val avg = weekday.firstOrNull { it.day == sqlDay }?.avgCount ?: 0.0
                (avg / avgCapacity * 100).roundToInt().coerceIn(0, 100)
            }

            val perLab = b.labs.map { lab ->
                val labSessions = b.sessions.filter { it.labId == lab.id && it.samples > 0 }
                val attendance = labSessions.mapNotNull { s ->
                    val enrolled = slotsById[s.scheduleId]?.enrolled ?: return@mapNotNull null
                    if (enrolled <= 0) null else (s.peakCount.toDouble() / enrolled).coerceAtMost(1.0)
                }
                val avg = b.averages.firstOrNull { it.labId == lab.id }
                LabAttendance(
                    labId = lab.id,
                    labName = lab.name,
                    attendancePct = if (attendance.isEmpty()) 0 else (attendance.average() * 100).roundToInt(),
                    avgOccupancyPct = ((avg?.avgCount ?: 0.0) / lab.capacity.coerceAtLeast(1) * 100).roundToInt().coerceIn(0, 100),
                    peak = avg?.peak ?: 0,
                )
            }.sortedByDescending { it.attendancePct }

            val late = b.sessions.mapNotNull { s ->
                val slot = slotsById[s.scheduleId] ?: return@mapNotNull null
                val first = s.firstPresenceAt ?: return@mapNotNull null
                val t = Instant.ofEpochMilli(first).atZone(ZoneId.systemDefault()).toLocalTime()
                val minutesLate = t.hour * 60 + t.minute - slot.startMinute
                if (minutesLate >= LATE_THRESHOLD_MIN) {
                    LateArrival(labsById[s.labId]?.name.orEmpty(), slot.subject, slot.teacher, LocalDate.ofEpochDay(s.epochDay), minutesLate)
                } else null
            }.sortedByDescending { it.date }

            val occupiedSessions = b.sessions.filter { it.firstPresenceAt != null }
            WeeklyReport(
                avgOccupancyPct = if (perLab.isEmpty()) 0 else perLab.map { it.avgOccupancyPct }.average().roundToInt(),
                totalUses = occupiedSessions.size,
                classesRegistered = b.sessions.size,
                alertsThisWeek = alerts,
                byWeekday = byDay,
                perLab = perLab,
                lateArrivals = late,
                earlyExits = b.sessions.count { it.endedEarly },
                hasDemoData = hasDemo,
            )
        }
    }

    private data class Base(
        val labs: List<ec.edu.espoch.omniflow.domain.model.Lab>,
        val slots: List<ec.edu.espoch.omniflow.domain.model.ScheduleSlot>,
        val sessions: List<ec.edu.espoch.omniflow.data.local.ClassSessionEntity>,
        val averages: List<ec.edu.espoch.omniflow.data.local.LabAvg>,
    )

    companion object {
        const val LATE_THRESHOLD_MIN = 10
    }
}
