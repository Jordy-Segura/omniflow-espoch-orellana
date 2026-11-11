package ec.edu.espoch.omniflow.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LabDao {
    @Query("SELECT * FROM labs ORDER BY name")
    fun observeAll(): Flow<List<LabEntity>>

    @Query("SELECT * FROM labs ORDER BY name")
    suspend fun getAll(): List<LabEntity>

    @Query("SELECT * FROM labs WHERE id = :id")
    suspend fun get(id: Long): LabEntity?

    @Query("SELECT COUNT(*) FROM labs")
    suspend fun count(): Int

    @Query("SELECT * FROM labs WHERE code = :code")
    suspend fun byCode(code: String): LabEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(lab: LabEntity): Long

    @Update
    suspend fun update(lab: LabEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(labs: List<LabEntity>): List<Long>
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedule")
    fun observeAll(): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedule WHERE labId = :labId ORDER BY dayOfWeek, startMinute")
    fun observeForLab(labId: Long): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedule WHERE labId = :labId")
    suspend fun forLab(labId: Long): List<ScheduleEntity>

    @Query("SELECT * FROM schedule")
    suspend fun getAll(): List<ScheduleEntity>

    @Insert
    suspend fun insertAll(slots: List<ScheduleEntity>)

    @Query("SELECT * FROM schedule WHERE id = :id")
    suspend fun get(id: Long): ScheduleEntity?

    @Query("SELECT * FROM schedule WHERE id = :id")
    fun observe(id: Long): Flow<ScheduleEntity?>

    @Insert
    suspend fun insert(slot: ScheduleEntity): Long

    @Query("DELETE FROM schedule WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE schedule SET remoteId = :remoteId WHERE id = :id")
    suspend fun setRemoteId(id: Long, remoteId: Long)

    @Query("DELETE FROM schedule WHERE source = :source")
    suspend fun deleteBySource(source: String)

    /** Reemplaza el horario oficial de un laboratorio (conserva los bloques creados a mano). */
    @Query("DELETE FROM schedule WHERE labId = :labId AND source != 'LOCAL'")
    suspend fun deleteOfficialForLab(labId: Long)
}

data class LabCount(val labId: Long, val count: Int, val timestamp: Long)
data class DayBucket(val day: Int, val avgCount: Double, val samples: Int)
data class LabAvg(val labId: Long, val avgCount: Double, val peak: Int)

@Dao
interface OccupancyDao {
    @Insert
    suspend fun insert(event: OccupancyEventEntity): Long

    @Insert
    suspend fun insertAll(events: List<OccupancyEventEntity>)

    /** Ultima muestra por laboratorio (ocupacion "actual"). */
    @Query(
        """SELECT e.labId AS labId, e.count AS count, e.timestamp AS timestamp FROM occupancy_events e
           INNER JOIN (SELECT labId, MAX(timestamp) AS ts FROM occupancy_events WHERE source != 'DEMO' GROUP BY labId) m
           ON e.labId = m.labId AND e.timestamp = m.ts""",
    )
    fun observeLatest(): Flow<List<LabCount>>

    @Query("SELECT * FROM occupancy_events WHERE labId = :labId AND timestamp >= :since ORDER BY timestamp")
    suspend fun since(labId: Long, since: Long): List<OccupancyEventEntity>

    @Query("SELECT * FROM occupancy_events WHERE labId = :labId AND timestamp >= :since ORDER BY timestamp")
    fun observeSince(labId: Long, since: Long): Flow<List<OccupancyEventEntity>>

    @Query("SELECT count FROM occupancy_events WHERE labId = :labId AND hourOfWeek = :hourOfWeek AND timestamp < :before ORDER BY timestamp DESC LIMIT 500")
    suspend fun historyForSlot(labId: Long, hourOfWeek: Int, before: Long): List<Int>

    /** Ocupacion promedio por dia de la semana (0 = domingo en strftime). */
    @Query(
        """SELECT CAST(strftime('%w', timestamp / 1000, 'unixepoch', 'localtime') AS INTEGER) AS day,
                  AVG(count) AS avgCount, COUNT(*) AS samples
           FROM occupancy_events WHERE timestamp >= :since GROUP BY day""",
    )
    fun observeByWeekday(since: Long): Flow<List<DayBucket>>

    @Query("SELECT labId, AVG(count) AS avgCount, MAX(count) AS peak FROM occupancy_events WHERE timestamp >= :since GROUP BY labId")
    fun observeLabAverages(since: Long): Flow<List<LabAvg>>

    @Query("SELECT COUNT(*) FROM occupancy_events WHERE timestamp >= :since AND count > 0")
    fun observeBusySamples(since: Long): Flow<Int>

    @Query("SELECT * FROM occupancy_events WHERE synced = 0 AND source != 'DEMO' ORDER BY timestamp LIMIT :limit")
    suspend fun unsynced(limit: Int = 200): List<OccupancyEventEntity>

    @Query("UPDATE occupancy_events SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("DELETE FROM occupancy_events WHERE source = 'DEMO'")
    suspend fun deleteDemo()

    @Query("SELECT EXISTS(SELECT 1 FROM occupancy_events WHERE source = 'DEMO')")
    fun observeHasDemo(): Flow<Boolean>
}

@Dao
interface ClassSessionDao {
    @Query("SELECT * FROM class_sessions WHERE scheduleId = :scheduleId AND epochDay = :epochDay")
    suspend fun find(scheduleId: Long, epochDay: Long): ClassSessionEntity?

    @Query("SELECT * FROM class_sessions WHERE scheduleId = :scheduleId AND epochDay = :epochDay")
    fun observe(scheduleId: Long, epochDay: Long): Flow<ClassSessionEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(session: ClassSessionEntity): Long

    @Update
    suspend fun update(session: ClassSessionEntity)

    @Query("SELECT * FROM class_sessions WHERE epochDay >= :fromDay ORDER BY epochDay DESC")
    fun observeFrom(fromDay: Long): Flow<List<ClassSessionEntity>>

    @Query("DELETE FROM class_sessions WHERE demo = 1")
    suspend fun deleteDemo()
}

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts ORDER BY timestamp DESC LIMIT 300")
    fun observeAll(): Flow<List<AlertEntity>>

    @Query("SELECT COUNT(*) FROM alerts WHERE read = 0")
    fun observeUnread(): Flow<Int>

    @Query("SELECT MAX(timestamp) FROM alerts WHERE labId = :labId AND type = :type")
    suspend fun lastOf(labId: Long, type: String): Long?

    @Insert
    suspend fun insert(alert: AlertEntity): Long

    @Query("UPDATE alerts SET read = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("UPDATE alerts SET read = 1")
    suspend fun markAllRead()

    @Query("SELECT COUNT(*) FROM alerts WHERE timestamp >= :since")
    fun observeCountSince(since: Long): Flow<Int>
}

