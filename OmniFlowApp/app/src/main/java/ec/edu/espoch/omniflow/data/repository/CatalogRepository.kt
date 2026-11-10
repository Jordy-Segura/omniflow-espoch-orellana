package ec.edu.espoch.omniflow.data.repository

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import ec.edu.espoch.omniflow.data.local.LabDao
import ec.edu.espoch.omniflow.data.local.LabEntity
import ec.edu.espoch.omniflow.data.local.OmniFlowDatabase
import ec.edu.espoch.omniflow.data.local.ScheduleDao
import ec.edu.espoch.omniflow.data.local.ScheduleEntity
import ec.edu.espoch.omniflow.data.remote.api.ScheduleEntryDto
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Formato de `assets/lab_schedule.json`: horario oficial de los 3 laboratorios (PDF P0046 + OASIS). */
@Serializable
data class CatalogFile(
    val period: String = "",
    val periodDescription: String = "",
    val labs: List<CatalogLab>,
)

@Serializable
data class CatalogLab(
    val code: String,
    val name: String,
    val building: String = "",
    val floor: String = "",
    val capacity: Int = 30,
    val slots: List<CatalogSlot> = emptyList(),
)

@Serializable
data class CatalogSlot(
    val subject: String,
    val teacher: String = "",
    val parallel: String = "",
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val enrolled: Int = 0,
    val careerCode: String = "",
    val level: Int = 0,
)

/**
 * Laboratorios y horario oficial. Se cargan del archivo incluido en la app y, cuando hay backend,
 * se reemplazan por la version del servidor (que se mantiene sincronizada con OASIS).
 */
@Singleton
class CatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: OmniFlowDatabase,
    private val labDao: LabDao,
    private val scheduleDao: ScheduleDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun bundled(): CatalogFile =
        context.assets.open(ASSET).use { json.decodeFromString(CatalogFile.serializer(), it.readBytes().decodeToString()) }

    suspend fun applyBundled() = apply(bundled().labs, source = "PDF")

    /** Aplica los laboratorios y el horario descargados del backend. */
    suspend fun applyRemote(labs: List<CatalogLab>, entries: List<ScheduleEntryDto>) {
        val byLab = entries.groupBy { it.labCode }
        apply(
            labs.map { lab ->
                lab.copy(slots = byLab[lab.code].orEmpty().map {
                    CatalogSlot(prettySubject(it.subject), it.teacher, it.parallel, it.dayOfWeek, it.startMinute,
                        it.endMinute, it.enrolled, it.careerCode, it.level)
                })
            },
            source = "BACKEND",
        )
    }

    private suspend fun apply(labs: List<CatalogLab>, source: String) = db.withTransaction {
        for (lab in labs) {
            val existing = labDao.byCode(lab.code)
            val labId = if (existing == null) {
                labDao.insert(LabEntity(code = lab.code, name = lab.name, building = lab.building, floor = lab.floor, capacity = lab.capacity))
            } else {
                labDao.update(existing.copy(name = lab.name, building = lab.building, floor = lab.floor, capacity = lab.capacity))
                existing.id
            }
            // Si el servidor aun no tiene horario para el laboratorio, se conserva el que ya hay.
            if (lab.slots.isEmpty() && source == "BACKEND") continue
            scheduleDao.deleteOfficialForLab(labId)
            scheduleDao.insertAll(lab.slots.map {
                ScheduleEntity(
                    labId = labId, subject = it.subject, teacher = it.teacher, parallel = it.parallel,
                    dayOfWeek = it.dayOfWeek, startMinute = it.startMinute, endMinute = it.endMinute,
                    enrolled = it.enrolled, source = source, careerCode = it.careerCode, level = it.level,
                )
            })
        }
    }

    companion object {
        const val ASSET = "lab_schedule.json"
        private val SMALL = setOf("de", "y", "la", "del", "en", "a", "los", "las", "el")
        private val ACRONYMS = mapOf("ti" to "TI", "iot" to "IoT", "i" to "I", "ii" to "II", "iii" to "III", "iv" to "IV")

        /** "ADMINISTRACIÓN DE BASES DE DATOS" -> "Administración de Bases de Datos" (OASIS/PDF vienen en mayusculas). */
        fun prettySubject(text: String): String {
            if (text != text.uppercase()) return text
            return text.lowercase().split(" ").filter { it.isNotEmpty() }.mapIndexed { i, w ->
                val core = w.trimEnd(':')
                val suffix = w.substring(core.length)
                when {
                    core in ACRONYMS -> ACRONYMS.getValue(core) + suffix
                    i > 0 && core in SMALL -> w
                    else -> w.replaceFirstChar { it.titlecase() }
                }
            }.joinToString(" ")
        }
    }
}
