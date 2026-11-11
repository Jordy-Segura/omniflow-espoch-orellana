package ec.edu.espoch.omniflow.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        LabEntity::class,
        ScheduleEntity::class,
        OccupancyEventEntity::class,
        ClassSessionEntity::class,
        AlertEntity::class,
        LocalPersonEntity::class,
        LocalMarkEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class OmniFlowDatabase : RoomDatabase() {
    abstract fun labDao(): LabDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun occupancyDao(): OccupancyDao
    abstract fun classSessionDao(): ClassSessionDao
    abstract fun alertDao(): AlertDao
    abstract fun localAttendanceDao(): LocalAttendanceDao
}
